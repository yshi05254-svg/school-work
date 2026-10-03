package com.example.menuui.config

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.menuui.publish.Probe
import com.example.menuui.publish.SnapshotPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

data class PublishOutcome(
    val ok: Boolean,
    val via: String,
    val message: String,
    val at: Long,
    /** 用户手动发起（发布按钮）：UI 据此提示结果；自动发布只在失败时提示 */
    val manual: Boolean = false,
) {
    fun toRecord(): PublishRecord = PublishRecord(at, ok, via, message)
}

/**
 * 发布状态（全局状态条）：
 *  - dirty：当前配置中会进入快照的部分与模块里最后一次成功发布的不一致；
 *  - inFlight：界面发起的发布进行中（摇杆服务节拍发布不计入，避免状态条闪烁）；
 *  - last：最近一次界面发起发布的结果。
 */
data class PublishUiState(
    val dirty: Boolean = false,
    val inFlight: Boolean = false,
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

    private val _publishUi = MutableStateFlow(PublishUiState())
    val publishUi: StateFlow<PublishUiState> = _publishUi

    /** 最后一次成功发布的快照指纹（持久化，重启后仍能判断"有未发布改动"） */
    @Volatile
    private var publishedFingerprint: Int? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            _state.value = ConfigStore.load(context)
            publishedFingerprint = ConfigStore.loadPublishedFingerprint(context)
            initialized = true
        }
        refreshDirty()
    }

    /** 配置变更：内存即时生效 + 防抖落盘 + （autoPublish 开启时）防抖自动发布 */
    fun update(transform: (ManagerConfig) -> ManagerConfig) {
        synchronized(lock) { _state.value = transform(_state.value) }
        refreshDirty()
        scheduleSave()
        scheduleAutoPublish()
    }

    /**
     * 只取会进入快照的字段（与 SnapshotBuilder 对应）：发布记录、自动发布开关、
     * 摇杆档位等管理端本地设置变化不算"未发布改动"。data class / List 的
     * hashCode 跨进程稳定，可持久化比对。
     */
    private fun fingerprint(c: ManagerConfig): Int = listOf(
        c.masterEnabled, c.jitterEnabled, c.jitterAmplitudeMeters,
        c.env, c.apps, c.excludedPackages, c.excludedUids, c.sim,
    ).hashCode()

    private fun refreshDirty() {
        val dirty = publishedFingerprint != fingerprint(_state.value)
        mutateUi { if (it.dirty == dirty) it else it.copy(dirty = dirty) }
    }

    private inline fun mutateUi(f: (PublishUiState) -> PublishUiState) {
        synchronized(lock) { _publishUi.value = f(_publishUi.value) }
    }

    /** 进行中的界面发布数（自动 + 手动可能重叠，最后一个完成才清 inFlight） */
    private val uiPending = java.util.concurrent.atomic.AtomicInteger(0)

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
        val cfg = _state.value
        val json = SnapshotBuilder.build(cfg, joystickSection())
        val r = SnapshotPublisher.publish(ctx, json)
        val outcome = PublishOutcome(r.ok, r.via, r.message, System.currentTimeMillis(), manual = record)
        if (r.ok) {
            val fp = fingerprint(cfg)
            if (publishedFingerprint != fp) {
                publishedFingerprint = fp
                runCatching { ConfigStore.savePublishedFingerprint(ctx, fp) }
            }
            refreshDirty()
        }
        if (record) {
            update { it.copy(history = (listOf(outcome.toRecord()) + it.history).take(20)) }
        }
        return outcome
    }

    /**
     * @param quiet 摇杆服务节拍发布：不驱动全局状态条（每秒数次，否则"发布中"闪烁），
     *              结果只回调给调用方
     */
    fun publishAsync(record: Boolean, quiet: Boolean = false, onDone: (PublishOutcome) -> Unit = {}) {
        if (!quiet) {
            uiPending.incrementAndGet()
            mutateUi { it.copy(inFlight = true) }
        }
        io.execute {
            val outcome = publishNow(record)
            if (!quiet) {
                val left = uiPending.decrementAndGet()
                mutateUi { it.copy(inFlight = left > 0, last = outcome) }
            }
            handler.post { onDone(outcome) }
        }
    }

    /** 服务停止且历史摇杆段可能仍在模块里生效时，显式发布 disabled 清理 */
    fun publishJoystickOffAsync() {
        publishAsync(record = false, quiet = true)
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

    /**
     * 编辑改动自动发布（800ms 防抖；关闭该选项后只能手动发布）。只在快照内容与
     * 模块不一致时触发：发布记录、摇杆档位等本地字段变化不再引起多余发布
     * （此前手动发布写入记录后还会再自动发布一次）。
     */
    private fun scheduleAutoPublish() {
        if (!_state.value.autoPublish || !_publishUi.value.dirty) return
        handler.removeCallbacks(autoPublishRunnable)
        handler.postDelayed(autoPublishRunnable, 800)
    }
}
