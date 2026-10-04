package com.example.menuui.joystick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.JoystickLive
import com.example.menuui.config.JoystickPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 摇杆悬浮窗前台服务：任何界面可操作的方向输入 → 速度矢量 → 实时发布快照 joystick 段。
 *
 * 发布契约（与模块 DynamicLocationSource.joystickFix 对应）：
 *  - 100ms tick 积分推算当前位置；速度矢量变化 ≥250ms 节流发布（基点=当前积分位置、
 *    anchoredAt=now、epoch++），模块端按 基点+v×Δt 连续插值，快照 1s 轮询也不跳变；
 *  - 静止也每 3s 心跳发布（只续期 expiresAt=now+6s，不动基点，位置连续）；
 *  - 服务被杀不补发 → 模块 6s 内过期回落静态环境（死人开关）；
 *  - 停止：发布 enabled=false 显式关闭；"停驻"先把最终位置写回环境再关闭——
 *    位置不跳回预设起点。
 */
class JoystickOverlayService : Service() {

    companion object {
        const val ACTION_START = "dev.ven11.joystick.START"
        const val ACTION_STOP = "dev.ven11.joystick.STOP"
        const val ACTION_STOP_PARK = "dev.ven11.joystick.STOP_PARK"

        const val EXPIRES_MS = 6000L
        const val HEARTBEAT_MS = 3000L
        const val INPUT_PUBLISH_MS = 250L
        const val TICK_MS = 100L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        private const val CHANNEL_ID = "ven11_joystick"
        private const val PREFS = "joystick_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val NOTIF_ID = 1101
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var overlayRoot: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var joystickView: JoystickView? = null

    // ---- 以下状态仅主线程访问（handler tick + 触摸回调） ----
    private var curLat = 0.0
    private var curLon = 0.0
    private var inputNx = 0f
    private var inputNy = 0f
    private var vNorth = 0.0
    private var vEast = 0.0
    private var epoch = 0L
    private var anchoredAt = 0L
    private var lastPublishAt = 0L
    private var lastPublishedVn = Double.NaN
    private var lastPublishedVe = Double.NaN
    private var lastTickNs = 0L
    private var overlayAdded = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!overlayAdded) return
            tick()
            handler.postDelayed(this, nextTickDelay())
        }
    }

    /**
     * 摇杆松开且零速度已发布后，位置不再变化，只需按心跳续期：此时不再 100ms 空转，
     * 直接睡到下次心跳；重新推动摇杆时 [wakeTick] 立即恢复 100ms 节拍。
     */
    private fun isIdle(): Boolean =
        inputNx == 0f && inputNy == 0f && lastPublishedVn == 0.0 && lastPublishedVe == 0.0

    private fun nextTickDelay(): Long {
        if (!isIdle()) return TICK_MS
        val untilHeartbeat = HEARTBEAT_MS - (SystemClock.elapsedRealtime() - lastPublishAt)
        return untilHeartbeat.coerceAtLeast(TICK_MS)
    }

    /** 从空闲长睡中唤醒：积分起点重置为现在，避免把空闲时长当作移动时间 */
    private fun wakeTick() {
        if (!overlayAdded || !isIdle()) return
        lastTickNs = System.nanoTime()
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInternal(park = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_PARK -> {
                stopInternal(park = true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    // startForegroundService 启动的服务必须先 startForeground 才能 stopSelf，
                    // 否则触发系统 5s 超时异常
                    startForeground(NOTIF_ID, buildNotification())
                    Toast.makeText(this, "缺少\"显示在其他应用上层\"权限", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (overlayAdded) return START_NOT_STICKY // 已在运行，忽略重复启动
                startOverlay()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- 启动

    private fun startOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val env = ConfigBus.state.value.env
        curLat = env.lat
        curLon = env.lon
        vNorth = 0.0
        vEast = 0.0
        inputNx = 0f
        inputNy = 0f
        epoch = 1L
        lastTickNs = System.nanoTime()

        val root = buildOverlay()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 恢复上次拖到的位置（首次用默认位置）
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            // 屏幕尺寸可能变了（换方向/分辨率），粗略夹回屏幕内
            val dm = resources.displayMetrics
            val keep = (48 * dm.density).toInt()
            x = prefs.getInt(KEY_X, 40).coerceIn(0, (dm.widthPixels - keep).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, 240).coerceIn(0, (dm.heightPixels - keep).coerceAtLeast(0))
        }
        wm.addView(root, lp)
        overlayRoot = root
        params = lp
        overlayAdded = true

        startForeground(NOTIF_ID, buildNotification())
        publishJoystick(reanchor = true, bumpEpoch = false)
        handler.postDelayed(tickRunnable, TICK_MS)
        _running.value = true
    }

    /** 拖动把手 + 关闭按钮 + 摇杆本体，纯代码构建（app 无 res 目录） */
    private fun buildOverlay(): View {
        val density = resources.displayMetrics.density
        val px = { dp: Int -> (dp * density).toInt() }

        val handle = TextView(this).apply {
            text = "⠿ 摇杆 · 拖动   "
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(px(12), px(6), px(12), px(6))
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(px(12), px(6), px(12), px(6))
            setOnClickListener { stopInternal(park = false) }
        }
        val handleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(170, 20, 20, 24))
            addView(handle)
            addView(close)
        }
        // 把手拖动窗口。监听必须挂在 handle 上：ViewGroup 先把触摸派给子 View，
        // 子 View 消费了 DOWN（此前 handle 挂了空点击监听），父行的监听就收不到 MOVE
        val drag = DragListener()
        handle.setOnTouchListener(drag)
        handleRow.setOnTouchListener(drag) // 把手行的空白处也能拖

        val stick = JoystickView(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(132), px(132))
            onMove = { nx, ny ->
                if (nx != 0f || ny != 0f) wakeTick() // 先判空闲（用旧输入），再写新输入
                inputNx = nx
                inputNy = ny
            }
        }
        joystickView = stick

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(90, 20, 20, 24))
            setPadding(px(4), px(4), px(4), px(4))
            addView(handleRow, LinearLayout.LayoutParams(-1, -2))
            addView(stick)
        }
    }

    /**
     * 按按下时的窗口位置 + 手指总位移算目标位置（不累加增量，不会漂移）；
     * MOVE 事件可达 120Hz，而 updateViewLayout 每次都是一次跨进程调用，
     * 所以每帧最多提交一次。松手时把位置存下来，下次打开悬浮窗还在原处。
     */
    private inner class DragListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var targetX = 0
        private var targetY = 0
        private var updatePosted = false

        private val applyUpdate = Runnable {
            updatePosted = false
            val lp = params ?: return@Runnable
            val root = overlayRoot ?: return@Runnable
            if (lp.x == targetX && lp.y == targetY) return@Runnable
            lp.x = targetX
            lp.y = targetY
            try {
                wm.updateViewLayout(root, lp)
            } catch (_: Throwable) {
            }
        }

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val lp = params ?: return false
            val root = overlayRoot ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    downX = lp.x
                    downY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val (x, y) = clampToScreen(
                        downX + (ev.rawX - downRawX).toInt(),
                        downY + (ev.rawY - downRawY).toInt(),
                        root,
                    )
                    targetX = x
                    targetY = y
                    if (!updatePosted) {
                        updatePosted = true
                        root.postOnAnimation(applyUpdate)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (updatePosted) {
                        root.removeCallbacks(applyUpdate)
                        applyUpdate.run()
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                }
                else -> return false
            }
            return true
        }
    }

    /** 至少留把手的一截在屏幕内，避免拖出屏幕后找不回来 */
    private fun clampToScreen(x: Int, y: Int, root: View): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val keep = (48 * dm.density).toInt()
        val maxX = (dm.widthPixels - keep).coerceAtLeast(0)
        val minX = -(root.width - keep).coerceAtLeast(0)
        val maxY = (dm.heightPixels - keep).coerceAtLeast(0)
        return x.coerceIn(minX, maxX) to y.coerceIn(0, maxY)
    }

    // ---------------------------------------------------------------- tick

    private fun tick() {
        val nowNs = System.nanoTime()
        val dtSec = ((nowNs - lastTickNs) / 1_000_000_000.0).coerceIn(0.0, 1.0)
        lastTickNs = nowNs

        val speed = ConfigBus.state.value.joystickSpeedMps.coerceAtLeast(0.0)
        vNorth = inputNy * speed
        vEast = inputNx * speed

        // 积分推进（米 → 度）
        curLat += vNorth * dtSec / 111_320.0
        curLon += vEast * dtSec /
            (111_320.0 * cos(Math.toRadians(curLat)).coerceAtLeast(1e-6))

        val now = SystemClock.elapsedRealtime()
        val velocityChanged = !(vNorth == lastPublishedVn && vEast == lastPublishedVe)
        when {
            now - lastPublishAt >= HEARTBEAT_MS ->
                publishJoystick(reanchor = true, bumpEpoch = false)
            velocityChanged && now - lastPublishAt >= INPUT_PUBLISH_MS ->
                publishJoystick(reanchor = true, bumpEpoch = true)
            else -> {
                // 未发布也把最新积分位置刷给 UI（展示用；anchored/expires 沿用上次发布）
                val last = ConfigBus.joystick.value
                if (last != null && last.active) {
                    ConfigBus.setJoystick(
                        last.copy(lat = curLat, lon = curLon, vNorthMps = vNorth, vEastMps = vEast),
                    )
                }
            }
        }
    }

    private fun publishJoystick(reanchor: Boolean, bumpEpoch: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (reanchor) anchoredAt = now
        if (bumpEpoch) epoch++
        ConfigBus.setJoystick(
            JoystickLive(
                active = true,
                lat = curLat,
                lon = curLon,
                vNorthMps = vNorth,
                vEastMps = vEast,
                anchoredAtElapsedMs = anchoredAt,
                expiresAtElapsedMs = anchoredAt + EXPIRES_MS,
                epoch = epoch,
            ),
        )
        ConfigBus.publishAsync(record = false)
        lastPublishAt = now
        lastPublishedVn = vNorth
        lastPublishedVe = vEast
    }

    // ---------------------------------------------------------------- 停止

    private fun stopInternal(park: Boolean) {
        if (!overlayAdded) {
            stopSelf()
            return
        }
        handler.removeCallbacks(tickRunnable)
        overlayAdded = false
        inputNx = 0f
        inputNy = 0f
        vNorth = 0.0
        vEast = 0.0

        if (park) {
            // 停驻：最终位置写回环境（速度/朝向归零），摇杆关停后静态环境即该位置
            ConfigBus.update { c ->
                c.copy(env = c.env.copy(lat = curLat, lon = curLon, speed = 0f, bearing = 0f))
            }
        }
        // 显式关闭 joystick 段（否则模块里旧段要等 6s 过期才失效）
        val last = ConfigBus.joystick.value
        if (last != null) {
            ConfigBus.setJoystick(last.copy(active = false, vNorthMps = 0.0, vEastMps = 0.0))
            ConfigBus.publishAsync(record = false) {
                ConfigBus.setJoystick(null)
            }
        }

        try {
            wm.removeView(overlayRoot)
        } catch (_: Throwable) {
        }
        overlayRoot = null
        params = null
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // 非正常销毁（被系统杀）：悬浮窗必须移除；模块侧 6s 过期兜底，无需补发
        if (overlayAdded) {
            overlayAdded = false
            try {
                wm.removeView(overlayRoot)
            } catch (_: Throwable) {
            }
            _running.value = false
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ven11 摇杆", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, com.example.menuui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JoystickOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val park = PendingIntent.getService(
            this, 2, Intent(this, JoystickOverlayService::class.java).setAction(ACTION_STOP_PARK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val speed = ConfigBus.state.value.joystickSpeedMps
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("ven11 摇杆运行中")
            .setContentText(
                "档位 ${JoystickPresets.byId(ConfigBus.state.value.joystickPresetId).label} " +
                    "(${hypot(speed, 0.0)} m/s) · 在目标应用中查看位置变化",
            )
            .setContentIntent(content)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .addAction(Notification.Action.Builder(null, "停驻", park).build())
            .setOngoing(true)
            .build()
    }
}
