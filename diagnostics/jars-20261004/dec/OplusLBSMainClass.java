package com.android.server.location;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.location.GnssStatus;
import android.location.LastLocationRequest;
import android.location.Location;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.location.provider.ForwardGeocodeRequest;
import android.location.provider.IGeocodeCallback;
import android.location.provider.ProviderRequest;
import android.location.provider.ReverseGeocodeRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OplusSystemProperties;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.WorkSource;
import android.text.TextUtils;
import com.android.internal.content.PackageMonitor;
import com.android.server.location.aidl.OplusLocationAidlClient;
import com.android.server.location.blacklist.OplusGnssWhiteListProxy;
import com.android.server.location.blacklist.OplusHighFrequencyLocationBlacklist;
import com.android.server.location.blacklist.OplusLocationBlacklistUtil;
import com.android.server.location.blacklist.OplusVirtualGpsVisibleBlackList;
import com.android.server.location.common.OplusLbsCommonConstant;
import com.android.server.location.common.debugreport.DebugReportListener;
import com.android.server.location.common.debugreport.QcomXtraThrottleController;
import com.android.server.location.common.passive.OplusProviderInternalDeliverProxy;
import com.android.server.location.customize.OplusLbsCustomize;
import com.android.server.location.ecall.OplusEmergencyCall;
import com.android.server.location.fused.OplusFlpCoordinator;
import com.android.server.location.fused.OplusFlpHelper;
import com.android.server.location.fused.OplusFused3Controller;
import com.android.server.location.fused.OplusLocationCache;
import com.android.server.location.garage.OplusGarageExitLocationController;
import com.android.server.location.gnss.GnssLocationProvider;
import com.android.server.location.gnss.GnssMeasurementsProvider;
import com.android.server.location.gnss.GnssPowerStats;
import com.android.server.location.gnss.OplusGnssLocationVerifier;
import com.android.server.location.gnss.OplusGnssSvStrategy;
import com.android.server.location.gnss.OplusMtkBnssPreferredController;
import com.android.server.location.gnss.OplusPreciseLocationController;
import com.android.server.location.gnss.OplusPreciseLocationUtils;
import com.android.server.location.gnss.OplusQcomBdsOnlyController;
import com.android.server.location.gnss.OplusSatelliteSimulator;
import com.android.server.location.interfaces.IOplusConfigListener;
import com.android.server.location.interfaces.IOplusLBSMainClass;
import com.android.server.location.intermediatepos.OplusIntermediatePosController;
import com.android.server.location.log.LBSLog;
import com.android.server.location.log.OplusLbsLogController;
import com.android.server.location.log.PlatformDumpManager;
import com.android.server.location.nlp.OplusGeocoderProxy;
import com.android.server.location.nlp.OplusMultiNlpHelper;
import com.android.server.location.nlp.OplusNlpProxy;
import com.android.server.location.nvutils.GnssNvController;
import com.android.server.location.ols.OplusLocationServiceProxy;
import com.android.server.location.pnet.OplusPnetLocationController;
import com.android.server.location.power.disconnect.OplusLbsSleepManager;
import com.android.server.location.power.freeze.LocationFreezeProc;
import com.android.server.location.power.saver.NavigationStatusController;
import com.android.server.location.power.saver.PowerSaverDump;
import com.android.server.location.power.statistics.OplusGnssPowerSceneRecognition;
import com.android.server.location.power.stats.OplusGnssPowerCorrector;
import com.android.server.location.power.stats.OplusGnssPowerModel;
import com.android.server.location.provider.AbstractLocationProvider;
import com.android.server.location.provider.StationaryThrottlingLocationProvider;
import com.android.server.location.record.OplusGeocoderRecord;
import com.android.server.location.repairer.OplusDataRepairerStats;
import com.android.server.location.repairer.OplusLbsRepairer;
import com.android.server.location.repairer.OplusSuplRepairerStats;
import com.android.server.location.rus.OplusLbsRomUpdateUtil;
import com.android.server.location.statistics.AospImplStatistics;
import com.android.server.location.statistics.LocationSnapshotStatistics;
import com.android.server.location.statistics.LowActivityGnssUsageMonitor;
import com.android.server.location.statistics.OplusCorrectionsStatistics;
import com.android.server.location.statistics.OplusGnssDiagnosticTool;
import com.android.server.location.statistics.OplusLocationStatistics;
import com.android.server.location.statistics.OplusLocationStatusMonitor;
import com.android.server.location.thread.OplusLocationThreadRenter;
import com.oplus.pantaconnect.sdk.connectionservice.lan.LanConstants;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/* JADX INFO: loaded from: classes.dex */
public class OplusLBSMainClass implements IOplusLBSMainClass {
    private static final int DEFAULT_GEOCODER_TASK_CANCEL_TIME = 30000;
    private static final String KEY_GEOCODER_TASK_CANCEL_TIME_CN = "config_geocoderTaskCancelTimeCn";
    private static final String KEY_GEOCODER_TASK_CANCEL_TIME_GMS = "config_geocoderTaskCancelTimeGms";
    private static final String KEY_OLS_FEATURE = "olsFeature";
    private static final String OPLUS_NFW_PROXY_APP = "com.oplus.locationproxy";
    private static final int REMOVE_GPS = 1;
    private static final int REQUEST_GPS = 0;
    private static final String TAG = "OplusLBSMainClass";
    private static final int THREAD_PRIORITY = 1;
    private static volatile OplusLBSMainClass sInstance = null;
    private AospImplStatistics mAospImplStat;
    private final Context mContext;
    private OplusGeocoderProxy mGeocoderProxy;
    private OplusGnssWhiteListProxy mGnssWhiteListProxy;
    private HandlerThread mLbsThread;
    private OplusEmergencyCall mOplusEmergencyCall;
    private OplusFlpHelper mOplusFlpHelper;
    private OplusFused3Controller mOplusFused3Controller;
    private OplusGarageExitLocationController mOplusGarageExitLocationController;
    private OplusGeocoderRecord mOplusGeocoderRecord;
    private OplusGnssDiagnosticTool mOplusGnssDiagnosticTool;
    private OplusGnssPowerModel mOplusGnssPowerModel;
    private OplusGnssPowerSceneRecognition mOplusGnssPowerSceneRecognition;
    private OplusGnssSvStrategy mOplusGnssSvStrategy;
    private OplusHighFrequencyLocationBlacklist mOplusHighFreqLocationBlacklist;
    private OplusIntermediatePosController mOplusIntermediatePosController;
    private OplusLbsConfigNotifyer mOplusLbsConfigNotifyer;
    private OplusLbsCustomize mOplusLbsCustomize;
    private OplusLbsLogController mOplusLbsLogController;
    private OplusLbsRepairer mOplusLbsRepairer;
    private OplusLbsRomUpdateUtil mOplusLbsRomUpdateUtil;
    private OplusLbsSleepManager mOplusLbsSleepManager;
    private OplusLbsTestAdapter mOplusLbsTestAdapter;
    private OplusLocationAidlClient mOplusLocationAidlClient;
    private OplusLocationBlacklistUtil mOplusLocationBlacklistUtil;
    private OplusLocationCache mOplusLocationCache;
    private OplusLocationManagerService mOplusLocationManagerService;
    private OplusLocationStatistics mOplusLocationStatistics;
    private OplusLocationStatusMonitor mOplusLocationStatusMonitor;
    private OplusPnetLocationController mOplusPnetLocationController;
    private OplusPreciseLocationController mOplusPreciseLocationController;
    private OplusVirtualGpsVisibleBlackList mOplusVirtualGpsVisibleBlackList;
    private QcomXtraThrottleController mQcomXtraThrottleControl;
    private LocationSnapshotStatistics mSnapshot;
    private OplusNlpProxy mNlpProxy = null;
    private OplusFlpCoordinator mOplusFlpCoordinator = null;
    private NavigationStatusController mOplusNavigationStatusController = null;
    private OplusGnssPowerCorrector mOplusGnssPowerCorrector = null;
    private GnssLocationProvider mGnssLocationProvider = null;
    private GnssMeasurementsProvider mGnssMeasurementsProvider = null;
    private OplusMtkBnssPreferredController mOplusMtkBnssPreferredController = null;
    private OplusQcomBdsOnlyController mOplusQcomBdsOnlyController = null;
    private GnssNvController mGnssNvController = null;
    private OplusProviderInternalDeliverProxy mOplusProviderInternalDeliverProxy = null;
    private PackageMonitor mPackageMonitor = new PackageMonitor() { // from class: com.android.server.location.OplusLBSMainClass.1
        public void onPackageAdded(String name, int uid) {
            LBSLog.d(true, OplusLBSMainClass.TAG, "add: %s", name);
            if (OplusLBSMainClass.this.mOplusLocationStatusMonitor != null) {
                OplusLBSMainClass.this.mOplusLocationStatusMonitor.onPackageChanged(true, name);
            }
            LocationFreezeProc.getInstance(OplusLBSMainClass.this.mContext).onPackageChanged(true, name);
        }

        public void onPackageRemoved(String name, int uid) {
            LBSLog.d(true, OplusLBSMainClass.TAG, "remove: %s", name);
            if (OplusLBSMainClass.this.mOplusLocationStatusMonitor != null) {
                OplusLBSMainClass.this.mOplusLocationStatusMonitor.onPackageChanged(false, name);
            }
            LocationFreezeProc.getInstance(OplusLBSMainClass.this.mContext).onPackageChanged(false, name);
        }

        public void onPackageUpdateFinished(String name, int uid) {
            LBSLog.d(true, OplusLBSMainClass.TAG, "update: %s", name);
        }
    };

    private OplusLBSMainClass(Context context) {
        this.mOplusLocationManagerService = null;
        this.mOplusLocationBlacklistUtil = null;
        this.mGnssWhiteListProxy = null;
        this.mOplusHighFreqLocationBlacklist = null;
        this.mOplusGnssDiagnosticTool = null;
        this.mOplusLbsCustomize = null;
        this.mOplusLbsRomUpdateUtil = null;
        this.mOplusFlpHelper = null;
        this.mOplusGeocoderRecord = null;
        this.mOplusLbsRepairer = null;
        this.mOplusLocationStatistics = null;
        this.mOplusLocationStatusMonitor = null;
        this.mOplusLocationCache = null;
        this.mOplusLbsConfigNotifyer = null;
        this.mGeocoderProxy = null;
        this.mOplusVirtualGpsVisibleBlackList = null;
        this.mOplusFused3Controller = null;
        this.mOplusLbsLogController = null;
        this.mLbsThread = null;
        this.mOplusEmergencyCall = null;
        this.mOplusGnssSvStrategy = null;
        this.mOplusGnssPowerModel = null;
        this.mOplusPreciseLocationController = null;
        this.mOplusGarageExitLocationController = null;
        this.mOplusLocationAidlClient = null;
        this.mSnapshot = null;
        this.mOplusPnetLocationController = null;
        this.mOplusIntermediatePosController = null;
        this.mOplusLbsSleepManager = null;
        this.mOplusGnssPowerSceneRecognition = null;
        this.mOplusLbsTestAdapter = null;
        this.mAospImplStat = null;
        this.mContext = context;
        sInstance = this;
        this.mLbsThread = OplusLocationThreadRenter.getThread(1);
        this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        this.mOplusLocationBlacklistUtil = OplusLocationBlacklistUtil.getInstance();
        this.mOplusLocationBlacklistUtil.init(this.mContext, this.mLbsThread.getLooper());
        this.mGnssWhiteListProxy = OplusGnssWhiteListProxy.getInstall(this.mContext);
        this.mOplusHighFreqLocationBlacklist = OplusHighFrequencyLocationBlacklist.getInstall(this.mContext);
        this.mOplusGnssDiagnosticTool = OplusGnssDiagnosticTool.getInstall(this.mContext);
        this.mOplusGnssSvStrategy = OplusGnssSvStrategy.getInstance(this.mContext);
        this.mOplusGnssPowerModel = OplusGnssPowerModel.getInstance(this.mContext);
        this.mOplusLbsCustomize = OplusLbsCustomize.getInstall(this.mContext);
        this.mOplusLbsRomUpdateUtil = OplusLbsRomUpdateUtil.getInstall(this.mContext);
        this.mOplusGeocoderRecord = OplusGeocoderRecord.getInstance();
        this.mOplusFlpHelper = OplusFlpHelper.getInstance(this.mContext);
        this.mOplusFused3Controller = OplusFused3Controller.getInstance(this.mContext);
        this.mOplusLbsRepairer = OplusLbsRepairer.getInstance(this.mContext);
        this.mOplusVirtualGpsVisibleBlackList = OplusVirtualGpsVisibleBlackList.getInstall(this.mContext);
        this.mOplusLocationStatistics = OplusLocationStatistics.getInstance();
        this.mOplusLocationStatusMonitor = OplusLocationStatusMonitor.getInstance();
        this.mOplusLocationCache = OplusLocationCache.getInstance(this.mContext);
        this.mOplusGarageExitLocationController = OplusGarageExitLocationController.getInstance(this.mContext);
        this.mOplusLbsLogController = OplusLbsLogController.getInstance(this.mContext);
        this.mGeocoderProxy = OplusGeocoderProxy.getInstance(this.mContext);
        this.mOplusLbsConfigNotifyer = OplusLbsConfigNotifyer.getInstance(this.mContext);
        this.mOplusEmergencyCall = OplusEmergencyCall.getInstance(this.mContext);
        this.mOplusPreciseLocationController = OplusPreciseLocationController.getInstance(this.mContext);
        this.mOplusLocationAidlClient = OplusLocationAidlClient.getInstance(this.mContext);
        this.mSnapshot = LocationSnapshotStatistics.getInstance();
        this.mOplusPnetLocationController = OplusPnetLocationController.getInstance(this.mContext);
        this.mOplusIntermediatePosController = OplusIntermediatePosController.getInstance(this.mContext);
        this.mOplusLbsSleepManager = OplusLbsSleepManager.getInstance(this.mContext);
        this.mOplusLbsTestAdapter = OplusLbsTestAdapter.getInstance(this.mContext);
        this.mOplusGnssPowerSceneRecognition = OplusGnssPowerSceneRecognition.getInstance();
        this.mAospImplStat = AospImplStatistics.getInstance();
    }

    public static OplusLBSMainClass getInstance(Context context) {
        if (sInstance == null) {
            synchronized (OplusLBSMainClass.class) {
                if (sInstance == null) {
                    sInstance = new OplusLBSMainClass(context);
                }
            }
        }
        return sInstance;
    }

    public static OplusLBSMainClass getInstance() {
        return sInstance;
    }

    public static synchronized Context getContext() {
        if (sInstance == null) {
            LBSLog.e(TAG, "get OplusLBSMainClass Context before Constructor", new Object[0]);
            return null;
        }
        return sInstance.mContext;
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (this.mOplusLocationManagerService == null) {
            this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        }
        return this.mOplusLocationManagerService.onTransact(code, data, reply, flags);
    }

    public void oplusSystemReady(LocationManagerService locMgrService) {
        if (this.mOplusLocationManagerService == null) {
            this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        }
        this.mOplusLocationManagerService.oplusSystemReady(locMgrService);
        if (OplusLbsCommonConstant.QCOM_PLATFORM.equals(OplusLbsCommonConstant.getPlatform())) {
            if (this.mGnssNvController == null) {
                this.mGnssNvController = new GnssNvController(this.mContext);
            }
            if (this.mGnssNvController != null) {
                this.mGnssNvController.trigger();
            }
            if (this.mOplusQcomBdsOnlyController == null) {
                this.mOplusQcomBdsOnlyController = OplusQcomBdsOnlyController.getInstance(this.mContext);
            }
            this.mQcomXtraThrottleControl = new QcomXtraThrottleController(this.mContext);
        } else if (OplusLbsCommonConstant.MTK_PLATFORM.equals(OplusLbsCommonConstant.getPlatform())) {
            this.mOplusMtkBnssPreferredController = OplusMtkBnssPreferredController.getInstance(this.mContext);
        }
        this.mPackageMonitor.register(this.mContext, OplusLocationThreadRenter.getHandler(1).getLooper(), true);
    }

    public void oplusSystemThirdPartyAppsCanStart() {
        if (this.mOplusLocationManagerService == null) {
            this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        }
        this.mOplusLocationManagerService.oplusSystemThirdPartyAppsCanStart();
    }

    public boolean registerLocationListener(LocationRequest request, CallerIdentity identity, int permissionLevel) {
        if (this.mOplusLocationManagerService == null) {
            this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        }
        return this.mOplusLocationManagerService.registerLocationListener(request, identity, permissionLevel);
    }

    public boolean registerLocationListener(String provider, CallerIdentity identity, int permissionLevel) {
        if (this.mOplusLocationManagerService == null) {
            this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        }
        return this.mOplusLocationManagerService.registerLocationListener(provider, identity, permissionLevel);
    }

    public boolean sendExtraCommand(String provider, String command, Bundle extras) {
        if (TextUtils.isEmpty(provider) || TextUtils.isEmpty(command)) {
            return false;
        }
        if ("config_cmd_provider".equals(provider)) {
            LBSLog.d(true, TAG, "sendExtraCommand, OplusLocationAidlClient.CONFIG_CMD_PROVIDER, mOplusLocationAidlClient test", new Object[0]);
            if (this.mOplusLocationAidlClient == null) {
                this.mOplusLocationAidlClient = OplusLocationAidlClient.getInstance(this.mContext);
            }
            if (this.mOplusLocationAidlClient != null) {
                return this.mOplusLocationAidlClient.handleCustomizeCommand(command, extras);
            }
            return false;
        }
        if (OplusPreciseLocationController.CONFIG_PRECISE_LOCATION_PROVIDER.equals(provider)) {
            LBSLog.d(true, TAG, "sendExtraCommand, OplusPreciseLocationController.CONFIG_PRECISE_LOCATION_PROVIDER", new Object[0]);
            if (this.mOplusPreciseLocationController != null) {
                return this.mOplusPreciseLocationController.handlePreciseLocationCommand(command, extras);
            }
            return false;
        }
        if (this.mOplusLocationManagerService == null) {
            this.mOplusLocationManagerService = OplusLocationManagerService.getInstance(this.mContext);
        }
        if (!this.mOplusLocationManagerService.sendLmsExtraCommand(provider, command, extras)) {
            return false;
        }
        handleCommand(provider, command, extras);
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.sendExtraCommand(provider, command, extras);
        }
        return true;
    }

    public void handleLocationChanged(LocationResult locationResult, boolean debug) {
        this.mOplusLocationManagerService.printLocationResult(locationResult);
    }

    public void onLocationChanged(Location location) {
        if (location != null) {
            OplusDataRepairerStats.getInstance().testLocationBefore(location);
            try {
                Location oplusLoc = new Location(location);
                OplusDataRepairerStats.getInstance().testLocationAfter(oplusLoc);
                if (this.mOplusGnssPowerSceneRecognition != null && "gps".equals(oplusLoc.getProvider())) {
                    this.mOplusGnssPowerSceneRecognition.onLocationChanged(oplusLoc);
                }
                if (this.mOplusFused3Controller != null) {
                    if (this.mOplusFused3Controller.isFusedGeneratedLocation(oplusLoc)) {
                        return;
                    } else {
                        this.mOplusFused3Controller.inputLocationChanged(oplusLoc);
                    }
                }
                if (this.mOplusLocationStatistics != null && "gps".equals(oplusLoc.getProvider())) {
                    this.mOplusLocationStatistics.onGpsLocationChanged(oplusLoc);
                }
            } catch (Exception e) {
                LBSLog.e(true, TAG, "transform %s Location fail at %s", location.getProvider(), e.toString());
            }
        }
    }

    public void onGnssLocationProviderInit(Context context, GnssLocationProvider provider) {
        initGnssPowerSaver(provider);
        setGnssLocationProvider(provider);
        OplusLocationServiceProxy.getInstance(context).onGnssProviderInit(provider);
        OplusPreciseLocationController.getInstance(context).onGnssProviderInit(provider);
        OplusLocationManagerService.getInstance(context).onGnssProviderInit(provider);
    }

    @Deprecated
    public void onSetRequest(ProviderRequest request) {
        updateGpsWorksourceStatus(request.getWorkSource());
    }

    public void onStartNavigating(int interval) throws Throwable {
        onFreezeGnssStarted();
        startFlpAiding();
        recordGnssNavigatingStarted(interval);
        startRecordMonitor();
        startController();
        if (this.mOplusGnssPowerSceneRecognition != null) {
            this.mOplusGnssPowerSceneRecognition.onStartNavigating();
        }
        this.mOplusProviderInternalDeliverProxy = OplusProviderInternalDeliverProxy.getInstance(this.mContext);
        DebugReportListener.getInstance(this.mContext).registerDebugReport();
        if (this.mOplusLocationAidlClient != null) {
            this.mOplusLocationAidlClient.onStartNavigating();
        }
        if (this.mOplusFused3Controller != null) {
            this.mOplusFused3Controller.onStartNavigating();
        }
        OplusLocationServiceProxy.getInstance(this.mContext).onStartNavigating();
        OplusSuplRepairerStats.getInstance().onStartNavigating();
        LowActivityGnssUsageMonitor.getInstance(this.mContext).onStartNavigating();
    }

    public void onStopNavigating() {
        stopFlpAiding();
        recordGnssNavigatingStopped();
        stopRecordMonitor();
        if (this.mOplusGnssPowerSceneRecognition != null) {
            this.mOplusGnssPowerSceneRecognition.onStopNavigating();
        }
        if (OplusLbsCommonConstant.QCOM_PLATFORM.equals(OplusLbsCommonConstant.getPlatform())) {
            if (this.mGnssNvController == null) {
                this.mGnssNvController = new GnssNvController(this.mContext);
            }
            if (this.mGnssNvController != null) {
                this.mGnssNvController.trigger();
            }
        } else if (OplusLbsCommonConstant.MTK_PLATFORM.equals(OplusLbsCommonConstant.getPlatform())) {
            this.mOplusMtkBnssPreferredController = OplusMtkBnssPreferredController.getInstance(this.mContext);
        }
        onFreezeGnssStopped();
        OplusLocationServiceProxy.getInstance(this.mContext).onStopNavigating();
        if (this.mOplusLocationAidlClient != null) {
            this.mOplusLocationAidlClient.onStopNavigating();
        }
        if (this.mOplusFused3Controller != null) {
            this.mOplusFused3Controller.onStopNavigating();
        }
        DebugReportListener.getInstance(this.mContext).unregisterDebugReport();
        LowActivityGnssUsageMonitor.getInstance(this.mContext).onStopNavigating();
    }

    public void initOplusNlp() {
        if (this.mNlpProxy == null) {
            this.mNlpProxy = new OplusNlpProxy(this.mContext);
        }
    }

    public AbstractLocationProvider getLocationProvider() {
        if (this.mNlpProxy != null) {
            return this.mNlpProxy.getLocationProvider();
        }
        return null;
    }

    public String getNlpId() {
        if (this.mNlpProxy != null) {
            return this.mNlpProxy.getNlpId();
        }
        return null;
    }

    public boolean isUsingRegionNlp() {
        return "nlp".equals(OplusSystemProperties.get("persist.sys.oplus.gps.nlp_name", OplusMultiNlpHelper.SERVICE_NAME_NONE));
    }

    public boolean isGeocodeAvailable() {
        if (this.mGeocoderProxy != null) {
            return OplusGeocoderProxy.isGeocodeAvailable();
        }
        return false;
    }

    public int getGeoTaskCancelTimeMs() {
        OplusLbsRomUpdateUtil oplusLbsRomUpdateUtil;
        String str;
        if (this.mOplusLbsRomUpdateUtil == null) {
            return DEFAULT_GEOCODER_TASK_CANCEL_TIME;
        }
        if (OplusLbsCommonConstant.isCnRom()) {
            oplusLbsRomUpdateUtil = this.mOplusLbsRomUpdateUtil;
            str = KEY_GEOCODER_TASK_CANCEL_TIME_CN;
        } else {
            oplusLbsRomUpdateUtil = this.mOplusLbsRomUpdateUtil;
            str = KEY_GEOCODER_TASK_CANCEL_TIME_GMS;
        }
        return oplusLbsRomUpdateUtil.getInt(str);
    }

    public void reverseGeocode(ReverseGeocodeRequest request, IGeocodeCallback callback) {
        if (this.mGeocoderProxy != null) {
            this.mGeocoderProxy.reverseGeocode(request, callback);
        }
    }

    public void forwardGeocode(ForwardGeocodeRequest request, IGeocodeCallback callback) {
        if (this.mGeocoderProxy != null) {
            this.mGeocoderProxy.forwardGeocode(request, callback);
        }
    }

    public boolean checkRequestBlocked(String provider, String packagename) {
        return isPackageBlocked(packagename, provider);
    }

    public boolean isPackageBlocked(String packageName, String provider) {
        if (this.mOplusLocationBlacklistUtil != null) {
            return this.mOplusLocationBlacklistUtil.isPackageBlocked(packageName, provider);
        }
        return false;
    }

    public void recordPackagesLocationStatus(String packageName, int packageUid, int packagePid, String locationProvider) throws Throwable {
        if (this.mOplusLocationBlacklistUtil != null) {
            this.mOplusLocationBlacklistUtil.recordPackagesLocationStatus(packageName, packageUid, packagePid, locationProvider);
        }
    }

    public void removePackagesLocationStatus(String packageName, int packageUid, int packagePid, String locationProvider) {
        if (this.mOplusLocationBlacklistUtil != null) {
            this.mOplusLocationBlacklistUtil.removePackagesLocationStatus(packageName, packageUid, packagePid, locationProvider);
        }
    }

    public void receiveSvInfo(GnssStatus svStatus) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordSvInfo(svStatus);
        }
        if (this.mOplusLocationBlacklistUtil != null) {
            this.mOplusLocationBlacklistUtil.receiveSvInfo(svStatus);
        }
    }

    public boolean needChangeNotifyStatus(String packageName, boolean isBlocked) {
        if (this.mOplusLocationBlacklistUtil != null) {
            return this.mOplusLocationBlacklistUtil.needChangeNotifyStatus(packageName, isBlocked);
        }
        return false;
    }

    public boolean isAllowedPassLocationAccess(String packageName) {
        if (this.mGnssWhiteListProxy != null) {
            return this.mGnssWhiteListProxy.isAllowedPassLocationAccess(packageName);
        }
        return false;
    }

    public boolean isAllowedChangeChipData(String provider, String command) {
        if (this.mGnssWhiteListProxy != null) {
            return this.mGnssWhiteListProxy.isAllowedChangeChipData(provider, command);
        }
        return false;
    }

    public boolean checkInHighFreqLocationBlacklist(String pkgName, String provider) {
        if (this.mOplusHighFreqLocationBlacklist != null) {
            return this.mOplusHighFreqLocationBlacklist.isInHighFrequencyLocationBlacklist(pkgName, provider);
        }
        return false;
    }

    public void refreshRequestTimer() {
        if (this.mOplusGnssDiagnosticTool != null) {
            this.mOplusGnssDiagnosticTool.refreshRequestTimer();
        }
    }

    public void storeSatellitesInfo(int svCount, int usedSvcount, int cn0) {
        if (this.mOplusGnssDiagnosticTool != null) {
            this.mOplusGnssDiagnosticTool.storeSatellitesInfo(svCount, usedSvcount, cn0);
        }
    }

    public void storeAppSvInfo(int maxCn0, float speed) {
        if (this.mOplusGnssDiagnosticTool != null) {
            this.mOplusGnssDiagnosticTool.storeAppSvInfo(maxCn0, speed);
        }
    }

    public void incomingNewGpsUsingApp(String providerName, String apkName) {
        if (this.mOplusGnssDiagnosticTool != null) {
            this.mOplusGnssDiagnosticTool.incomingNewGpsUsingApp(providerName, apkName);
        }
        if (this.mOplusNavigationStatusController != null && "gps".equals(providerName)) {
            this.mOplusNavigationStatusController.onGpsPackageListChanged(apkName, 0);
        }
    }

    public void removingGpsUsingApp(String providerName, String apkName) {
        if (this.mOplusGnssDiagnosticTool != null) {
            this.mOplusGnssDiagnosticTool.removingGpsUsingApp(providerName, apkName);
        }
        if (this.mOplusNavigationStatusController != null && "gps".equals(providerName)) {
            this.mOplusNavigationStatusController.onGpsPackageListChanged(apkName, 1);
        }
    }

    public boolean isForceGnssDisabled() {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.isForceGnssDisabled();
        }
        return false;
    }

    public boolean getOplusLocationMode(int userId) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.getOplusLocationMode(userId);
        }
        return true;
    }

    public boolean isForceAgpsEnabled(boolean agpsEnabledFromSettings) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.isForceAgpsEnabled(agpsEnabledFromSettings);
        }
        return agpsEnabledFromSettings;
    }

    public int customizePositionMode(int originalPositionMode) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.customizePositionMode(originalPositionMode);
        }
        return originalPositionMode;
    }

    public void getAppInfoForTr(String methodName, String providerName, int pid, String packageName) throws Throwable {
        if (this.mOplusLbsCustomize != null) {
            this.mOplusLbsCustomize.getAppInfoForTr(methodName, providerName, pid, packageName);
        }
    }

    public void setDebug(boolean isDebug) {
        LBSLog.setDebug(isDebug);
    }

    public boolean registerLbsConfigListener(IOplusConfigListener listener) {
        return this.mOplusLbsConfigNotifyer.registerLbsConfigListener(listener);
    }

    public boolean logoutLbsConfigListener(IOplusConfigListener listener) {
        return this.mOplusLbsConfigNotifyer.logoutLbsConfigListener(listener);
    }

    public void initGnssPowerSaver(GnssLocationProvider provider) {
        if (this.mGnssLocationProvider == null) {
            this.mGnssLocationProvider = provider;
        }
        this.mOplusNavigationStatusController = NavigationStatusController.getInstance(this.mContext, provider);
        this.mSnapshot.setNavigationStatusController(this.mOplusNavigationStatusController);
        this.mOplusNavigationStatusController.init();
        if (this.mGnssMeasurementsProvider != null) {
            this.mOplusNavigationStatusController.setGnssMeasurementsProvider(this.mGnssMeasurementsProvider);
        }
        this.mOplusGnssPowerCorrector = OplusGnssPowerCorrector.getInstance(this.mContext, provider);
    }

    public void startController() throws Throwable {
        if (this.mOplusNavigationStatusController != null) {
            this.mOplusNavigationStatusController.startController();
        }
    }

    public void stopController() throws Throwable {
        if (this.mOplusNavigationStatusController != null) {
            this.mOplusNavigationStatusController.stopController();
        }
    }

    public void storeWorkSource(WorkSource source) {
        if (this.mOplusGnssPowerCorrector != null) {
            this.mOplusGnssPowerCorrector.storeWorkSource(source, false);
        }
    }

    public boolean getInPowerSaveMode() {
        return isEngineOffByStrategy();
    }

    public void forceNotifyEmptyWorksource() {
        if (this.mOplusGnssPowerCorrector != null) {
            this.mOplusGnssPowerCorrector.forceNotifyEmptyWorksource();
        }
    }

    public boolean isEngineOffByStrategy() {
        if (this.mOplusGnssPowerCorrector != null) {
            return this.mOplusGnssPowerCorrector.isEngineOffByStrategy();
        }
        return false;
    }

    public void setUpGnssPowerSaver() {
        if (this.mOplusNavigationStatusController != null) {
            this.mOplusNavigationStatusController.setUp();
        }
    }

    public void collectSvStatus(GnssStatus svStatus) {
        if (this.mOplusNavigationStatusController != null) {
            this.mOplusNavigationStatusController.collectSvStatus(svStatus);
        }
    }

    public boolean resistStartGps() {
        if (this.mOplusNavigationStatusController != null) {
            return this.mOplusNavigationStatusController.resistStartGps();
        }
        return false;
    }

    public int getNavigateMode() {
        if (this.mOplusNavigationStatusController != null) {
            return this.mOplusNavigationStatusController.getNavigateMode();
        }
        return -1;
    }

    private boolean checkDumpCommand(String[] args, PrintWriter pw) {
        if (this.mOplusNavigationStatusController != null) {
            return this.mOplusNavigationStatusController.checkDumpCommand(args, pw);
        }
        return false;
    }

    @Deprecated
    public boolean checkDumpCommand(String[] args) {
        if (this.mOplusNavigationStatusController != null) {
            return this.mOplusNavigationStatusController.checkDumpCommand(args);
        }
        return false;
    }

    @Deprecated
    public boolean powerSaveEnabled() {
        if (this.mOplusNavigationStatusController != null) {
            return this.mOplusNavigationStatusController.powerSaveEnabled();
        }
        return false;
    }

    public List<String> getInUsePackagesList() {
        if (this.mOplusNavigationStatusController != null) {
            return this.mOplusNavigationStatusController.getInUsePackagesList();
        }
        return null;
    }

    public void onGnssMeasurementsProviderInit(GnssMeasurementsProvider provider) {
        this.mGnssMeasurementsProvider = provider;
        if (this.mOplusLbsTestAdapter != null) {
            this.mOplusLbsTestAdapter.setGnssMeasurementsProvider(provider);
        }
        if (this.mOplusNavigationStatusController != null) {
            this.mOplusNavigationStatusController.setGnssMeasurementsProvider(provider);
        }
    }

    public GnssMeasurementsProvider getGnssMeasurementsProvider() {
        return this.mGnssMeasurementsProvider;
    }

    public int getFlpResId(String resName) {
        return this.mOplusFlpHelper.getFlpResId(resName);
    }

    public void initFlpCoordinator(Context context) {
        this.mOplusFlpCoordinator = OplusFlpCoordinator.getInstall(context);
    }

    public void setGnssLocationProvider(GnssLocationProvider provider) {
        if (this.mOplusLbsTestAdapter != null) {
            this.mOplusLbsTestAdapter.setGnssLocationProvider(provider);
        }
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.setGnssLocationProvider(provider);
        }
        if (this.mOplusFused3Controller != null) {
            this.mOplusFused3Controller.setGnssLocationProvider(provider);
        }
        if (this.mOplusPnetLocationController != null) {
            this.mOplusPnetLocationController.setGnssLocationProvider(provider);
        }
        if (this.mOplusGarageExitLocationController != null) {
            this.mOplusGarageExitLocationController.setGnssLocationProvider(provider);
        }
    }

    public void startFlpAiding() {
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.enterUltraMode();
        }
    }

    public void stopFlpAiding() {
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.quitUltraMode();
        }
    }

    @Deprecated
    public void updateGpsWorksourceStatus(WorkSource worksource) {
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.updateGpsWorksourceStatus(worksource);
        }
    }

    public boolean isFlpReqLimited(String pkgName) {
        if (this.mOplusFlpCoordinator != null) {
            return this.mOplusFlpCoordinator.isFlpReqLimited(pkgName);
        }
        return false;
    }

    public boolean isPdrActive() {
        if (this.mOplusFlpCoordinator != null) {
            return this.mOplusFlpCoordinator.isPdrActive();
        }
        return false;
    }

    public boolean shouldReportFlpAsGps(Location location, String pkgName) {
        boolean flpCoordinator = true;
        boolean flpFused3Controller = true;
        if (this.mOplusFlpCoordinator != null) {
            flpCoordinator = this.mOplusFlpCoordinator.shouldReportFlpAsGps(location, pkgName);
        }
        if (this.mOplusFused3Controller != null) {
            flpFused3Controller = this.mOplusFused3Controller.shouldReportFlpAsGps(location, pkgName);
        }
        return flpCoordinator && flpFused3Controller;
    }

    public void setOlsPackageName(Intent intent) {
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.setOlsPackageName(intent);
        }
    }

    public boolean shouldReportPnetLocationAsGps(Location location, String pkgName) {
        if (this.mOplusPnetLocationController != null) {
            return this.mOplusPnetLocationController.shouldReportPnetLocationAsGps(location, pkgName);
        }
        return false;
    }

    public void onStationaryThrottlingLocationProviderInit(String name, StationaryThrottlingLocationProvider provider) {
        if (this.mOplusLbsTestAdapter != null) {
            this.mOplusLbsTestAdapter.setStationaryThrottlingLocationProvider(name, provider);
        }
    }

    public void onAddMockProvider(String packageName, String providerName) {
        if (this.mOplusLbsRepairer != null) {
            this.mOplusLbsRepairer.onAddMockProvider(packageName, providerName);
        }
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.onAddMockProvider(packageName, providerName);
        }
    }

    public void onRemoveMockProvider(String packageName, String providerName) {
        if (this.mOplusLbsRepairer != null) {
            this.mOplusLbsRepairer.onRemoveMockProvider(packageName, providerName);
        }
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.onRemoveMockProvider(packageName, providerName);
        }
    }

    public int getRec() {
        if (this.mOplusLbsRepairer != null) {
            return this.mOplusLbsRepairer.getRec();
        }
        return -1;
    }

    public boolean ignoreDisabled(String name, boolean allowed) {
        if (this.mOplusLbsRepairer != null) {
            return this.mOplusLbsRepairer.ignoreDisabled(name, allowed);
        }
        return false;
    }

    public void updateSettings(String name, int uid) {
        if (this.mOplusLbsRepairer != null) {
            this.mOplusLbsRepairer.updateSettings(name, uid);
        }
    }

    public void getProviderStatus(String providerName, boolean isProviderActivated, boolean isProviderActuallyWork, boolean isForceShow, int currentUserId, String packageName) {
        this.mOplusLbsRepairer.getProviderStatus(providerName, isProviderActivated, isProviderActuallyWork, isForceShow, currentUserId, packageName);
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordProviderStatus(providerName, isProviderActivated, isProviderActuallyWork, packageName);
        }
    }

    public boolean isForegroundActivity(int uidImportance) {
        if (this.mOplusLbsRepairer != null) {
            return this.mOplusLbsRepairer.isForegroundActivity(uidImportance);
        }
        return false;
    }

    public boolean isForegroundActivity(String packageName) {
        if (this.mOplusLbsRepairer != null) {
            return this.mOplusLbsRepairer.isForegroundActivity(packageName);
        }
        return false;
    }

    public void updateBindStatus(boolean hasBind) {
        if (this.mOplusLbsRepairer != null) {
            this.mOplusLbsRepairer.updateBindStatus(hasBind);
        }
    }

    public boolean checkOpNoThrow(AppOpsManager appOps, int appOp, CallerIdentity callerIdentity, long identity) {
        if (this.mOplusLbsRepairer != null) {
            return this.mOplusLbsRepairer.checkOpNoThrow(appOps, appOp, callerIdentity, identity);
        }
        return false;
    }

    public boolean handleCommand(String provider, String command, Bundle extras) {
        if (this.mOplusLocationStatistics != null) {
            return this.mOplusLocationStatistics.handleCommand(provider, command, extras);
        }
        return false;
    }

    public void stopRequesting(CallerIdentity identity, String providerName, LocationRequest req, String hash) {
        CallerIdentity identity2;
        String providerName2;
        String hash2;
        if (this.mOplusPnetLocationController != null) {
            this.mOplusPnetLocationController.shouldSendStopPnetCommand(identity, providerName, hash);
        }
        if (this.mOplusLocationStatistics == null) {
            identity2 = identity;
            providerName2 = providerName;
            hash2 = hash;
        } else {
            identity2 = identity;
            providerName2 = providerName;
            hash2 = hash;
            this.mOplusLocationStatistics.stopRequesting(identity2, providerName2, req.getIntervalMillis(), hash2);
        }
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.stopRequesting(identity2.getPackageName(), providerName2);
        }
        if (this.mOplusHighFreqLocationBlacklist != null) {
            this.mOplusHighFreqLocationBlacklist.updateRequestRecordOnRemove(identity2.getPackageName(), hash2);
        }
        if (this.mSnapshot != null) {
            this.mSnapshot.finishSnapshotWithLocationRequest(req, providerName2, identity2.getPackageName());
        }
        removingGpsUsingApp(providerName2, identity2.getPackageName());
        if (providerName2.equals("gps")) {
            removePackagesLocationStatus(identity2.getPackageName(), identity2.getUid(), identity2.getPid(), providerName2);
        }
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.removeHiddenAppOps(providerName2, hash2);
        }
    }

    public void startRequesting(CallerIdentity identity, String providerName, LocationRequest req, boolean isForeground, String hash) throws Throwable {
        CallerIdentity identity2;
        String providerName2;
        String hash2;
        if (this.mOplusPnetLocationController != null) {
            this.mOplusPnetLocationController.shouldSendStartPnetCommand(identity, providerName, hash);
        }
        if (this.mOplusLocationStatistics == null) {
            identity2 = identity;
            providerName2 = providerName;
            hash2 = hash;
        } else {
            identity2 = identity;
            providerName2 = providerName;
            hash2 = hash;
            this.mOplusLocationStatistics.startRequesting(identity2, providerName2, req.getIntervalMillis(), isForeground, hash2);
        }
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.startRequesting(identity2.getPackageName(), providerName2);
        }
        if (this.mOplusHighFreqLocationBlacklist != null) {
            this.mOplusHighFreqLocationBlacklist.updateRequestRecordOnRequest(identity2.getPackageName(), hash2);
        }
        if (this.mSnapshot != null) {
            this.mSnapshot.createSnapshotWithLocationRequest(req, providerName2, identity2.getPackageName());
        }
        incomingNewGpsUsingApp(providerName2, identity2.getPackageName());
        if (providerName2.equals("gps")) {
            recordPackagesLocationStatus(identity2.getPackageName(), identity2.getUid(), identity2.getPid(), providerName2);
        }
        if (this.mOplusFlpCoordinator != null) {
            this.mOplusFlpCoordinator.addHiddenAppOps(providerName2, req.isHiddenFromAppOps(), hash2, req.getWorkSource());
        }
    }

    public void deliverLocation(CallerIdentity identity, String providerName, String hash) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.deliverLocation(identity, providerName, hash);
        }
    }

    public void recordGnssNavigatingStarted(long interval) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGnssNavigatingStarted(interval);
        }
    }

    public void recordGnssNavigatingStopped() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGnssNavigatingStopped();
        }
    }

    public void recordGnssPowerSaveStarted(int strategyCode) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGnssPowerSaveStarted(strategyCode);
        }
    }

    public void recordGnssPowerSaveStopped(int strategyCode) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGnssPowerSaveStarted(strategyCode);
        }
    }

    public void recordHeldWakelock(String name) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordHeldWakelock(name);
        }
    }

    public void recordReleaseWakelock(String name) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordReleaseWakelock(name);
        }
    }

    public void recordNlpNavigatingStarted() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordNlpNavigatingStarted();
        }
    }

    public void recordNlpNavigatingStopped() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordNlpNavigatingStopped();
        }
    }

    public void recordNlpError(int code) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordNlpError(code);
        }
    }

    public void recordNlpScanWifiTotal(String packageName) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordNlpScanWifiTotal(packageName);
        }
    }

    public void recordNlpScanWifiSucceed(String packageName) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordNlpScanWifiSucceed(packageName);
        }
    }

    public void recordGeocoderRequestStarted() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGeocoderRequestStarted();
        }
    }

    public void recordGeocoderRequestStopped(long costTime) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGeocoderRequestStopped(costTime);
        }
    }

    public void recordGeocoderError(int code) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordGeocoderError(code);
        }
    }

    public void recordRgcRequestStarted() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordRgcRequestStarted();
        }
    }

    public void recordRgcRequestStopped(long costTime) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordRgcRequestStopped(costTime);
        }
    }

    public void recordRgcError(int code) {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.recordRgcError(code);
        }
    }

    public void forceStopStatistics() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.forceStopStatistics();
        }
    }

    public void startPowerStatistics() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.startPowerStatistics();
        }
    }

    public void stopPowerStatistics() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.startPowerStatistics();
        }
    }

    public void resetPowerStatistics() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.resetPowerStatistics();
        }
    }

    public String collectPowerStatistics() {
        if (this.mOplusLocationStatistics != null) {
            this.mOplusLocationStatistics.collectPowerStatistics();
            return LanConstants.DEFAULT_IP;
        }
        return LanConstants.DEFAULT_IP;
    }

    public boolean isMetalCaseDetectEnabled() {
        int metalCaseConfig = this.mOplusLbsRomUpdateUtil.getInt("config_gpsShowTipsConfigMtk");
        return (metalCaseConfig == -1 || (metalCaseConfig & 1) == 0) ? false : true;
    }

    public void startRecordMonitor() {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.startRecordMonitor();
        }
    }

    public void stopRecordMonitor() {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.stopRecordMonitor();
        }
    }

    public void recordLocationBlocked(String packageName) {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.recordLocationBlocked(packageName);
        }
    }

    public void setGpsBackgroundFlag(String packageName, boolean flag) {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.setGpsBackgroundFlag(packageName, flag);
        }
    }

    public void updateForeground(String packageName, String providerName, boolean isForeground) {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.updateForeground(packageName, providerName, isForeground);
        }
    }

    public void onFirstFix(int timeToFirstFix) {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.onFirstFix(timeToFirstFix);
        }
        if (this.mOplusProviderInternalDeliverProxy != null) {
            this.mOplusProviderInternalDeliverProxy.onFirstFix(timeToFirstFix);
        }
    }

    public void onStatusChanged(boolean isNavigating) {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.onStatusChanged(isNavigating);
        }
    }

    public void onSvStatusChanged(GnssStatus status) {
        if (this.mOplusFused3Controller != null) {
            this.mOplusFused3Controller.inputSvStatusChanged(status);
        }
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.onSvStatusChanged(status);
        }
        if (this.mOplusProviderInternalDeliverProxy != null) {
            this.mOplusProviderInternalDeliverProxy.onSatelliteStatusChanged(status);
        }
    }

    public void checkLocationHasChanged(String provider, String packageName, int hashCode) {
        if (this.mOplusLocationStatusMonitor != null) {
            this.mOplusLocationStatusMonitor.checkLocationHasChanged(provider, packageName, hashCode);
        }
    }

    public int generateStatusChangedExtra(String provider, String packageName, Bundle extras, int status) {
        if (this.mOplusLocationStatusMonitor != null) {
            return this.mOplusLocationStatusMonitor.generateStatusChangedExtra(provider, packageName, extras, status);
        }
        return 0;
    }

    public Location getLastLocation(Location originLocation, LastLocationRequest request, int permissionLevel) {
        if (this.mOplusLocationCache != null) {
            return this.mOplusLocationCache.getLocation(originLocation, permissionLevel);
        }
        return null;
    }

    public void triggerLogCollect(int type) {
        if (this.mOplusLbsLogController != null) {
            this.mOplusLbsLogController.triggerLogCollect(type);
        }
    }

    public void stopLogCollect(int type) {
        if (this.mOplusLbsLogController != null) {
            this.mOplusLbsLogController.stopLogCollect(type);
        }
    }

    public void collectLbsData(int type, Bundle extra) {
        if (this.mOplusLbsLogController != null) {
            this.mOplusLbsLogController.collectLbsData(type, extra);
        }
    }

    public void listenEmergencyCallStatus() {
        if (this.mOplusEmergencyCall != null) {
            this.mOplusEmergencyCall.listenEmergencyCallStatus();
        }
    }

    public GnssStatus onGnssSvStrategy(GnssStatus status) {
        GnssStatus simulatedStatus = OplusSatelliteSimulator.updateStatus(status);
        if (this.mOplusQcomBdsOnlyController != null) {
            this.mOplusQcomBdsOnlyController.checkBdsOnlyActive(simulatedStatus);
        }
        if (this.mOplusGnssSvStrategy == null) {
            this.mOplusGnssSvStrategy = OplusGnssSvStrategy.getInstance(this.mContext);
        }
        return this.mOplusGnssSvStrategy.strategySupply(simulatedStatus);
    }

    public GnssPowerStats reportGnssPowerStatsExt(GnssPowerStats powerStats) {
        if (this.mOplusGnssPowerModel == null) {
            this.mOplusGnssPowerModel = OplusGnssPowerModel.getInstance(this.mContext);
        }
        if (this.mOplusGnssPowerModel != null) {
            return this.mOplusGnssPowerModel.reportGnssPowerStatsExt(powerStats);
        }
        return powerStats;
    }

    public void injectMeasurementCorrectionsStatsExt(boolean success) {
        OplusCorrectionsStatistics.injectMeasurementCorrections(success);
    }

    public void dump(PrintWriter pw, String[] args) {
        if (this.mOplusLbsRomUpdateUtil != null) {
            this.mOplusLbsRomUpdateUtil.dump(args[1], pw);
        }
    }

    private void dumpToOls(PrintWriter pw, String[] args) {
        Bundle extras = new Bundle();
        OplusLocationServiceProxy.getInstance(this.mContext).onDump(args, extras);
        if (extras.getString(KEY_OLS_FEATURE) != null) {
            pw.println(extras.getString(KEY_OLS_FEATURE));
        }
    }

    public boolean dealDumpCommand(PrintWriter pw, String[] args) {
        boolean result = false;
        if (args != null && args.length > 0) {
            if (args[0].equals("--rus")) {
                if (this.mOplusLbsRomUpdateUtil != null) {
                    this.mOplusLbsRomUpdateUtil.dump(args[1], pw);
                } else {
                    LBSLog.d(true, TAG, "Lbs RUS doesn't run!", new Object[0]);
                }
                result = true;
            } else if (args[0].equals("--disableSimilarNetworkLocation") || args[0].equals("--enableSimilarNetworkLocation") || args[0].equals("--getSimilarNetworkLocation")) {
                result = true;
            } else {
                result = checkDumpCommand(args, pw);
            }
            dumpToOls(pw, args);
        }
        return result;
    }

    public void dumpOplusContent(PrintWriter pw) {
        DumpHelper dh = new DumpHelper();
        dh.dumpGeocoderRecord(pw);
        dh.dumpOnOffHistory(pw);
        dh.dumpOls(pw);
        dh.dumpQxwzUuid(pw);
        dh.dumpDeepSleepNetworkState(pw);
        dh.dumpOplusLbsSleepManager(pw);
        dh.dumpPowerSceneRecognition(pw);
        dh.dumpPlatform(pw);
        dh.dumpPowerSaverDetail(pw);
    }

    private class DumpHelper {
        private static final String COMMAND_GET_GEOFENCE_EVENT = "getGeofenceEvent";
        private static final String COMMAND_GET_NLP_ID = "getNlpId";
        private static final String COMMAND_GET_OLS_ACTIVATE = "getOlsActivate";
        private static final String KEY_GEOFENCE_EVENT = "geofenceEvent";
        private static final String KEY_NLP_ID = "nlpId";
        private static final String KEY_OLS_ACTIVATE = "DumpOlsActivate";

        private DumpHelper() {
        }

        public void dumpGeocoderRecord(PrintWriter pw) {
            if (OplusLBSMainClass.this.mOplusGeocoderRecord != null) {
                OplusLBSMainClass.this.mOplusGeocoderRecord.dump(pw);
            }
        }

        public void dumpOnOffHistory(PrintWriter pw) {
            if (OplusLBSMainClass.this.mOplusLbsRepairer != null) {
                OplusLBSMainClass.this.mOplusLbsRepairer.dump(pw);
            }
        }

        public void dumpOls(PrintWriter pw) {
            String[] args = {COMMAND_GET_NLP_ID, COMMAND_GET_GEOFENCE_EVENT, COMMAND_GET_OLS_ACTIVATE};
            Bundle extras = new Bundle();
            OplusLocationServiceProxy.getInstance(OplusLBSMainClass.this.mContext).onDump(args, extras);
            pw.println("Oplus NLP ICU:" + extras.getString(KEY_NLP_ID));
            pw.println("Oplus Geofence Events:" + extras.getString(KEY_GEOFENCE_EVENT));
            pw.println("Oplus ActivationLock Location:" + extras.getString(KEY_OLS_ACTIVATE));
        }

        public void dumpQxwzUuid(PrintWriter pw) {
            if (OplusLBSMainClass.this.mOplusPreciseLocationController != null) {
                OplusLBSMainClass.this.mOplusPreciseLocationController.dump(pw);
            }
        }

        public void dumpDeepSleepNetworkState(PrintWriter pw) {
            LocationFreezeProc.getInstance(OplusLBSMainClass.this.mContext).dump(pw);
        }

        public void dumpOplusLbsSleepManager(PrintWriter pw) {
            if (OplusLBSMainClass.this.mOplusLbsSleepManager != null) {
                OplusLBSMainClass.this.mOplusLbsSleepManager.dump(pw);
            }
        }

        public void dumpPowerSceneRecognition(PrintWriter pw) {
            if (OplusLBSMainClass.this.mOplusGnssPowerSceneRecognition != null) {
                OplusLBSMainClass.this.mOplusGnssPowerSceneRecognition.dump(pw);
            }
        }

        public void dumpPlatform(PrintWriter pw) {
            PlatformDumpManager.getInstance(OplusLBSMainClass.this.mContext).dump(pw);
        }

        public void dumpPowerSaverDetail(PrintWriter pw) {
            PowerSaverDump.getInstance().dump(pw);
        }
    }

    public HandlerThread getThread(int priority) {
        return OplusLocationThreadRenter.getThread(priority);
    }

    public Handler getHandler(int priority) {
        return OplusLocationThreadRenter.getHandler(priority);
    }

    public Executor getExecutor(int priority) {
        return OplusLocationThreadRenter.getExecutor(priority);
    }

    public boolean recordTaskMark(String key) {
        return this.mOplusLocationStatistics.recordTaskMark(key);
    }

    public boolean releaseTaskMark(String key) {
        return this.mOplusLocationStatistics.releaseTaskMark(key);
    }

    public boolean recordTaskTime(String key, Long time) {
        return this.mOplusLocationStatistics.recordTaskTime(key, time);
    }

    public List<String> getNfwProxyApps(List<String> proxyApps) {
        List<String> oplusNfwLocationApps = new ArrayList<>();
        if (proxyApps != null && !proxyApps.isEmpty()) {
            for (String proxyApp : proxyApps) {
                oplusNfwLocationApps.add(proxyApp);
            }
        }
        if (!oplusNfwLocationApps.contains(OPLUS_NFW_PROXY_APP)) {
            oplusNfwLocationApps.add(OPLUS_NFW_PROXY_APP);
        }
        OplusSystemProperties.set("persist.sys.oplus.nfw.enable", OplusLbsCommonConstant.TYPE_LOCATION_PDR);
        return oplusNfwLocationApps;
    }

    public Location addCoarseLocationExtra(Location location) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.lambda$addCoarseLocationExtra$0(location);
        }
        return location;
    }

    public LocationResult addCoarseLocationExtra(LocationResult locationResult) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.addCoarseLocationExtra(locationResult);
        }
        return locationResult;
    }

    public boolean isGpsEnableForSpecialApp(String provider, int userId, String calledPackage) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.isGpsEnableForSpecialApp(provider, userId, calledPackage);
        }
        return false;
    }

    public boolean isPreciseLocationSupported() {
        return OplusPreciseLocationController.isPreciseLocationEnable();
    }

    public Location reduceAccuracyOfLocation(Location location) {
        return OplusPreciseLocationUtils.reduceAccuracyOfLocation(location);
    }

    public String reduceAccuracyOfNmeaSentences(String nmea) {
        return OplusPreciseLocationUtils.reduceAccuracyOfNmeaSentences(nmea);
    }

    public boolean isStealthSecurity() {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.isStealthSecurity();
        }
        return false;
    }

    public void onActiveDataSubscriptionIdChanged() {
        this.mGnssLocationProvider.getGnssLocationProviderWrapper().subscriptionOrCarrierConfigChanged();
    }

    public void onFreezeGnssStarted() {
        LocationFreezeProc.getInstance(this.mContext).onGnssStarted();
    }

    public void onFreezeGnssStopped() {
        LocationFreezeProc.getInstance(this.mContext).onGnssStopped();
    }

    public void reportQcomConnectStatus(int status) {
        this.mOplusLocationStatistics.reportQcomConnectStatus(status);
    }

    public String setAgpsServer(int type, String hostname, int port) {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.setAgpsServer(type, hostname, port);
        }
        return hostname;
    }

    public boolean isSatelliteCommunicationEnable() {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.isSatelliteCommunicationEnable();
        }
        return false;
    }

    public boolean isVirtualGpsVisibleOnlyForPad(int uid) {
        if (this.mOplusVirtualGpsVisibleBlackList != null) {
            return this.mOplusVirtualGpsVisibleBlackList.isVirtualGpsVisibleOnlyForPad(uid);
        }
        return true;
    }

    public boolean isStationaryThrottlingEnable() {
        if (this.mOplusLbsCustomize != null) {
            return this.mOplusLbsCustomize.isStationaryThrottlingEnable();
        }
        return true;
    }

    public void handleAppLackLocationPermission(int uid, int requiredPermissionLevel) {
        if (this.mOplusGnssDiagnosticTool != null) {
            this.mOplusGnssDiagnosticTool.handleAppLackLocationPermission(uid, requiredPermissionLevel);
        }
    }

    public void deliverLocationForSnapshot(CallerIdentity identity, String providerName, String hash, LocationResult locationResult) {
        if (this.mSnapshot != null) {
            this.mSnapshot.deliverLocation(identity, providerName, hash, locationResult);
        }
    }

    public void setEngMode(int mode) {
        if (this.mSnapshot != null) {
            this.mSnapshot.setEngMode(mode);
        }
    }

    public void setEngInterval(int interval) {
        if (this.mSnapshot != null) {
            this.mSnapshot.setEngInterval(interval);
        }
    }

    public void recordGeoFenceRequest(String pkgName) {
        if (this.mAospImplStat != null) {
            this.mAospImplStat.recordGeoFenceRequest(pkgName);
        }
    }

    public void recordLocationRequest(String pkgName) {
        if (this.mAospImplStat != null) {
            this.mAospImplStat.recordLocationRequest(pkgName);
        }
    }

    public void recordGnssStatusRequest(String pkgName) {
        if (this.mAospImplStat != null) {
            this.mAospImplStat.recordGnssStatusRequest(pkgName);
        }
    }

    public void recordNmeaRequest(String pkgName) {
        if (this.mAospImplStat != null) {
            this.mAospImplStat.recordNmeaRequest(pkgName);
        }
    }

    public void recordGnssMeasurementRequest(String pkgName) {
        if (this.mAospImplStat != null) {
            this.mAospImplStat.recordGnssMeasurementRequest(pkgName);
        }
    }

    public int gnssLocationVerify(Location location, long maxSpeed) {
        if (this.mOplusLocationManagerService != null && this.mOplusLocationManagerService.getLocationManagerService() != null) {
            return OplusGnssLocationVerifier.gnssLocationVerify(this.mOplusLocationManagerService.getLocationManagerService().getWrapper().getLocationProviderManager("gps"), location, maxSpeed);
        }
        LBSLog.e(true, TAG, "OLMS/LMS is null", new Object[0]);
        return OplusGnssLocationVerifier.RET_CODE_ERR_SYSTEM_ABNORMAL;
    }
}
