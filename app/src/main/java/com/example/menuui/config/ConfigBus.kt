package com.example.menuui.config

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.menuui.publish.Probe
import com.example.menuui.publish.SnapshotPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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

/** Vector / LSPosed 作用域同步状态（应用页展示 + 手动同步反馈） */
data class ScopeSyncStatus(
    val syncing: Boolean = false,
    val lastOk: Boolean? = null,
    val via: String = "",
    val count: Int = 0,
    val at: Long = 0L,
    val message: String = "",
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

    private val _scopeSync = MutableStateFlow(ScopeSyncStatus())
    val scopeSync: StateFlow<ScopeSyncStatus> = _scopeSync

    private val scopeSyncGen = AtomicLong(0L)

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

    /**
     * 按 Vector / LSPosed 作用域增量同步目标应用：新勾选的**第三方**包自动加入 apps（默认生效），
     * 系统应用不参与（视同未勾选：此前同步进来的会随差集被移除）；
     * 取消勾选的包从 apps 移除；普通自动同步只看与上次同步（scopeSeen）的差集，用户手动
     * 删除 / 停用的应用不会被反复改回；forceReconcile=true（手动同步）时补齐所有已勾选
     * 但当前不在列表中的第三方应用。作用域读取失败时不动配置，并更新 scopeSync 状态。
     */
    fun syncScopeAsync(forceReconcile: Boolean = false) {
        val ctx = appContext ?: return
        synchronized(lock) {
            if (_scopeSync.value.syncing && !forceReconcile) return
            _scopeSync.value = _scopeSync.value.copy(syncing = true)
        }
        val gen = scopeSyncGen.incrementAndGet()
        io.execute {
            val res = SnapshotPublisher.queryScopeWithSource(ctx)
            if (res == null) {
                handler.post {
                    if (gen != scopeSyncGen.get()) return@post
                    _scopeSync.value = ScopeSyncStatus(
                        syncing = false,
                        lastOk = false,
                        via = "",
                        count = _state.value.scopeSeen.size,
                        at = System.currentTimeMillis(),
                        message = "读取 Vector 作用域失败（模块未响应且 su/CLI 不可用）",
                    )
                }
                return@execute
            }
            val filtered = res.packages.filter { InstalledApps.isThirdParty(ctx, it) }
            handler.post {
                if (gen != scopeSyncGen.get()) return@post
                applyScope(filtered, res.via, forceReconcile)
            }
        }
    }

    private fun applyScope(scope: List<String>, via: String, forceReconcile: Boolean) {
        val cur = _state.value
        val seen = cur.scopeSeen.toSet()
        val now = scope.toSet()
        val removed = seen - now
        val existing = cur.apps.map { it.pkg }.toSet()
        val added = if (forceReconcile) {
            scope.filter { it !in existing }
        } else {
            scope.filter { it !in seen && it !in existing }
        }
        val apps = cur.apps.filter { it.pkg !in removed } + added.map { AppPolicy(pkg = it) }
        val nowMs = System.currentTimeMillis()
        if (apps == cur.apps) {
            // 只是基线变化（如已手动添加过的包被勾选）：落盘即可，不触发发布
            if (now != seen) {
                synchronized(lock) { _state.value = _state.value.copy(scopeSeen = scope) }
                scheduleSave()
            }
        } else {
            update { it.copy(apps = apps, scopeSeen = scope) }
            // 作用域变化涉及新勾选/取消的目标应用，开启自动发布时立即发布，不等 800ms 防抖
            if (cur.autoPublish) {
                handler.removeCallbacks(autoPublishRunnable)
                publishAsync(record = false)
            }
        }
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(nowMs))
        val delta = buildList {
            if (added.isNotEmpty()) add("+${added.size}")
            if (removed.isNotEmpty()) add("-${removed.size}")
        }.joinToString("/")
        val suffix = if (delta.isNotEmpty()) "（$via · $delta · $timeStr）" else "（$via · $timeStr）"
        _scopeSync.value = ScopeSyncStatus(
            syncing = false,
            lastOk = true,
            via = via,
            count = scope.size,
            at = nowMs,
            message = "已从 Vector 同步 ${scope.size} 个应用$suffix",
        )
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
