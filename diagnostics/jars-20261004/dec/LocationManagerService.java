package com.android.server.location;

import android.R;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.Intent;
import android.hardware.location.GeofenceHardwareImpl;
import android.location.Criteria;
import android.location.Geofence;
import android.location.GnssAntennaInfo;
import android.location.GnssCapabilities;
import android.location.GnssMeasurementCorrections;
import android.location.GnssMeasurementRequest;
import android.location.IGnssAntennaInfoListener;
import android.location.IGnssMeasurementsListener;
import android.location.IGnssNavigationMessageListener;
import android.location.IGnssNmeaListener;
import android.location.IGnssStatusListener;
import android.location.IGpsGeofenceHardware;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.ILocationManager;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.IGeocodeCallback;
import android.location.provider.IProviderRequestListener;
import android.location.provider.ProviderProperties;
import android.location.provider.ReverseGeocodeRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.PackageTagsList;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import com.android.internal.hidden_from_bootclasspath.android.location.flags.Flags;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.am.IOplusSceneManager;
import com.android.server.bluetooth.IOplusBluetoothManagerServiceExt;
import com.android.server.location.common.OplusLbsFactory;
import com.android.server.location.eventlog.LocationEventLog;
import com.android.server.location.fudger.LocationFudgerCache;
import com.android.server.location.geofence.GeofenceManager;
import com.android.server.location.geofence.GeofenceProxy;
import com.android.server.location.gnss.GnssConfiguration;
import com.android.server.location.gnss.GnssManagerService;
import com.android.server.location.gnss.hal.GnssNative;
import com.android.server.location.injector.AlarmHelper;
import com.android.server.location.injector.AppForegroundHelper;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.DeviceIdleHelper;
import com.android.server.location.injector.DeviceStationaryHelper;
import com.android.server.location.injector.EmergencyHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationPermissionsHelper;
import com.android.server.location.injector.LocationPowerSaveModeHelper;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.PackageResetHelper;
import com.android.server.location.injector.ScreenInteractiveHelper;
import com.android.server.location.injector.SettingsHelper;
import com.android.server.location.injector.SystemAlarmHelper;
import com.android.server.location.injector.SystemAppForegroundHelper;
import com.android.server.location.injector.SystemAppOpsHelper;
import com.android.server.location.injector.SystemDeviceIdleHelper;
import com.android.server.location.injector.SystemDeviceStationaryHelper;
import com.android.server.location.injector.SystemEmergencyHelper;
import com.android.server.location.injector.SystemLocationPermissionsHelper;
import com.android.server.location.injector.SystemLocationPowerSaveModeHelper;
import com.android.server.location.injector.SystemPackageResetHelper;
import com.android.server.location.injector.SystemScreenInteractiveHelper;
import com.android.server.location.injector.SystemSettingsHelper;
import com.android.server.location.injector.SystemUserInfoHelper;
import com.android.server.location.injector.UserInfoHelper;
import com.android.server.location.interfaces.ILocationFreezeProc;
import com.android.server.location.interfaces.IOplusLBSMainClass;
import com.android.server.location.interfaces.IVirtualGnssHal;
import com.android.server.location.interfaces.IVirtualGnssLocationProvider;
import com.android.server.location.provider.AbstractLocationProvider;
import com.android.server.location.provider.LocationProviderManager;
import com.android.server.location.provider.MockLocationProvider;
import com.android.server.location.provider.PassiveLocationProvider;
import com.android.server.location.provider.PassiveLocationProviderManager;
import com.android.server.location.provider.StationaryThrottlingLocationProvider;
import com.android.server.location.provider.proxy.ProxyGeocodeProvider;
import com.android.server.location.provider.proxy.ProxyLocationProvider;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;
import com.android.server.location.settings.LocationSettings;
import com.android.server.location.settings.LocationUserSettings;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import system.ext.loader.core.ExtLoader;

/* JADX INFO: loaded from: classes2.dex */
public class LocationManagerService extends ILocationManager.Stub implements LocationProviderManager.StateChangedListener {
    private static final String ATTRIBUTION_TAG = "LocationService";
    private final Context mContext;
    private ILocationListener mDeprecatedGnssBatchingListener;
    private String mExtraLocationControllerPackage;
    private boolean mExtraLocationControllerPackageEnabled;
    private ProxyGeocodeProvider mGeocodeProvider;
    private final GeofenceManager mGeofenceManager;
    private final Injector mInjector;
    private ILocationManagerServiceExt mLocationManagerServiceExt;
    LocationManagerInternal.LocationPackageTagsListener mLocationTagsChangedListener;
    private final PassiveLocationProviderManager mPassiveManager;
    public static final String TAG = "LocationManagerService";
    public static boolean D = Log.isLoggable(TAG, 3);
    private static IOplusLBSMainClass mOplusLbsClass = null;
    final Object mLock = new Object();
    private volatile GnssManagerService mGnssManagerService = null;
    private ProxyPopulationDensityProvider mPopulationDensityProvider = null;
    private LocationFudgerCache mLocationFudgerCache = null;
    private final Object mDeprecatedGnssBatchingLock = new Object();
    final CopyOnWriteArrayList<LocationProviderManager> mProviderManagers = new CopyOnWriteArrayList<>();
    private LocationManagerServiceWrapper mLmsWrapper = new LocationManagerServiceWrapper();
    private ILocationFreezeProc mLocationFreeze = null;
    private IVirtualGnssLocationProvider mVirtualProvider = null;
    private IVirtualGnssHal mVirtualGnssHal = null;
    private GeofenceProxy mGeofenceProxy = null;
    private final LocalService mLocalService = new LocalService();

    public static class Lifecycle extends SystemService {
        private final LocationManagerService mService;
        private final SystemInjector mSystemInjector;
        private final LifecycleUserInfoHelper mUserInfoHelper;

        public Lifecycle(Context context) {
            super(context);
            this.mUserInfoHelper = new LifecycleUserInfoHelper(context);
            this.mSystemInjector = new SystemInjector(context, this.mUserInfoHelper);
            this.mService = new LocationManagerService(context, this.mSystemInjector);
        }

        @Override // com.android.server.SystemService
        public void onStart() {
            publishBinderService("location", this.mService);
            LocationManager.invalidateLocalLocationEnabledCaches();
            LocationManager.disableLocalLocationEnabledCaches();
        }

        @Override // com.android.server.SystemService
        public void onBootPhase(int phase) {
            if (phase == 500) {
                this.mSystemInjector.onSystemReady();
                this.mService.onSystemReady();
                this.mService.oplusSystemReady(this.mService);
            } else if (phase == 600) {
                this.mService.onSystemThirdPartyAppsCanStart();
                this.mService.oplusSystemThirdPartyAppsCanStart();
            }
        }

        @Override // com.android.server.SystemService
        public void onUserStarting(SystemService.TargetUser user) {
            this.mUserInfoHelper.onUserStarted(user.getUserIdentifier());
            this.mService.logLocationEnabledState();
            this.mService.logEmergencyState();
        }

        @Override // com.android.server.SystemService
        public void onUserSwitching(SystemService.TargetUser from, SystemService.TargetUser to) {
            this.mUserInfoHelper.onCurrentUserChanged(from.getUserIdentifier(), to.getUserIdentifier());
        }

        @Override // com.android.server.SystemService
        public void onUserStopped(SystemService.TargetUser user) {
            this.mUserInfoHelper.onUserStopped(user.getUserIdentifier());
        }

        /* JADX INFO: Access modifiers changed from: private */
        static class LifecycleUserInfoHelper extends SystemUserInfoHelper {
            LifecycleUserInfoHelper(Context context) {
                super(context);
            }

            void onUserStarted(int userId) {
                dispatchOnUserStarted(userId);
            }

            void onUserStopped(int userId) {
                dispatchOnUserStopped(userId);
            }

            void onCurrentUserChanged(final int fromUserId, final int toUserId) {
                if (LocationManagerService.mOplusLbsClass == null) {
                    dispatchOnCurrentUserChanged(fromUserId, toUserId);
                } else {
                    LocationManagerService.mOplusLbsClass.getHandler(0).post(new Runnable() { // from class: com.android.server.location.LocationManagerService$Lifecycle$LifecycleUserInfoHelper$$ExternalSyntheticLambda0
                        @Override // java.lang.Runnable
                        public final void run() {
                            this.f$0.lambda$onCurrentUserChanged$0(fromUserId, toUserId);
                        }
                    });
                }
            }

            /* JADX INFO: Access modifiers changed from: private */
            public /* synthetic */ void lambda$onCurrentUserChanged$0(int fromUserId, int toUserId) {
                dispatchOnCurrentUserChanged(fromUserId, toUserId);
            }
        }
    }

    LocationManagerService(Context context, Injector injector) {
        this.mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        this.mInjector = injector;
        LocalServices.addService(LocationManagerInternal.class, this.mLocalService);
        this.mGeofenceManager = new GeofenceManager(this.mContext, injector);
        this.mInjector.getLocationSettings().registerLocationUserSettingsListener(new LocationSettings.LocationUserSettingsListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda5
            @Override // com.android.server.location.settings.LocationSettings.LocationUserSettingsListener
            public final void onLocationUserSettingsChanged(int i, LocationUserSettings locationUserSettings, LocationUserSettings locationUserSettings2) {
                this.f$0.onLocationUserSettingsChanged(i, locationUserSettings, locationUserSettings2);
            }
        });
        this.mInjector.getSettingsHelper().addOnLocationEnabledChangedListener(new SettingsHelper.UserSettingChangedListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda6
            @Override // com.android.server.location.injector.SettingsHelper.UserSettingChangedListener
            public final void onSettingChanged(int i) {
                this.f$0.onLocationModeChanged(i);
            }
        });
        this.mInjector.getSettingsHelper().addAdasAllowlistChangedListener(new SettingsHelper.GlobalSettingChangedListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda7
            @Override // com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener
            public final void onSettingChanged() {
                this.f$0.lambda$new$0();
            }
        });
        this.mInjector.getSettingsHelper().addIgnoreSettingsAllowlistChangedListener(new SettingsHelper.GlobalSettingChangedListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda8
            @Override // com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener
            public final void onSettingChanged() {
                this.f$0.lambda$new$1();
            }
        });
        this.mInjector.getUserInfoHelper().addListener(new UserInfoHelper.UserListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda9
            @Override // com.android.server.location.injector.UserInfoHelper.UserListener
            public final void onUserChanged(int i, int i2) {
                this.f$0.lambda$new$2(i, i2);
            }
        });
        this.mInjector.getEmergencyHelper().addOnEmergencyStateChangedListener(new EmergencyHelper.EmergencyStateChangedListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda10
            @Override // com.android.server.location.injector.EmergencyHelper.EmergencyStateChangedListener
            public final void onStateChanged() {
                this.f$0.onEmergencyStateChanged();
            }
        });
        this.mPassiveManager = new PassiveLocationProviderManager(this.mContext, injector);
        addLocationProviderManager(this.mPassiveManager, new PassiveLocationProvider(this.mContext));
        LegacyPermissionManagerInternal permissionManagerInternal = (LegacyPermissionManagerInternal) LocalServices.getService(LegacyPermissionManagerInternal.class);
        permissionManagerInternal.setLocationPackagesProvider(new LegacyPermissionManagerInternal.PackagesProvider() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda11
            @Override // com.android.server.pm.permission.LegacyPermissionManagerInternal.PackagesProvider
            public final String[] getPackages(int i) {
                return this.f$0.lambda$new$3(i);
            }
        });
        permissionManagerInternal.setLocationExtraPackagesProvider(new LegacyPermissionManagerInternal.PackagesProvider() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda12
            @Override // com.android.server.pm.permission.LegacyPermissionManagerInternal.PackagesProvider
            public final String[] getPackages(int i) {
                return this.f$0.lambda$new$4(i);
            }
        });
        this.mLocationManagerServiceExt = (ILocationManagerServiceExt) ExtLoader.type(ILocationManagerServiceExt.class).create();
        this.mLocationManagerServiceExt.hookServiceStart(this, this.mContext);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$0() {
        refreshAppOpsRestrictions(-1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$1() {
        refreshAppOpsRestrictions(-1);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$new$2(int userId, int change) {
        if (change == 2) {
            refreshAppOpsRestrictions(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ String[] lambda$new$3(int userId) {
        return this.mContext.getResources().getStringArray(R.array.config_locationProviderPackageNames);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ String[] lambda$new$4(int userId) {
        return this.mContext.getResources().getStringArray(R.array.config_locationExtraPackageNames);
    }

    LocationProviderManager getLocationProviderManager(String providerName) {
        if (providerName == null) {
            return null;
        }
        for (LocationProviderManager manager : this.mProviderManagers) {
            if (providerName.equals(manager.getName())) {
                if (!manager.isVisibleToCaller()) {
                    return null;
                }
                return manager;
            }
        }
        return null;
    }

    private LocationProviderManager getOrAddLocationProviderManager(String providerName) {
        synchronized (this.mProviderManagers) {
            for (LocationProviderManager manager : this.mProviderManagers) {
                if (providerName.equals(manager.getName())) {
                    return manager;
                }
            }
            LocationProviderManager manager2 = new LocationProviderManager(this.mContext, this.mInjector, providerName, this.mPassiveManager);
            addLocationProviderManager(manager2, null);
            return manager2;
        }
    }

    void addLocationProviderManager(LocationProviderManager manager, AbstractLocationProvider realProvider) {
        synchronized (this.mProviderManagers) {
            boolean z = true;
            Preconditions.checkState(getLocationProviderManager(manager.getName()) == null);
            manager.startManager(this);
            if (realProvider != null) {
                if (manager != this.mPassiveManager) {
                    int defaultStationaryThrottlingSetting = this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.watch") ? 0 : 1;
                    boolean enableStationaryThrottling = Settings.Global.getInt(this.mContext.getContentResolver(), "location_enable_stationary_throttle", defaultStationaryThrottlingSetting) != 0;
                    if (Flags.disableStationaryThrottling() && (!Flags.keepGnssStationaryThrottling() || !enableStationaryThrottling || !IOplusSceneManager.APP_SCENE_GPS.equals(manager.getName()))) {
                        enableStationaryThrottling = false;
                    }
                    if (mOplusLbsClass != null) {
                        if (!enableStationaryThrottling || !mOplusLbsClass.isStationaryThrottlingEnable()) {
                            z = false;
                        }
                        enableStationaryThrottling = z;
                    }
                    if (enableStationaryThrottling) {
                        realProvider = new StationaryThrottlingLocationProvider(manager.getName(), this.mInjector, realProvider);
                    }
                }
                manager.setRealProvider(realProvider);
            }
            this.mProviderManagers.add(manager);
        }
    }

    protected void setProxyPopulationDensityProvider(ProxyPopulationDensityProvider provider) {
        if (Flags.populationDensityProvider()) {
            this.mPopulationDensityProvider = provider;
        }
    }

    protected void setLocationFudgerCache(LocationFudgerCache cache) {
        if (!Flags.densityBasedCoarseLocations()) {
            return;
        }
        this.mLocationFudgerCache = cache;
        for (LocationProviderManager manager : this.mProviderManagers) {
            manager.setLocationFudgerCache(cache);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void removeLocationProviderManager(LocationProviderManager manager) {
        synchronized (this.mProviderManagers) {
            boolean removed = this.mProviderManagers.remove(manager);
            Preconditions.checkArgument(removed);
            manager.setMockProvider(null);
            manager.setRealProvider(null);
            manager.stopManager();
        }
    }

    void onSystemReady() {
        if (Build.IS_DEBUGGABLE) {
            AppOpsManager appOps = (AppOpsManager) Objects.requireNonNull((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class));
            appOps.startWatchingNoted(new int[]{1, 0}, new AppOpsManager.OnOpNotedListener() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda2
                public final void onOpNoted(String str, int i, String str2, String str3, int i2, int i3) {
                    this.f$0.lambda$onSystemReady$5(str, i, str2, str3, i2, i3);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onSystemReady$5(String code, int uid, String packageName, String attributionTag, int flags, int result) {
        if (!isLocationEnabledForUser(UserHandle.getUserId(uid))) {
            Log.w(TAG, "location noteOp with location off - " + CallerIdentity.forTest(uid, 0, packageName, attributionTag));
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    void onSystemThirdPartyAppsCanStart() {
        char c;
        char c2 = 0;
        try {
            Preconditions.checkState(!this.mContext.getPackageManager().queryIntentServicesAsUser(new Intent("com.android.location.service.FusedLocationProvider"), 1572864, 0).isEmpty(), "Unable to find a direct boot aware fused location provider");
        } catch (IllegalStateException expected) {
            Log.e(TAG, expected.getMessage());
        }
        ProxyLocationProvider fusedProvider = ProxyLocationProvider.create(this.mContext, "fused", "com.android.location.service.FusedLocationProvider", mOplusLbsClass.getFlpResId(IOplusBluetoothManagerServiceExt.FLAG_ENABLE), mOplusLbsClass.getFlpResId("packageName"));
        if (fusedProvider != null) {
            LocationProviderManager fusedManager = new LocationProviderManager(this.mContext, this.mInjector, "fused", this.mPassiveManager);
            addLocationProviderManager(fusedManager, fusedProvider);
        } else {
            Log.wtf(TAG, "no fused location provider found");
        }
        boolean hasLocationFeature = this.mContext.getPackageManager().hasSystemFeature("android.hardware.location");
        boolean hasGpsFeature = this.mContext.getPackageManager().hasSystemFeature("android.hardware.location.gps");
        AbstractLocationProvider virtualProvider = this.mVirtualProvider.getVirtualProvider(this.mContext);
        if (!hasLocationFeature) {
            c = 1;
        } else if (GnssNative.isSupported() || virtualProvider != null) {
            if (virtualProvider != null) {
                Log.d(TAG, "using virtual gnssProvider");
            }
            GnssConfiguration gnssConfiguration = new GnssConfiguration(this.mContext);
            GnssNative gnssNative = GnssNative.create(this.mInjector, gnssConfiguration, hasGpsFeature || virtualProvider == null, this.mVirtualGnssHal);
            this.mGnssManagerService = new GnssManagerService(this.mContext, this.mInjector, gnssNative);
            this.mGnssManagerService.onSystemReady();
            boolean useGnssHardwareProvider = this.mContext.getResources().getBoolean(R.bool.config_use_strict_phone_number_comparation_for_russia);
            AbstractLocationProvider gnssProvider = null;
            if (!useGnssHardwareProvider) {
                gnssProvider = ProxyLocationProvider.create(this.mContext, IOplusSceneManager.APP_SCENE_GPS, "android.location.provider.action.GNSS_PROVIDER", R.bool.config_enableMotionPrediction, R.string.config_oem_enabled_satellite_s2cell_file, R.bool.config_handleVolumeKeysInWindowManager);
            }
            if (gnssProvider == null) {
                if (hasGpsFeature) {
                    gnssProvider = this.mGnssManagerService.getGnssLocationProvider();
                } else {
                    gnssProvider = virtualProvider;
                }
            } else {
                LocationProviderManager gnssHardwareManager = new LocationProviderManager(this.mContext, this.mInjector, "gps_hardware", null, Collections.singletonList("android.permission.LOCATION_HARDWARE"));
                addLocationProviderManager(gnssHardwareManager, this.mGnssManagerService.getGnssLocationProvider());
            }
            c = 1;
            LocationProviderManager gnssManager = new LocationProviderManager(this.mContext, this.mInjector, IOplusSceneManager.APP_SCENE_GPS, this.mPassiveManager);
            addLocationProviderManager(gnssManager, gnssProvider);
        } else {
            c = 1;
        }
        if (Flags.populationDensityProvider()) {
            long startTime = System.currentTimeMillis();
            setProxyPopulationDensityProvider(ProxyPopulationDensityProvider.createAndRegister(this.mContext));
            int duration = (int) (System.currentTimeMillis() - startTime);
            if (this.mPopulationDensityProvider == null) {
                Log.e(TAG, "no population density provider found");
            }
            FrameworkStatsLog.write(1002, this.mPopulationDensityProvider == null ? c : 0, duration);
        }
        if (this.mPopulationDensityProvider != null && Flags.densityBasedCoarseLocations()) {
            setLocationFudgerCache(new LocationFudgerCache(this.mPopulationDensityProvider));
        }
        HardwareActivityRecognitionProxy hardwareActivityRecognitionProxy = HardwareActivityRecognitionProxy.createAndRegister(this.mContext);
        if (hardwareActivityRecognitionProxy == null) {
            Log.e(TAG, "unable to bind ActivityRecognitionProxy");
        }
        if (this.mGnssManagerService != null) {
            this.mGeofenceProxy = GeofenceProxy.createAndBind(this.mContext, this.mGnssManagerService.getGnssGeofenceProxy());
            if (this.mGeofenceProxy == null) {
                Log.e(TAG, "unable to bind to GeofenceProxy");
            }
        }
        String[] testProviderStrings = this.mContext.getResources().getStringArray(R.array.config_testLocationProviders);
        int length = testProviderStrings.length;
        int i = 0;
        while (i < length) {
            String testProviderString = testProviderStrings[i];
            String[] fragments = testProviderString.split(",");
            String name = fragments[c2].trim();
            ProviderProperties properties = new ProviderProperties.Builder().setHasNetworkRequirement(Boolean.parseBoolean(fragments[c])).setHasSatelliteRequirement(Boolean.parseBoolean(fragments[2])).setHasCellRequirement(Boolean.parseBoolean(fragments[3])).setHasMonetaryCost(Boolean.parseBoolean(fragments[4])).setHasAltitudeSupport(Boolean.parseBoolean(fragments[5])).setHasSpeedSupport(Boolean.parseBoolean(fragments[6])).setHasBearingSupport(Boolean.parseBoolean(fragments[7])).setPowerUsage(Integer.parseInt(fragments[8])).setAccuracy(Integer.parseInt(fragments[9])).build();
            LocationProviderManager manager = getOrAddLocationProviderManager(name);
            manager.setMockProvider(new MockLocationProvider(properties, CallerIdentity.fromContext(this.mContext), Collections.emptySet()));
            i++;
            fusedProvider = fusedProvider;
            c2 = 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationUserSettingsChanged(int userId, LocationUserSettings oldSettings, LocationUserSettings newSettings) {
        if (oldSettings.isAdasGnssLocationEnabled() != newSettings.isAdasGnssLocationEnabled()) {
            boolean enabled = newSettings.isAdasGnssLocationEnabled();
            if (D) {
                Log.d(TAG, "[u" + userId + "] adas gnss location enabled = " + enabled);
            }
            LocationEventLog.EVENT_LOG.logAdasLocationEnabled(userId, enabled);
            Intent intent = new Intent("android.location.action.ADAS_GNSS_ENABLED_CHANGED").putExtra("android.location.extra.ADAS_GNSS_ENABLED", enabled).addFlags(1073741824).addFlags(268435456);
            this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationModeChanged(int userId) {
        boolean enabled = this.mInjector.getSettingsHelper().isLocationEnabled(userId);
        LocationManager.invalidateLocalLocationEnabledCaches();
        Log.d(TAG, "[u" + userId + "] location enabled = " + enabled);
        LocationEventLog.EVENT_LOG.logLocationEnabled(userId, enabled);
        logLocationEnabledState();
        Intent intent = new Intent("android.location.MODE_CHANGED").putExtra("android.location.extra.LOCATION_ENABLED", enabled).addFlags(1073741824).addFlags(268435456);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
        refreshAppOpsRestrictions(userId);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onEmergencyStateChanged() {
        logEmergencyState();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logEmergencyState() {
        boolean isInEmergency = this.mInjector.getEmergencyHelper().isInEmergency(Long.MIN_VALUE);
        this.mInjector.getLocationUsageLogger().logEmergencyStateChanged(isInEmergency);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void logLocationEnabledState() {
        boolean locationEnabled = false;
        int[] runningUserIds = this.mInjector.getUserInfoHelper().getRunningUserIds();
        for (int userId : runningUserIds) {
            locationEnabled = this.mInjector.getSettingsHelper().isLocationEnabled(userId);
            if (locationEnabled) {
                break;
            }
        }
        this.mInjector.getLocationUsageLogger().logLocationEnabledStateChanged(locationEnabled);
    }

    public int getGnssYearOfHardware() {
        if (this.mGnssManagerService == null) {
            return 0;
        }
        return this.mGnssManagerService.getGnssYearOfHardware();
    }

    public String getGnssHardwareModelName() {
        return this.mGnssManagerService == null ? "" : this.mGnssManagerService.getGnssHardwareModelName();
    }

    public int getGnssBatchSize() {
        if (this.mGnssManagerService == null) {
            return 0;
        }
        return this.mGnssManagerService.getGnssBatchSize();
    }

    public void startGnssBatch(long periodNanos, ILocationListener listener, String packageName, String attributionTag, String listenerId) {
        startGnssBatch_enforcePermission();
        if (this.mGnssManagerService == null) {
            return;
        }
        long intervalMs = TimeUnit.NANOSECONDS.toMillis(periodNanos);
        synchronized (this.mDeprecatedGnssBatchingLock) {
            stopGnssBatch();
            registerLocationListener(IOplusSceneManager.APP_SCENE_GPS, new LocationRequest.Builder(intervalMs).setMaxUpdateDelayMillis(((long) this.mGnssManagerService.getGnssBatchSize()) * intervalMs).setHiddenFromAppOps(true).build(), listener, packageName, attributionTag, listenerId);
            this.mDeprecatedGnssBatchingListener = listener;
        }
    }

    public void flushGnssBatch() {
        flushGnssBatch_enforcePermission();
        if (this.mGnssManagerService == null) {
            return;
        }
        synchronized (this.mDeprecatedGnssBatchingLock) {
            if (this.mDeprecatedGnssBatchingListener != null) {
                requestListenerFlush(IOplusSceneManager.APP_SCENE_GPS, this.mDeprecatedGnssBatchingListener, 0);
            }
        }
    }

    public void stopGnssBatch() {
        stopGnssBatch_enforcePermission();
        if (this.mGnssManagerService == null) {
            return;
        }
        synchronized (this.mDeprecatedGnssBatchingLock) {
            if (this.mDeprecatedGnssBatchingListener != null) {
                ILocationListener listener = this.mDeprecatedGnssBatchingListener;
                this.mDeprecatedGnssBatchingListener = null;
                unregisterLocationListener(listener);
            }
        }
    }

    public boolean hasProvider(String provider) {
        return getLocationProviderManager(provider) != null;
    }

    public List<String> getAllProviders() {
        ArrayList<String> providers = new ArrayList<>(this.mProviderManagers.size());
        for (LocationProviderManager manager : this.mProviderManagers) {
            if (manager.isVisibleToCaller()) {
                providers.add(manager.getName());
            }
        }
        return providers;
    }

    public List<String> getProviders(Criteria criteria, boolean enabledOnly) {
        ArrayList<String> providers;
        if (!LocationPermissions.checkCallingOrSelfLocationPermission(this.mContext, 1)) {
            return Collections.emptyList();
        }
        synchronized (this.mLock) {
            providers = new ArrayList<>(this.mProviderManagers.size());
            for (LocationProviderManager manager : this.mProviderManagers) {
                if (manager.isVisibleToCaller()) {
                    String name = manager.getName();
                    if (!name.equals(IOplusSceneManager.APP_SCENE_GPS) || mOplusLbsClass.isVirtualGpsVisibleOnlyForPad(Binder.getCallingUid())) {
                        if (!enabledOnly || manager.isEnabled(UserHandle.getCallingUserId())) {
                            if (criteria == null || LocationProvider.propertiesMeetCriteria(name, manager.getProperties(), criteria)) {
                                providers.add(name);
                            }
                        }
                    }
                }
            }
        }
        return providers;
    }

    public String getBestProvider(Criteria criteria, boolean enabledOnly) {
        List<String> providers;
        synchronized (this.mLock) {
            providers = getProviders(criteria, enabledOnly);
            if (providers.isEmpty()) {
                providers = getProviders(null, enabledOnly);
            }
        }
        if (providers.isEmpty()) {
            return null;
        }
        if (providers.contains("fused")) {
            return "fused";
        }
        if (providers.contains(IOplusSceneManager.APP_SCENE_GPS)) {
            return IOplusSceneManager.APP_SCENE_GPS;
        }
        if (providers.contains("network")) {
            return "network";
        }
        return providers.get(0);
    }

    public String[] getBackgroundThrottlingWhitelist() {
        return (String[]) this.mInjector.getSettingsHelper().getBackgroundThrottlePackageWhitelist().toArray(new String[0]);
    }

    public PackageTagsList getIgnoreSettingsAllowlist() {
        return this.mInjector.getSettingsHelper().getIgnoreSettingsAllowlist();
    }

    public PackageTagsList getAdasAllowlist() {
        return this.mInjector.getSettingsHelper().getAdasAllowlist();
    }

    public ICancellationSignal getCurrentLocation(String provider, LocationRequest request, ILocationCallback consumer, String packageName, String attributionTag, String listenerId) {
        CallerIdentity identity = CallerIdentity.fromBinder(this.mContext, packageName, attributionTag, listenerId);
        int permissionLevel = LocationPermissions.getPermissionLevel(this.mContext, identity.getUid(), identity.getPid());
        if (Flags.enableLocationBypass()) {
            if (permissionLevel == 0) {
                if (this.mContext.checkCallingPermission("android.permission.LOCATION_BYPASS") != 0) {
                    LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel, 1);
                } else {
                    permissionLevel = 2;
                }
            }
        } else {
            LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel, 1);
        }
        Preconditions.checkState((identity.getPid() == Process.myPid() && attributionTag == null) ? false : true);
        LocationRequest request2 = validateLocationRequest(provider, request, identity);
        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null, "provider \"" + provider + "\" does not exist");
        return manager.getCurrentLocation(request2, identity, permissionLevel, consumer);
    }

    /* JADX WARN: Code duplicated, block: B:29:0x008d  */
    /* JADX WARN: Code duplicated, block: B:32:0x00af  */
    /* JADX WARN: Code duplicated, block: B:35:0x00cd  */
    /* JADX WARN: Code duplicated, block: B:37:0x00d6  */
    /* JADX WARN: Code duplicated, block: B:39:0x00dd  */
    /* JADX WARN: Code duplicated, block: B:42:0x00e5  */
    /* JADX WARN: Code duplicated, block: B:44:? A[RETURN, SYNTHETIC] */
    public void registerLocationListener(String provider, LocationRequest request, ILocationListener listener, String packageName, String attributionTag, String listenerId) {
        int permissionLevel;
        LocationRequest request2;
        boolean z;
        LocationProviderManager manager;
        ILocationListener listener2;
        if (mOplusLbsClass != null) {
            mOplusLbsClass.recordLocationRequest(packageName);
        }
        ActivityManagerInternal managerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        if (managerInternal != null) {
            managerInternal.logFgsApiBegin(3, Binder.getCallingUid(), Binder.getCallingPid());
        }
        CallerIdentity identity = CallerIdentity.fromBinder(this.mContext, packageName, attributionTag, listenerId);
        int permissionLevel2 = LocationPermissions.getPermissionLevel(this.mContext, identity.getUid(), identity.getPid());
        if (Flags.enableLocationBypass()) {
            if (permissionLevel2 == 0) {
                if (this.mContext.checkCallingPermission("android.permission.LOCATION_BYPASS") != 0) {
                    LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel2, 1);
                } else {
                    permissionLevel = 2;
                }
            }
            if (identity.getPid() == Process.myPid() && attributionTag == null) {
                Log.w(TAG, "system location request with no attribution tag", new IllegalArgumentException());
            }
            request2 = validateLocationRequest(provider, request, identity);
            if (mOplusLbsClass == null && !mOplusLbsClass.registerLocationListener(provider, identity, permissionLevel)) {
                return;
            }
            z = true;
            manager = getLocationProviderManager(provider);
            if (manager == null) {
                z = false;
            }
            Preconditions.checkArgument(z, "provider \"" + provider + "\" does not exist");
            if (this.mLocationFreeze == null) {
                Log.i(TAG, "new LocationFreeze before storeLocationRequest.");
                this.mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, this.mContext);
            }
            if (this.mLocationFreeze != null) {
                listener2 = listener;
                if (this.mLocationFreeze.storeLocationRequest(manager, request2, identity, permissionLevel, listener2)) {
                    Log.i(TAG, "the app is freeze, return.");
                    return;
                }
            } else {
                listener2 = listener;
            }
            manager.registerLocationRequest(request2, identity, permissionLevel, listener2);
            if (mOplusLbsClass != null) {
                mOplusLbsClass.getAppInfoForTr("registerLocationListener()", request2.getProvider(), Binder.getCallingPid(), packageName);
            }
        }
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel2, 1);
        permissionLevel = permissionLevel2;
        if (identity.getPid() == Process.myPid()) {
            Log.w(TAG, "system location request with no attribution tag", new IllegalArgumentException());
        }
        request2 = validateLocationRequest(provider, request, identity);
        if (mOplusLbsClass == null) {
        }
        z = true;
        manager = getLocationProviderManager(provider);
        if (manager == null) {
            z = false;
        }
        Preconditions.checkArgument(z, "provider \"" + provider + "\" does not exist");
        if (this.mLocationFreeze == null) {
            Log.i(TAG, "new LocationFreeze before storeLocationRequest.");
            this.mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, this.mContext);
        }
        if (this.mLocationFreeze != null) {
            listener2 = listener;
            if (this.mLocationFreeze.storeLocationRequest(manager, request2, identity, permissionLevel, listener2)) {
                Log.i(TAG, "the app is freeze, return.");
                return;
            }
        } else {
            listener2 = listener;
        }
        manager.registerLocationRequest(request2, identity, permissionLevel, listener2);
        if (mOplusLbsClass != null) {
            mOplusLbsClass.getAppInfoForTr("registerLocationListener()", request2.getProvider(), Binder.getCallingPid(), packageName);
        }
    }

    /* JADX WARN: Code duplicated, block: B:16:0x004f  */
    /* JADX WARN: Code duplicated, block: B:19:0x0060  */
    /* JADX WARN: Code duplicated, block: B:29:0x007f  */
    /* JADX WARN: Code duplicated, block: B:32:0x0083  */
    /* JADX WARN: Code duplicated, block: B:37:0x00a8  */
    /* JADX WARN: Code duplicated, block: B:40:0x00cc  */
    /* JADX WARN: Code duplicated, block: B:43:0x00ea  */
    /* JADX WARN: Code duplicated, block: B:45:0x00f3  */
    /* JADX WARN: Code duplicated, block: B:47:0x00fa  */
    public void registerLocationPendingIntent(String provider, LocationRequest request, PendingIntent pendingIntent, String packageName, String attributionTag) {
        int permissionLevel;
        boolean z;
        LocationRequest request2;
        LocationProviderManager manager;
        PendingIntent pendingIntent2;
        boolean usesSystemApi;
        CallerIdentity identity = CallerIdentity.fromBinder(this.mContext, packageName, attributionTag, AppOpsManager.toReceiverId(pendingIntent));
        int permissionLevel2 = LocationPermissions.getPermissionLevel(this.mContext, identity.getUid(), identity.getPid());
        if (Flags.enableLocationBypass()) {
            if (permissionLevel2 == 0) {
                if (this.mContext.checkCallingPermission("android.permission.LOCATION_BYPASS") != 0) {
                    LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel2, 1);
                } else {
                    permissionLevel = 2;
                }
            }
            if (identity.getPid() == Process.myPid() || attributionTag != null) {
                z = true;
            } else {
                z = false;
            }
            Preconditions.checkArgument(z);
            if (CompatChanges.isChangeEnabled(169887240L, identity.getUid())) {
                if (!request.isLowPower() || request.isHiddenFromAppOps() || request.isLocationSettingsIgnored() || !request.getWorkSource().isEmpty()) {
                    usesSystemApi = true;
                } else {
                    usesSystemApi = false;
                }
                if (usesSystemApi) {
                    throw new SecurityException("PendingIntent location requests may not use system APIs: " + request);
                }
            }
            request2 = validateLocationRequest(provider, request, identity);
            manager = getLocationProviderManager(provider);
            Preconditions.checkArgument(manager != null, "provider \"" + provider + "\" does not exist");
            if (this.mLocationFreeze == null) {
                Log.i(TAG, "new LocationFreeze before storeLocationRequest.");
                this.mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, this.mContext);
            }
            if (this.mLocationFreeze != null) {
                pendingIntent2 = pendingIntent;
                if (this.mLocationFreeze.storeLocationRequest(manager, request2, identity, permissionLevel, pendingIntent2)) {
                    Log.i(TAG, "the app is freeze, return.");
                    return;
                }
            } else {
                pendingIntent2 = pendingIntent;
            }
            manager.registerLocationRequest(request2, identity, permissionLevel, pendingIntent2);
        }
        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel2, 1);
        permissionLevel = permissionLevel2;
        if (identity.getPid() == Process.myPid()) {
            z = true;
        } else {
            z = true;
        }
        Preconditions.checkArgument(z);
        if (CompatChanges.isChangeEnabled(169887240L, identity.getUid())) {
            if (request.isLowPower()) {
                usesSystemApi = true;
            } else {
                usesSystemApi = true;
            }
            if (usesSystemApi) {
                throw new SecurityException("PendingIntent location requests may not use system APIs: " + request);
            }
        }
        request2 = validateLocationRequest(provider, request, identity);
        manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null, "provider \"" + provider + "\" does not exist");
        if (this.mLocationFreeze == null) {
            Log.i(TAG, "new LocationFreeze before storeLocationRequest.");
            this.mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, this.mContext);
        }
        if (this.mLocationFreeze != null) {
            pendingIntent2 = pendingIntent;
            if (this.mLocationFreeze.storeLocationRequest(manager, request2, identity, permissionLevel, pendingIntent2)) {
                Log.i(TAG, "the app is freeze, return.");
                return;
            }
        } else {
            pendingIntent2 = pendingIntent;
        }
        manager.registerLocationRequest(request2, identity, permissionLevel, pendingIntent2);
    }

    private LocationRequest validateLocationRequest(String provider, LocationRequest request, CallerIdentity identity) {
        if (!request.getWorkSource().isEmpty()) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_DEVICE_STATS", "setting a work source requires android.permission.UPDATE_DEVICE_STATS");
        }
        LocationRequest.Builder sanitized = new LocationRequest.Builder(request);
        if (!CompatChanges.isChangeEnabled(168936375L, Binder.getCallingUid()) && this.mContext.checkCallingPermission("android.permission.LOCATION_HARDWARE") != 0) {
            sanitized.setLowPower(false);
        }
        WorkSource workSource = new WorkSource(request.getWorkSource());
        if (workSource.size() > 0 && workSource.getPackageName(0) == null) {
            Log.w(TAG, "received (and ignoring) illegal worksource with no package name");
            workSource.clear();
        } else {
            List<WorkSource.WorkChain> workChains = workSource.getWorkChains();
            if (workChains != null && !workChains.isEmpty() && workChains.get(0).getAttributionTag() == null) {
                Log.w(TAG, "received (and ignoring) illegal worksource with no attribution tag");
                workSource.clear();
            }
        }
        if (workSource.isEmpty()) {
            identity.addToWorkSource(workSource);
        }
        sanitized.setWorkSource(workSource);
        LocationRequest request2 = sanitized.build();
        boolean isLocationProvider = this.mLocalService.isProvider(null, identity);
        if (request2.isLowPower() && CompatChanges.isChangeEnabled(168936375L, identity.getUid())) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.LOCATION_HARDWARE", "low power request requires android.permission.LOCATION_HARDWARE");
        }
        if (request2.isHiddenFromAppOps()) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_APP_OPS_STATS", "hiding from app ops requires android.permission.UPDATE_APP_OPS_STATS");
        }
        if (request2.isAdasGnssBypass()) {
            if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
                throw new IllegalArgumentException("adas gnss bypass requests are only allowed on automotive devices");
            }
            if (!IOplusSceneManager.APP_SCENE_GPS.equals(provider)) {
                throw new IllegalArgumentException("adas gnss bypass requests are only allowed on the \"gps\" provider");
            }
            if (!isLocationProvider) {
                LocationPermissions.enforceCallingOrSelfBypassPermission(this.mContext);
            }
        }
        if (request2.isLocationSettingsIgnored() && !isLocationProvider) {
            LocationPermissions.enforceCallingOrSelfBypassPermission(this.mContext);
        }
        return request2;
    }

    public void requestListenerFlush(String provider, ILocationListener listener, int requestCode) {
        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null, "provider \"" + provider + "\" does not exist");
        manager.flush((ILocationListener) Objects.requireNonNull(listener), requestCode);
    }

    public void requestPendingIntentFlush(String provider, PendingIntent pendingIntent, int requestCode) {
        LocationProviderManager manager = getLocationProviderManager(provider);
        Preconditions.checkArgument(manager != null, "provider \"" + provider + "\" does not exist");
        manager.flush((PendingIntent) Objects.requireNonNull(pendingIntent), requestCode);
    }

    public void unregisterLocationListener(ILocationListener listener) {
        ActivityManagerInternal managerInternal = (ActivityManagerInternal) LocalServices.getService(ActivityManagerInternal.class);
        if (managerInternal != null) {
            managerInternal.logFgsApiEnd(3, Binder.getCallingUid(), Binder.getCallingPid());
        }
        if (this.mLocationFreeze != null) {
            this.mLocationFreeze.removeLocationRequest(listener.asBinder());
        }
        for (LocationProviderManager manager : this.mProviderManagers) {
            manager.unregisterLocationRequest(listener);
        }
    }

    public void unregisterLocationPendingIntent(PendingIntent pendingIntent) {
        if (this.mLocationFreeze != null) {
            this.mLocationFreeze.removeLocationRequest(pendingIntent);
        }
        for (LocationProviderManager manager : this.mProviderManagers) {
            manager.unregisterLocationRequest(pendingIntent);
        }
    }

    public Location getLastLocation(String provider, LastLocationRequest request, String packageName, String attributionTag) {
        try {
            CallerIdentity identity = CallerIdentity.fromBinder(this.mContext, packageName, attributionTag);
            int permissionLevel = LocationPermissions.getPermissionLevel(this.mContext, identity.getUid(), identity.getPid());
            boolean z = true;
            if (Flags.enableLocationBypass()) {
                if (permissionLevel == 0) {
                    if (this.mContext.checkCallingPermission("android.permission.LOCATION_BYPASS") != 0) {
                        LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel, 1);
                    } else {
                        permissionLevel = 2;
                    }
                }
            } else {
                LocationPermissions.enforceLocationPermission(identity.getUid(), permissionLevel, 1);
            }
            if (identity.getPid() == Process.myPid() && attributionTag == null) {
                z = false;
            }
            Preconditions.checkArgument(z);
            request = validateLastLocationRequest(provider, request, identity);
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return null;
            }
            Location originLocation = manager.getLastLocation(request, identity, permissionLevel);
            if (mOplusLbsClass != null && "network".equals(provider)) {
                return mOplusLbsClass.getLastLocation(originLocation, request, permissionLevel);
            }
            return originLocation;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "getLastLocation catch IllegalArgumentException, provider = " + provider + "| lastLocationRequest = " + request + "| packageName = " + packageName + "| attributionTag = " + attributionTag);
            e.printStackTrace();
            throw e;
        }
    }

    private LastLocationRequest validateLastLocationRequest(String provider, LastLocationRequest request, CallerIdentity identity) {
        LastLocationRequest.Builder sanitized = new LastLocationRequest.Builder(request);
        LastLocationRequest request2 = sanitized.build();
        boolean isLocationProvider = this.mLocalService.isProvider(null, identity);
        if (request2.isHiddenFromAppOps()) {
            this.mContext.enforceCallingOrSelfPermission("android.permission.UPDATE_APP_OPS_STATS", "hiding from app ops requires android.permission.UPDATE_APP_OPS_STATS");
        }
        if (request2.isAdasGnssBypass()) {
            if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
                throw new IllegalArgumentException("adas gnss bypass requests are only allowed on automotive devices");
            }
            if (!IOplusSceneManager.APP_SCENE_GPS.equals(provider)) {
                throw new IllegalArgumentException("adas gnss bypass requests are only allowed on the \"gps\" provider");
            }
            if (!isLocationProvider) {
                LocationPermissions.enforceCallingOrSelfBypassPermission(this.mContext);
            }
        }
        if (request2.isLocationSettingsIgnored() && !isLocationProvider) {
            LocationPermissions.enforceCallingOrSelfBypassPermission(this.mContext);
        }
        return request2;
    }

    public LocationTime getGnssTimeMillis() {
        return this.mLocalService.getGnssTimeMillis();
    }

    public void injectLocation(Location location) throws Throwable {
        super.injectLocation_enforcePermission();
        Preconditions.checkArgument(location.isComplete());
        int userId = UserHandle.getCallingUserId();
        LocationProviderManager manager = getLocationProviderManager(location.getProvider());
        if (manager != null && manager.isEnabled(userId)) {
            manager.injectLastLocation((Location) Objects.requireNonNull(location), userId);
        }
    }

    public void requestGeofence(Geofence geofence, PendingIntent intent, String packageName, String attributionTag) {
        if (mOplusLbsClass != null) {
            mOplusLbsClass.recordGeoFenceRequest(packageName);
        }
        this.mGeofenceManager.addGeofence(geofence, intent, packageName, attributionTag);
    }

    public void removeGeofence(PendingIntent pendingIntent) {
        this.mGeofenceManager.removeGeofence(pendingIntent);
    }

    public void registerGnssStatusCallback(IGnssStatusListener listener, String packageName, String attributionTag, String listenerId) {
        if (mOplusLbsClass != null) {
            mOplusLbsClass.recordGnssStatusRequest(packageName);
        }
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.registerGnssStatusCallback(listener, packageName, attributionTag, listenerId);
        }
    }

    public void unregisterGnssStatusCallback(IGnssStatusListener listener) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.unregisterGnssStatusCallback(listener);
        }
    }

    public void registerGnssNmeaCallback(IGnssNmeaListener listener, String packageName, String attributionTag, String listenerId) {
        if (mOplusLbsClass != null) {
            mOplusLbsClass.recordNmeaRequest(packageName);
        }
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.registerGnssNmeaCallback(listener, packageName, attributionTag, listenerId);
        }
    }

    public void unregisterGnssNmeaCallback(IGnssNmeaListener listener) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.unregisterGnssNmeaCallback(listener);
        }
    }

    public void addGnssMeasurementsListener(GnssMeasurementRequest request, IGnssMeasurementsListener listener, String packageName, String attributionTag, String listenerId) {
        if (mOplusLbsClass != null) {
            mOplusLbsClass.recordGnssMeasurementRequest(packageName);
        }
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.addGnssMeasurementsListener(request, listener, packageName, attributionTag, listenerId);
        }
    }

    public void removeGnssMeasurementsListener(IGnssMeasurementsListener listener) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.removeGnssMeasurementsListener(listener);
        }
    }

    public void addGnssAntennaInfoListener(IGnssAntennaInfoListener listener, String packageName, String attributionTag, String listenerId) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.addGnssAntennaInfoListener(listener, packageName, attributionTag, listenerId);
        }
    }

    public void removeGnssAntennaInfoListener(IGnssAntennaInfoListener listener) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.removeGnssAntennaInfoListener(listener);
        }
    }

    public void addProviderRequestListener(IProviderRequestListener listener) {
        addProviderRequestListener_enforcePermission();
        for (LocationProviderManager manager : this.mProviderManagers) {
            if (manager.isVisibleToCaller()) {
                manager.addProviderRequestListener(listener);
            }
        }
    }

    public void removeProviderRequestListener(IProviderRequestListener listener) {
        for (LocationProviderManager manager : this.mProviderManagers) {
            manager.removeProviderRequestListener(listener);
        }
    }

    public void injectGnssMeasurementCorrections(GnssMeasurementCorrections corrections) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.injectGnssMeasurementCorrections(corrections);
        }
    }

    public GnssCapabilities getGnssCapabilities() {
        return this.mGnssManagerService == null ? new GnssCapabilities.Builder().build() : this.mGnssManagerService.getGnssCapabilities();
    }

    public List<GnssAntennaInfo> getGnssAntennaInfos() {
        if (this.mGnssManagerService == null) {
            return null;
        }
        return this.mGnssManagerService.getGnssAntennaInfos();
    }

    public void addGnssNavigationMessageListener(IGnssNavigationMessageListener listener, String packageName, String attributionTag, String listenerId) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.addGnssNavigationMessageListener(listener, packageName, attributionTag, listenerId);
        }
    }

    public void removeGnssNavigationMessageListener(IGnssNavigationMessageListener listener) {
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.removeGnssNavigationMessageListener(listener);
        }
    }

    public void sendExtraCommand(String provider, String command, Bundle extras) {
        if (mOplusLbsClass != null && !mOplusLbsClass.sendExtraCommand(provider, command, extras)) {
            return;
        }
        LocationPermissions.enforceCallingOrSelfLocationPermission(this.mContext, 1);
        this.mContext.enforceCallingOrSelfPermission("android.permission.ACCESS_LOCATION_EXTRA_COMMANDS", null);
        LocationProviderManager manager = getLocationProviderManager((String) Objects.requireNonNull(provider));
        if (manager != null) {
            manager.sendExtraCommand(Binder.getCallingUid(), Binder.getCallingPid(), (String) Objects.requireNonNull(command), extras);
        }
        this.mInjector.getLocationUsageLogger().logLocationApiUsage(0, 5, provider);
        this.mInjector.getLocationUsageLogger().logLocationApiUsage(1, 5, provider);
    }

    public ProviderProperties getProviderProperties(String provider) {
        LocationProviderManager manager = getLocationProviderManager(provider);
        if (provider.equals(IOplusSceneManager.APP_SCENE_GPS) && !mOplusLbsClass.isVirtualGpsVisibleOnlyForPad(Binder.getCallingUid())) {
            throw new IllegalArgumentException();
        }
        Preconditions.checkArgument(manager != null, "provider \"" + provider + "\" does not exist");
        return manager.getProperties();
    }

    public boolean isProviderPackage(String provider, String packageName, String attributionTag) {
        isProviderPackage_enforcePermission();
        for (LocationProviderManager manager : this.mProviderManagers) {
            if (provider == null || provider.equals(manager.getName())) {
                CallerIdentity identity = manager.getProviderIdentity();
                if (identity == null) {
                    continue;
                } else {
                    if (identity.getPackageName().equals(packageName) && (attributionTag == null || Objects.equals(identity.getAttributionTag(), attributionTag))) {
                        return true;
                    }
                    if ("network".equals(provider) && packageName != null && packageName.equals("com.google.android.gms")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public List<String> getProviderPackages(String provider) {
        getProviderPackages_enforcePermission();
        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            return Collections.emptyList();
        }
        CallerIdentity identity = manager.getProviderIdentity();
        if (identity == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(identity.getPackageName());
    }

    public void setExtraLocationControllerPackage(String packageName) {
        super.setExtraLocationControllerPackage_enforcePermission();
        synchronized (this.mLock) {
            this.mExtraLocationControllerPackage = packageName;
        }
    }

    public String getExtraLocationControllerPackage() {
        String str;
        synchronized (this.mLock) {
            str = this.mExtraLocationControllerPackage;
        }
        return str;
    }

    public void setExtraLocationControllerPackageEnabled(boolean enabled) {
        super.setExtraLocationControllerPackageEnabled_enforcePermission();
        synchronized (this.mLock) {
            this.mExtraLocationControllerPackageEnabled = enabled;
        }
    }

    public boolean isExtraLocationControllerPackageEnabled() {
        boolean z;
        synchronized (this.mLock) {
            z = this.mExtraLocationControllerPackageEnabled && this.mExtraLocationControllerPackage != null;
        }
        return z;
    }

    public void setLocationEnabledForUser(boolean enabled, int userId) {
        Log.i(TAG, "setLocationEnabledForUser enabled = " + enabled);
        if (mOplusLbsClass != null && enabled && (mOplusLbsClass.isStealthSecurity() || mOplusLbsClass.isSatelliteCommunicationEnable())) {
            return;
        }
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "setLocationEnabledForUser", null);
        this.mContext.enforceCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS", null);
        LocationManager.invalidateLocalLocationEnabledCaches();
        this.mInjector.getSettingsHelper().setLocationEnabled(enabled, userId2);
    }

    public boolean isLocationEnabledForUser(int userId) {
        if (mOplusLbsClass != null) {
            return mOplusLbsClass.getOplusLocationMode(userId);
        }
        return this.mInjector.getSettingsHelper().isLocationEnabled(ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "isLocationEnabledForUser", null));
    }

    public void setAdasGnssLocationEnabledForUser(final boolean enabled, int userId) {
        int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "setAdasGnssLocationEnabledForUser", null);
        LocationPermissions.enforceCallingOrSelfBypassPermission(this.mContext);
        this.mInjector.getLocationSettings().updateUserSettings(userId2, new Function() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda0
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((LocationUserSettings) obj).withAdasGnssLocationEnabled(enabled);
            }
        });
    }

    public boolean isAdasGnssLocationEnabledForUser(int userId) {
        return this.mInjector.getLocationSettings().getUserSettings(ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "isAdasGnssLocationEnabledForUser", null)).isAdasGnssLocationEnabled();
    }

    public boolean isProviderEnabledForUser(String provider, int userId) {
        if (mOplusLbsClass != null && mOplusLbsClass.isGpsEnableForSpecialApp(provider, userId, this.mContext.getPackageManager().getNameForUid(Binder.getCallingUid()))) {
            provider = "network";
        }
        return this.mLocalService.isProviderEnabledForUser(provider, userId);
    }

    public void setAutomotiveGnssSuspended(boolean suspended) {
        super.setAutomotiveGnssSuspended_enforcePermission();
        if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
            throw new IllegalStateException("setAutomotiveGnssSuspended only allowed on automotive devices");
        }
        if (this.mGnssManagerService != null) {
            this.mGnssManagerService.setAutomotiveGnssSuspended(suspended);
        }
    }

    public boolean isAutomotiveGnssSuspended() {
        super.isAutomotiveGnssSuspended_enforcePermission();
        if (!this.mContext.getPackageManager().hasSystemFeature("android.hardware.type.automotive")) {
            throw new IllegalStateException("isAutomotiveGnssSuspended only allowed on automotive devices");
        }
        if (this.mGnssManagerService != null) {
            return this.mGnssManagerService.isAutomotiveGnssSuspended();
        }
        return false;
    }

    public boolean isGeocodeAvailable() {
        return this.mGeocodeProvider != null || (mOplusLbsClass != null && mOplusLbsClass.isGeocodeAvailable());
    }

    public void reverseGeocode(ReverseGeocodeRequest request, IGeocodeCallback callback) {
        CallerIdentity identity = CallerIdentity.fromBinder(this.mContext, request.getCallingPackage(), request.getCallingAttributionTag());
        Preconditions.checkArgument(identity.getUid() == request.getCallingUid());
        if (mOplusLbsClass != null) {
            mOplusLbsClass.reverseGeocode(request, callback);
        } else if (this.mGeocodeProvider != null) {
            this.mGeocodeProvider.reverseGeocode(request, callback);
        } else {
            try {
                callback.onError((String) null);
            } catch (RemoteException e) {
            }
        }
    }

    public void forwardGeocode(ForwardGeocodeRequest request, IGeocodeCallback callback) {
        CallerIdentity identity = CallerIdentity.fromBinder(this.mContext, request.getCallingPackage(), request.getCallingAttributionTag());
        Preconditions.checkArgument(identity.getUid() == request.getCallingUid());
        if (mOplusLbsClass != null) {
            mOplusLbsClass.forwardGeocode(request, callback);
        } else if (this.mGeocodeProvider != null) {
            this.mGeocodeProvider.forwardGeocode(request, callback);
        } else {
            try {
                callback.onError((String) null);
            } catch (RemoteException e) {
            }
        }
    }

    public void addTestProvider(String provider, ProviderProperties properties, List<String> extraAttributionTags, String packageName, String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!this.mInjector.getAppOpsHelper().noteOp(58, identity)) {
            return;
        }
        if (mOplusLbsClass != null) {
            mOplusLbsClass.onAddMockProvider(packageName, provider);
        }
        LocationProviderManager manager = getOrAddLocationProviderManager(provider);
        manager.setMockProvider(new MockLocationProvider(properties, identity, new ArraySet(extraAttributionTags)));
    }

    public void removeTestProvider(String provider, String packageName, String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!packageName.equalsIgnoreCase(PackageManagerService.PLATFORM_PACKAGE_NAME) && !this.mInjector.getAppOpsHelper().noteOp(58, identity)) {
            return;
        }
        if (mOplusLbsClass != null) {
            mOplusLbsClass.onRemoveMockProvider(packageName, provider);
        }
        synchronized (this.mLock) {
            LocationProviderManager manager = getLocationProviderManager(provider);
            if (manager == null) {
                return;
            }
            manager.setMockProvider(null);
            if (!manager.hasProvider()) {
                removeLocationProviderManager(manager);
            }
        }
    }

    public void setTestProviderLocation(String provider, Location location, String packageName, String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!this.mInjector.getAppOpsHelper().noteOp(58, identity)) {
            return;
        }
        Preconditions.checkArgument(location.isComplete(), "incomplete location object, missing timestamp or accuracy?");
        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }
        manager.setMockProviderLocation(location);
    }

    public void setTestProviderEnabled(String provider, boolean enabled, String packageName, String attributionTag) {
        CallerIdentity identity = CallerIdentity.fromBinderUnsafe(packageName, attributionTag);
        if (!packageName.equalsIgnoreCase(PackageManagerService.PLATFORM_PACKAGE_NAME) && !this.mInjector.getAppOpsHelper().noteOp(58, identity)) {
            return;
        }
        LocationProviderManager manager = getLocationProviderManager(provider);
        if (manager == null) {
            throw new IllegalArgumentException("provider doesn't exist: " + provider);
        }
        manager.setMockProviderAllowed(enabled);
    }

    /* JADX WARN: Multi-variable type inference failed */
    public int handleShellCommand(ParcelFileDescriptor in, ParcelFileDescriptor out, ParcelFileDescriptor err, String[] args) {
        return new LocationShellCommand(this.mContext, this).exec(this, in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(), args);
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) throws Throwable {
        if (!DumpUtils.checkDumpPermission(this.mContext, TAG, pw)) {
            return;
        }
        if (mOplusLbsClass != null && mOplusLbsClass.dealDumpCommand(pw, args)) {
            return;
        }
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        if (args.length > 0) {
            LocationProviderManager manager = getLocationProviderManager(args[0]);
            if (manager != null) {
                ipw.println("Provider:");
                ipw.increaseIndent();
                manager.dump(fd, ipw, args);
                ipw.decreaseIndent();
                ipw.println("Event Log:");
                ipw.increaseIndent();
                LocationEventLog locationEventLog = LocationEventLog.EVENT_LOG;
                Objects.requireNonNull(ipw);
                locationEventLog.iterate(new Consumer() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda1
                    @Override // java.util.function.Consumer
                    public final void accept(Object obj) {
                        ipw.println((String) obj);
                    }
                }, manager.getName());
                ipw.decreaseIndent();
                return;
            }
            if ("--gnssmetrics".equals(args[0])) {
                if (this.mGnssManagerService != null) {
                    this.mGnssManagerService.dump(fd, ipw, args);
                    return;
                }
                return;
            }
        }
        ipw.println("Location Manager State:");
        ipw.increaseIndent();
        ipw.println("User Info:");
        ipw.increaseIndent();
        this.mInjector.getUserInfoHelper().dump(fd, ipw, args);
        ipw.decreaseIndent();
        ipw.println("Location Settings:");
        ipw.increaseIndent();
        this.mInjector.getSettingsHelper().dump(fd, ipw, args);
        this.mInjector.getLocationSettings().dump(fd, ipw, args);
        ipw.decreaseIndent();
        synchronized (this.mLock) {
            if (this.mExtraLocationControllerPackage != null) {
                ipw.println("Location Controller Extra Package: " + this.mExtraLocationControllerPackage + (this.mExtraLocationControllerPackageEnabled ? " [enabled]" : " [disabled]"));
            }
        }
        ipw.println("Location Providers:");
        ipw.increaseIndent();
        Iterator<LocationProviderManager> it = this.mProviderManagers.iterator();
        while (it.hasNext()) {
            it.next().dump(fd, ipw, args);
        }
        ipw.decreaseIndent();
        ipw.println("Historical Aggregate Location Provider Data:");
        ipw.increaseIndent();
        ArrayMap<String, ArrayMap<CallerIdentity, LocationEventLog.AggregateStats>> aggregateStats = LocationEventLog.EVENT_LOG.copyAggregateStats();
        for (int i = 0; i < aggregateStats.size(); i++) {
            ipw.print(aggregateStats.keyAt(i));
            ipw.println(":");
            ipw.increaseIndent();
            ArrayMap<CallerIdentity, LocationEventLog.AggregateStats> providerStats = aggregateStats.valueAt(i);
            for (int j = 0; j < providerStats.size(); j++) {
                ipw.print(providerStats.keyAt(j));
                ipw.print(": ");
                providerStats.valueAt(j).updateTotals();
                ipw.println(providerStats.valueAt(j));
            }
            ipw.decreaseIndent();
        }
        ipw.decreaseIndent();
        ipw.println("Historical Aggregate Gnss Measurement Provider Data:");
        ipw.increaseIndent();
        ArrayMap<CallerIdentity, LocationEventLog.GnssMeasurementAggregateStats> gnssAggregateStats = LocationEventLog.EVENT_LOG.copyGnssMeasurementAggregateStats();
        for (int i2 = 0; i2 < gnssAggregateStats.size(); i2++) {
            ipw.print(gnssAggregateStats.keyAt(i2));
            ipw.print(": ");
            gnssAggregateStats.valueAt(i2).updateTotals();
            ipw.println(gnssAggregateStats.valueAt(i2));
        }
        ipw.decreaseIndent();
        if (this.mGnssManagerService != null) {
            ipw.println("GNSS Manager:");
            ipw.increaseIndent();
            this.mGnssManagerService.dump(fd, ipw, args);
            ipw.decreaseIndent();
        }
        ipw.println("Geofence Manager:");
        ipw.increaseIndent();
        this.mGeofenceManager.dump(fd, ipw, args);
        ipw.decreaseIndent();
        ipw.println("Event Log:");
        ipw.increaseIndent();
        LocationEventLog locationEventLog2 = LocationEventLog.EVENT_LOG;
        Objects.requireNonNull(ipw);
        locationEventLog2.iterate(new Consumer() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda1
            @Override // java.util.function.Consumer
            public final void accept(Object obj) {
                ipw.println((String) obj);
            }
        });
        ipw.decreaseIndent();
        if (mOplusLbsClass != null) {
            mOplusLbsClass.dumpOplusContent(pw);
        }
        if (this.mGeofenceProxy != null) {
            ipw.println("GeofenceHardware Manager:");
            ipw.increaseIndent();
            ipw.println("Service: " + (this.mGeofenceProxy.getServiceName() == null ? "unregistered" : this.mGeofenceProxy.getServiceName()));
            ipw.println("Ids: [" + GeofenceHardwareImpl.getInstance(this.mContext).getGeofenceIds() + "]");
        }
    }

    @Override // com.android.server.location.provider.LocationProviderManager.StateChangedListener
    public void onStateChanged(String provider, AbstractLocationProvider.State oldState, AbstractLocationProvider.State newState) {
        if (!Objects.equals(oldState.identity, newState.identity)) {
            refreshAppOpsRestrictions(-1);
        }
        if (!oldState.extraAttributionTags.equals(newState.extraAttributionTags) || !Objects.equals(oldState.identity, newState.identity)) {
            synchronized (this.mLock) {
                final LocationManagerInternal.LocationPackageTagsListener listener = this.mLocationTagsChangedListener;
                if (listener != null) {
                    final int oldUid = oldState.identity != null ? oldState.identity.getUid() : -1;
                    final int newUid = newState.identity != null ? newState.identity.getUid() : -1;
                    if (oldUid != -1) {
                        final PackageTagsList tags = calculateAppOpsLocationSourceTags(oldUid);
                        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda3
                            @Override // java.lang.Runnable
                            public final void run() {
                                listener.onLocationPackageTagsChanged(oldUid, tags);
                            }
                        });
                    }
                    if (newUid != -1 && newUid != oldUid) {
                        final PackageTagsList tags2 = calculateAppOpsLocationSourceTags(newUid);
                        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.location.LocationManagerService$$ExternalSyntheticLambda4
                            @Override // java.lang.Runnable
                            public final void run() {
                                listener.onLocationPackageTagsChanged(newUid, tags2);
                            }
                        });
                    }
                }
            }
        }
    }

    /* JADX WARN: Multi-variable type inference failed */
    private void refreshAppOpsRestrictions(int userId) {
        PackageTagsList allowedPackages;
        if (userId == -1) {
            int[] runningUserIds = this.mInjector.getUserInfoHelper().getRunningUserIds();
            for (int i : runningUserIds) {
                refreshAppOpsRestrictions(i);
            }
            return;
        }
        Preconditions.checkArgument(userId >= 0);
        boolean enabled = this.mInjector.getSettingsHelper().isLocationEnabled(userId);
        if (enabled) {
            allowedPackages = null;
        } else {
            PackageTagsList.Builder builder = new PackageTagsList.Builder();
            for (LocationProviderManager manager : this.mProviderManagers) {
                CallerIdentity identity = manager.getProviderIdentity();
                if (identity != null) {
                    builder.add(identity.getPackageName(), identity.getAttributionTag());
                }
            }
            builder.add(this.mInjector.getSettingsHelper().getIgnoreSettingsAllowlist());
            builder.add(this.mInjector.getSettingsHelper().getAdasAllowlist());
            PackageTagsList allowedPackages2 = builder.build();
            allowedPackages = allowedPackages2;
        }
        AppOpsManager appOpsManager = (AppOpsManager) Objects.requireNonNull((AppOpsManager) this.mContext.getSystemService(AppOpsManager.class));
        appOpsManager.setUserRestrictionForUser(0, !enabled, this, allowedPackages, userId);
        appOpsManager.setUserRestrictionForUser(1, !enabled, this, allowedPackages, userId);
    }

    PackageTagsList calculateAppOpsLocationSourceTags(int uid) {
        PackageTagsList.Builder builder = new PackageTagsList.Builder();
        for (LocationProviderManager manager : this.mProviderManagers) {
            AbstractLocationProvider.State managerState = manager.getState();
            if (managerState.identity != null && managerState.identity.getUid() == uid) {
                builder.add(managerState.identity.getPackageName(), managerState.extraAttributionTags);
                if (managerState.extraAttributionTags.isEmpty() || managerState.identity.getAttributionTag() != null) {
                    builder.add(managerState.identity.getPackageName(), managerState.identity.getAttributionTag());
                } else {
                    Log.e(TAG, manager.getName() + " provider has specified a null attribution tag and a non-empty set of extra attribution tags - dropping the null attribution tag");
                }
            }
        }
        return builder.build();
    }

    /* JADX INFO: Access modifiers changed from: private */
    class LocalService extends LocationManagerInternal {
        LocalService() {
        }

        public boolean isProviderEnabledForUser(String provider, int userId) {
            int userId2 = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(), userId, false, false, "isProviderEnabledForUser", null);
            LocationProviderManager manager = LocationManagerService.this.getLocationProviderManager(provider);
            if (manager == null) {
                return false;
            }
            return manager.isEnabled(userId2);
        }

        public void addProviderEnabledListener(String provider, LocationManagerInternal.ProviderEnabledListener listener) {
            LocationProviderManager manager = (LocationProviderManager) Objects.requireNonNull(LocationManagerService.this.getLocationProviderManager(provider));
            manager.addEnabledListener(listener);
        }

        public void removeProviderEnabledListener(String provider, LocationManagerInternal.ProviderEnabledListener listener) {
            LocationProviderManager manager = (LocationProviderManager) Objects.requireNonNull(LocationManagerService.this.getLocationProviderManager(provider));
            manager.removeEnabledListener(listener);
        }

        public boolean isProvider(String provider, CallerIdentity identity) {
            for (LocationProviderManager manager : LocationManagerService.this.mProviderManagers) {
                if (provider == null || provider.equals(manager.getName())) {
                    if (identity.equals(manager.getProviderIdentity()) && manager.isVisibleToCaller()) {
                        return true;
                    }
                }
            }
            return false;
        }

        public LocationTime getGnssTimeMillis() {
            Location location;
            LocationProviderManager gpsManager = LocationManagerService.this.getLocationProviderManager(IOplusSceneManager.APP_SCENE_GPS);
            if (gpsManager == null || (location = gpsManager.getLastLocationUnsafe(-1, 2, false, Long.MAX_VALUE)) == null) {
                return null;
            }
            return new LocationTime(location.getTime(), location.getElapsedRealtimeNanos());
        }

        public void setLocationPackageTagsListener(final LocationManagerInternal.LocationPackageTagsListener listener) {
            synchronized (LocationManagerService.this.mLock) {
                LocationManagerService.this.mLocationTagsChangedListener = listener;
                if (listener != null) {
                    ArraySet<Integer> uids = new ArraySet<>(LocationManagerService.this.mProviderManagers.size());
                    for (LocationProviderManager manager : LocationManagerService.this.mProviderManagers) {
                        CallerIdentity identity = manager.getProviderIdentity();
                        if (identity != null) {
                            uids.add(Integer.valueOf(identity.getUid()));
                        }
                    }
                    Iterator<Integer> it = uids.iterator();
                    while (it.hasNext()) {
                        final int uid = it.next().intValue();
                        final PackageTagsList tags = LocationManagerService.this.calculateAppOpsLocationSourceTags(uid);
                        if (!tags.isEmpty()) {
                            FgThread.getHandler().post(new Runnable() { // from class: com.android.server.location.LocationManagerService$LocalService$$ExternalSyntheticLambda0
                                @Override // java.lang.Runnable
                                public final void run() {
                                    listener.onLocationPackageTagsChanged(uid, tags);
                                }
                            });
                        }
                    }
                }
            }
        }
    }

    private static final class SystemInjector implements Injector {
        private final AlarmHelper mAlarmHelper;
        private final SystemAppForegroundHelper mAppForegroundHelper;
        private final SystemAppOpsHelper mAppOpsHelper;
        private final Context mContext;
        private final SystemDeviceIdleHelper mDeviceIdleHelper;
        private SystemEmergencyHelper mEmergencyCallHelper;
        private final SystemLocationPermissionsHelper mLocationPermissionsHelper;
        private final SystemLocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
        private final LocationSettings mLocationSettings;
        private final PackageResetHelper mPackageResetHelper;
        private final SystemScreenInteractiveHelper mScreenInteractiveHelper;
        private final SystemSettingsHelper mSettingsHelper;
        private boolean mSystemReady;
        private final SystemUserInfoHelper mUserInfoHelper;
        private final SystemDeviceStationaryHelper mDeviceStationaryHelper = new SystemDeviceStationaryHelper();
        private final LocationUsageLogger mLocationUsageLogger = new LocationUsageLogger();

        SystemInjector(Context context, SystemUserInfoHelper userInfoHelper) {
            this.mContext = context;
            this.mUserInfoHelper = userInfoHelper;
            this.mLocationSettings = new LocationSettings(context);
            this.mAlarmHelper = new SystemAlarmHelper(context);
            this.mAppOpsHelper = new SystemAppOpsHelper(context);
            this.mLocationPermissionsHelper = new SystemLocationPermissionsHelper(context, this.mAppOpsHelper);
            this.mSettingsHelper = new SystemSettingsHelper(context);
            this.mAppForegroundHelper = new SystemAppForegroundHelper(context);
            this.mLocationPowerSaveModeHelper = new SystemLocationPowerSaveModeHelper(context);
            this.mScreenInteractiveHelper = new SystemScreenInteractiveHelper(context);
            this.mDeviceIdleHelper = new SystemDeviceIdleHelper(context);
            this.mPackageResetHelper = new SystemPackageResetHelper(context);
        }

        synchronized void onSystemReady() {
            this.mUserInfoHelper.onSystemReady();
            this.mAppOpsHelper.onSystemReady();
            this.mLocationPermissionsHelper.onSystemReady();
            this.mSettingsHelper.onSystemReady();
            this.mAppForegroundHelper.onSystemReady();
            this.mLocationPowerSaveModeHelper.onSystemReady();
            this.mScreenInteractiveHelper.onSystemReady();
            this.mDeviceStationaryHelper.onSystemReady();
            this.mDeviceIdleHelper.onSystemReady();
            if (this.mEmergencyCallHelper != null) {
                this.mEmergencyCallHelper.onSystemReady();
            }
            this.mSystemReady = true;
        }

        @Override // com.android.server.location.injector.Injector
        public UserInfoHelper getUserInfoHelper() {
            return this.mUserInfoHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public LocationSettings getLocationSettings() {
            return this.mLocationSettings;
        }

        @Override // com.android.server.location.injector.Injector
        public AlarmHelper getAlarmHelper() {
            return this.mAlarmHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public AppOpsHelper getAppOpsHelper() {
            return this.mAppOpsHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public LocationPermissionsHelper getLocationPermissionsHelper() {
            return this.mLocationPermissionsHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public SettingsHelper getSettingsHelper() {
            return this.mSettingsHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public AppForegroundHelper getAppForegroundHelper() {
            return this.mAppForegroundHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public LocationPowerSaveModeHelper getLocationPowerSaveModeHelper() {
            return this.mLocationPowerSaveModeHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public ScreenInteractiveHelper getScreenInteractiveHelper() {
            return this.mScreenInteractiveHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public DeviceStationaryHelper getDeviceStationaryHelper() {
            return this.mDeviceStationaryHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public DeviceIdleHelper getDeviceIdleHelper() {
            return this.mDeviceIdleHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public synchronized EmergencyHelper getEmergencyHelper() {
            if (this.mEmergencyCallHelper == null) {
                this.mEmergencyCallHelper = new SystemEmergencyHelper(this.mContext);
                if (this.mSystemReady) {
                    this.mEmergencyCallHelper.onSystemReady();
                }
            }
            return this.mEmergencyCallHelper;
        }

        @Override // com.android.server.location.injector.Injector
        public LocationUsageLogger getLocationUsageLogger() {
            return this.mLocationUsageLogger;
        }

        @Override // com.android.server.location.injector.Injector
        public PackageResetHelper getPackageResetHelper() {
            return this.mPackageResetHelper;
        }
    }

    public ILocationManagerServiceWrapper getWrapper() {
        return this.mLmsWrapper;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void oplusSystemReady(LocationManagerService service) {
        this.mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, this.mContext);
        this.mVirtualProvider = (IVirtualGnssLocationProvider) OplusLbsFactory.getInstance().getFeature(IVirtualGnssLocationProvider.DEFAULT, this.mContext);
        this.mVirtualGnssHal = (IVirtualGnssHal) OplusLbsFactory.getInstance().getFeature(IVirtualGnssHal.DEFAULT, this.mContext);
        mOplusLbsClass = (IOplusLBSMainClass) OplusLbsFactory.getInstance().getFeature(IOplusLBSMainClass.DEFAULT, this.mContext);
        if (mOplusLbsClass != null) {
            mOplusLbsClass.oplusSystemReady(service);
            mOplusLbsClass.initFlpCoordinator(this.mContext);
            LocationProviderManager.oplusSystemReady(this.mContext);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void oplusSystemThirdPartyAppsCanStart() {
        if (mOplusLbsClass != null) {
            mOplusLbsClass.oplusSystemThirdPartyAppsCanStart();
        }
    }

    private class LocationManagerServiceWrapper implements ILocationManagerServiceWrapper {
        private LocationManagerServiceWrapper() {
        }

        @Override // com.android.server.location.ILocationManagerServiceWrapper
        public LocationProviderManager getLocationProviderManager(String providerName) {
            return LocationManagerService.this.getLocationProviderManager(providerName);
        }

        @Override // com.android.server.location.ILocationManagerServiceWrapper
        public void addLocationProviderManager(LocationProviderManager locationProviderManager, AbstractLocationProvider abstractLocationProvider) {
            LocationManagerService.this.addLocationProviderManager(locationProviderManager, abstractLocationProvider);
        }

        @Override // com.android.server.location.ILocationManagerServiceWrapper
        public void removeLocationProviderManager(LocationProviderManager locationProviderManager) {
            LocationManagerService.this.removeLocationProviderManager(locationProviderManager);
        }

        @Override // com.android.server.location.ILocationManagerServiceWrapper
        public LocationProviderManager creatLocationProviderManager(String providerName) {
            return new LocationProviderManager(LocationManagerService.this.mContext, LocationManagerService.this.mInjector, providerName, LocationManagerService.this.mPassiveManager);
        }

        @Override // com.android.server.location.ILocationManagerServiceWrapper
        public IGpsGeofenceHardware getGpsGeofenceHardware() {
            if (LocationManagerService.this.mGnssManagerService != null) {
                return LocationManagerService.this.mGnssManagerService.getGnssGeofenceProxy();
            }
            return null;
        }
    }
}
