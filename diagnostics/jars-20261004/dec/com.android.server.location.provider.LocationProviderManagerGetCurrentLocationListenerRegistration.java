package com.android.server.location.provider;

import android.app.AlarmManager;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.content.Intent;
import android.location.ILocationCallback;
import android.location.ILocationListener;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.altitude.AltitudeConverter;
import android.location.provider.IProviderRequestListener;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import com.android.internal.hidden_from_bootclasspath.android.location.flags.Flags;
import com.android.internal.listeners.ListenerExecutor;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.am.IOplusSceneManager;
import com.android.server.location.LocationManagerService;
import com.android.server.location.LocationPermissions;
import com.android.server.location.common.OplusLbsFactory;
import com.android.server.location.eventlog.LocationEventLog;
import com.android.server.location.fudger.LocationFudger;
import com.android.server.location.fudger.LocationFudgerCache;
import com.android.server.location.injector.AlarmHelper;
import com.android.server.location.injector.AppForegroundHelper;
import com.android.server.location.injector.AppOpsHelper;
import com.android.server.location.injector.EmergencyHelper;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.LocationPermissionsHelper;
import com.android.server.location.injector.LocationPowerSaveModeHelper;
import com.android.server.location.injector.LocationUsageLogger;
import com.android.server.location.injector.PackageResetHelper;
import com.android.server.location.injector.ScreenInteractiveHelper;
import com.android.server.location.injector.SettingsHelper;
import com.android.server.location.injector.UserInfoHelper;
import com.android.server.location.interfaces.ILocationFreezeProc;
import com.android.server.location.interfaces.IOplusLBSMainClass;
import com.android.server.location.listeners.ListenerMultiplexer;
import com.android.server.location.listeners.RemovableListenerRegistration;
import com.android.server.location.settings.LocationSettings;
import com.android.server.location.settings.LocationUserSettings;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/* JADX INFO: loaded from: classes2.dex */
public class LocationProviderManager extends ListenerMultiplexer<Object, LocationTransport, Registration, ProviderRequest> implements AbstractLocationProvider.Listener {
    private static final long DEFAULT_REGION_NLP_REPORT_INTERVAL = 30000;
    private static final float FASTEST_INTERVAL_JITTER_PERCENTAGE = 0.1f;
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 30000;
    private static final int MAX_FASTEST_INTERVAL_JITTER_MS = 30000;
    private static final long MAX_GET_CURRENT_LOCATION_TIMEOUT_MS = 30000;
    private static final long MAX_HIGH_POWER_INTERVAL_MS = 300000;
    private static final long MIN_COARSE_INTERVAL_MS = 600000;
    private static final long MIN_REQUEST_DELAY_MS = 30000;
    private static final int STATE_STARTED = 0;
    private static final int STATE_STOPPED = 2;
    private static final int STATE_STOPPING = 1;
    private static final long TEMPORARY_APP_ALLOWLIST_DURATION_MS = 10000;
    private static final String TEST_PROVIDER = "test_provider";
    private static final String WAKELOCK_TAG = "*location*";
    private static final long WAKELOCK_TIMEOUT_MS = 30000;
    private final SettingsHelper.GlobalSettingChangedListener mAdasPackageAllowlistChangedListener;
    protected final AlarmHelper mAlarmHelper;
    private final AltitudeConverter mAltitudeConverter;
    private final AppForegroundHelper.AppForegroundListener mAppForegroundChangedListener;
    protected final AppForegroundHelper mAppForegroundHelper;
    protected final AppOpsHelper mAppOpsHelper;
    private final SettingsHelper.GlobalSettingChangedListener mBackgroundThrottleIntervalChangedListener;
    private final SettingsHelper.GlobalSettingChangedListener mBackgroundThrottlePackageWhitelistChangedListener;
    protected final Context mContext;
    private AlarmManager.OnAlarmListener mDelayedRegister;
    protected final EmergencyHelper mEmergencyHelper;
    private final EmergencyHelper.EmergencyStateChangedListener mEmergencyStateChangedListener;
    private final SparseBooleanArray mEnabled;
    private final ArrayList<LocationManagerInternal.ProviderEnabledListener> mEnabledListeners;
    private final SettingsHelper.GlobalSettingChangedListener mIgnoreSettingsPackageWhitelistChangedListener;
    private volatile boolean mIsAltitudeConverterIdle;
    private final SparseArray<LastLocation> mLastLocations;
    private final SettingsHelper.UserSettingChangedListener mLocationEnabledChangedListener;
    protected final LocationFudger mLocationFudger;
    protected final LocationManagerInternal mLocationManagerInternal;
    private final SettingsHelper.UserSettingChangedListener mLocationPackageBlacklistChangedListener;
    protected final LocationPermissionsHelper mLocationPermissionsHelper;
    private final LocationPermissionsHelper.LocationPermissionsListener mLocationPermissionsListener;
    private final LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener mLocationPowerSaveModeChangedListener;
    protected final LocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
    private ILocationProviderManagerWrapper mLocationProviderManagerWrapper;
    protected final LocationSettings mLocationSettings;
    protected final LocationUsageLogger mLocationUsageLogger;
    private final LocationSettings.LocationUserSettingsListener mLocationUserSettingsListener;
    protected final String mName;
    private final PackageResetHelper mPackageResetHelper;
    private final PackageResetHelper.Responder mPackageResetResponder;
    private final PassiveLocationProviderManager mPassiveManager;
    protected final MockableLocationProvider mProvider;
    private final CopyOnWriteArrayList<IProviderRequestListener> mProviderRequestListeners;
    private final Collection<String> mRequiredPermissions;
    private final ScreenInteractiveHelper.ScreenInteractiveChangedListener mScreenInteractiveChangedListener;
    protected final ScreenInteractiveHelper mScreenInteractiveHelper;
    protected final SettingsHelper mSettingsHelper;
    private int mState;
    private StateChangedListener mStateChangedListener;
    private final UserInfoHelper.UserListener mUserChangedListener;
    protected final UserInfoHelper mUserHelper;
    private static IOplusLBSMainClass mOplusLbsClass = null;
    private static ILocationFreezeProc mLocationFreeze = null;

    protected interface LocationTransport {
        void deliverOnFlushComplete(int i) throws Exception;

        void deliverOnLocationChanged(LocationResult locationResult, IRemoteCallback iRemoteCallback) throws Exception;
    }

    protected interface ProviderTransport {
        void deliverOnProviderEnabledChanged(String str, boolean z) throws Exception;
    }

    @Retention(RetentionPolicy.SOURCE)
    private @interface State {
    }

    public interface StateChangedListener {
        void onStateChanged(String str, AbstractLocationProvider.State state, AbstractLocationProvider.State state2);
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected /* bridge */ /* synthetic */ ProviderRequest mergeRegistrations(Collection collection) {
        return mergeRegistrations((Collection<Registration>) collection);
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected /* bridge */ /* synthetic */ boolean registerWithService(ProviderRequest providerRequest, Collection collection) {
        return registerWithService2(providerRequest, (Collection<Registration>) collection);
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected /* bridge */ /* synthetic */ boolean reregisterWithService(ProviderRequest providerRequest, ProviderRequest providerRequest2, Collection collection) {
        return reregisterWithService2(providerRequest, providerRequest2, (Collection<Registration>) collection);
    }

    protected static final class LocationListenerTransport implements LocationTransport, ProviderTransport {
        private final ILocationListener mListener;

        LocationListenerTransport(ILocationListener listener) {
            this.mListener = (ILocationListener) Objects.requireNonNull(listener);
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationTransport
        public void deliverOnLocationChanged(LocationResult locationResult, IRemoteCallback onCompleteCallback) throws RemoteException {
            try {
                this.mListener.onLocationChanged(locationResult.asList(), onCompleteCallback);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationTransport
        public void deliverOnFlushComplete(int requestCode) throws RemoteException {
            try {
                this.mListener.onFlushComplete(requestCode);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.ProviderTransport
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled) throws RemoteException {
            try {
                this.mListener.onProviderEnabledChanged(provider, enabled);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    protected static final class LocationPendingIntentTransport implements LocationTransport, ProviderTransport {
        private final Context mContext;
        private final PendingIntent mPendingIntent;

        public LocationPendingIntentTransport(Context context, PendingIntent pendingIntent) {
            this.mContext = context;
            this.mPendingIntent = pendingIntent;
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationTransport
        public void deliverOnLocationChanged(LocationResult locationResult, final IRemoteCallback onCompleteCallback) throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            options.setTemporaryAppAllowlist(10000L, 0, FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO__EXEMPTION_REASON__REASON_LOCATION_PROVIDER, "");
            Intent intent = new Intent().putExtra("location", locationResult.getLastLocation());
            if (locationResult.size() > 1) {
                intent.putExtra("locations", (Parcelable[]) locationResult.asList().toArray(new Location[0]));
            }
            Runnable callback = null;
            if (onCompleteCallback != null) {
                callback = new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$LocationPendingIntentTransport$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        LocationProviderManager.LocationPendingIntentTransport.lambda$deliverOnLocationChanged$0(onCompleteCallback);
                    }
                };
            }
            PendingIntentSender.send(this.mPendingIntent, this.mContext, intent, callback, options.toBundle());
        }

        static /* synthetic */ void lambda$deliverOnLocationChanged$0(IRemoteCallback onCompleteCallback) {
            try {
                onCompleteCallback.sendResult((Bundle) null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationTransport
        public void deliverOnFlushComplete(int requestCode) throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            this.mPendingIntent.send(this.mContext, 0, new Intent().putExtra("flushComplete", requestCode), null, null, null, options.toBundle());
        }

        @Override // com.android.server.location.provider.LocationProviderManager.ProviderTransport
        public void deliverOnProviderEnabledChanged(String provider, boolean enabled) throws PendingIntent.CanceledException {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setDontSendToRestrictedApps(true);
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            this.mPendingIntent.send(this.mContext, 0, new Intent().putExtra("providerEnabled", enabled), null, null, null, options.toBundle());
        }
    }

    protected static final class GetCurrentLocationTransport implements LocationTransport {
        private final ILocationCallback mCallback;

        GetCurrentLocationTransport(ILocationCallback callback) {
            this.mCallback = (ILocationCallback) Objects.requireNonNull(callback);
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationTransport
        public void deliverOnLocationChanged(LocationResult locationResult, IRemoteCallback onCompleteCallback) throws RemoteException {
            Preconditions.checkState(onCompleteCallback == null);
            try {
                if (locationResult != null) {
                    this.mCallback.onLocation(locationResult.getLastLocation());
                } else {
                    this.mCallback.onLocation((Location) null);
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationTransport
        public void deliverOnFlushComplete(int requestCode) {
        }
    }

    protected abstract class Registration extends RemovableListenerRegistration<Object, LocationTransport> {
        private final LocationRequest mBaseRequest;
        private boolean mBypassPermitted;
        private boolean mForeground;
        private final CallerIdentity mIdentity;
        private boolean mIsUsingHighPower;
        private Location mLastLocation;
        private final int mPermissionLevel;
        private boolean mPermitted;
        private LocationRequest mProviderLocationRequest;

        /* JADX INFO: Access modifiers changed from: package-private */
        public abstract ListenerExecutor.ListenerOperation<LocationTransport> acceptLocationChange(LocationResult locationResult);

        protected Registration(LocationRequest request, CallerIdentity identity, Executor executor, LocationTransport transport, int permissionLevel) {
            super(executor, transport);
            this.mLastLocation = null;
            Preconditions.checkArgument(identity.getListenerId() != null);
            Preconditions.checkArgument(permissionLevel > 0);
            Preconditions.checkArgument(!request.getWorkSource().isEmpty());
            this.mBaseRequest = (LocationRequest) Objects.requireNonNull(request);
            this.mIdentity = (CallerIdentity) Objects.requireNonNull(identity);
            this.mPermissionLevel = permissionLevel;
            this.mProviderLocationRequest = request;
        }

        public final CallerIdentity getIdentity() {
            return this.mIdentity;
        }

        public final LocationRequest getRequest() {
            LocationRequest locationRequest;
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                locationRequest = this.mProviderLocationRequest;
            }
            return locationRequest;
        }

        @Override // com.android.server.location.listeners.RemovableListenerRegistration
        protected void onRegister() {
            super.onRegister();
            Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider added registration from " + getIdentity() + " -> " + getRequest() + " permissionLevel: " + this.mPermissionLevel + " permitted: " + LocationProviderManager.this.mLocationPermissionsHelper.hasLocationPermissions(this.mPermissionLevel, getIdentity()));
            LocationEventLog.EVENT_LOG.logProviderClientRegistered(LocationProviderManager.this.mName, getIdentity(), this.mBaseRequest);
            onLocationPermissionsChanged();
            onBypassLocationPermissionsChanged(LocationProviderManager.this.mEmergencyHelper.isInEmergency(0L));
            this.mForeground = LocationProviderManager.this.mAppForegroundHelper.isAppForeground(getIdentity().getUid());
            if (LocationProviderManager.mOplusLbsClass != null) {
                LocationProviderManager.mOplusLbsClass.startRequesting(getIdentity(), LocationProviderManager.this.mName, getRequest(), this.mForeground, "" + getKey().hashCode());
            }
            this.mProviderLocationRequest = calculateProviderLocationRequest();
            this.mIsUsingHighPower = isUsingHighPower();
            if (this.mForeground) {
                LocationEventLog.EVENT_LOG.logProviderClientForeground(LocationProviderManager.this.mName, getIdentity());
            }
        }

        @Override // com.android.server.location.listeners.RemovableListenerRegistration, com.android.server.location.listeners.ListenerRegistration
        protected void onUnregister() {
            LocationEventLog.EVENT_LOG.logProviderClientUnregistered(LocationProviderManager.this.mName, getIdentity());
            if (LocationProviderManager.mOplusLbsClass != null) {
                LocationProviderManager.mOplusLbsClass.stopRequesting(getIdentity(), LocationProviderManager.this.mName, getRequest(), "" + getKey().hashCode());
            }
            Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider removed registration from " + getIdentity());
            if (LocationProviderManager.mLocationFreeze == null) {
                LocationProviderManager.mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, LocationProviderManager.this.mContext);
            }
            if (LocationProviderManager.mLocationFreeze != null) {
                LocationProviderManager.mLocationFreeze.removeLocationRequestDone(LocationProviderManager.this.mName, getKey(), getIdentity());
            }
            super.onUnregister();
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        protected void onActive() {
            LocationEventLog.EVENT_LOG.logProviderClientActive(LocationProviderManager.this.mName, getIdentity());
            if (!getRequest().isHiddenFromAppOps()) {
                LocationProviderManager.this.mAppOpsHelper.startOpNoThrow(41, getIdentity());
            }
            onHighPowerUsageChanged();
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        protected void onInactive() {
            onHighPowerUsageChanged();
            if (!getRequest().isHiddenFromAppOps()) {
                LocationProviderManager.this.mAppOpsHelper.finishOp(41, getIdentity());
            }
            LocationEventLog.EVENT_LOG.logProviderClientInactive(LocationProviderManager.this.mName, getIdentity());
        }

        final void setLastDeliveredLocation(Location location) {
            this.mLastLocation = location;
        }

        public final Location getLastDeliveredLocation() {
            Location location;
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                location = this.mLastLocation;
            }
            return location;
        }

        public int getPermissionLevel() {
            int i;
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                i = this.mPermissionLevel;
            }
            return i;
        }

        public final boolean isForeground() {
            boolean z;
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                z = this.mForeground;
            }
            return z;
        }

        public final boolean isPermitted() {
            boolean z;
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                z = this.mPermitted || this.mBypassPermitted;
            }
            return z;
        }

        public final boolean isOnlyBypassPermitted() {
            boolean z;
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                z = this.mBypassPermitted && !this.mPermitted;
            }
            return z;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$flush$1(final int requestCode) {
            executeOperation(new ListenerExecutor.ListenerOperation() { // from class: com.android.server.location.provider.LocationProviderManager$Registration$$ExternalSyntheticLambda0
                public final void operate(Object obj) throws Exception {
                    ((LocationProviderManager.LocationTransport) obj).deliverOnFlushComplete(requestCode);
                }
            });
        }

        public final void flush(final int requestCode) {
            LocationProviderManager.this.mProvider.getController().flush(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$Registration$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$flush$1(requestCode);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: protected */
        @Override // com.android.server.location.listeners.RemovableListenerRegistration
        public final ListenerMultiplexer<Object, ? super LocationTransport, ?, ?> getOwner() {
            return LocationProviderManager.this;
        }

        final boolean onProviderPropertiesChanged() {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                onHighPowerUsageChanged();
            }
            return false;
        }

        private void onHighPowerUsageChanged() {
            boolean isUsingHighPower = isUsingHighPower();
            if (isUsingHighPower != this.mIsUsingHighPower) {
                this.mIsUsingHighPower = isUsingHighPower;
                if (!getRequest().isHiddenFromAppOps()) {
                    if (this.mIsUsingHighPower) {
                        LocationProviderManager.this.mAppOpsHelper.startOpNoThrow(42, getIdentity());
                    } else {
                        LocationProviderManager.this.mAppOpsHelper.finishOp(42, getIdentity());
                    }
                }
            }
        }

        private boolean isUsingHighPower() {
            ProviderProperties properties = LocationProviderManager.this.getProperties();
            return properties != null && isActive() && getRequest().getIntervalMillis() < 300000 && properties.getPowerUsage() == 3;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public final boolean onLocationPermissionsChanged(String packageName) {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                if (packageName != null) {
                    if (!getIdentity().getPackageName().equals(packageName)) {
                        return false;
                    }
                }
                return onLocationPermissionsChanged();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public final boolean onLocationPermissionsChanged(int uid) {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                if (getIdentity().getUid() != uid) {
                    return false;
                }
                return onLocationPermissionsChanged();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public boolean onBypassLocationPermissionsChanged(boolean isInEmergency) {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                boolean bypassPermitted = Flags.enableLocationBypass() && isInEmergency && LocationProviderManager.this.mContext.checkPermission("android.permission.LOCATION_BYPASS", this.mIdentity.getPid(), this.mIdentity.getUid()) == 0;
                if (this.mBypassPermitted == bypassPermitted) {
                    return false;
                }
                if (LocationManagerService.D) {
                    Log.v(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider package " + getIdentity().getPackageName() + " bypass permitted = " + bypassPermitted);
                }
                this.mBypassPermitted = bypassPermitted;
                return true;
            }
        }

        private boolean onLocationPermissionsChanged() {
            boolean permitted = LocationProviderManager.this.mLocationPermissionsHelper.hasLocationPermissions(this.mPermissionLevel, getIdentity());
            if (permitted != this.mPermitted) {
                if (LocationManagerService.D) {
                    Log.v(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider package " + getIdentity().getPackageName() + " permitted = " + permitted);
                }
                this.mPermitted = permitted;
                if (this.mPermitted) {
                    LocationEventLog.EVENT_LOG.logProviderClientPermitted(LocationProviderManager.this.mName, getIdentity());
                    return true;
                }
                LocationEventLog.EVENT_LOG.logProviderClientUnpermitted(LocationProviderManager.this.mName, getIdentity());
                return true;
            }
            return false;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public final boolean onAdasGnssLocationEnabledChanged(int userId) {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                if (getIdentity().getUserId() != userId) {
                    return false;
                }
                return onProviderLocationRequestChanged();
            }
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public final boolean onForegroundChanged(int uid, boolean foreground) {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                if (getIdentity().getUid() != uid || foreground == this.mForeground) {
                    return false;
                }
                Log.v(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider uid " + uid + " foreground = " + foreground);
                this.mForeground = foreground;
                if (LocationProviderManager.mOplusLbsClass != null) {
                    LocationProviderManager.mOplusLbsClass.updateForeground(getIdentity().getPackageName(), LocationProviderManager.this.mName, this.mForeground);
                }
                if (this.mForeground) {
                    LocationEventLog.EVENT_LOG.logProviderClientForeground(LocationProviderManager.this.mName, getIdentity());
                } else {
                    LocationEventLog.EVENT_LOG.logProviderClientBackground(LocationProviderManager.this.mName, getIdentity());
                }
                return onProviderLocationRequestChanged() || LocationProviderManager.this.mLocationPowerSaveModeHelper.getLocationPowerSaveMode() == 3;
            }
        }

        final boolean onProviderLocationRequestChanged() {
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                LocationRequest newRequest = calculateProviderLocationRequest();
                if (this.mProviderLocationRequest.equals(newRequest)) {
                    return false;
                }
                LocationRequest oldRequest = this.mProviderLocationRequest;
                this.mProviderLocationRequest = newRequest;
                onHighPowerUsageChanged();
                LocationProviderManager.this.updateService();
                return oldRequest.isBypass() != newRequest.isBypass();
            }
        }

        private LocationRequest calculateProviderLocationRequest() {
            LocationRequest.Builder builder = new LocationRequest.Builder(this.mBaseRequest);
            if (this.mPermissionLevel < 2) {
                builder.setQuality(104);
                if (this.mBaseRequest.getIntervalMillis() < 600000) {
                    builder.setIntervalMillis(600000L);
                }
                if (this.mBaseRequest.getMinUpdateIntervalMillis() < 600000) {
                    builder.setMinUpdateIntervalMillis(600000L);
                }
            }
            boolean locationSettingsIgnored = this.mBaseRequest.isLocationSettingsIgnored();
            if (locationSettingsIgnored) {
                if (!LocationProviderManager.this.mSettingsHelper.getIgnoreSettingsAllowlist().contains(getIdentity().getPackageName(), getIdentity().getAttributionTag()) && !LocationProviderManager.this.mLocationManagerInternal.isProvider((String) null, getIdentity())) {
                    locationSettingsIgnored = false;
                }
                builder.setLocationSettingsIgnored(locationSettingsIgnored);
            }
            boolean adasGnssBypass = this.mBaseRequest.isAdasGnssBypass();
            if (adasGnssBypass) {
                if (!IOplusSceneManager.APP_SCENE_GPS.equals(LocationProviderManager.this.mName)) {
                    Log.e(LocationManagerService.TAG, "adas gnss bypass request received in non-gps provider");
                    adasGnssBypass = false;
                } else if (!LocationProviderManager.this.mUserHelper.isCurrentUserId(getIdentity().getUserId()) || !LocationProviderManager.this.mLocationSettings.getUserSettings(getIdentity().getUserId()).isAdasGnssLocationEnabled() || !LocationProviderManager.this.mSettingsHelper.getAdasAllowlist().contains(getIdentity().getPackageName(), getIdentity().getAttributionTag())) {
                    adasGnssBypass = false;
                }
                builder.setAdasGnssBypass(adasGnssBypass);
            }
            if (!locationSettingsIgnored && !isThrottlingExempt()) {
                if (!this.mForeground) {
                    builder.setIntervalMillis(Math.max(this.mBaseRequest.getIntervalMillis(), LocationProviderManager.this.mSettingsHelper.getBackgroundThrottleIntervalMs()));
                }
                if (IOplusSceneManager.APP_SCENE_GPS.equals(LocationProviderManager.this.mName) && LocationProviderManager.mOplusLbsClass.checkRequestBlocked(LocationProviderManager.this.mName, getIdentity().getPackageName())) {
                    builder.setIntervalMillis(Long.MAX_VALUE).setMinUpdateIntervalMillis(Long.MAX_VALUE);
                }
            }
            if ("fused".equals(LocationProviderManager.this.mName) && LocationProviderManager.mOplusLbsClass.isFlpReqLimited(getIdentity().getPackageName())) {
                builder.setQuality(104);
            }
            return builder.build();
        }

        private boolean isThrottlingExempt() {
            if (LocationProviderManager.this.mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(getIdentity().getPackageName())) {
                return true;
            }
            return LocationProviderManager.this.mLocationManagerInternal.isProvider((String) null, getIdentity());
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(getIdentity());
            ArraySet<String> flags = new ArraySet<>(2);
            if (!isForeground()) {
                flags.add("bg");
            }
            if (!isPermitted()) {
                flags.add("na");
            }
            if (!flags.isEmpty()) {
                builder.append(" ").append(flags);
            }
            if (this.mPermissionLevel == 1) {
                builder.append(" (COARSE)");
            }
            builder.append(" ").append(getRequest());
            return builder.toString();
        }
    }

    protected abstract class LocationRegistration extends Registration implements AlarmManager.OnAlarmListener, LocationManagerInternal.ProviderEnabledListener {
        private long mExpirationRealtimeMs;
        private int mNumLocationsDelivered;
        private volatile ProviderTransport mProviderTransport;
        final PowerManager.WakeLock mWakeLock;
        final ExternalWakeLockReleaser mWakeLockReleaser;

        protected abstract void onProviderOperationFailure(ListenerExecutor.ListenerOperation<ProviderTransport> listenerOperation, Exception exc);

        protected <TTransport extends LocationTransport & ProviderTransport> LocationRegistration(LocationRequest request, CallerIdentity identity, Executor executor, TTransport transport, int permissionLevel) {
            super(request, identity, executor, transport, permissionLevel);
            this.mNumLocationsDelivered = 0;
            this.mExpirationRealtimeMs = Long.MAX_VALUE;
            this.mProviderTransport = transport;
            this.mWakeLock = ((PowerManager) Objects.requireNonNull((PowerManager) LocationProviderManager.this.mContext.getSystemService(PowerManager.class))).newWakeLock(1, LocationProviderManager.WAKELOCK_TAG);
            this.mWakeLock.setReferenceCounted(true);
            this.mWakeLock.setWorkSource(request.getWorkSource());
            this.mWakeLockReleaser = new ExternalWakeLockReleaser(identity, this.mWakeLock);
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        protected void onListenerUnregister() {
            this.mProviderTransport = null;
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration
        protected void onRegister() {
            super.onRegister();
            long registerTimeMs = SystemClock.elapsedRealtime();
            this.mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(registerTimeMs);
            if (this.mExpirationRealtimeMs <= registerTimeMs) {
                onAlarm();
            } else if (this.mExpirationRealtimeMs < Long.MAX_VALUE) {
                LocationProviderManager.this.mAlarmHelper.setDelayedAlarm(this.mExpirationRealtimeMs - registerTimeMs, this, null);
            }
            LocationProviderManager.this.addEnabledListener(this);
            int userId = getIdentity().getUserId();
            if (!LocationProviderManager.this.isEnabled(userId)) {
                onProviderEnabledChanged(LocationProviderManager.this.mName, userId, false);
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration, com.android.server.location.listeners.ListenerRegistration
        protected void onUnregister() {
            LocationProviderManager.this.removeEnabledListener(this);
            if (this.mExpirationRealtimeMs < Long.MAX_VALUE) {
                LocationProviderManager.this.mAlarmHelper.cancel(this);
            }
            super.onUnregister();
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.ListenerRegistration
        protected void onActive() {
            long maxLocationAgeMs;
            Location lastLocation;
            super.onActive();
            if (CompatChanges.isChangeEnabled(73144566L, getIdentity().getUid())) {
                long maxLocationAgeMs2 = getRequest().getIntervalMillis();
                Location lastDeliveredLocation = getLastDeliveredLocation();
                if (lastDeliveredLocation == null) {
                    maxLocationAgeMs = maxLocationAgeMs2;
                } else {
                    maxLocationAgeMs = Math.min(maxLocationAgeMs2, lastDeliveredLocation.getElapsedRealtimeAgeMillis() - 1);
                }
                if (maxLocationAgeMs <= 30000 || (lastLocation = LocationProviderManager.this.getLastLocationUnsafe(getIdentity().getUserId(), getPermissionLevel(), getRequest().isBypass(), maxLocationAgeMs)) == null) {
                    return;
                }
                executeOperation(acceptLocationChange(LocationResult.wrap(new Location[]{lastLocation})));
            }
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " expired at " + TimeUtils.formatRealtime(this.mExpirationRealtimeMs));
            }
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                this.mExpirationRealtimeMs = Long.MAX_VALUE;
                remove();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration
        ListenerExecutor.ListenerOperation<LocationTransport> acceptLocationChange(LocationResult fineLocationResult) {
            int op;
            if ((LocationProviderManager.mOplusLbsClass != null && !LocationProviderManager.mOplusLbsClass.shouldReportFlpAsGps(fineLocationResult.getLastLocation(), getIdentity().getPackageName())) || !LocationProviderManager.mOplusLbsClass.shouldReportPnetLocationAsGps(fineLocationResult.getLastLocation(), getIdentity().getPackageName())) {
                return null;
            }
            if (SystemClock.elapsedRealtime() >= this.mExpirationRealtimeMs) {
                if (LocationManagerService.D) {
                    Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " expired at " + TimeUtils.formatRealtime(this.mExpirationRealtimeMs));
                }
                remove();
                return null;
            }
            LocationResult permittedLocationResult = (LocationResult) Objects.requireNonNull(LocationProviderManager.this.getPermittedLocationResult(fineLocationResult, getPermissionLevel()));
            LocationResult locationResult = permittedLocationResult.filter(new Predicate<Location>() { // from class: com.android.server.location.provider.LocationProviderManager.LocationRegistration.1
                private Location mPreviousLocation;

                {
                    this.mPreviousLocation = LocationRegistration.this.getLastDeliveredLocation();
                }

                @Override // java.util.function.Predicate
                public boolean test(Location location) {
                    if (Double.isNaN(location.getLatitude()) || location.getLatitude() < -90.0d || location.getLatitude() > 90.0d || Double.isNaN(location.getLongitude()) || location.getLongitude() < -180.0d || location.getLongitude() > 180.0d) {
                        Log.e(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + LocationRegistration.this.getIdentity() + " dropped delivery - invalid latitude or longitude.");
                        return false;
                    }
                    if (this.mPreviousLocation != null) {
                        long deltaMs = location.getElapsedRealtimeMillis() - this.mPreviousLocation.getElapsedRealtimeMillis();
                        long maxJitterMs = Math.min((long) (LocationRegistration.this.getRequest().getIntervalMillis() * LocationProviderManager.FASTEST_INTERVAL_JITTER_PERCENTAGE), 30000L);
                        if (deltaMs < LocationRegistration.this.getRequest().getMinUpdateIntervalMillis() - maxJitterMs) {
                            if (LocationManagerService.D) {
                                Log.v(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + LocationRegistration.this.getIdentity() + " dropped delivery - too fast (deltaMs=" + deltaMs + ").");
                            }
                            return false;
                        }
                        double smallestDisplacementM = LocationRegistration.this.getRequest().getMinUpdateDistanceMeters();
                        if (smallestDisplacementM > 0.0d && location.distanceTo(this.mPreviousLocation) <= smallestDisplacementM) {
                            if (LocationManagerService.D) {
                                Log.v(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + LocationRegistration.this.getIdentity() + " dropped delivery - too close");
                            }
                            return false;
                        }
                    }
                    this.mPreviousLocation = location;
                    return true;
                }
            });
            if (locationResult == null) {
                return null;
            }
            if (Flags.enableLocationBypass() && isOnlyBypassPermitted()) {
                op = 147;
            } else {
                op = LocationPermissions.asAppOp(getPermissionLevel());
            }
            if (!LocationProviderManager.this.mAppOpsHelper.noteOpNoThrow(op, getIdentity())) {
                if (LocationManagerService.D) {
                    Log.w(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " noteOp denied");
                }
                return null;
            }
            boolean useWakeLock = getRequest().getIntervalMillis() != Long.MAX_VALUE;
            return new AnonymousClass2(locationResult, useWakeLock);
        }

        /* JADX INFO: renamed from: com.android.server.location.provider.LocationProviderManager$LocationRegistration$2, reason: invalid class name */
        class AnonymousClass2 implements ListenerExecutor.ListenerOperation<LocationTransport> {
            final /* synthetic */ LocationResult val$locationResult;
            final /* synthetic */ boolean val$useWakeLock;

            AnonymousClass2(LocationResult locationResult, boolean z) {
                this.val$locationResult = locationResult;
                this.val$useWakeLock = z;
            }

            public void onPreExecute() {
                LocationRegistration.this.setLastDeliveredLocation(this.val$locationResult.getLastLocation());
                if (this.val$useWakeLock) {
                    LocationRegistration.this.mWakeLock.acquire(30000L);
                }
            }

            public void operate(LocationTransport listener) throws Exception {
                LocationResult deliverLocationResult;
                if (LocationRegistration.this.getIdentity().getPid() == Process.myPid()) {
                    deliverLocationResult = this.val$locationResult.deepCopy();
                } else {
                    deliverLocationResult = this.val$locationResult;
                }
                listener.deliverOnLocationChanged(deliverLocationResult, this.val$useWakeLock ? LocationRegistration.this.mWakeLockReleaser : null);
                if (LocationProviderManager.mOplusLbsClass != null) {
                    try {
                        LocationProviderManager.mOplusLbsClass.deliverLocation(LocationRegistration.this.getIdentity(), LocationProviderManager.this.mName, "" + LocationRegistration.this.getKey().hashCode());
                        LocationProviderManager.mOplusLbsClass.deliverLocationForSnapshot(LocationRegistration.this.getIdentity(), LocationProviderManager.this.mName, "" + LocationRegistration.this.getKey().hashCode(), deliverLocationResult);
                    } catch (NullPointerException e) {
                        Log.e(LocationManagerService.TAG, "deliverLocation, NullPointerException fail " + e);
                    }
                }
                LocationEventLog.EVENT_LOG.logProviderDeliveredLocations(LocationProviderManager.this.mName, this.val$locationResult.size(), LocationRegistration.this.getIdentity());
            }

            public void onPostExecute(boolean success) {
                if (!success && this.val$useWakeLock) {
                    try {
                        LocationRegistration.this.mWakeLock.release();
                    } catch (RuntimeException e) {
                        if (e.getClass() == RuntimeException.class) {
                            Log.e(LocationManagerService.TAG, "wakelock over-released by " + e);
                        } else {
                            FgThread.getExecutor().execute(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$LocationRegistration$2$$ExternalSyntheticLambda0
                                @Override // java.lang.Runnable
                                public final void run() {
                                    LocationProviderManager.LocationRegistration.AnonymousClass2.lambda$onPostExecute$0(e);
                                }
                            });
                            throw e;
                        }
                    }
                }
                if (success) {
                    if (LocationProviderManager.mOplusLbsClass != null) {
                        LocationProviderManager.mOplusLbsClass.checkLocationHasChanged(LocationProviderManager.this.mName, LocationRegistration.this.getIdentity().getPackageName(), 0);
                    }
                    LocationRegistration locationRegistration = LocationRegistration.this;
                    int i = locationRegistration.mNumLocationsDelivered + 1;
                    locationRegistration.mNumLocationsDelivered = i;
                    boolean remove = i >= LocationRegistration.this.getRequest().getMaxUpdates();
                    if (remove) {
                        if (LocationManagerService.D) {
                            Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + LocationRegistration.this.getIdentity() + " finished after " + LocationRegistration.this.mNumLocationsDelivered + " updates");
                        }
                        LocationRegistration.this.remove();
                    }
                }
            }

            static /* synthetic */ void lambda$onPostExecute$0(RuntimeException e) {
                throw new AssertionError(e);
            }
        }

        public void onProviderEnabledChanged(String provider, int userId, final boolean enabled) {
            Preconditions.checkState(LocationProviderManager.this.mName.equals(provider));
            Log.d(LocationManagerService.TAG, "onProviderEnabledChanged name: " + LocationProviderManager.this.mName + " enabled: " + enabled);
            if (userId != getIdentity().getUserId()) {
                return;
            }
            executeSafely(getExecutor(), new Supplier() { // from class: com.android.server.location.provider.LocationProviderManager$LocationRegistration$$ExternalSyntheticLambda0
                @Override // java.util.function.Supplier
                public final Object get() {
                    return this.f$0.lambda$onProviderEnabledChanged$0();
                }
            }, new ListenerExecutor.ListenerOperation() { // from class: com.android.server.location.provider.LocationProviderManager$LocationRegistration$$ExternalSyntheticLambda1
                public final void operate(Object obj) throws Exception {
                    this.f$0.lambda$onProviderEnabledChanged$1(enabled, (LocationProviderManager.ProviderTransport) obj);
                }
            }, new ListenerExecutor.FailureCallback() { // from class: com.android.server.location.provider.LocationProviderManager$LocationRegistration$$ExternalSyntheticLambda2
                public final void onFailure(ListenerExecutor.ListenerOperation listenerOperation, Exception exc) {
                    this.f$0.onProviderOperationFailure(listenerOperation, exc);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ ProviderTransport lambda$onProviderEnabledChanged$0() {
            return this.mProviderTransport;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onProviderEnabledChanged$1(boolean enabled, ProviderTransport listener) throws Exception {
            listener.deliverOnProviderEnabledChanged(LocationProviderManager.this.mName, enabled);
        }
    }

    protected final class LocationListenerRegistration extends LocationRegistration implements IBinder.DeathRecipient {
        LocationListenerRegistration(LocationRequest request, CallerIdentity identity, LocationListenerTransport transport, int permissionLevel) {
            super(request, identity, identity.isMyProcess() ? FgThread.getExecutor() : ConcurrentUtils.DIRECT_EXECUTOR, transport, permissionLevel);
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationRegistration, com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration
        protected void onRegister() {
            super.onRegister();
            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationRegistration, com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration, com.android.server.location.listeners.ListenerRegistration
        protected void onUnregister() {
            try {
                ((IBinder) getKey()).unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.w(getTag(), "failed to unregister binder death listener", e);
            }
            super.onUnregister();
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationRegistration
        protected void onProviderOperationFailure(ListenerExecutor.ListenerOperation<ProviderTransport> operation, Exception exception) {
            onTransportFailure(exception);
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        public void onOperationFailure(ListenerExecutor.ListenerOperation<LocationTransport> operation, Exception exception) {
            onTransportFailure(exception);
        }

        private void onTransportFailure(Exception e) {
            if (e instanceof RemoteException) {
                Log.w(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " removed", e);
                remove();
                return;
            }
            throw new AssertionError(e);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            try {
                if (LocationManagerService.D) {
                    Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " died");
                }
                if (LocationProviderManager.mLocationFreeze != null) {
                    try {
                        LocationProviderManager.mLocationFreeze.onBinderDied(LocationProviderManager.this.mName, getKey(), getIdentity().getUid(), getIdentity().getPackageName());
                    } catch (NullPointerException e) {
                        Log.d(LocationManagerService.TAG, "onBinderDied getKey is null!");
                    }
                }
                remove();
            } catch (RuntimeException e2) {
                throw new AssertionError(e2);
            }
        }
    }

    protected final class LocationPendingIntentRegistration extends LocationRegistration implements PendingIntent.CancelListener {
        LocationPendingIntentRegistration(LocationRequest request, CallerIdentity identity, LocationPendingIntentTransport transport, int permissionLevel) {
            super(request, identity, ConcurrentUtils.DIRECT_EXECUTOR, transport, permissionLevel);
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationRegistration, com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration
        protected void onRegister() {
            super.onRegister();
            if (!((PendingIntent) getKey()).addCancelListener(ConcurrentUtils.DIRECT_EXECUTOR, this)) {
                remove();
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationRegistration, com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration, com.android.server.location.listeners.ListenerRegistration
        protected void onUnregister() {
            ((PendingIntent) getKey()).removeCancelListener(this);
            super.onUnregister();
        }

        @Override // com.android.server.location.provider.LocationProviderManager.LocationRegistration
        protected void onProviderOperationFailure(ListenerExecutor.ListenerOperation<ProviderTransport> operation, Exception exception) {
            onTransportFailure(exception);
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        public void onOperationFailure(ListenerExecutor.ListenerOperation<LocationTransport> operation, Exception exception) {
            onTransportFailure(exception);
        }

        private void onTransportFailure(Exception e) {
            if (e instanceof PendingIntent.CanceledException) {
                Log.w(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " removed", e);
                remove();
                return;
            }
            throw new AssertionError(e);
        }

        public void onCanceled(PendingIntent intent) {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " canceled");
            }
            if (LocationProviderManager.mLocationFreeze != null && intent != null) {
                LocationProviderManager.mLocationFreeze.onBinderDied(LocationProviderManager.this.mName, intent, getIdentity().getUid(), getIdentity().getPackageName());
            }
            remove();
        }
    }

    protected final class GetCurrentLocationListenerRegistration extends Registration implements IBinder.DeathRecipient, AlarmManager.OnAlarmListener {
        private long mExpirationRealtimeMs;

        GetCurrentLocationListenerRegistration(LocationRequest request, CallerIdentity identity, LocationTransport transport, int permissionLevel) {
            super(request, identity, identity.isMyProcess() ? FgThread.getExecutor() : ConcurrentUtils.DIRECT_EXECUTOR, transport, permissionLevel);
            this.mExpirationRealtimeMs = Long.MAX_VALUE;
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration
        protected void onRegister() {
            super.onRegister();
            try {
                ((IBinder) getKey()).linkToDeath(this, 0);
            } catch (RemoteException e) {
                remove();
            }
            long registerTimeMs = SystemClock.elapsedRealtime();
            this.mExpirationRealtimeMs = getRequest().getExpirationRealtimeMs(registerTimeMs);
            if (this.mExpirationRealtimeMs <= registerTimeMs) {
                onAlarm();
            } else if (this.mExpirationRealtimeMs < Long.MAX_VALUE) {
                LocationProviderManager.this.mAlarmHelper.setDelayedAlarm(this.mExpirationRealtimeMs - registerTimeMs, this, null);
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.RemovableListenerRegistration, com.android.server.location.listeners.ListenerRegistration
        protected void onUnregister() {
            if (this.mExpirationRealtimeMs < Long.MAX_VALUE) {
                LocationProviderManager.this.mAlarmHelper.cancel(this);
            }
            try {
                ((IBinder) getKey()).unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.w(getTag(), "failed to unregister binder death listener", e);
            }
            super.onUnregister();
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.ListenerRegistration
        protected void onActive() {
            super.onActive();
            Location lastLocation = LocationProviderManager.this.getLastLocationUnsafe(getIdentity().getUserId(), getPermissionLevel(), getRequest().isBypass(), 30000L);
            if (lastLocation != null) {
                executeOperation(acceptLocationChange(LocationResult.wrap(new Location[]{lastLocation})));
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration, com.android.server.location.listeners.ListenerRegistration
        protected void onInactive() {
            executeOperation(acceptLocationChange(null));
            super.onInactive();
        }

        void deliverNull() {
            executeOperation(acceptLocationChange(null));
        }

        @Override // android.app.AlarmManager.OnAlarmListener
        public void onAlarm() {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " expired at " + TimeUtils.formatRealtime(this.mExpirationRealtimeMs));
            }
            synchronized (LocationProviderManager.this.mMultiplexerLock) {
                this.mExpirationRealtimeMs = Long.MAX_VALUE;
                executeOperation(acceptLocationChange(null));
            }
        }

        @Override // com.android.server.location.provider.LocationProviderManager.Registration
        ListenerExecutor.ListenerOperation<LocationTransport> acceptLocationChange(LocationResult fineLocationResult) {
            int op;
            if (SystemClock.elapsedRealtime() >= this.mExpirationRealtimeMs) {
                if (LocationManagerService.D) {
                    Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " expired at " + TimeUtils.formatRealtime(this.mExpirationRealtimeMs));
                }
                fineLocationResult = null;
            }
            if (fineLocationResult != null) {
                if (Flags.enableLocationBypass() && isOnlyBypassPermitted()) {
                    op = 147;
                } else {
                    op = LocationPermissions.asAppOp(getPermissionLevel());
                }
                if (!LocationProviderManager.this.mAppOpsHelper.noteOpNoThrow(op, getIdentity())) {
                    if (LocationManagerService.D) {
                        Log.w(LocationManagerService.TAG, "noteOp denied for " + getIdentity());
                    }
                    fineLocationResult = null;
                }
            }
            if (fineLocationResult != null) {
                fineLocationResult = fineLocationResult.asLastLocationResult();
            }
            final LocationResult locationResult = LocationProviderManager.this.getPermittedLocationResult(fineLocationResult, getPermissionLevel());
            return new ListenerExecutor.ListenerOperation<LocationTransport>() { // from class: com.android.server.location.provider.LocationProviderManager.GetCurrentLocationListenerRegistration.1
                public void operate(LocationTransport listener) throws Exception {
                    LocationResult deliverLocationResult;
                    if (GetCurrentLocationListenerRegistration.this.getIdentity().getPid() == Process.myPid() && locationResult != null) {
                        deliverLocationResult = locationResult.deepCopy();
                    } else {
                        deliverLocationResult = locationResult;
                    }
                    listener.deliverOnLocationChanged(deliverLocationResult, null);
                    LocationEventLog.EVENT_LOG.logProviderDeliveredLocations(LocationProviderManager.this.mName, locationResult != null ? locationResult.size() : 0, GetCurrentLocationListenerRegistration.this.getIdentity());
                }

                public void onPostExecute(boolean success) {
                    if (success) {
                        GetCurrentLocationListenerRegistration.this.remove();
                    }
                }
            };
        }

        @Override // com.android.server.location.listeners.ListenerRegistration
        public void onOperationFailure(ListenerExecutor.ListenerOperation<LocationTransport> operation, Exception e) {
            if (e instanceof RemoteException) {
                Log.w(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " removed", e);
                remove();
                return;
            }
            throw new AssertionError(e);
        }

        @Override // android.os.IBinder.DeathRecipient
        public void binderDied() {
            try {
                if (LocationManagerService.D) {
                    Log.d(LocationManagerService.TAG, LocationProviderManager.this.mName + " provider registration " + getIdentity() + " died");
                }
                remove();
            } catch (RuntimeException e) {
                throw new AssertionError(e);
            }
        }
    }

    public LocationProviderManager(Context context, Injector injector, String name, PassiveLocationProviderManager passiveManager) {
        this(context, injector, name, passiveManager, Collections.emptyList());
    }

    public LocationProviderManager(Context context, Injector injector, String name, PassiveLocationProviderManager passiveManager, Collection<String> requiredPermissions) {
        this.mUserChangedListener = new UserInfoHelper.UserListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda5
            @Override // com.android.server.location.injector.UserInfoHelper.UserListener
            public final void onUserChanged(int i, int i2) {
                this.f$0.onUserChanged(i, i2);
            }
        };
        this.mLocationUserSettingsListener = new LocationSettings.LocationUserSettingsListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda8
            @Override // com.android.server.location.settings.LocationSettings.LocationUserSettingsListener
            public final void onLocationUserSettingsChanged(int i, LocationUserSettings locationUserSettings, LocationUserSettings locationUserSettings2) {
                this.f$0.onLocationUserSettingsChanged(i, locationUserSettings, locationUserSettings2);
            }
        };
        this.mLocationEnabledChangedListener = new SettingsHelper.UserSettingChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda9
            @Override // com.android.server.location.injector.SettingsHelper.UserSettingChangedListener
            public final void onSettingChanged(int i) {
                this.f$0.onLocationEnabledChanged(i);
            }
        };
        this.mBackgroundThrottlePackageWhitelistChangedListener = new SettingsHelper.GlobalSettingChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda10
            @Override // com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener
            public final void onSettingChanged() {
                this.f$0.onBackgroundThrottlePackageWhitelistChanged();
            }
        };
        this.mLocationPackageBlacklistChangedListener = new SettingsHelper.UserSettingChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda11
            @Override // com.android.server.location.injector.SettingsHelper.UserSettingChangedListener
            public final void onSettingChanged(int i) {
                this.f$0.onLocationPackageBlacklistChanged(i);
            }
        };
        this.mLocationPermissionsListener = new LocationPermissionsHelper.LocationPermissionsListener() { // from class: com.android.server.location.provider.LocationProviderManager.1
            @Override // com.android.server.location.injector.LocationPermissionsHelper.LocationPermissionsListener
            public void onLocationPermissionsChanged(String packageName) {
                LocationProviderManager.this.onLocationPermissionsChanged(packageName);
            }

            @Override // com.android.server.location.injector.LocationPermissionsHelper.LocationPermissionsListener
            public void onLocationPermissionsChanged(int uid) {
                LocationProviderManager.this.onLocationPermissionsChanged(uid);
            }
        };
        this.mAppForegroundChangedListener = new AppForegroundHelper.AppForegroundListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda12
            @Override // com.android.server.location.injector.AppForegroundHelper.AppForegroundListener
            public final void onAppForegroundChanged(int i, boolean z) {
                this.f$0.onAppForegroundChanged(i, z);
            }
        };
        this.mBackgroundThrottleIntervalChangedListener = new SettingsHelper.GlobalSettingChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda13
            @Override // com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener
            public final void onSettingChanged() {
                this.f$0.onBackgroundThrottleIntervalChanged();
            }
        };
        this.mAdasPackageAllowlistChangedListener = new SettingsHelper.GlobalSettingChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda14
            @Override // com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener
            public final void onSettingChanged() {
                this.f$0.onAdasAllowlistChanged();
            }
        };
        this.mIgnoreSettingsPackageWhitelistChangedListener = new SettingsHelper.GlobalSettingChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda15
            @Override // com.android.server.location.injector.SettingsHelper.GlobalSettingChangedListener
            public final void onSettingChanged() {
                this.f$0.onIgnoreSettingsWhitelistChanged();
            }
        };
        this.mLocationPowerSaveModeChangedListener = new LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda16
            @Override // com.android.server.location.injector.LocationPowerSaveModeHelper.LocationPowerSaveModeChangedListener
            public final void onLocationPowerSaveModeChanged(int i) {
                this.f$0.onLocationPowerSaveModeChanged(i);
            }
        };
        this.mScreenInteractiveChangedListener = new ScreenInteractiveHelper.ScreenInteractiveChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda6
            @Override // com.android.server.location.injector.ScreenInteractiveHelper.ScreenInteractiveChangedListener
            public final void onScreenInteractiveChanged(boolean z) {
                this.f$0.onScreenInteractiveChanged(z);
            }
        };
        this.mEmergencyStateChangedListener = new EmergencyHelper.EmergencyStateChangedListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda7
            @Override // com.android.server.location.injector.EmergencyHelper.EmergencyStateChangedListener
            public final void onStateChanged() {
                this.f$0.onEmergencyStateChanged();
            }
        };
        this.mPackageResetResponder = new PackageResetHelper.Responder() { // from class: com.android.server.location.provider.LocationProviderManager.2
            @Override // com.android.server.location.injector.PackageResetHelper.Responder
            public void onPackageReset(String packageName) {
                LocationProviderManager.this.onPackageReset(packageName);
            }

            @Override // com.android.server.location.injector.PackageResetHelper.Responder
            public boolean isResetableForPackage(String packageName) {
                return LocationProviderManager.this.isResetableForPackage(packageName);
            }
        };
        this.mAltitudeConverter = new AltitudeConverter();
        this.mIsAltitudeConverterIdle = true;
        this.mLocationProviderManagerWrapper = new LocationProviderManagerWrapper();
        this.mContext = context;
        this.mName = (String) Objects.requireNonNull(name);
        this.mPassiveManager = passiveManager;
        this.mState = 2;
        this.mEnabled = new SparseBooleanArray(2);
        this.mLastLocations = new SparseArray<>(2);
        this.mRequiredPermissions = requiredPermissions;
        this.mEnabledListeners = new ArrayList<>();
        this.mProviderRequestListeners = new CopyOnWriteArrayList<>();
        this.mLocationManagerInternal = (LocationManagerInternal) Objects.requireNonNull((LocationManagerInternal) LocalServices.getService(LocationManagerInternal.class));
        this.mLocationSettings = injector.getLocationSettings();
        this.mSettingsHelper = injector.getSettingsHelper();
        this.mUserHelper = injector.getUserInfoHelper();
        this.mAlarmHelper = injector.getAlarmHelper();
        this.mAppOpsHelper = injector.getAppOpsHelper();
        this.mLocationPermissionsHelper = injector.getLocationPermissionsHelper();
        this.mAppForegroundHelper = injector.getAppForegroundHelper();
        this.mLocationPowerSaveModeHelper = injector.getLocationPowerSaveModeHelper();
        this.mScreenInteractiveHelper = injector.getScreenInteractiveHelper();
        this.mLocationUsageLogger = injector.getLocationUsageLogger();
        this.mLocationFudger = new LocationFudger(this.mSettingsHelper.getCoarseLocationAccuracyM());
        this.mEmergencyHelper = injector.getEmergencyHelper();
        this.mPackageResetHelper = injector.getPackageResetHelper();
        this.mProvider = new MockableLocationProvider(this.mMultiplexerLock);
        this.mProvider.getController().setListener(this);
    }

    public void startManager(StateChangedListener listener) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState == 2);
            this.mState = 0;
            this.mStateChangedListener = listener;
            this.mUserHelper.addListener(this.mUserChangedListener);
            this.mLocationSettings.registerLocationUserSettingsListener(this.mLocationUserSettingsListener);
            this.mSettingsHelper.addOnLocationEnabledChangedListener(this.mLocationEnabledChangedListener);
            long identity = Binder.clearCallingIdentity();
            try {
                this.mProvider.getController().start();
                onUserStarted(-1);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public void stopManager() {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState == 0);
            this.mState = 1;
            long identity = Binder.clearCallingIdentity();
            try {
                onEnabledChanged(-1);
                removeRegistrationIf(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda19
                    @Override // java.util.function.Predicate
                    public final boolean test(Object obj) {
                        return LocationProviderManager.lambda$stopManager$0(obj);
                    }
                });
                this.mProvider.getController().stop();
                Binder.restoreCallingIdentity(identity);
                this.mUserHelper.removeListener(this.mUserChangedListener);
                this.mLocationSettings.unregisterLocationUserSettingsListener(this.mLocationUserSettingsListener);
                this.mSettingsHelper.removeOnLocationEnabledChangedListener(this.mLocationEnabledChangedListener);
                Preconditions.checkState(this.mEnabledListeners.isEmpty());
                this.mProviderRequestListeners.clear();
                this.mEnabled.clear();
                this.mLastLocations.clear();
                this.mStateChangedListener = null;
                this.mState = 2;
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    static /* synthetic */ boolean lambda$stopManager$0(Object key) {
        return true;
    }

    public String getName() {
        return this.mName;
    }

    public AbstractLocationProvider.State getState() {
        return this.mProvider.getState();
    }

    public CallerIdentity getProviderIdentity() {
        return this.mProvider.getState().identity;
    }

    public ProviderProperties getProperties() {
        return this.mProvider.getState().properties;
    }

    public boolean hasProvider() {
        return this.mProvider.getProvider() != null;
    }

    public boolean isEnabled(int userId) {
        boolean zValueAt;
        if (userId == -10000) {
            return false;
        }
        if (userId == -2) {
            return isEnabled(this.mUserHelper.getCurrentUserId());
        }
        Preconditions.checkArgument(userId >= 0);
        synchronized (this.mMultiplexerLock) {
            int index = this.mEnabled.indexOfKey(userId);
            if (index < 0) {
                Log.w(LocationManagerService.TAG, this.mName + " provider saw user " + userId + " unexpectedly");
                onEnabledChanged(userId);
                index = this.mEnabled.indexOfKey(userId);
            }
            zValueAt = this.mEnabled.valueAt(index);
        }
        return zValueAt;
    }

    public void setLocationFudgerCache(LocationFudgerCache cache) {
        if (!Flags.densityBasedCoarseLocations()) {
            return;
        }
        this.mLocationFudger.setLocationFudgerCache(cache);
    }

    public boolean isVisibleToCaller() {
        if (Binder.getCallingUid() == 1000 || this.mProvider.isMock()) {
            return true;
        }
        for (String permission : this.mRequiredPermissions) {
            if (this.mContext.checkCallingOrSelfPermission(permission) != 0) {
                return false;
            }
        }
        return true;
    }

    public void addEnabledListener(LocationManagerInternal.ProviderEnabledListener listener) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            this.mEnabledListeners.add(listener);
        }
    }

    public void removeEnabledListener(LocationManagerInternal.ProviderEnabledListener listener) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            this.mEnabledListeners.remove(listener);
        }
    }

    public void addProviderRequestListener(IProviderRequestListener listener) {
        this.mProviderRequestListeners.add(listener);
    }

    public void removeProviderRequestListener(IProviderRequestListener listener) {
        this.mProviderRequestListeners.remove(listener);
    }

    public void setRealProvider(AbstractLocationProvider provider) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            long identity = Binder.clearCallingIdentity();
            try {
                this.mProvider.setRealProvider(provider);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public void setMockProvider(MockLocationProvider provider) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            LocationEventLog.EVENT_LOG.logProviderMocked(this.mName, provider != null);
            long identity = Binder.clearCallingIdentity();
            try {
                this.mProvider.setMockProvider(provider);
                Binder.restoreCallingIdentity(identity);
                if (provider == null) {
                    int lastLocationSize = this.mLastLocations.size();
                    for (int i = 0; i < lastLocationSize; i++) {
                        this.mLastLocations.valueAt(i).clearMock();
                    }
                    this.mLocationFudger.resetOffsets();
                }
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public void setMockProviderAllowed(boolean enabled) {
        synchronized (this.mMultiplexerLock) {
            if (!this.mProvider.isMock()) {
                throw new IllegalArgumentException(this.mName + " provider is not a test provider");
            }
            long identity = Binder.clearCallingIdentity();
            try {
                this.mProvider.setMockProviderAllowed(enabled);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public void setMockProviderLocation(Location location) {
        synchronized (this.mMultiplexerLock) {
            if (!this.mProvider.isMock()) {
                throw new IllegalArgumentException(this.mName + " provider is not a test provider");
            }
            String locationProvider = location.getProvider();
            if (!TextUtils.isEmpty(locationProvider) && !this.mName.equals(locationProvider)) {
                EventLog.writeEvent(1397638484, "33091107", Integer.valueOf(Binder.getCallingUid()), this.mName + "!=" + locationProvider);
            }
            long identity = Binder.clearCallingIdentity();
            try {
                this.mProvider.setMockProviderLocation(location);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public Location getLastLocation(LastLocationRequest request, CallerIdentity identity, int permissionLevel) {
        int op;
        LastLocationRequest request2 = calculateLastLocationRequest(request, identity);
        if (!isActive(request2.isBypass(), identity)) {
            return null;
        }
        Location location = getPermittedLocation(getLastLocationUnsafe(identity.getUserId(), permissionLevel, request2.isBypass(), Long.MAX_VALUE), permissionLevel);
        if (!TEST_PROVIDER.equals(this.mName) && location == null) {
            if (Flags.enableLocationBypass() && !this.mLocationPermissionsHelper.hasLocationPermissions(permissionLevel, identity) && this.mEmergencyHelper.isInEmergency(0L) && this.mContext.checkPermission("android.permission.LOCATION_BYPASS", identity.getPid(), identity.getUid()) == 0) {
                op = 147;
            } else {
                op = LocationPermissions.asAppOp(permissionLevel);
            }
            if (!this.mAppOpsHelper.noteOpNoThrow(op, identity)) {
                return null;
            }
        }
        if (location != null) {
            int op2 = (Flags.enableLocationBypass() && !this.mLocationPermissionsHelper.hasLocationPermissions(permissionLevel, identity) && this.mEmergencyHelper.isInEmergency(0L) && this.mContext.checkPermission("android.permission.LOCATION_BYPASS", identity.getPid(), identity.getUid()) == 0) ? 147 : LocationPermissions.asAppOp(permissionLevel);
            if (!this.mAppOpsHelper.noteOpNoThrow(op2, identity)) {
                return null;
            }
            if (identity.getPid() == Process.myPid()) {
                return new Location(location);
            }
            return location;
        }
        return location;
    }

    private LastLocationRequest calculateLastLocationRequest(LastLocationRequest baseRequest, CallerIdentity identity) {
        LastLocationRequest.Builder builder = new LastLocationRequest.Builder(baseRequest);
        boolean locationSettingsIgnored = baseRequest.isLocationSettingsIgnored();
        if (locationSettingsIgnored) {
            if (!this.mSettingsHelper.getIgnoreSettingsAllowlist().contains(identity.getPackageName(), identity.getAttributionTag()) && !this.mLocationManagerInternal.isProvider((String) null, identity)) {
                locationSettingsIgnored = false;
            }
            builder.setLocationSettingsIgnored(locationSettingsIgnored);
        }
        boolean adasGnssBypass = baseRequest.isAdasGnssBypass();
        if (adasGnssBypass) {
            if (!IOplusSceneManager.APP_SCENE_GPS.equals(this.mName)) {
                Log.e(LocationManagerService.TAG, "adas gnss bypass request received in non-gps provider");
                adasGnssBypass = false;
            } else if (!this.mUserHelper.isCurrentUserId(identity.getUserId()) || !this.mLocationSettings.getUserSettings(identity.getUserId()).isAdasGnssLocationEnabled() || !this.mSettingsHelper.getAdasAllowlist().contains(identity.getPackageName(), identity.getAttributionTag())) {
                adasGnssBypass = false;
            }
            builder.setAdasGnssBypass(adasGnssBypass);
        }
        return builder.build();
    }

    public Location getLastLocationUnsafe(int userId, int permissionLevel, boolean isBypass, long maximumAgeMs) {
        Location location;
        if (userId == -1) {
            Location lastLocation = null;
            int[] runningUserIds = this.mUserHelper.getRunningUserIds();
            for (int i : runningUserIds) {
                Location next = getLastLocationUnsafe(i, permissionLevel, isBypass, maximumAgeMs);
                if (lastLocation == null || (next != null && next.getElapsedRealtimeNanos() > lastLocation.getElapsedRealtimeNanos())) {
                    lastLocation = next;
                }
            }
            return lastLocation;
        }
        if (userId == -2) {
            return getLastLocationUnsafe(this.mUserHelper.getCurrentUserId(), permissionLevel, isBypass, maximumAgeMs);
        }
        Preconditions.checkArgument(userId >= 0);
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            LastLocation lastLocation2 = this.mLastLocations.get(userId);
            if (lastLocation2 == null) {
                location = null;
            } else {
                location = lastLocation2.get(permissionLevel, isBypass);
            }
        }
        if (location == null || location.getElapsedRealtimeAgeMillis() > maximumAgeMs) {
            return null;
        }
        return location;
    }

    public void injectLastLocation(Location location, int userId) {
        Throwable th;
        synchronized (this.mMultiplexerLock) {
            try {
                try {
                    Preconditions.checkState(this.mState != 2);
                    if (getLastLocationUnsafe(userId, 2, false, Long.MAX_VALUE) == null) {
                        setLastLocation(location, userId);
                    }
                } catch (Throwable th2) {
                    th = th2;
                    throw th;
                }
            } catch (Throwable th3) {
                th = th3;
            }
        }
    }

    private void setLastLocation(Location location, int userId) {
        if (userId == -1) {
            int[] runningUserIds = this.mUserHelper.getRunningUserIds();
            for (int i : runningUserIds) {
                setLastLocation(location, i);
            }
            return;
        }
        if (userId == -2) {
            setLastLocation(location, this.mUserHelper.getCurrentUserId());
            return;
        }
        Preconditions.checkArgument(userId >= 0);
        synchronized (this.mMultiplexerLock) {
            LastLocation lastLocation = this.mLastLocations.get(userId);
            if (lastLocation == null) {
                lastLocation = new LastLocation();
                this.mLastLocations.put(userId, lastLocation);
            }
            if (isEnabled(userId)) {
                lastLocation.set(location);
            }
            lastLocation.setBypass(location);
        }
    }

    public ICancellationSignal getCurrentLocation(LocationRequest request, CallerIdentity identity, int permissionLevel, final ILocationCallback callback) {
        LocationRequest request2;
        if (request.getDurationMillis() <= 30000) {
            request2 = request;
        } else {
            request2 = new LocationRequest.Builder(request).setDurationMillis(30000L).build();
        }
        final GetCurrentLocationListenerRegistration registration = new GetCurrentLocationListenerRegistration(request2, identity, new GetCurrentLocationTransport(callback), permissionLevel);
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            long ident = Binder.clearCallingIdentity();
            try {
                putRegistration(callback.asBinder(), registration);
                if (!registration.isActive()) {
                    registration.deliverNull();
                }
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        }
        ICancellationSignal cancelTransport = CancellationSignal.createTransport();
        CancellationSignal.fromTransport(cancelTransport).setOnCancelListener(new CancellationSignal.OnCancelListener() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda23
            @Override // android.os.CancellationSignal.OnCancelListener
            public final void onCancel() {
                this.f$0.lambda$getCurrentLocation$2(callback, registration);
            }
        });
        return cancelTransport;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$getCurrentLocation$2(ILocationCallback callback, GetCurrentLocationListenerRegistration registration) {
        long ident = Binder.clearCallingIdentity();
        try {
            try {
                removeRegistration(callback.asBinder(), registration);
                Binder.restoreCallingIdentity(ident);
            } catch (RuntimeException e) {
                FgThread.getExecutor().execute(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        LocationProviderManager.lambda$getCurrentLocation$1(e);
                    }
                });
                throw e;
            }
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(ident);
            throw th;
        }
    }

    static /* synthetic */ void lambda$getCurrentLocation$1(RuntimeException e) {
        throw new AssertionError(e);
    }

    public void sendExtraCommand(int uid, int pid, String command, Bundle extras) {
        long identity = Binder.clearCallingIdentity();
        try {
            this.mProvider.getController().sendExtraCommand(uid, pid, command, extras);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity identity, int permissionLevel, ILocationListener listener) {
        LocationListenerRegistration registration = new LocationListenerRegistration(request, identity, new LocationListenerTransport(listener), permissionLevel);
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            long ident = Binder.clearCallingIdentity();
            try {
                if (mOplusLbsClass != null) {
                    mOplusLbsClass.getProviderStatus(this.mName, this.mProvider.getState().allowed, isEnabled(identity.getUserId()), true, identity.getUserId(), identity.getPackageName());
                }
                putRegistration(listener.asBinder(), registration);
                Binder.restoreCallingIdentity(ident);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(ident);
                throw th;
            }
        }
    }

    public void registerLocationRequest(LocationRequest request, CallerIdentity callerIdentity, int permissionLevel, PendingIntent pendingIntent) {
        LocationPendingIntentRegistration registration = new LocationPendingIntentRegistration(request, callerIdentity, new LocationPendingIntentTransport(this.mContext, pendingIntent), permissionLevel);
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            long identity = Binder.clearCallingIdentity();
            try {
                putRegistration(pendingIntent, registration);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public void flush(ILocationListener listener, final int requestCode) {
        long identity = Binder.clearCallingIdentity();
        try {
            boolean flushed = updateRegistration(listener.asBinder(), new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda33
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return LocationProviderManager.lambda$flush$3(requestCode, (LocationProviderManager.Registration) obj);
                }
            });
            if (!flushed) {
                throw new IllegalArgumentException("unregistered listener cannot be flushed");
            }
            Binder.restoreCallingIdentity(identity);
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
    }

    static /* synthetic */ boolean lambda$flush$3(int requestCode, Registration registration) {
        registration.flush(requestCode);
        return false;
    }

    public void flush(PendingIntent pendingIntent, final int requestCode) {
        long identity = Binder.clearCallingIdentity();
        try {
            boolean flushed = updateRegistration(pendingIntent, new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda32
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return LocationProviderManager.lambda$flush$4(requestCode, (LocationProviderManager.Registration) obj);
                }
            });
            if (!flushed) {
                throw new IllegalArgumentException("unregistered pending intent cannot be flushed");
            }
            Binder.restoreCallingIdentity(identity);
        } catch (Throwable th) {
            Binder.restoreCallingIdentity(identity);
            throw th;
        }
    }

    static /* synthetic */ boolean lambda$flush$4(int requestCode, Registration registration) {
        registration.flush(requestCode);
        return false;
    }

    public void unregisterLocationRequest(ILocationListener listener) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(listener.asBinder());
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    public void unregisterLocationRequest(PendingIntent pendingIntent) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(this.mState != 2);
            long identity = Binder.clearCallingIdentity();
            try {
                removeRegistration(pendingIntent);
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected void onRegister() {
        this.mSettingsHelper.addOnBackgroundThrottleIntervalChangedListener(this.mBackgroundThrottleIntervalChangedListener);
        this.mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(this.mBackgroundThrottlePackageWhitelistChangedListener);
        this.mSettingsHelper.addOnLocationPackageBlacklistChangedListener(this.mLocationPackageBlacklistChangedListener);
        this.mSettingsHelper.addAdasAllowlistChangedListener(this.mAdasPackageAllowlistChangedListener);
        this.mSettingsHelper.addIgnoreSettingsAllowlistChangedListener(this.mIgnoreSettingsPackageWhitelistChangedListener);
        this.mLocationPermissionsHelper.addListener(this.mLocationPermissionsListener);
        this.mAppForegroundHelper.addListener(this.mAppForegroundChangedListener);
        this.mLocationPowerSaveModeHelper.addListener(this.mLocationPowerSaveModeChangedListener);
        this.mScreenInteractiveHelper.addListener(this.mScreenInteractiveChangedListener);
        if (Flags.enableLocationBypass()) {
            this.mEmergencyHelper.addOnEmergencyStateChangedListener(this.mEmergencyStateChangedListener);
        }
        this.mPackageResetHelper.register(this.mPackageResetResponder);
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected void onUnregister() {
        this.mSettingsHelper.removeOnBackgroundThrottleIntervalChangedListener(this.mBackgroundThrottleIntervalChangedListener);
        this.mSettingsHelper.removeOnBackgroundThrottlePackageWhitelistChangedListener(this.mBackgroundThrottlePackageWhitelistChangedListener);
        this.mSettingsHelper.removeOnLocationPackageBlacklistChangedListener(this.mLocationPackageBlacklistChangedListener);
        this.mSettingsHelper.removeAdasAllowlistChangedListener(this.mAdasPackageAllowlistChangedListener);
        this.mSettingsHelper.removeIgnoreSettingsAllowlistChangedListener(this.mIgnoreSettingsPackageWhitelistChangedListener);
        this.mLocationPermissionsHelper.removeListener(this.mLocationPermissionsListener);
        this.mAppForegroundHelper.removeListener(this.mAppForegroundChangedListener);
        this.mLocationPowerSaveModeHelper.removeListener(this.mLocationPowerSaveModeChangedListener);
        this.mScreenInteractiveHelper.removeListener(this.mScreenInteractiveChangedListener);
        if (Flags.enableLocationBypass()) {
            this.mEmergencyHelper.removeOnEmergencyStateChangedListener(this.mEmergencyStateChangedListener);
        }
        this.mPackageResetHelper.unregister(this.mPackageResetResponder);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.location.listeners.ListenerMultiplexer
    public void onRegistrationAdded(Object key, Registration registration) {
        this.mLocationUsageLogger.logLocationApiUsage(0, 1, registration.getIdentity().getPackageName(), registration.getIdentity().getAttributionTag(), this.mName, registration.getRequest(), key instanceof PendingIntent, key instanceof IBinder, null, registration.isForeground());
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.location.listeners.ListenerMultiplexer
    public void onRegistrationReplaced(Object oldKey, Registration oldRegistration, Object newKey, Registration newRegistration) {
        newRegistration.setLastDeliveredLocation(oldRegistration.getLastDeliveredLocation());
        super.onRegistrationReplaced(oldKey, oldRegistration, newKey, newRegistration);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.location.listeners.ListenerMultiplexer
    public void onRegistrationRemoved(Object key, Registration registration) {
        this.mLocationUsageLogger.logLocationApiUsage(1, 1, registration.getIdentity().getPackageName(), registration.getIdentity().getAttributionTag(), this.mName, registration.getRequest(), key instanceof PendingIntent, key instanceof IBinder, null, registration.isForeground());
    }

    /* JADX INFO: renamed from: registerWithService, reason: avoid collision after fix types in other method */
    protected boolean registerWithService2(ProviderRequest request, Collection<Registration> registrations) {
        if (!request.isActive()) {
            return true;
        }
        return reregisterWithService2(ProviderRequest.EMPTY_REQUEST, request, registrations);
    }

    /* JADX INFO: renamed from: reregisterWithService, reason: avoid collision after fix types in other method */
    protected boolean reregisterWithService2(ProviderRequest oldRequest, final ProviderRequest newRequest, Collection<Registration> registrations) {
        long delayMs;
        if (!oldRequest.isBypass() && newRequest.isBypass()) {
            delayMs = 0;
        } else {
            long delayMs2 = newRequest.getIntervalMillis();
            if (delayMs2 > oldRequest.getIntervalMillis()) {
                delayMs = 0;
            } else {
                long delayMs3 = newRequest.getIntervalMillis();
                delayMs = calculateRequestDelayMillis(delayMs3, registrations);
            }
        }
        Preconditions.checkState(delayMs >= 0 && delayMs <= newRequest.getIntervalMillis());
        if (delayMs < 30000) {
            setProviderRequest(newRequest);
        } else {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, this.mName + " provider delaying request update " + newRequest + " by " + TimeUtils.formatDuration(delayMs));
            }
            if (this.mDelayedRegister != null) {
                this.mAlarmHelper.cancel(this.mDelayedRegister);
                this.mDelayedRegister = null;
            }
            this.mDelayedRegister = new AlarmManager.OnAlarmListener() { // from class: com.android.server.location.provider.LocationProviderManager.3
                @Override // android.app.AlarmManager.OnAlarmListener
                public void onAlarm() {
                    synchronized (LocationProviderManager.this.mMultiplexerLock) {
                        if (LocationProviderManager.this.mDelayedRegister == this) {
                            LocationProviderManager.this.mDelayedRegister = null;
                            LocationProviderManager.this.setProviderRequest(newRequest);
                        }
                    }
                }
            };
            this.mAlarmHelper.setDelayedAlarm(delayMs, this.mDelayedRegister, null);
        }
        return true;
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected void unregisterWithService() {
        setProviderRequest(ProviderRequest.EMPTY_REQUEST);
    }

    void setProviderRequest(final ProviderRequest request) {
        if (this.mDelayedRegister != null) {
            this.mAlarmHelper.cancel(this.mDelayedRegister);
            this.mDelayedRegister = null;
        }
        LocationEventLog.EVENT_LOG.logProviderUpdateRequest(this.mName, request);
        if (LocationManagerService.D) {
            Log.d(LocationManagerService.TAG, this.mName + " provider request changed to " + request);
        }
        this.mProvider.getController().setRequest(request);
        FgThread.getHandler().post(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.lambda$setProviderRequest$5(request);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$setProviderRequest$5(ProviderRequest request) {
        for (IProviderRequestListener listener : this.mProviderRequestListeners) {
            try {
                listener.onProviderRequestChanged(this.mName, request);
            } catch (RemoteException e) {
                this.mProviderRequestListeners.remove(listener);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.android.server.location.listeners.ListenerMultiplexer
    public boolean isActive(Registration registration) {
        if (!registration.isPermitted()) {
            return false;
        }
        boolean isBypass = registration.getRequest().isBypass();
        if (!isActive(isBypass, registration.getIdentity())) {
            return false;
        }
        if (!isBypass) {
            switch (this.mLocationPowerSaveModeHelper.getLocationPowerSaveMode()) {
                case 1:
                    if (!IOplusSceneManager.APP_SCENE_GPS.equals(this.mName)) {
                        return true;
                    }
                    break;
                case 2:
                case 4:
                    break;
                case 3:
                    return registration.isForeground();
                default:
                    return true;
            }
            return this.mScreenInteractiveHelper.isInteractive();
        }
        return true;
    }

    private boolean isActive(boolean isBypass, CallerIdentity identity) {
        if (identity.isSystemServer()) {
            return isBypass || isEnabled(this.mUserHelper.getCurrentUserId());
        }
        return (isBypass || (isEnabled(identity.getUserId()) && this.mUserHelper.isVisibleUserId(identity.getUserId()))) && !this.mSettingsHelper.isLocationPackageBlacklisted(identity.getUserId(), identity.getPackageName());
    }

    /* JADX WARN: Can't rename method to resolve collision */
    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected ProviderRequest mergeRegistrations(Collection<Registration> registrations) {
        long thresholdIntervalMs;
        Iterator<Registration> it = registrations.iterator();
        boolean lowPower = true;
        boolean lowPower2 = false;
        boolean locationSettingsIgnored = false;
        long maxUpdateDelayMs = Long.MAX_VALUE;
        int quality = 104;
        long intervalMs = Long.MAX_VALUE;
        while (it.hasNext()) {
            LocationRequest request = it.next().getRequest();
            if (request.getIntervalMillis() != Long.MAX_VALUE) {
                intervalMs = Math.min(request.getIntervalMillis(), intervalMs);
                quality = Math.min(request.getQuality(), quality);
                maxUpdateDelayMs = Math.min(request.getMaxUpdateDelayMillis(), maxUpdateDelayMs);
                locationSettingsIgnored |= request.isAdasGnssBypass();
                lowPower2 |= request.isLocationSettingsIgnored();
                lowPower &= request.isLowPower();
            }
        }
        if (intervalMs == Long.MAX_VALUE) {
            return ProviderRequest.EMPTY_REQUEST;
        }
        if (maxUpdateDelayMs / 2 < intervalMs) {
            maxUpdateDelayMs = 0;
        }
        try {
            thresholdIntervalMs = Math.multiplyExact(Math.addExact(intervalMs, 1000L) / 2, 3);
            try {
                if ("network".equals(this.mName) && mOplusLbsClass != null && mOplusLbsClass.isUsingRegionNlp() && intervalMs <= 30000) {
                    thresholdIntervalMs = 30000;
                }
            } catch (ArithmeticException e) {
                thresholdIntervalMs = 9223372036854775806L;
            }
        } catch (ArithmeticException e2) {
        }
        WorkSource workSource = new WorkSource();
        for (Registration registration : registrations) {
            if (registration.getRequest().getIntervalMillis() <= thresholdIntervalMs) {
                workSource.add(registration.getRequest().getWorkSource());
            }
        }
        return new ProviderRequest.Builder().setIntervalMillis(intervalMs).setQuality(quality).setMaxUpdateDelayMillis(maxUpdateDelayMs).setAdasGnssBypass(locationSettingsIgnored).setLocationSettingsIgnored(lowPower2).setLowPower(lowPower).setWorkSource(workSource).build();
    }

    protected long calculateRequestDelayMillis(long newIntervalMs, Collection<Registration> registrations) {
        long registrationDelayMs;
        long delayMs = newIntervalMs;
        for (Registration registration : registrations) {
            if (delayMs == 0) {
                break;
            }
            LocationRequest locationRequest = registration.getRequest();
            Location last = registration.getLastDeliveredLocation();
            if (last == null && !locationRequest.isLocationSettingsIgnored()) {
                last = getLastLocationUnsafe(registration.getIdentity().getUserId(), registration.getPermissionLevel(), false, locationRequest.getIntervalMillis());
            }
            if (last == null) {
                registrationDelayMs = 0;
            } else {
                registrationDelayMs = Math.max(0L, locationRequest.getIntervalMillis() - last.getElapsedRealtimeAgeMillis());
            }
            delayMs = Math.min(delayMs, registrationDelayMs);
        }
        return delayMs;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onUserChanged(final int userId, int change) {
        synchronized (this.mMultiplexerLock) {
            if (this.mState == 2) {
                return;
            }
            switch (change) {
                case 1:
                case 4:
                    updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda17
                        @Override // java.util.function.Predicate
                        public final boolean test(Object obj) {
                            return LocationProviderManager.lambda$onUserChanged$6(userId, (LocationProviderManager.Registration) obj);
                        }
                    });
                    break;
                case 2:
                    onUserStarted(userId);
                    break;
                case 3:
                    onUserStopped(userId);
                    break;
            }
        }
    }

    static /* synthetic */ boolean lambda$onUserChanged$6(int userId, Registration registration) {
        return registration.getIdentity().getUserId() == userId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationUserSettingsChanged(final int userId, LocationUserSettings oldSettings, LocationUserSettings newSettings) {
        if (oldSettings.isAdasGnssLocationEnabled() != newSettings.isAdasGnssLocationEnabled()) {
            updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda30
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return ((LocationProviderManager.Registration) obj).onAdasGnssLocationEnabledChanged(userId);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationEnabledChanged(int userId) {
        synchronized (this.mMultiplexerLock) {
            if (this.mState == 2) {
                return;
            }
            onEnabledChanged(userId);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onScreenInteractiveChanged(boolean screenInteractive) {
        switch (this.mLocationPowerSaveModeHelper.getLocationPowerSaveMode()) {
            case 1:
                if (!IOplusSceneManager.APP_SCENE_GPS.equals(this.mName)) {
                    return;
                }
                break;
            case 2:
            case 4:
                break;
            case 3:
            default:
                return;
        }
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda34
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return LocationProviderManager.lambda$onScreenInteractiveChanged$8((LocationProviderManager.Registration) obj);
            }
        });
    }

    static /* synthetic */ boolean lambda$onScreenInteractiveChanged$8(Registration registration) {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onEmergencyStateChanged() {
        final boolean inEmergency = this.mEmergencyHelper.isInEmergency(0L);
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda36
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ((LocationProviderManager.Registration) obj).onBypassLocationPermissionsChanged(inEmergency);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBackgroundThrottlePackageWhitelistChanged() {
        updateRegistrations(new LocationProviderManager$$ExternalSyntheticLambda24());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onBackgroundThrottleIntervalChanged() {
        updateRegistrations(new LocationProviderManager$$ExternalSyntheticLambda24());
    }

    static /* synthetic */ boolean lambda$onLocationPowerSaveModeChanged$10(Registration registration) {
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationPowerSaveModeChanged(int locationPowerSaveMode) {
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda20
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return LocationProviderManager.lambda$onLocationPowerSaveModeChanged$10((LocationProviderManager.Registration) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onAppForegroundChanged(final int uid, final boolean foreground) {
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda26
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ((LocationProviderManager.Registration) obj).onForegroundChanged(uid, foreground);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onAdasAllowlistChanged() {
        updateRegistrations(new LocationProviderManager$$ExternalSyntheticLambda24());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onIgnoreSettingsWhitelistChanged() {
        updateRegistrations(new LocationProviderManager$$ExternalSyntheticLambda24());
    }

    static /* synthetic */ boolean lambda$onLocationPackageBlacklistChanged$12(int userId, Registration registration) {
        return registration.getIdentity().getUserId() == userId;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationPackageBlacklistChanged(final int userId) {
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda35
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return LocationProviderManager.lambda$onLocationPackageBlacklistChanged$12(userId, (LocationProviderManager.Registration) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationPermissionsChanged(final String packageName) {
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda25
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ((LocationProviderManager.Registration) obj).onLocationPermissionsChanged(packageName);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onLocationPermissionsChanged(final int uid) {
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda18
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ((LocationProviderManager.Registration) obj).onLocationPermissionsChanged(uid);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onPackageReset(final String packageName) {
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda31
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return LocationProviderManager.lambda$onPackageReset$15(packageName, (LocationProviderManager.Registration) obj);
            }
        });
    }

    static /* synthetic */ boolean lambda$onPackageReset$15(String packageName, Registration registration) {
        if (registration.getIdentity().getPackageName().equals(packageName)) {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, "package reset remove registration " + registration);
            }
            registration.remove();
            return false;
        }
        return false;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isResetableForPackage(final String packageName) {
        return findRegistration(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda1
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return ((LocationProviderManager.Registration) obj).getIdentity().getPackageName().equals(packageName);
            }
        });
    }

    @Override // com.android.server.location.provider.AbstractLocationProvider.Listener
    public void onStateChanged(final AbstractLocationProvider.State oldState, final AbstractLocationProvider.State newState) {
        if (oldState.allowed != newState.allowed) {
            onEnabledChanged(-1);
        }
        if (!Objects.equals(oldState.properties, newState.properties)) {
            updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda3
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return ((LocationProviderManager.Registration) obj).onProviderPropertiesChanged();
                }
            });
        }
        if (this.mStateChangedListener != null) {
            final StateChangedListener listener = this.mStateChangedListener;
            FgThread.getExecutor().execute(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$onStateChanged$17(listener, oldState, newState);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onStateChanged$17(StateChangedListener listener, AbstractLocationProvider.State oldState, AbstractLocationProvider.State newState) {
        listener.onStateChanged(this.mName, oldState, newState);
    }

    @Override // com.android.server.location.provider.AbstractLocationProvider.Listener
    public void onReportLocation(LocationResult locationResult) {
        final LocationResult processed;
        LocationProviderManager locationProviderManager;
        if (this.mPassiveManager != null) {
            processed = processReportedLocation(locationResult);
            if (processed == null) {
                return;
            } else {
                LocationEventLog.EVENT_LOG.logProviderReceivedLocations(this.mName, processed.size());
            }
        } else {
            processed = locationResult;
        }
        if (this.mPassiveManager == null) {
            locationProviderManager = this;
        } else {
            locationProviderManager = this;
            Location last = locationProviderManager.getLastLocationUnsafe(-2, 2, true, Long.MAX_VALUE);
            if (last != null && locationResult.get(0).getElapsedRealtimeNanos() < last.getElapsedRealtimeNanos()) {
                Log.e(LocationManagerService.TAG, "non-monotonic location received from " + locationProviderManager.mName + " provider");
            }
        }
        setLastLocation(processed.getLastLocation(), -1);
        if (mOplusLbsClass != null) {
            mOplusLbsClass.handleLocationChanged(processed, LocationManagerService.D);
        }
        deliverToListeners(new Function() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda21
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((LocationProviderManager.Registration) obj).acceptLocationChange(processed);
            }
        });
        if (locationProviderManager.mPassiveManager != null) {
            locationProviderManager.mPassiveManager.updateLocation(processed);
        }
    }

    private LocationResult processReportedLocation(LocationResult locationResult) {
        try {
            locationResult.validate();
            if (DeviceConfig.getBoolean("location", "enable_location_provider_manager_msl", true)) {
                return locationResult.map(new Function() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda29
                    @Override // java.util.function.Function
                    public final Object apply(Object obj) {
                        return this.f$0.lambda$processReportedLocation$20((Location) obj);
                    }
                });
            }
            return locationResult;
        } catch (LocationResult.BadLocationException e) {
            Log.e(LocationManagerService.TAG, "Dropping invalid locations: " + e);
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ Location lambda$processReportedLocation$20(Location location) {
        if (!location.hasMslAltitude() && location.hasAltitude()) {
            try {
                final Location locationCopy = new Location(location);
                if (this.mAltitudeConverter.tryAddMslAltitudeToLocation(locationCopy)) {
                    return locationCopy;
                }
                if (this.mIsAltitudeConverterIdle) {
                    this.mIsAltitudeConverterIdle = false;
                    IoThread.getExecutor().execute(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda2
                        @Override // java.lang.Runnable
                        public final void run() {
                            this.f$0.lambda$processReportedLocation$19(locationCopy);
                        }
                    });
                }
            } catch (IllegalArgumentException e) {
                Log.e(LocationManagerService.TAG, "not adding MSL altitude to location: " + e);
            }
        }
        return location;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$processReportedLocation$19(Location locationCopy) {
        try {
            this.mAltitudeConverter.addMslAltitudeToLocation(this.mContext, locationCopy);
        } catch (IOException e) {
            Log.e(LocationManagerService.TAG, "not loading MSL altitude assets: " + e);
        }
        this.mIsAltitudeConverterIdle = true;
    }

    private void onUserStarted(int userId) {
        if (userId == -10000) {
            return;
        }
        if (userId == -1) {
            this.mEnabled.clear();
            onEnabledChanged(-1);
        } else {
            Preconditions.checkArgument(userId >= 0);
            this.mEnabled.delete(userId);
            onEnabledChanged(userId);
        }
    }

    private void onUserStopped(int userId) {
        if (userId == -10000) {
            return;
        }
        if (userId == -1) {
            this.mEnabled.clear();
            this.mLastLocations.clear();
        } else {
            Preconditions.checkArgument(userId >= 0);
            this.mEnabled.delete(userId);
            this.mLastLocations.remove(userId);
        }
    }

    private void onEnabledChanged(final int userId) {
        LastLocation lastLocation;
        if (userId == -10000) {
            return;
        }
        if (userId == -1) {
            int[] runningUserIds = this.mUserHelper.getRunningUserIds();
            for (int i : runningUserIds) {
                onEnabledChanged(i);
            }
            return;
        }
        Preconditions.checkArgument(userId >= 0);
        final boolean enabled = this.mState == 0 && this.mProvider.getState().allowed && this.mSettingsHelper.isLocationEnabled(userId);
        int index = this.mEnabled.indexOfKey(userId);
        Boolean wasEnabled = index < 0 ? null : Boolean.valueOf(this.mEnabled.valueAt(index));
        if (wasEnabled != null && wasEnabled.booleanValue() == enabled) {
            return;
        }
        this.mEnabled.put(userId, enabled);
        if (wasEnabled != null || enabled) {
            if (LocationManagerService.D) {
                Log.d(LocationManagerService.TAG, "[u" + userId + "] " + this.mName + " provider enabled = " + enabled);
            }
            LocationEventLog.EVENT_LOG.logProviderEnabled(this.mName, userId, enabled);
        }
        if (!enabled && (lastLocation = this.mLastLocations.get(userId)) != null) {
            lastLocation.clearLocations();
        }
        if (mOplusLbsClass != null) {
            mOplusLbsClass.updateSettings(this.mName, userId);
        }
        if (wasEnabled != null) {
            if (!"passive".equals(this.mName)) {
                Intent intent = new Intent("android.location.PROVIDERS_CHANGED").putExtra("android.location.extra.PROVIDER_NAME", this.mName).putExtra("android.location.extra.PROVIDER_ENABLED", enabled).addFlags(1073741824).addFlags(268435456);
                this.mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            }
            if (!this.mEnabledListeners.isEmpty()) {
                final LocationManagerInternal.ProviderEnabledListener[] listeners = (LocationManagerInternal.ProviderEnabledListener[]) this.mEnabledListeners.toArray(new LocationManagerInternal.ProviderEnabledListener[0]);
                FgThread.getHandler().post(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda27
                    @Override // java.lang.Runnable
                    public final void run() {
                        this.f$0.lambda$onEnabledChanged$21(listeners, userId, enabled);
                    }
                });
            }
        }
        updateRegistrations(new Predicate() { // from class: com.android.server.location.provider.LocationProviderManager$$ExternalSyntheticLambda28
            @Override // java.util.function.Predicate
            public final boolean test(Object obj) {
                return LocationProviderManager.lambda$onEnabledChanged$22(userId, (LocationProviderManager.Registration) obj);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onEnabledChanged$21(LocationManagerInternal.ProviderEnabledListener[] listeners, int userId, boolean enabled) {
        for (LocationManagerInternal.ProviderEnabledListener providerEnabledListener : listeners) {
            providerEnabledListener.onProviderEnabledChanged(this.mName, userId, enabled);
        }
    }

    static /* synthetic */ boolean lambda$onEnabledChanged$22(int userId, Registration registration) {
        return registration.getIdentity().getUserId() == userId;
    }

    Location getPermittedLocation(Location fineLocation, int permissionLevel) {
        switch (permissionLevel) {
            case 1:
                if (mOplusLbsClass == null) {
                    if (fineLocation != null) {
                        return this.mLocationFudger.createCoarse(fineLocation);
                    }
                    return null;
                }
                if (fineLocation != null) {
                    return mOplusLbsClass.addCoarseLocationExtra(this.mLocationFudger.createCoarse(fineLocation));
                }
                return null;
            case 2:
                return fineLocation;
            default:
                throw new AssertionError();
        }
    }

    LocationResult getPermittedLocationResult(LocationResult fineLocationResult, int permissionLevel) {
        switch (permissionLevel) {
            case 1:
                if (mOplusLbsClass == null) {
                    if (fineLocationResult != null) {
                        return this.mLocationFudger.createCoarse(fineLocationResult);
                    }
                    return null;
                }
                if (fineLocationResult != null) {
                    return mOplusLbsClass.addCoarseLocationExtra(this.mLocationFudger.createCoarse(fineLocationResult));
                }
                return null;
            case 2:
                return fineLocationResult;
            default:
                throw new AssertionError();
        }
    }

    public void dump(FileDescriptor fd, IndentingPrintWriter ipw, String[] args) {
        synchronized (this.mMultiplexerLock) {
            try {
                try {
                    ipw.print(this.mName);
                    ipw.print(" provider");
                    if (this.mProvider.isMock()) {
                        ipw.print(" [mock]");
                    }
                    ipw.println(":");
                    ipw.increaseIndent();
                    super.dump(fd, (PrintWriter) ipw, args);
                    int[] userIds = this.mUserHelper.getRunningUserIds();
                    for (int userId : userIds) {
                        if (userIds.length != 1) {
                            ipw.print("user ");
                            ipw.print(userId);
                            ipw.println(":");
                            ipw.increaseIndent();
                        }
                        ipw.print("last location=");
                        ipw.println(getLastLocationUnsafe(userId, 2, false, Long.MAX_VALUE));
                        ipw.print("enabled=");
                        ipw.println(isEnabled(userId));
                        if (userIds.length != 1) {
                            ipw.decreaseIndent();
                        }
                    }
                    this.mProvider.dump(fd, ipw, args);
                    ipw.decreaseIndent();
                } catch (Throwable th) {
                    th = th;
                    throw th;
                }
            } catch (Throwable th2) {
                th = th2;
                throw th;
            }
        }
    }

    @Override // com.android.server.location.listeners.ListenerMultiplexer
    protected String getServiceState() {
        return this.mProvider.getCurrentRequest().toString();
    }

    private static class LastLocation {
        private Location mCoarseBypassLocation;
        private Location mCoarseLocation;
        private Location mFineBypassLocation;
        private Location mFineLocation;

        LastLocation() {
        }

        public void clearMock() {
            if (this.mFineLocation != null && this.mFineLocation.isMock()) {
                this.mFineLocation = null;
            }
            if (this.mCoarseLocation != null && this.mCoarseLocation.isMock()) {
                this.mCoarseLocation = null;
            }
            if (this.mFineBypassLocation != null && this.mFineBypassLocation.isMock()) {
                this.mFineBypassLocation = null;
            }
            if (this.mCoarseBypassLocation != null && this.mCoarseBypassLocation.isMock()) {
                this.mCoarseBypassLocation = null;
            }
        }

        public void clearLocations() {
            this.mFineLocation = null;
            this.mCoarseLocation = null;
        }

        public Location get(int permissionLevel, boolean isBypass) {
            switch (permissionLevel) {
                case 1:
                    if (isBypass) {
                        return this.mCoarseBypassLocation;
                    }
                    return this.mCoarseLocation;
                case 2:
                    if (isBypass) {
                        return this.mFineBypassLocation;
                    }
                    return this.mFineLocation;
                default:
                    throw new AssertionError();
            }
        }

        public void set(Location location) {
            this.mFineLocation = calculateNextFine(this.mFineLocation, location);
            this.mCoarseLocation = calculateNextCoarse(this.mCoarseLocation, location);
        }

        public void setBypass(Location location) {
            this.mFineBypassLocation = calculateNextFine(this.mFineBypassLocation, location);
            this.mCoarseBypassLocation = calculateNextCoarse(this.mCoarseBypassLocation, location);
        }

        private Location calculateNextFine(Location oldFine, Location newFine) {
            if (oldFine == null || newFine.getElapsedRealtimeNanos() > oldFine.getElapsedRealtimeNanos()) {
                return newFine;
            }
            return oldFine;
        }

        private Location calculateNextCoarse(Location oldCoarse, Location newCoarse) {
            if (oldCoarse == null || newCoarse.getElapsedRealtimeMillis() - 600000 > oldCoarse.getElapsedRealtimeMillis()) {
                return newCoarse;
            }
            return oldCoarse;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class PendingIntentSender {
        private PendingIntentSender() {
        }

        public static void send(PendingIntent pendingIntent, Context context, Intent intent, Runnable callback, Bundle options) throws PendingIntent.CanceledException {
            final GatedCallback gatedCallback;
            PendingIntent.OnFinished onFinished;
            if (callback != null) {
                gatedCallback = new GatedCallback(callback);
                onFinished = new PendingIntent.OnFinished() { // from class: com.android.server.location.provider.LocationProviderManager$PendingIntentSender$$ExternalSyntheticLambda0
                    @Override // android.app.PendingIntent.OnFinished
                    public final void onSendFinished(PendingIntent pendingIntent2, Intent intent2, int i, String str, Bundle bundle) {
                        gatedCallback.run();
                    }
                };
            } else {
                gatedCallback = null;
                onFinished = null;
            }
            pendingIntent.send(context, 0, intent, onFinished, null, null, options);
            if (gatedCallback != null) {
                gatedCallback.allow();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        static class GatedCallback implements Runnable {
            private Runnable mCallback;
            private boolean mGate;
            private boolean mRun;

            private GatedCallback(Runnable callback) {
                this.mCallback = callback;
            }

            public void allow() {
                Runnable callback = null;
                synchronized (this) {
                    this.mGate = true;
                    if (this.mRun && this.mCallback != null) {
                        callback = this.mCallback;
                        this.mCallback = null;
                    }
                }
                if (callback != null) {
                    callback.run();
                }
            }

            @Override // java.lang.Runnable
            public void run() {
                Runnable callback = null;
                synchronized (this) {
                    this.mRun = true;
                    if (this.mGate && this.mCallback != null) {
                        callback = this.mCallback;
                        this.mCallback = null;
                    }
                }
                if (callback != null) {
                    callback.run();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class ExternalWakeLockReleaser extends IRemoteCallback.Stub {
        private final CallerIdentity mIdentity;
        private final PowerManager.WakeLock mWakeLock;

        ExternalWakeLockReleaser(CallerIdentity identity, PowerManager.WakeLock wakeLock) {
            this.mIdentity = identity;
            this.mWakeLock = (PowerManager.WakeLock) Objects.requireNonNull(wakeLock);
        }

        public void sendResult(Bundle data) {
            long identity = Binder.clearCallingIdentity();
            try {
                try {
                    this.mWakeLock.release();
                } catch (RuntimeException e) {
                    if (e.getClass() == RuntimeException.class) {
                        Log.e(LocationManagerService.TAG, "wakelock over-released by " + this.mIdentity, e);
                    } else {
                        FgThread.getExecutor().execute(new Runnable() { // from class: com.android.server.location.provider.LocationProviderManager$ExternalWakeLockReleaser$$ExternalSyntheticLambda0
                            @Override // java.lang.Runnable
                            public final void run() {
                                LocationProviderManager.ExternalWakeLockReleaser.lambda$sendResult$0(e);
                            }
                        });
                        throw e;
                    }
                }
                Binder.restoreCallingIdentity(identity);
            } catch (Throwable th) {
                Binder.restoreCallingIdentity(identity);
                throw th;
            }
        }

        static /* synthetic */ void lambda$sendResult$0(RuntimeException e) {
            throw new AssertionError(e);
        }
    }

    public ILocationProviderManagerWrapper getWrapper() {
        return this.mLocationProviderManagerWrapper;
    }

    public static void oplusSystemReady(Context context) {
        if (mOplusLbsClass == null) {
            mOplusLbsClass = (IOplusLBSMainClass) OplusLbsFactory.getInstance().getFeature(IOplusLBSMainClass.DEFAULT, context);
        }
        mLocationFreeze = (ILocationFreezeProc) OplusLbsFactory.getInstance().getFeature(ILocationFreezeProc.DEFAULT, context);
    }

    private class LocationProviderManagerWrapper implements ILocationProviderManagerWrapper {
        private LocationProviderManagerWrapper() {
        }

        @Override // com.android.server.location.provider.ILocationProviderManagerWrapper
        public void backgroundThrottleIntervalChanged() {
            LocationProviderManager.this.onBackgroundThrottleIntervalChanged();
        }
    }
}
