package com.android.server.location.pnet;

import android.content.Context;
import android.location.Location;
import android.location.LocationResult;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import com.android.server.location.OplusLbsConfigNotifyer;
import com.android.server.location.common.OplusLbsCommonConstant;
import com.android.server.location.common.OplusLbsFeatureManager;
import com.android.server.location.gnss.GnssLocationProvider;
import com.android.server.location.interfaces.IOplusConfigListener;
import com.android.server.location.log.LBSLog;
import com.android.server.location.ols.OplusLocationServiceProxy;
import com.android.server.location.rus.OplusLbsRomUpdateUtil;
import com.android.server.location.thread.OplusLocationThreadRenter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes.dex */
public class OplusPnetLocationController {
    private static final int COMMAND_START_PNET_LOCATION = 1;
    private static final int COMMAND_STOP_PNET_LOCATION = 2;
    private static final String DIAGNOSIS_METHOD_ALL = "all";
    private static final String DIAGNOSIS_METHOD_FW_APP_WHITE_LIST = "fwAppWhiteList";
    private static final String DIAGNOSIS_METHOD_FW_FEATURE_ENABLE = "fwFeatureEnable";
    private static final String KEY_PNET_LOCATION_RUS_ENABLE = "config_pnetLocationEnable";
    private static final String KEY_PNET_LOCATION_WHITELIST = "config_PnetLocationWhitelist";
    private static final String KEY_PNET_RESULT = "pnet_result";
    private static final String OPLUS_LBS_CONFIG_UPDATE_ACTION = "com.oplus.lbsconfig.update.success";
    private static final String PNET_APP_REQUEST = "pnet_white_list_request";
    private static final long PNET_EFFECTIVE_INTERVAL_MS = 2000;
    public static final String PNET_LOCATION_ACTIVITY = "pnet_location_activity";
    private static final String TAG = "OplusPnetLocationController";
    private static OplusPnetLocationController sInstance = null;
    private Context mContext;
    private GnssLocationProvider mGnssLocationProvider;
    private boolean mPnetLocationRusEnable;
    private List<String> mPnetLocationWhiteList;
    private List<String> mPnetRequestList;
    private OplusLbsRomUpdateUtil mRomUpdateUtil;
    private final Object mLock = new Object();
    private long mLastPnetLocationTime = Long.MAX_VALUE;
    private volatile boolean mIsPnetLocationActive = false;
    private IOplusConfigListener mConfigListener = new IOplusConfigListener() { // from class: com.android.server.location.pnet.OplusPnetLocationController.1
        public void onRusChanged() {
            OplusPnetLocationController.this.mPnetLocationRusEnable = OplusPnetLocationController.this.mRomUpdateUtil.getBoolean(OplusPnetLocationController.KEY_PNET_LOCATION_RUS_ENABLE) && OplusLbsCommonConstant.isCnRom() && !OplusLbsFeatureManager.isLightOs(OplusPnetLocationController.this.mContext);
            synchronized (OplusPnetLocationController.this.mLock) {
                OplusPnetLocationController.this.mPnetLocationWhiteList = OplusPnetLocationController.this.mRomUpdateUtil.getStringArray(OplusPnetLocationController.KEY_PNET_LOCATION_WHITELIST);
            }
        }
    };
    private Handler mHandler = OplusLocationThreadRenter.getHandler(1);

    private OplusPnetLocationController(Context context) {
        this.mRomUpdateUtil = null;
        boolean z = false;
        this.mPnetLocationRusEnable = false;
        this.mContext = context;
        this.mRomUpdateUtil = OplusLbsRomUpdateUtil.getInstall(this.mContext);
        if (this.mRomUpdateUtil.getBoolean(KEY_PNET_LOCATION_RUS_ENABLE) && OplusLbsCommonConstant.isCnRom() && !OplusLbsFeatureManager.isLightOs(context)) {
            z = true;
        }
        this.mPnetLocationRusEnable = z;
        this.mPnetRequestList = new ArrayList();
        synchronized (this.mLock) {
            this.mPnetLocationWhiteList = this.mRomUpdateUtil.getStringArray(KEY_PNET_LOCATION_WHITELIST);
            if (this.mPnetLocationWhiteList != null) {
                LBSLog.d(TAG, "PnetLocationList: %s", this.mPnetLocationWhiteList);
            }
        }
        OplusLbsConfigNotifyer.getInstance(context).registerLbsConfigListener(this.mConfigListener);
    }

    public static synchronized OplusPnetLocationController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new OplusPnetLocationController(context);
        }
        return sInstance;
    }

    public void setGnssLocationProvider(GnssLocationProvider provider) {
        this.mGnssLocationProvider = provider;
    }

    public void inputPnetLocationChanged(Bundle bundle) {
        Location location = (Location) bundle.getParcelable(KEY_PNET_RESULT, Location.class);
        String type = location.getExtras().getString("type");
        if (OplusLbsCommonConstant.TYPE_LOCATION_PNET.equals(type)) {
            this.mIsPnetLocationActive = true;
            this.mLastPnetLocationTime = TimeUnit.NANOSECONDS.toMillis(location.getElapsedRealtimeNanos());
            onPnetLocationChange(location);
            return;
        }
        LBSLog.d(TAG, "inputPnetLocationChanged not input, mPnetLocationRusEnable = %b | mIsPnetLocationActive = %b", Boolean.valueOf(this.mPnetLocationRusEnable), Boolean.valueOf(this.mIsPnetLocationActive));
    }

    private void onPnetLocationChange(Location location) {
        if (this.mIsPnetLocationActive && this.mGnssLocationProvider != null) {
            try {
                this.mGnssLocationProvider.getGnssLocationProviderWrapper().reportLocation(LocationResult.wrap(new Location[]{location}).validate());
            } catch (LocationResult.BadLocationException e) {
                LBSLog.e(TAG, "BadLocationException %s", e.toString());
            }
        }
    }

    public boolean shouldReportPnetLocationAsGps(Location loc, String pkgName) {
        boolean inList;
        if (!this.mIsPnetLocationActive || !this.mPnetLocationRusEnable || !"gps".equals(loc.getProvider())) {
            return true;
        }
        Bundle bundle = loc.getExtras();
        synchronized (this.mLock) {
            inList = this.mPnetLocationWhiteList == null ? false : this.mPnetLocationWhiteList.contains(pkgName);
        }
        if (bundle != null) {
            String source = bundle.getString("type");
            if (OplusLbsCommonConstant.TYPE_LOCATION_PNET.equals(source)) {
                LBSLog.d(TAG, "shouldReportPnetLocationAsGps report Pnet location to %s? %b", pkgName, Boolean.valueOf(inList));
                return inList;
            }
        }
        long pnetLocationAgeMs = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos()) - this.mLastPnetLocationTime;
        if (pnetLocationAgeMs >= PNET_EFFECTIVE_INTERVAL_MS) {
            LBSLog.d(TAG, "shouldReportPnetLocationAsGps receive no pnet loc for long time, quit pnet report", new Object[0]);
            this.mIsPnetLocationActive = false;
            return true;
        }
        LBSLog.d(TAG, "shouldReportPnetLocationAsGps report normal GPS location to %s? %b", pkgName, Boolean.valueOf(!inList));
        return !inList;
    }

    public void shouldSendStartPnetCommand(CallerIdentity identity, String providerName, String hash) {
        boolean inList;
        if ("gps".equals(providerName) && this.mPnetLocationRusEnable) {
            synchronized (this.mLock) {
                inList = this.mPnetLocationWhiteList == null ? false : this.mPnetLocationWhiteList.contains(identity.getPackageName());
            }
            if (inList) {
                LBSLog.d(TAG, "in PnetLocationList start request from: %s", identity.getPackageName());
                Bundle extras = new Bundle();
                extras.putInt(PNET_APP_REQUEST, 1);
                OplusLocationServiceProxy.getInstance(this.mContext).onMapsNavigatingChanged(extras);
                this.mPnetRequestList.add(hash);
            }
        }
    }

    public void shouldSendStopPnetCommand(CallerIdentity identity, String providerName, String hash) {
        boolean inList;
        if ("gps".equals(providerName) && this.mPnetLocationRusEnable) {
            synchronized (this.mLock) {
                inList = this.mPnetLocationWhiteList == null ? false : this.mPnetLocationWhiteList.contains(identity.getPackageName());
            }
            if (inList && this.mPnetRequestList.contains(hash)) {
                LBSLog.d(TAG, "in PnetLocationList stop request from: %s", identity.getPackageName());
                Bundle extras = new Bundle();
                extras.putInt(PNET_APP_REQUEST, 2);
                OplusLocationServiceProxy.getInstance(this.mContext).onMapsNavigatingChanged(extras);
                this.mPnetRequestList.remove(hash);
                LBSLog.d(TAG, "shouldSendStopPnetCommand mPnetRequestList = %s", this.mPnetRequestList.toString());
            }
        }
    }

    public Bundle getDiagnosis(String method) {
        LBSLog.d(TAG, "diagnosis method %s", method);
        Bundle bundle = new Bundle();
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_FW_FEATURE_ENABLE.equals(method)) {
            bundle.putBoolean(DIAGNOSIS_METHOD_FW_FEATURE_ENABLE, this.mPnetLocationRusEnable);
        }
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_FW_APP_WHITE_LIST.equals(method)) {
            synchronized (this.mLock) {
                if (this.mPnetLocationWhiteList != null) {
                    bundle.putStringArrayList(DIAGNOSIS_METHOD_FW_APP_WHITE_LIST, (ArrayList) this.mPnetLocationWhiteList);
                } else {
                    bundle.putStringArrayList(DIAGNOSIS_METHOD_FW_APP_WHITE_LIST, new ArrayList<>());
                }
            }
        }
        LBSLog.i(TAG, "get diagnosis ret = %s", bundle);
        return bundle;
    }
}
