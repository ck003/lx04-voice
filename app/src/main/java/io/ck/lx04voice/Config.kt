package io.ck.lx04voice

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置：HA 地址、令牌、Assist pipeline。
 *
 * 唤醒词/STT/对话/TTS 全部由 HA 服务端的 pipeline 决定，
 * 本机不持有这些参数。
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

    /** HA 的 Assist pipeline ID */
    var pipelineId: String
        get() = sp.getString("pipeline", "01j0nsxjxe65sh7rejj31bjw09") ?: ""
        set(v) = sp.edit().putString("pipeline", v.trim()).apply()

    var language: String
        get() = sp.getString("language", "zh") ?: "zh"
        set(v) = sp.edit().putString("language", v.trim()).apply()

    val isReady: Boolean
        get() = haUrl.startsWith("http") && token.length > 20
}
