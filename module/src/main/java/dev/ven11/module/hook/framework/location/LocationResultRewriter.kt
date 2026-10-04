package dev.ven11.module.hook.framework.location

import android.location.Location
import dev.ven11.module.hook.location.LocationFactory
import dev.ven11.module.hook.location.LocationFactory.Decision

/**
 * LocationResult 改写器（方案B ③）：为单个应用生成一份新的 LocationResult，
 * 里面的每个 Location 都是复制后再伪装的；原结果与其中对象一律不动
 * （约束 3：同一个 LocationResult 会依次交给多个应用，改写共享对象会污染）。
 *
 * 全部走 LocationFactory.decide（与客户端同一构造路径：环境 + 抖动/路线/摇杆，
 * 模板取原 Location，mock 标记清除），保证各通道同一秒内坐标一致。
 *
 * 返回 null = 无法整份伪装（模板读取 / 构造 / 决策翻转），调用方透传原结果
 * （约束 1：出错时的默认行为是透传真实值，绝不产出半真半假的混合结果）。
 */
object LocationResultRewriter {

    /** 按调用方策略改写整份结果；null = 透传原结果 */
    fun rewrite(pkg: String, uid: Int, original: Any?): Any? {
        val templates = ServerReflect.locationResultList(original) ?: return null
        if (templates.isEmpty()) return null
        val out = ArrayList<Location>(templates.size)
        for (t in templates) {
            val template = t as? Location ?: return null
            val provider = template.provider ?: "gps"
            // 决策翻转（快照在 Decider 判定后瞬间变化）：整份透传，不做混合改写
            val d = LocationFactory.decide(pkg, uid, provider, template)
            when (d) {
                is Decision.Spoof -> out.add(d.loc)
                else -> return null
            }
        }
        return ServerReflect.createLocationResult(out) ?: return null
    }
}
