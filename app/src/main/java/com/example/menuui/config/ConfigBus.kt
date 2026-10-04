package com.example.menuui.config

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.menuui.publish.Probe
import com.example.menuui.publish.SnapshotPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** 摇杆实时状态（服务积分推算的结果快照，供 UI 展示与快照 joystick 段构建） */
data class JoystickLive(
    val active: Boolean,
    val lat: Double,
    val lon: Double,
    val vNorthMps: Double,
    val vEastMps: Double,
    val anchoredAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
    val epoch: Long,
) {
    val speedMps: Double get() = kotlin.math.hypot(vNorthMps, vEastMps)
}

data class PublishOutcome(val ok: Boolean, val via: String, val message: String, val at: Long) {
    fun toRecord(): PublishRecord = PublishRecord(at, ok, via, message)
}

/**
 * 发布状态（顶栏常驻展示）：
 *  - pending：有改动尚未成功发布到模块；
 *  - publishing：UI 手动发布进行中（服务节拍发布不置位，避免顶栏闪烁）；
 *  - last：最近一次发布结果（含自动发布与服务节拍），失败在顶栏可见而非静默。
 */
data class PublishStatus(
    val pending: Boolean = false,
    val publishing: Boolean = false,
    val last: PublishOutcome? = null,
)

/**
 * 进程单例配置总线：UI（Activity 存活期）与摇杆前台服务（Activity 销毁后仍运行）
 * 共享同一份 ManagerConfig 与摇杆实时状态。
 *  - 变更即防抖落盘（400ms），不自动发布——发布是显式动作（按钮/开关回调）；
 *  - 所有发布走 [publishNow]：构建含 joystick 段的完整快照（服务运行中时任何
 *    配置发布都带上当前摇杆状态，避免把模块里尚在生效的摇杆段清掉）；
 *  - 服务节拍发布不写历史（record=false），只有 UI 发布留痕。
 */
object ConfigBus {

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var appContext: Context? = null
    private var initialized = false

    private val _state = MutableStateFlow(ManagerConfig())
    val state: StateFlow<ManagerConfig> = _state

    private val _joystick = MutableStateFlow<JoystickLive?>(null)
    val joystick: StateFlow<JoystickLive?> = _joystick

    private val _status = MutableStateFlow(PublishStatus())
    val status: StateFlow<PublishStatus> = _status

    /** 配置版本：每次 update 自增；发布成功时版本未变才清除 pending */
    @Volatile private var version = 0L

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            _state.value = ConfigStore.load(context)
            initialized = true
        }
    }

    /** 配置变更：内存即时生效 + 防抖落盘 + （autoPublish 开启时）防抖自动发布 */
    fun update(transform: (ManagerConfig) -> ManagerConfig) {
        synchronized(lock) {
            _state.value = transform(_state.value)
            version++
        }
        _status.update { it.copy(pending = true) }
        scheduleSave()
        scheduleAutoPublish()
    }

    /** 摇杆服务更新实时状态（UI 观察展示） */
    fun setJoystick(live: JoystickLive?) {
        _joystick.value = live
    }

    /**
     * 发布快照到模块。⚠ 含 su 等待 root 授权的阻塞 IO，必须在后台线程调用
     * （或使用 [publishAsync]）。record=true 写发布记录（UI 发布）；服务节拍传 false。
     */
    fun publishNow(record: Boolean): PublishOutcome {
        val ctx = appContext
            ?: return PublishOutcome(false, "none", "配置总线未初始化", System.currentTimeMillis())
        val publishedVersion = version
        val json = SnapshotBuilder.build(_state.value, joystickSection())
        val r = SnapshotPublisher.publish(ctx, json)
        val outcome = PublishOutcome(r.ok, r.via, r.message, System.currentTimeMillis())
        _status.update { s ->
            s.copy(pending = if (r.ok) version != publishedVersion else s.pending, last = outcome)
        }
        if (record) {
            // 发布记录不是配置改动：不标 pending、不触发自动发布，只落盘
            synchronized(lock) {
                _state.value = _state.value.let { it.copy(history = (listOf(outcome.toRecord()) + it.history).take(20)) }
            }
            scheduleSave()
        }
        return outcome
    }

    fun publishAsync(record: Boolean, onDone: (PublishOutcome) -> Unit = {}) {
        if (record) _status.update { it.copy(publishing = true) }
        io.execute {
            val outcome = publishNow(record)
            handler.post {
                if (record) _status.update { it.copy(publishing = false) }
                onDone(outcome)
            }
        }
    }

    /** 服务停止且历史摇杆段可能仍在模块里生效时，显式发布 disabled 清理 */
    fun publishJoystickOffAsync() {
        publishAsync(record = false)
    }

    fun probeAsync(onDone: (Probe) -> Unit) {
        val ctx = appContext ?: return
        io.execute {
            val p = SnapshotPublisher.probeModule(ctx)
            handler.post { onDone(p) }
        }
    }

    /** joystick 段：服务从未运行过 → 省略；运行过 → 始终显式写入（含关闭态） */
    private fun joystickSection(): org.json.JSONObject? {
        val live = _joystick.value ?: return null
        return SnapshotBuilder.joystickJson(
            enabled = live.active,
            baseLat = live.lat,
            baseLon = live.lon,
            vNorthMps = live.vNorthMps,
            vEastMps = live.vEastMps,
            anchoredAtElapsedMs = live.anchoredAtElapsedMs,
            expiresAtElapsedMs = live.expiresAtElapsedMs,
            sessionEpoch = live.epoch,
        )
    }

    private val saveRunnable = Runnable {
        val snapshot = _state.value
        io.execute { appContext?.let { ConfigStore.save(it, snapshot) } }
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 400)
    }

    private val autoPublishRunnable = Runnable {
        publishAsync(record = false)
    }

    /** 编辑改动自动发布（800ms 防抖；关闭该选项后只能手动发布） */
    private fun scheduleAutoPublish() {
        if (!_state.value.autoPublish) return
        handler.removeCallbacks(autoPublishRunnable)
        handler.postDelayed(autoPublishRunnable, 800)
    }
}
