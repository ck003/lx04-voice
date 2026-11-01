package io.ck.lx04voice

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置：HA 地址 + 长期令牌 + 灵敏度。
 */
class Config(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("lx04voice", Context.MODE_PRIVATE)

    var haUrl: String
        get() = sp.getString("ha_url", "http://192.168.1.191:8123") ?: ""
        set(v) = sp.edit().putString("ha_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** 触发阈值：音量 RMS 超过此值即认为有人在说话 */
    var threshold: Int
        get() = sp.getInt("threshold", 1200)
        set(v) = sp.edit().putInt("threshold", v).apply()

    /** 连续多少毫秒超过阈值才算触发，过滤突发噪声 */
    var holdMs: Int
        get() = sp.getInt("hold_ms", 250)
        set(v) = sp.edit().putInt("hold_ms", v).apply()

    /** 触发后冷却时间，避免重复触发 */
    var cooldownMs: Int
        get() = sp.getInt("cooldown_ms", 6000)
        set(v) = sp.edit().putInt("cooldown_ms", v).apply()

    val isReady: Boolean
        get() = haUrl.startsWith("http") && token.length > 20
}
