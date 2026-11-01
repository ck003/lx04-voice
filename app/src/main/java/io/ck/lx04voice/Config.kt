package io.ck.lx04voice

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置：HA 地址、令牌、STT/TTS 引擎、灵敏度。
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

    /** HA 的 STT 实体 ID，带 stt. 前缀 */
    var sttEngine: String
        get() = sp.getString("stt_engine",
            "stt.fun_asr_server_http_192_168_1_248_5001") ?: ""
        set(v) = sp.edit().putString("stt_engine", v.trim()).apply()

    var ttsEngine: String
        get() = sp.getString("tts_engine", "tts.edge_tts_2") ?: ""
        set(v) = sp.edit().putString("tts_engine", v.trim()).apply()

    var conversationAgent: String
        get() = sp.getString("conversation_agent", "conversation.deepseek") ?: ""
        set(v) = sp.edit().putString("conversation_agent", v.trim()).apply()

    var language: String
        get() = sp.getString("language", "zh") ?: "zh"
        set(v) = sp.edit().putString("language", v.trim()).apply()

    /** 唤醒词前缀，识别后自动剥离。多个用逗号分隔 */
    var wakeWords: List<String>
        get() = (sp.getString("wake_words", "hey jarvis,小爱同学") ?: "")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("wake_words", v.joinToString(",")).apply()

    /** VAD 触发阈值（RMS），越小越灵敏 */
    var threshold: Int
        get() = sp.getInt("threshold", 1200)
        set(v) = sp.edit().putInt("threshold", v).apply()

    /** 连续多少毫秒超过阈值才算触发 */
    var holdMs: Int
        get() = sp.getInt("hold_ms", 250)
        set(v) = sp.edit().putInt("hold_ms", v).apply()

    /** 触发后冷却，避免重复 */
    var cooldownMs: Int
        get() = sp.getInt("cooldown_ms", 4000)
        set(v) = sp.edit().putInt("cooldown_ms", v).apply()

    /** 单次最长录音时长 */
    var maxRecordMs: Int
        get() = sp.getInt("max_record_ms", 6000)
        set(v) = sp.edit().putInt("max_record_ms", v).apply()

    /** 静音多久算一句话结束 */
    var silenceMs: Int
        get() = sp.getInt("silence_ms", 700)
        set(v) = sp.edit().putInt("silence_ms", v).apply()

    val isReady: Boolean
        get() = haUrl.startsWith("http") && token.length > 20
}
