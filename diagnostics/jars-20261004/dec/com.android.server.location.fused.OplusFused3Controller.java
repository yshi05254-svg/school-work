package com.android.server.location.fused;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.location.GnssStatus;
import android.location.IOplusLocationManager;
import android.location.Location;
import android.location.LocationResult;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import com.android.server.location.OplusLbsConfigNotifyer;
import com.android.server.location.common.OplusLbsCommonConstant;
import com.android.server.location.common.OplusLbsFeatureManager;
import com.android.server.location.common.sensor.SensorManagerProxy;
import com.android.server.location.gnss.GnssLocationProvider;
import com.android.server.location.interfaces.IOplusConfigListener;
import com.android.server.location.log.LBSLog;
import com.android.server.location.nlp.OplusMultiNlpHelper;
import com.android.server.location.rus.OplusLbsRomUpdateUtil;
import com.android.server.location.thread.OplusLocationThreadRenter;
import com.oplus.location.fused.FlpDataManager;
import com.oplus.location.fused.GetPdrLibFunction;
import com.oplus.location.fused.GetVdrLibFunction;
import com.oplus.location.fused.IFlpListener;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/* JADX INFO: loaded from: classes.dex */
public class OplusFused3Controller {
    private static final int CAR_LINK_SENSOR_TYPE_ACCEL = 3;
    private static final int CAR_LINK_SENSOR_TYPE_BATTERY = 7;
    private static final int CAR_LINK_SENSOR_TYPE_GEAR = 5;
    private static final int CAR_LINK_SENSOR_TYPE_GPS = 0;
    private static final int CAR_LINK_SENSOR_TYPE_GYRO = 2;
    private static final int CAR_LINK_SENSOR_TYPE_LIGHTS = 1;
    private static final int CAR_LINK_SENSOR_TYPE_LIGHTSENSOR = 6;
    private static final int CAR_LINK_SENSOR_TYPE_OIL = 4;
    private static final String DIAGNOSIS_METHOD_ALL = "all";
    private static final String DIAGNOSIS_METHOD_FEATURE_MODE = "fwFeatureMode";
    private static final String DIAGNOSIS_METHOD_PHYSICAL_GYRO = "isPhysicalGyroscope";
    private static final String DIAGNOSIS_METHOD_SENSOR_IN_SPEC = "isSensorInSpec";
    private static final String DIAGNOSIS_METHOD_SUPPORT_SENSOR_AR = "isSupportSensorAR";
    private static final String DIAGNOSIS_METHOD_VERSION_PDR = "pdrLibVer";
    private static final String DIAGNOSIS_METHOD_VERSION_VDR = "vdrLibVer";
    private static final int GLOBAL_FLP_MODE_OFF = 0;
    private static final int GLOBAL_FLP_MODE_ON = 1;
    private static final int GLOBAL_FLP_MODE_PDR_OFF_VDR_ON = 21;
    private static final int GLOBAL_FLP_MODE_PDR_ON_VDR_OFF = 20;
    private static final int GLOBAL_FLP_MODE_TEST = 2;
    private static final int GLOBAL_FLP_MODE_TEST_FORCE_ON_PDR = 3;
    private static final int GLOBAL_FLP_MODE_TEST_FORCE_ON_VDR = 4;
    private static final int ICCOA_CAR_MODE_CONNECT_DRIVER = 1002;
    private static final int ICCOA_CAR_MODE_CONNECT_INTEGRATION = 1003;
    private static final int ICCOA_CAR_MODE_CONNECT_SCREEN = 1001;
    private static final String KEY_GLOBAL_CASTING_STATE = "ucar_casting_state";
    private static final String KEY_GLOBAL_FLP_MODE = "config_globalFlpMode";
    private static final String OCAR_MODE = "mode";
    private static final String OCAR_RUNNING_MODE = "getRunningMode";
    private static final int SENSOR_TYPE_ACC = 2;
    private static final int SENSOR_TYPE_GEAR = 1;
    private static final int SENSOR_TYPE_GPS = 8;
    private static final int SENSOR_TYPE_GYRO = 4;
    private static final int SENSOR_TYPE_MAX = 4;
    private static final int SENSOR_TYPE_OPPO_M_ACTIVITY_RECOGNITION = 65618;
    private static final int SENSOR_TYPE_OPPO_Q_ACTIVITY_RECOGNITION = 33171037;
    private static final String TAG = "OplusFused3Controller";
    private static final String URL_OCAR_ENTRY = "content://com.oplus.ocar.OCarEntryProvider";
    private Context mContext;
    private final boolean mIsLightOs;
    private static final HashMap<String, String> SENSOR_LIST_MAP = new HashMap<String, String>() { // from class: com.android.server.location.fused.OplusFused3Controller.1
        {
            put("bmi220", "bmi220");
            put("bmi26x", "bmi260");
            put("bmi2xy", "bmi260");
            put("bmi3xy", "bmi320");
            put("icm4n607", "icm42607");
            put("icm4263x", "icm42631");
            put("icm456xy", "icm45621");
            put("lsm6dso", "lsm6dso");
            put("lsm6dsm", "lsm6dsm");
            put("lsm6ds3", "lsm6ds3trc");
        }
    };
    private static OplusFused3Controller sInstance = null;
    private final Object mLock = new Object();
    private final IOplusConfigListener mConfigListener = new IOplusConfigListener() { // from class: com.android.server.location.fused.OplusFused3Controller.2
        public void onRusChanged() {
            OplusFused3Controller.this.updateRusConfig();
        }
    };
    private int mGlobalFlpMode = 0;
    private GnssLocationProvider mGnssLocationProvider = null;
    private FlpDataManager mFlpDataManager = null;
    private IFlpListener mFlpListener = null;
    private OplusFlpTestMode mTestMode = null;
    private boolean mIsGlobalFlpEnable = false;
    private int mRequestSensorType = 0;
    private int mRequestSensorRate = 0;
    private int mCarStateMode = 0;
    private Integer[] mCarLinkSupportedSensor = new Integer[4];
    private IOplusLocationManager.ICarLinkListener mCarLinkListener = null;
    private AtomicBoolean mIsAbandonOriginGpsLoc = new AtomicBoolean(false);

    private OplusFused3Controller(Context context) {
        this.mContext = context;
        this.mIsLightOs = OplusLbsFeatureManager.isLightOs(context);
        OplusLbsConfigNotifyer.getInstance(this.mContext).registerLbsConfigListener(this.mConfigListener);
        updateRusConfig();
        try {
            final ContentResolver contentResolver = this.mContext.getContentResolver();
            if (contentResolver != null) {
                updateCarRunningState(contentResolver);
                contentResolver.registerContentObserver(Settings.Global.getUriFor(KEY_GLOBAL_CASTING_STATE), true, new ContentObserver(OplusLocationThreadRenter.getHandler(2)) { // from class: com.android.server.location.fused.OplusFused3Controller.3
                    @Override // android.database.ContentObserver
                    public void onChange(boolean selfChange) {
                        OplusFused3Controller.this.updateCarRunningState(contentResolver);
                    }
                }, -1);
            }
        } catch (Exception e) {
            LBSLog.e(TAG, "Failed to initialize content observer.", new Object[0]);
        }
    }

    public static synchronized OplusFused3Controller getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new OplusFused3Controller(context);
        }
        return sInstance;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateRusConfig() {
        this.mGlobalFlpMode = this.mIsLightOs ? 0 : OplusLbsRomUpdateUtil.getInstall(this.mContext).getInt(KEY_GLOBAL_FLP_MODE);
        LBSLog.i(TAG, "Global FLP working mode: %d, isLightOs: %b", Integer.valueOf(this.mGlobalFlpMode), Boolean.valueOf(this.mIsLightOs));
        FlpDataManager.setPdrTestMode(false);
        FlpDataManager.setVdrTestMode(false);
        if ((this.mGlobalFlpMode >= 1 && this.mGlobalFlpMode <= 4) || this.mGlobalFlpMode == 20 || this.mGlobalFlpMode == 21) {
            this.mIsGlobalFlpEnable = true;
            LBSLog.i(TAG, "set Global FLP enable", new Object[0]);
            if (this.mFlpListener == null) {
                this.mFlpListener = getFlpListener();
            }
            if (this.mFlpDataManager == null) {
                this.mFlpDataManager = FlpDataManager.getInstance(this.mContext);
            }
            this.mFlpDataManager.setFlpListener(this.mFlpListener, OplusLocationThreadRenter.getThread(1).getLooper());
            if (this.mGlobalFlpMode == 20) {
                LBSLog.i(TAG, "Global FLP is working in PDR only Mode", new Object[0]);
                FlpDataManager.setVdrModeOff(true);
                return;
            }
            if (this.mGlobalFlpMode == 21) {
                LBSLog.i(TAG, "Global FLP is working in VDR only Mode", new Object[0]);
                FlpDataManager.setPdrModeOff(true);
                return;
            }
            if (this.mGlobalFlpMode >= 2 && this.mGlobalFlpMode <= 4) {
                LBSLog.w(TAG, "Global FLP is working in Test Mode", new Object[0]);
                if (this.mTestMode == null) {
                    this.mTestMode = new OplusFlpTestMode(this.mContext);
                }
                if (this.mGlobalFlpMode == 3) {
                    LBSLog.w(TAG, "Global FLP Force PDR on", new Object[0]);
                    FlpDataManager.setPdrTestMode(true);
                    return;
                } else {
                    if (this.mGlobalFlpMode == 4) {
                        LBSLog.w(TAG, "Global FLP Force VDR on", new Object[0]);
                        FlpDataManager.setVdrTestMode(true);
                        return;
                    }
                    return;
                }
            }
            return;
        }
        this.mIsGlobalFlpEnable = false;
        LBSLog.i(TAG, "set Global FLP disable", new Object[0]);
        if (this.mFlpDataManager != null) {
            this.mFlpDataManager.unregisterFlpEvent();
        }
        this.mFlpListener = null;
        this.mFlpDataManager = null;
        this.mTestMode = null;
    }

    public void setGnssLocationProvider(GnssLocationProvider provider) {
        LBSLog.d(TAG, "setGnssLocationProvider: %s", provider.toString());
        this.mGnssLocationProvider = provider;
    }

    public void onStartNavigating() {
        if (!this.mIsGlobalFlpEnable) {
            return;
        }
        LBSLog.d(TAG, "onStartNavigating", new Object[0]);
        if (this.mFlpDataManager != null) {
            this.mFlpDataManager.registerFlpEvent();
        }
    }

    public void onStopNavigating() {
        if (!this.mIsGlobalFlpEnable) {
            return;
        }
        LBSLog.d(TAG, "onStopNavigating", new Object[0]);
        if (this.mFlpDataManager != null) {
            this.mFlpDataManager.unregisterFlpEvent();
        }
    }

    public void inputSvStatusChanged(GnssStatus status) {
        if (!this.mIsGlobalFlpEnable || status == null) {
            return;
        }
        LBSLog.d(TAG, "inputSvStatusChanged: %s", status.toString());
        if (this.mFlpDataManager != null) {
            this.mFlpDataManager.setGnssStatus(status);
        }
    }

    public void inputLocationChanged(Location loc) throws Throwable {
        if (!this.mIsGlobalFlpEnable || loc == null) {
            return;
        }
        LBSLog.d(TAG, "inputLocationChanged: %s", loc.toString());
        if (this.mFlpDataManager != null) {
            this.mFlpDataManager.setLocation(loc);
        }
    }

    public boolean isFusedGeneratedLocation(Location location) {
        Bundle bundle;
        if (location != null && "gps".equals(location.getProvider()) && (bundle = location.getExtras()) != null) {
            String type = bundle.getString("type");
            if (OplusLbsCommonConstant.TYPE_LOCATION_PDR.equals(type)) {
                return true;
            }
            return false;
        }
        return false;
    }

    public void registerCarSensorListener(IOplusLocationManager.ICarLinkListener listener) {
        LBSLog.i(true, TAG, "Fused3Controller.registerCarSensorListener %s", listener);
        this.mCarLinkListener = listener;
        if (this.mCarLinkListener != null && this.mRequestSensorType != 0 && this.mRequestSensorRate != 0) {
            try {
                this.mCarLinkListener.requestSensorData(this.mRequestSensorType, this.mRequestSensorRate);
                LBSLog.d(TAG, "ReRequest sensor data type [%d], rate [%d]", Integer.valueOf(this.mRequestSensorType), Integer.valueOf(this.mRequestSensorRate));
            } catch (RemoteException e) {
                LBSLog.e(true, TAG, "rebind fail!", new Object[0]);
            }
        }
    }

    public void unregisterCarSensorListener(IOplusLocationManager.ICarLinkListener listener) {
        LBSLog.i(true, TAG, "Fused3Controller.unregisterCarSensorListener %s", listener);
        if (this.mCarLinkListener == listener) {
            this.mCarLinkListener = null;
        } else {
            LBSLog.w(TAG, "unregisterCarSensorListener: listener is not registered!", new Object[0]);
        }
    }

    public void setCarLinkSupportSensorTypes(int[] types) {
        LBSLog.i(true, TAG, "Fused3Controller.setCarLinkSupportSensorTypes %s", Arrays.toString(types));
        Arrays.fill((Object[]) this.mCarLinkSupportedSensor, (Object) 0);
        int size = Math.min(this.mCarLinkSupportedSensor.length, types.length);
        for (int i = 0; i < size; i++) {
            this.mCarLinkSupportedSensor[i] = Integer.valueOf(transSensorType(types[i]));
        }
    }

    public boolean setGearStateInfo(int gearState, double currentSpeed, long currentTime) {
        if (this.mFlpDataManager == null) {
            this.mFlpDataManager = FlpDataManager.getInstance(this.mContext);
            LBSLog.w(TAG, "setGearStatInfo mFlpDataManager is Null", new Object[0]);
        }
        return this.mFlpDataManager.setGearStateInfo(gearState, currentSpeed, currentTime);
    }

    public boolean setAccInfo(double accX, double accY, double accZ, long timeStamp) {
        if (this.mFlpDataManager == null) {
            this.mFlpDataManager = FlpDataManager.getInstance(this.mContext);
            LBSLog.w(TAG, "setAccInfo mFlpDataManager is Null", new Object[0]);
        }
        return this.mFlpDataManager.setAccInfo(accX, accY, accZ, timeStamp);
    }

    public boolean setGyroInfo(int gyroType, double legyroX, double legyroY, double legyroZ, long timeStamp) {
        if (this.mFlpDataManager == null) {
            this.mFlpDataManager = FlpDataManager.getInstance(this.mContext);
            LBSLog.w(TAG, "setGyroInfo mFlpDataManager is Null", new Object[0]);
        }
        return this.mFlpDataManager.setGyroInfo(gyroType, legyroX, legyroY, legyroZ, timeStamp);
    }

    public boolean setGpsInfo(double altitude, double latitude, double longitude, int antennaState, double pDop, double speed, double heading, int satsUsed, int satsVisible, long timeStamp) {
        if (this.mFlpDataManager == null) {
            this.mFlpDataManager = FlpDataManager.getInstance(this.mContext);
            LBSLog.w(TAG, "setGpsInfo mFlpDataManager is Null", new Object[0]);
        }
        return this.mFlpDataManager.setGpsInfo(altitude, latitude, longitude, antennaState, pDop, speed, heading, satsUsed, satsVisible, timeStamp);
    }

    public boolean shouldReportFlpAsGps(Location loc, String pkgName) {
        if (!this.mIsGlobalFlpEnable) {
            return true;
        }
        if (loc == null) {
            return false;
        }
        if (!this.mIsAbandonOriginGpsLoc.get() || !"gps".equals(loc.getProvider())) {
            return true;
        }
        Bundle bundle = loc.getExtras();
        if (bundle != null) {
            try {
                String source = bundle.getString("type");
                if (OplusLbsCommonConstant.TYPE_LOCATION_VDR_VEHICLE.equals(source)) {
                    LBSLog.d(TAG, "report OPLUS-VDR-Vehicle location to %s", pkgName);
                    return true;
                }
            } catch (Exception e) {
                LBSLog.e(TAG, "report OPLUS-VDR-Vehicle error: %s", e.getMessage());
            }
        }
        return false;
    }

    private int transSensorType(int carLinkType) {
        switch (carLinkType) {
            case 0:
                return 8;
            case 1:
            case 4:
            case 6:
            case 7:
                LBSLog.d(TAG, "don't support car-link sensor [%d]", Integer.valueOf(carLinkType));
                return 0;
            case 2:
                return 4;
            case 3:
                return 2;
            case 5:
                return 1;
            default:
                return 0;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateCarRunningState(ContentResolver contentResolver) {
        try {
            int castingState = Settings.Global.getInt(contentResolver, KEY_GLOBAL_CASTING_STATE, 0);
            LBSLog.d(TAG, "Casting state value %d", Integer.valueOf(castingState));
            if (1 == castingState) {
                Uri uri = Uri.parse(URL_OCAR_ENTRY);
                Bundle result = contentResolver.call(uri, OCAR_RUNNING_MODE, (String) null, (Bundle) null);
                if (result == null) {
                    LBSLog.i(TAG, "casting result is null", new Object[0]);
                } else {
                    this.mCarStateMode = result.getInt(OCAR_MODE);
                    LBSLog.i(TAG, "get the casting mCarStateMode: %d", Integer.valueOf(this.mCarStateMode));
                }
                return;
            }
            this.mCarStateMode = 0;
            LBSLog.d(TAG, "casting state is zero and reset", new Object[0]);
        } catch (Exception e) {
            LBSLog.e(TAG, "get the casting uri error", new Object[0]);
            this.mCarStateMode = 0;
        }
    }

    private IFlpListener getFlpListener() {
        IFlpListener listener = new IFlpListener() { // from class: com.android.server.location.fused.OplusFused3Controller.4
            @Override // com.oplus.location.fused.IFlpListener
            public void flpRegister(boolean b) {
                LBSLog.i(OplusFused3Controller.TAG, "flpRegister: b:%b", Boolean.valueOf(b));
            }

            @Override // com.oplus.location.fused.IFlpListener
            public void flpUnRegister(boolean b) {
                LBSLog.i(OplusFused3Controller.TAG, "flpUnRegister: b:%b", Boolean.valueOf(b));
            }

            @Override // com.oplus.location.fused.IFlpListener
            public void flpLocation(Location location) {
                synchronized (OplusFused3Controller.this.mLock) {
                    OplusFused3Controller.this.onFusedLocationChanged(location);
                }
            }

            @Override // com.oplus.location.fused.IFlpListener
            public void setLocationType(Bundle bundle) {
                if (OplusFused3Controller.this.mGnssLocationProvider != null) {
                    OplusFused3Controller.this.mGnssLocationProvider.getGnssLocationProviderWrapper().updateData(bundle);
                } else {
                    LBSLog.e(OplusFused3Controller.TAG, "setLocationType when target GnssLocationProvider is null", new Object[0]);
                }
            }

            @Override // com.oplus.location.fused.IFlpListener
            public boolean isCarLinkConnected() {
                boolean isCarModeConnected = 1001 == OplusFused3Controller.this.mCarStateMode || 1003 == OplusFused3Controller.this.mCarStateMode;
                LBSLog.i(OplusFused3Controller.TAG, "ICarConnectMode %d, %b, listener %s", Integer.valueOf(OplusFused3Controller.this.mCarStateMode), Boolean.valueOf(isCarModeConnected), OplusFused3Controller.this.mCarLinkListener);
                return isCarModeConnected && OplusFused3Controller.this.mCarLinkListener != null;
            }

            @Override // com.oplus.location.fused.IFlpListener
            public boolean requestCarlinkSupportSensorTypes() {
                if (OplusFused3Controller.this.mCarLinkListener != null) {
                    try {
                        OplusFused3Controller.this.mCarLinkListener.requestSupportSensorTypes();
                        return true;
                    } catch (RemoteException e) {
                        LBSLog.e(true, OplusFused3Controller.TAG, "Failed to request car-link sensor types", new Object[0]);
                    }
                }
                return false;
            }

            @Override // com.oplus.location.fused.IFlpListener
            public boolean requestCarlinkSensorData(int sensorId, int rate) {
                if (!Arrays.asList(OplusFused3Controller.this.mCarLinkSupportedSensor).contains(Integer.valueOf(sensorId))) {
                    LBSLog.e(true, OplusFused3Controller.TAG, "Not support car-link sensor [%d]", Integer.valueOf(sensorId));
                    return false;
                }
                if (OplusFused3Controller.this.mCarLinkListener != null) {
                    try {
                        OplusFused3Controller.this.mCarLinkListener.requestSensorData(sensorId, rate);
                        OplusFused3Controller.this.mRequestSensorType = sensorId;
                        OplusFused3Controller.this.mRequestSensorRate = rate;
                        return true;
                    } catch (RemoteException e) {
                        LBSLog.e(true, OplusFused3Controller.TAG, "Failed to request sensor data!", new Object[0]);
                    }
                }
                return false;
            }

            @Override // com.oplus.location.fused.IFlpListener
            public boolean stopCarlinkSensorData(int sensorId) {
                if (!Arrays.asList(OplusFused3Controller.this.mCarLinkSupportedSensor).contains(Integer.valueOf(sensorId))) {
                    LBSLog.e(true, OplusFused3Controller.TAG, "Not support car-link sensor [%d]", Integer.valueOf(sensorId));
                    return false;
                }
                if (OplusFused3Controller.this.mCarLinkListener != null) {
                    try {
                        OplusFused3Controller.this.mCarLinkListener.stopSensorData(sensorId);
                        OplusFused3Controller.this.mRequestSensorType = 0;
                        OplusFused3Controller.this.mRequestSensorRate = 0;
                        return true;
                    } catch (RemoteException e) {
                        LBSLog.e(true, OplusFused3Controller.TAG, "Failed to stop sensor data!", new Object[0]);
                    }
                }
                return false;
            }

            @Override // com.oplus.location.fused.IFlpListener
            public void updateAbandonOriginGpsLoc(boolean abandonOriginGpsLoc) {
                LBSLog.w(true, OplusFused3Controller.TAG, "update abandon origin gnss loc: %b", Boolean.valueOf(abandonOriginGpsLoc));
                OplusFused3Controller.this.mIsAbandonOriginGpsLoc.set(abandonOriginGpsLoc);
            }
        };
        return listener;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void onFusedLocationChanged(Location location) {
        if (!this.mIsGlobalFlpEnable) {
            LBSLog.e(TAG, "receive flp-3.0 location while config off", new Object[0]);
            return;
        }
        if (location == null) {
            LBSLog.e(TAG, "receive flp-3.0 location null", new Object[0]);
            return;
        }
        LBSLog.d(TAG, "location from flp-3.0: %s", location.toString());
        if (this.mGnssLocationProvider != null) {
            try {
                this.mGnssLocationProvider.getGnssLocationProviderWrapper().reportLocation(LocationResult.wrap(new Location[]{location}).validate());
                return;
            } catch (LocationResult.BadLocationException e) {
                LBSLog.e(TAG, "BadLocationException %s", e.toString());
                return;
            }
        }
        LBSLog.e(TAG, "reportLocation when target GnssLocationProvider is null", new Object[0]);
    }

    public boolean isPseudoGyro() {
        if (this.mContext == null) {
            return true;
        }
        boolean isPseudoGyro = SensorManagerProxy.getInstance(this.mContext).isPseudoGyro();
        LBSLog.d(TAG, "is pseudo gyro:%b", Boolean.valueOf(isPseudoGyro));
        return isPseudoGyro;
    }

    public boolean isSensorActivityRecognitionSupported() {
        if (this.mContext == null) {
            return false;
        }
        int sensorARType = SENSOR_TYPE_OPPO_Q_ACTIVITY_RECOGNITION;
        if (OplusLbsCommonConstant.MTK_PLATFORM.equals(OplusLbsCommonConstant.getPlatform())) {
            sensorARType = SENSOR_TYPE_OPPO_M_ACTIVITY_RECOGNITION;
        }
        boolean isSensorSupported = SensorManagerProxy.getInstance(this.mContext).isWakeupSensorSupported(sensorARType);
        LBSLog.d(TAG, "is support sensor ar:%b", Boolean.valueOf(isSensorSupported));
        return isSensorSupported;
    }

    public boolean isSensorInSpecList() {
        if (this.mContext == null) {
            return false;
        }
        String sensorName = SensorManagerProxy.getInstance(this.mContext).getSensorName(4);
        boolean isSensorInSpecList = false;
        if (sensorName != null) {
            for (String key : SENSOR_LIST_MAP.keySet()) {
                if (sensorName.contains(key)) {
                    isSensorInSpecList = true;
                    break;
                }
            }
        }
        LBSLog.d(TAG, "gyro name:%s,is in:%b", sensorName, Boolean.valueOf(isSensorInSpecList));
        return isSensorInSpecList;
    }

    public class OplusFlpTestMode {
        private static final String ACTION_OPLUS_FLP_TEST_MODE = "com.oplus.location.FLP_TEST_MODE";
        private static final String TEST_CMD_FUSED_LOCATION = "CMD_FUSED_LOCATION";
        private static final String TEST_CMD_JNI_LIB_TRIGGER = "CMD_JNI_LIB_TRIGGER";
        private static final String TEST_CMD_REPORT_FLP_LOCATION = "CMD_REPORT_FLP_LOCATION";
        private static final String TEST_TAG = "OplusFused3Controller-TestMode";
        private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() { // from class: com.android.server.location.fused.OplusFused3Controller.OplusFlpTestMode.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                Log.d(OplusFlpTestMode.TEST_TAG, "onReceive");
                if (intent != null) {
                    String action = intent.getAction();
                    Bundle extras = intent.getExtras();
                    if (OplusFlpTestMode.ACTION_OPLUS_FLP_TEST_MODE.equals(action) && extras != null) {
                        String cmd = extras.getString("cmd");
                        if (OplusFlpTestMode.TEST_CMD_REPORT_FLP_LOCATION.equals(cmd)) {
                            OplusFlpTestMode.this.testReportFlpLocation();
                            return;
                        }
                        if (OplusFlpTestMode.TEST_CMD_FUSED_LOCATION.equals(cmd)) {
                            OplusFlpTestMode.this.testFusedLocation();
                        } else if (OplusFlpTestMode.TEST_CMD_JNI_LIB_TRIGGER.equals(cmd)) {
                            OplusFlpTestMode.this.testJniPdrLibTrigger();
                            OplusFlpTestMode.this.testJniVdrLibTrigger();
                        } else {
                            Log.e(OplusFlpTestMode.TEST_TAG, "error command");
                        }
                    }
                }
            }
        };
        private final Context mContext;

        public OplusFlpTestMode(Context context) {
            Log.d(TEST_TAG, "OplusFlpTestMode Constructor");
            this.mContext = context;
            registerBroadcast();
        }

        private void registerBroadcast() {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_OPLUS_FLP_TEST_MODE);
            this.mContext.registerReceiver(this.mBroadcastReceiver, intentFilter, 2);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void testReportFlpLocation() {
            Log.d(TEST_TAG, "testReportFlpLocation");
            OplusFused3Controller.this.onFusedLocationChanged(getTestLocation("gps"));
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void testFusedLocation() {
            Log.d(TEST_TAG, "testFusedLocation");
            OplusFused3Controller.this.onFusedLocationChanged(getTestLocation("gps"));
            OplusFused3Controller.this.onFusedLocationChanged(getTestLocation("network"));
            OplusFused3Controller.this.onFusedLocationChanged(getTestLocation(OplusMultiNlpHelper.SERVICE_NAME_FUSED));
        }

        private Location getTestLocation(String provider) {
            int[] testOut = {1, 2, 3, 4, 5, 6, 7, 8};
            Location loc = new Location(provider);
            loc.setTime(testOut[0]);
            loc.setLatitude(testOut[1]);
            loc.setLongitude(testOut[2]);
            loc.setAltitude(testOut[3]);
            loc.setAccuracy(testOut[4]);
            loc.setSpeed(testOut[5]);
            loc.setBearing(testOut[6]);
            loc.setElapsedRealtimeNanos(testOut[7]);
            Bundle bundle = new Bundle();
            bundle.putString("type", OplusLbsCommonConstant.TYPE_LOCATION_PDR);
            loc.setExtras(bundle);
            return loc;
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void testJniPdrLibTrigger() {
            Log.d(TEST_TAG, "testJniPdrLibTrigger");
            try {
                GetPdrLibFunction jniLib = new GetPdrLibFunction();
                Log.d(TEST_TAG, "testJniPdrLibTrigger ret = " + jniLib.apdrGetLibVersion());
            } catch (Exception exception) {
                Log.e(TEST_TAG, "testJniPdrLibTrigger exception = " + exception);
            } catch (UnsatisfiedLinkError error) {
                Log.e(TEST_TAG, "testJniPdrLibTrigger error = " + error);
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public void testJniVdrLibTrigger() {
            Log.d(TEST_TAG, "testJniVdrLibTrigger");
            try {
                GetVdrLibFunction jniLib = new GetVdrLibFunction();
                Log.d(TEST_TAG, "testJniVdrLibTrigger ret = " + jniLib.aVdrGetLibVersion());
            } catch (Exception exception) {
                Log.e(TEST_TAG, "testJniVdrLibTrigger exception = " + exception);
            } catch (UnsatisfiedLinkError error) {
                Log.e(TEST_TAG, "testJniVdrLibTrigger error = " + error);
            }
        }
    }

    public Bundle getDiagnosis(String method) {
        LBSLog.d(TAG, "diagnosis method %s", method);
        Bundle bundle = new Bundle();
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_FEATURE_MODE.equals(method)) {
            bundle.putInt(DIAGNOSIS_METHOD_FEATURE_MODE, this.mGlobalFlpMode);
        }
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_VERSION_PDR.equals(method)) {
            GetPdrLibFunction pdrLib = new GetPdrLibFunction();
            bundle.putString(DIAGNOSIS_METHOD_VERSION_PDR, pdrLib.apdrGetLibVersion());
        }
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_VERSION_VDR.equals(method)) {
            GetVdrLibFunction vdrLib = new GetVdrLibFunction();
            bundle.putString(DIAGNOSIS_METHOD_VERSION_VDR, vdrLib.aVdrGetLibVersion());
        }
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_PHYSICAL_GYRO.equals(method)) {
            bundle.putBoolean(DIAGNOSIS_METHOD_PHYSICAL_GYRO, !isPseudoGyro());
        }
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_SUPPORT_SENSOR_AR.equals(method)) {
            bundle.putBoolean(DIAGNOSIS_METHOD_SUPPORT_SENSOR_AR, isSensorActivityRecognitionSupported());
        }
        if (DIAGNOSIS_METHOD_ALL.equals(method) || DIAGNOSIS_METHOD_SENSOR_IN_SPEC.equals(method)) {
            bundle.putBoolean(DIAGNOSIS_METHOD_SENSOR_IN_SPEC, isSensorInSpecList());
        }
        LBSLog.i(TAG, "get diagnosis ret = %s", bundle);
        return bundle;
    }
}
