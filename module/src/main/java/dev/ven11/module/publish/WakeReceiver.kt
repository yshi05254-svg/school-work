package dev.ven11.module.publish

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 唤醒接收器（空实现）：管理端探测/发布前用它把模块进程拉起来，让
 * [ConfigContentProvider] 可达。ColorOS 等系统限制后台自启时，provider
 * 获取返回 Unknown URL / null cursor（进程无法被 binder 调用拉起），
 * 显式广播仍可拉起；进程起来后 AMS 缓存一段时间，provider 通道随之可用。
 * 无业务逻辑：谁唤醒都无副作用，写侧安全由签名权限保护。
 */
class WakeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WAKE = "dev.ven11.module.action.WAKE"
    }

    override fun onReceive(context: Context?, intent: Intent?) = Unit
}
