package com.android.server.location.fused;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.location.LocationResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import com.android.server.location.OplusLbsConfigNotifyer;
import com.android.server.location.common.OplusLbsCommonConstant;
import com.android.server.location.gnss.GnssLocationProvider;
import com.android.server.location.interfaces.IOplusConfigListener;
import com.android.server.location.log.LBSLog;
import com.android.server.location.nlp.OplusMultiNlpHelper;
import com.android.server.location.rus.OplusLbsRomUpdateUtil;
import com.android.server.location.statistics.OplusLocationStatistics;
import com.android.server.location.thread.OplusLocationThreadRenter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes.dex */
public class OplusFlpCoordinator {
    private static final String ANDROID_FUSED_PACKAGE = "com.android.location.fused";
    private static final String COMMAND_START_NAVIGATING = "startNavigating";
    private static final String COMMAND_STOP_NAVIGATING = "stopNavigating";
    private static final String COMMAND_UPDATE_STATUS = "updateStatus";
    private static final String CONTENT_FLP_WORKING = "FLP_Working";
    private static final int GLOBAL_FLP_MODE_OFF = 0;
    private static final String GMS_FUSED_PACKAGE = "com.google.android.gms";
    private static final String KEY_FLP_BLACKLIST = "config_flpBlacklist";
    private static final String KEY_FLP_BLACKLIST_ENABLE = "config_flpBlacklistEnable";
    private static final String KEY_FLP_COORDINATOR_ENABLED = "config_FlpCoordinatorEnabled";
    private static final String KEY_FLP_COORDINATOR_WHITELIST = "config_FlpCoordinatorWhitelist";
    private static final String KEY_GLOBAL_FLP_MODE = "config_globalFlpMode";
    private static final String OLS_LOCATION_PACKAGE = "com.oplus.location";
    private static final long PASSIVE_INTERVAL_MS = 1000;
    private static final long PDR_EFFECTIVE_INTERVAL_MS = 2000;
    private static final String PDR_KEY = "type";
    private static final String PDR_LOCATION_SIGNAL = "1";
    private static final String QUIT_PDR_SIGNAL = "0";
    private static final String RES_NAME_OPLUS = "config_nlp_packageName_oplus";
    private static final String TAG = "OplusFlpCoordinator";
    private static final int UNKNOWN_RES_ID = -1;
    private static OplusFlpCoordinator sInstall = null;
    private List<String> mBlacklist;
    private boolean mBlacklistEnabled;
    private Context mContext;
    private boolean mEnabled;
    private GnssLocationProvider mGnssLocationProvider;
    private LocationManager mLocationMananger;
    private OplusLbsRomUpdateUtil mRomUpdateUtil;
    private List<String> mWhitelist;
    private final Object mLock = new Object();
    private final Set<String> mHideSet = new HashSet();
    private final Set<String> mExposeSet = new HashSet();
    private long mLastPdrLocationTime = Long.MAX_VALUE;
    private volatile boolean mIsPdrActive = false;
    private volatile boolean mFlpWorking = false;
    private volatile boolean mHasValidGpsSource = false;
    private LocationListener mLocationListener = new LocationListener() { // from class: com.android.server.location.fused.OplusFlpCoordinator.1
        @Override // android.location.LocationListener
        public void onLocationChanged(Location location) {
            Bundle bundle = location.getExtras();
            LBSLog.d(OplusFlpCoordinator.TAG, "location from ols-flp: %s", location.toString());
            if (bundle != null) {
                String type = bundle.getString("type");
                if ("1".equals(type)) {
                    OplusFlpCoordinator.this.mIsPdrActive = true;
                    try {
                        OplusFlpCoordinator.this.mGnssLocationProvider.getGnssLocationProviderWrapper().reportLocation(LocationResult.wrap(new Location[]{location}).validate());
                    } catch (LocationResult.BadLocationException e) {
                        LBSLog.e(OplusFlpCoordinator.TAG, "BadLocationException %s", e.toString());
                    }
                    OplusFlpCoordinator.this.mLastPdrLocationTime = TimeUnit.NANOSECONDS.toMillis(location.getElapsedRealtimeNanos());
                    OplusLocationStatistics.getInstance().recordPdrLocationIncoming();
                }
            }
        }
    };
    private IOplusConfigListener mConfigListener = new IOplusConfigListener() { // from class: com.android.server.location.fused.OplusFlpCoordinator.2
        public void onRusChanged() {
            OplusFlpCoordinator.this.mEnabled = OplusFlpCoordinator.this.mRomUpdateUtil.getBoolean(OplusFlpCoordinator.KEY_FLP_COORDINATOR_ENABLED) && OplusLbsCommonConstant.isCnRom() && OplusFlpCoordinator.this.checkPackageExists(OplusFlpCoordinator.OLS_LOCATION_PACKAGE);
            OplusFlpCoordinator.this.mBlacklistEnabled = OplusFlpCoordinator.this.mRomUpdateUtil.getBoolean(OplusFlpCoordinator.KEY_FLP_BLACKLIST_ENABLE) && OplusLbsCommonConstant.isCnRom();
            if (OplusFlpCoordinator.this.mRomUpdateUtil.getInt(OplusFlpCoordinator.KEY_GLOBAL_FLP_MODE) != 0) {
                LBSLog.i(OplusFlpCoordinator.TAG, "GlobalFlp ON, Region OFF", new Object[0]);
                OplusFlpCoordinator.this.mEnabled = false;
                OplusFlpCoordinator.this.mBlacklistEnabled = false;
            }
            synchronized (OplusFlpCoordinator.this.mLock) {
                OplusFlpCoordinator.this.mWhitelist = OplusFlpCoordinator.this.mRomUpdateUtil.getStringArray(OplusFlpCoordinator.KEY_FLP_COORDINATOR_WHITELIST);
                OplusFlpCoordinator.this.mBlacklist = OplusFlpCoordinator.this.mRomUpdateUtil.getStringArray(OplusFlpCoordinator.KEY_FLP_BLACKLIST);
            }
            if (!OplusFlpCoordinator.this.mEnabled) {
                OplusFlpCoordinator.this.breakBondWithFlp();
            }
        }
    };
    private Handler mHandler = OplusLocationThreadRenter.getHandler(1);

    public OplusFlpCoordinator(Context context) {
        boolean z;
        boolean z2;
        this.mRomUpdateUtil = null;
        this.mEnabled = false;
        this.mBlacklistEnabled = false;
        this.mContext = context;
        this.mRomUpdateUtil = OplusLbsRomUpdateUtil.getInstall(context);
        if (!this.mRomUpdateUtil.getBoolean(KEY_FLP_COORDINATOR_ENABLED) || !OplusLbsCommonConstant.isCnRom() || !checkPackageExists(OLS_LOCATION_PACKAGE)) {
            z = false;
        } else {
            z = true;
        }
        this.mEnabled = z;
        if (!this.mRomUpdateUtil.getBoolean(KEY_FLP_BLACKLIST_ENABLE) || !OplusLbsCommonConstant.isCnRom()) {
            z2 = false;
        } else {
            z2 = true;
        }
        this.mBlacklistEnabled = z2;
        if (this.mRomUpdateUtil.getInt(KEY_GLOBAL_FLP_MODE) != 0) {
            LBSLog.i(TAG, "GlobalFlp ON, Region OFF", new Object[0]);
            this.mEnabled = false;
            this.mBlacklistEnabled = false;
        }
        LBSLog.d(TAG, "func enabled: %b blacklist enable: %b", Boolean.valueOf(this.mEnabled), Boolean.valueOf(this.mBlacklistEnabled));
        synchronized (this.mLock) {
            this.mWhitelist = this.mRomUpdateUtil.getStringArray(KEY_FLP_COORDINATOR_WHITELIST);
            if (this.mWhitelist != null) {
                LBSLog.d(TAG, "whiteList: %s", this.mWhitelist.toString());
            }
            this.mBlacklist = this.mRomUpdateUtil.getStringArray(KEY_FLP_BLACKLIST);
            if (this.mBlacklist != null) {
                LBSLog.d(TAG, "blackList: %s", this.mBlacklist.toString());
            }
        }
        this.mLocationMananger = (LocationManager) this.mContext.getSystemService("location");
        OplusLbsConfigNotifyer.getInstance(context).registerLbsConfigListener(this.mConfigListener);
        this.mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(CONTENT_FLP_WORKING), true, new ContentObserver(this.mHandler) { // from class: com.android.server.location.fused.OplusFlpCoordinator.3
            @Override // android.database.ContentObserver
            public void onChange(boolean selfChange) {
                OplusFlpCoordinator.this.mFlpWorking = OplusFlpCoordinator.this.getFlpWorking();
                if (!OplusFlpCoordinator.this.mFlpWorking) {
                    OplusFlpCoordinator.this.mIsPdrActive = false;
                }
                LBSLog.d(true, OplusFlpCoordinator.TAG, "mFlpWorking: %b", Boolean.valueOf(OplusFlpCoordinator.this.mFlpWorking));
            }
        }, -1);
    }

    public static OplusFlpCoordinator getInstall(Context context) {
        if (sInstall == null) {
            sInstall = new OplusFlpCoordinator(context);
        }
        return sInstall;
    }

    public void setGnssLocationProvider(GnssLocationProvider provider) {
        this.mGnssLocationProvider = provider;
    }

    public void enterUltraMode() {
        if (!this.mEnabled || !this.mHasValidGpsSource) {
            return;
        }
        LBSLog.d(TAG, "start ultra mode", new Object[0]);
        this.mFlpWorking = getFlpWorking();
        LocationRequest locationRequest = LocationRequest.create().setInterval(1000L).setQuality(100);
        if (this.mFlpWorking) {
            locationRequest.setProvider("passive").setFastestInterval(1000L);
        } else {
            this.mLocationMananger.sendExtraCommand(OplusMultiNlpHelper.SERVICE_NAME_FUSED, COMMAND_START_NAVIGATING, null);
            locationRequest.setWorkSource(new WorkSource(0, OLS_LOCATION_PACKAGE));
            locationRequest.setProvider(OplusMultiNlpHelper.SERVICE_NAME_FUSED).setFastestInterval(0L);
        }
        this.mLocationMananger.requestLocationUpdates(locationRequest, this.mLocationListener, this.mHandler.getLooper());
    }

    public void quitUltraMode() {
        if (!this.mEnabled) {
            return;
        }
        LBSLog.d(TAG, "stop ultra mode", new Object[0]);
        notifyFlpToQuit();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean getFlpWorking() {
        return Settings.System.getIntForUser(this.mContext.getContentResolver(), CONTENT_FLP_WORKING, 0, -2) > 0;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void breakBondWithFlp() {
        notifyFlpToQuit();
        Bundle extras = new Bundle();
        extras.putShort("Status", (short) 0);
        this.mLocationMananger.sendExtraCommand(OplusMultiNlpHelper.SERVICE_NAME_FUSED, COMMAND_UPDATE_STATUS, extras);
    }

    private void notifyFlpToQuit() {
        this.mLocationMananger.removeUpdates(this.mLocationListener);
        if (!this.mFlpWorking) {
            this.mLocationMananger.sendExtraCommand(OplusMultiNlpHelper.SERVICE_NAME_FUSED, COMMAND_STOP_NAVIGATING, null);
        }
        this.mIsPdrActive = false;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v1 */
    /* JADX WARN: Type inference failed for: r0v2 */
    /* JADX WARN: Type inference failed for: r0v3, types: [boolean, short] */
    /* JADX WARN: Type inference failed for: r0v4 */
    /* JADX WARN: Type inference failed for: r0v5 */
    /* JADX WARN: Type inference failed for: r0v6 */
    @Deprecated
    public void updateGpsWorksourceStatus(WorkSource workSource) {
        if (!this.mEnabled) {
            return;
        }
        ?? r0 = 0;
        r0 = 0;
        r0 = 0;
        r0 = 0;
        synchronized (this.mLock) {
            if (workSource != null) {
                if (workSource.size() > 0 && this.mWhitelist != null) {
                    for (int i = 0; i < workSource.size(); i++) {
                        if (this.mWhitelist.contains(workSource.getPackageName(i))) {
                            r0 = 1;
                            break;
                        }
                    }
                }
            }
        }
        if (this.mHasValidGpsSource != r0) {
            Bundle bundle = new Bundle();
            bundle.putShort("Status", r0);
            LBSLog.d(TAG, "change workSource status to: %b", Boolean.valueOf((boolean) r0));
            this.mLocationMananger.sendExtraCommand(OplusMultiNlpHelper.SERVICE_NAME_FUSED, COMMAND_UPDATE_STATUS, bundle);
            this.mHasValidGpsSource = r0;
        }
    }

    public void addHiddenAppOps(String providerName, boolean hide, String hash, WorkSource source) {
        if (!this.mEnabled || !"gps".equals(providerName) || source.size() == 0) {
            return;
        }
        synchronized (this.mLock) {
            if (this.mWhitelist == null) {
                return;
            }
            for (int i = 0; i < source.size(); i++) {
                if (this.mWhitelist.contains(source.getPackageName(i))) {
                    if (hide) {
                        this.mHideSet.add(hash);
                        break;
                    } else {
                        this.mExposeSet.add(hash);
                        break;
                    }
                }
            }
            LBSLog.d(TAG, "addHiddenAppOps|hide: %s |expose: %s", this.mHideSet.toString(), this.mExposeSet.toString());
            updateValidGpsSource();
        }
    }

    public void removeHiddenAppOps(String providerName, String hash) {
        if (!this.mEnabled || !"gps".equals(providerName)) {
            return;
        }
        synchronized (this.mLock) {
            this.mHideSet.remove(hash);
            this.mExposeSet.remove(hash);
            LBSLog.d(TAG, "removeHiddenAppOps|hide: %s |expose: %s", this.mHideSet.toString(), this.mExposeSet.toString());
        }
        updateValidGpsSource();
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r0v0 */
    /* JADX WARN: Type inference failed for: r0v1, types: [boolean, short] */
    /* JADX WARN: Type inference failed for: r0v2 */
    /* JADX WARN: Type inference failed for: r0v3 */
    private void updateValidGpsSource() {
        ?? r0 = 0;
        r0 = 0;
        synchronized (this.mLock) {
            if (this.mHideSet.size() == 0 && this.mExposeSet.size() > 0) {
                r0 = 1;
            }
        }
        if (this.mHasValidGpsSource != r0) {
            Bundle bundle = new Bundle();
            bundle.putShort("Status", r0);
            LBSLog.d(true, TAG, "change workSource status to: %b", Boolean.valueOf((boolean) r0));
            this.mLocationMananger.sendExtraCommand(OplusMultiNlpHelper.SERVICE_NAME_FUSED, COMMAND_UPDATE_STATUS, bundle);
            this.mHasValidGpsSource = r0;
        }
    }

    public boolean isPdrActive() {
        return this.mEnabled && this.mIsPdrActive;
    }

    public boolean shouldReportFlpAsGps(Location loc, String pkgName) {
        boolean inList;
        if (!this.mEnabled || !this.mIsPdrActive || !"gps".equals(loc.getProvider())) {
            return true;
        }
        Bundle bundle = loc.getExtras();
        synchronized (this.mLock) {
            inList = this.mWhitelist == null ? false : this.mWhitelist.contains(pkgName);
        }
        if (bundle != null) {
            String source = bundle.getString("type");
            if ("1".equals(source)) {
                LBSLog.d(TAG, "report OPLUS-PDR location to %s? %b", pkgName, Boolean.valueOf(inList));
                if (inList) {
                    OplusLocationStatistics.getInstance().recordReportPdrLocation(pkgName);
                }
                return inList || "android".equals(pkgName);
            }
        }
        long locationAgeMs = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos()) - this.mLastPdrLocationTime;
        if (locationAgeMs > PDR_EFFECTIVE_INTERVAL_MS) {
            LBSLog.d(TAG, "receive no pdr loc for long time, quit pdr!", new Object[0]);
            OplusLocationStatistics.getInstance().recordQuitPdrForTimeout();
            this.mIsPdrActive = false;
        }
        if (inList) {
            OplusLocationStatistics.getInstance().recordMissGpsLocation(pkgName);
        }
        LBSLog.d(TAG, "report normal GPS location to %s? %b", pkgName, Boolean.valueOf(!inList));
        return !inList;
    }

    public void setOlsPackageName(Intent intent) {
        if (intent != null && intent.getAction().contains("FusedLocationProvider")) {
            if (this.mEnabled && checkPackageExists(OLS_LOCATION_PACKAGE)) {
                LBSLog.d(TAG, "set FLP package to OLS", new Object[0]);
                intent.setPackage(OLS_LOCATION_PACKAGE);
            } else if (OplusLbsCommonConstant.isCnRom() && checkPackageExists(ANDROID_FUSED_PACKAGE)) {
                LBSLog.d(TAG, "set FLP package to android", new Object[0]);
                intent.setPackage(ANDROID_FUSED_PACKAGE);
            }
        }
    }

    public int getOlsFlpResId() {
        if (!this.mEnabled || !OplusLbsCommonConstant.isCnRom() || !checkPackageExists(OLS_LOCATION_PACKAGE)) {
            return -1;
        }
        LBSLog.d(TAG, "set FLP package to OLS", new Object[0]);
        return getOemResId(RES_NAME_OPLUS, "string", -1);
    }

    public boolean isFlpReqLimited(String pkgName) {
        if (!this.mBlacklistEnabled) {
            return false;
        }
        synchronized (this.mLock) {
            if (this.mBlacklist == null) {
                return false;
            }
            return this.mBlacklist.contains(pkgName);
        }
    }

    private int getOemResId(String resName, String type, int defValue) {
        try {
            int resId = this.mContext.getResources().getIdentifier(resName, type, OplusMultiNlpHelper.SERVICE_NAME_OPLUS);
            LBSLog.d(TAG, "resource of name:%s id is:%d", resName, Integer.valueOf(resId));
            return resId;
        } catch (RuntimeException ex) {
            LBSLog.e(true, TAG, ex.toString(), new Object[0]);
            return defValue;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean checkPackageExists(String packageName) {
        boolean exists = (packageName == null || packageName.isEmpty()) ? false : true;
        if (exists) {
            long identity = Binder.clearCallingIdentity();
            try {
                int userId = UserHandle.getCallingUserId();
                String info = this.mContext.getPackageManager().getPackageInfoAsUser(packageName, 0, userId).versionName;
                exists = (info == null || info.isEmpty()) ? false : true;
            } catch (PackageManager.NameNotFoundException e) {
                exists = false;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return exists;
    }
}
