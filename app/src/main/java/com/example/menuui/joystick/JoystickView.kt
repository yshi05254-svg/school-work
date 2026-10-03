package com.example.menuui.joystick

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 摇杆控件（经典 View，悬浮窗场景不能直接用 Compose 的廉价方案——这里是
 * WindowManager 直接 addView 的原生视图）：底盘 + 摇杆头，触摸输出归一化方向。
 * 输出约定：nx 东正西负、ny 北正南负（屏幕 y 轴向上取反），模长 0..1（模拟量），
 * 死区 10% 内视为静止。松手回中并输出 (0,0)。
 */
class JoystickView(context: Context) : View(context) {

    /** (nx, ny) 归一化方向，模长 0..1；nx=东，ny=北 */
    var onMove: ((nx: Float, ny: Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 20, 20, 24)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 133, 183, 235)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 133, 183, 235)
        style = Paint.Style.FILL
    }

    private var knobNx = 0f
    private var knobNy = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy)
        canvas.drawCircle(cx, cy, r * 0.96f, basePaint)
        canvas.drawCircle(cx, cy, r * 0.96f, ringPaint)
        canvas.drawCircle(cx + knobNx * r * 0.7f, cy - knobNy * r * 0.7f, r * 0.34f, knobPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_MOVE -> update(event.x, event.y)
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                knobNx = 0f
                knobNy = 0f
                invalidate()
                onMove?.invoke(0f, 0f)
            }
            else -> return false
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        val r = minOf(width / 2f, height / 2f).coerceAtLeast(1f)
        val dx = (x - width / 2f) / r
        val dy = (height / 2f - y) / r // 屏幕向下为负 y → 北为正
        val mag = kotlin.math.hypot(dx, dy)
        if (mag < DEAD_ZONE) {
            if (knobNx != 0f || knobNy != 0f) {
                knobNx = 0f
                knobNy = 0f
                invalidate()
                onMove?.invoke(0f, 0f)
            }
            return
        }
        val clamped = mag.coerceAtMost(1f)
        knobNx = dx / mag * clamped
        knobNy = dy / mag * clamped
        invalidate()
        onMove?.invoke(knobNx, knobNy)
    }

    companion object {
        const val DEAD_ZONE = 0.1f
    }
}
