package io.ck.lx04voice

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务：VAD 触发 → 录音 → HA STT → HA 对话 → HA TTS → 播放
 *
 * 本机只做音频录制和 HTTP，不做任何 ML 推理，CPU 占用接近 0。
 */
class ListenerService : Service() {

    companion object {
        private const val TAG = "LX04Voice"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_START = "io.ck.lx04voice.START"
        const val ACTION_STOP = "io.ck.lx04voice.STOP"

        @Volatile var running = false
            private set
        @Volatile var lastText = ""
            private set
        @Volatile var lastReply = ""
            private set
        @Volatile var lastTriggerAt = 0L
            private set
    }

    private lateinit var cfg: Config
    private var thread: Thread? = null
    @Volatile private var active = false
    private var record: AudioRecord? = null
    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        startForeground(NOTIF_ID, buildNotification("正在等待语音"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> if (!active) start()
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "lx04voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "语音监听", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("LX04 语音")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun start() {
        active = true
        running = true
        thread = Thread { loop() }.also { it?.start() }
        Log.i(TAG, "listener started")
    }

    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 10 * 2)  // ~100ms

        try {
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
            record?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord failed: ${e.message}")
            active = false; running = false
            return
        }

        val buf = ShortArray(bufSize)
        var overSince = 0L
        var lastFire = 0L

        while (active) {
            val n = record?.read(buf, 0, buf.size) ?: -1
            if (n <= 0) continue

            var sum = 0.0
            for (i in 0 until n) {
                val s = buf[i].toDouble()
                sum += s * s
            }
            val rms = Math.sqrt(sum / n)
            val now = System.currentTimeMillis()

            if (rms > cfg.threshold) {
                if (overSince == 0L) overSince = now
                if (now - overSince >= cfg.holdMs && now - lastFire > cfg.cooldownMs) {
                    overSince = 0L
                    lastFire = now
                    lastTriggerAt = now
                    // 采集一句话
                    val pcm = captureUtterance(buf)
                    if (pcm != null) {
                        Thread { pipeline(pcm) }.start()
                    }
                }
            } else {
                overSince = 0L
            }
        }

        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        running = false
        Log.i(TAG, "listener stopped")
    }

    /** 从触发点开始录一句话，直到静音或超时 */
    private fun captureUtterance(first: ShortArray): ShortArray? {
        val out = ByteArrayOutputStream()
        // 先写入触发时这一块
        for (s in first) out.write(s.toInt() and 0xFF)
        for (s in first) out.write((s.toInt() shr 8) and 0xFF)

        val chunk = ShortArray(SAMPLE_RATE / 10)  // 100ms
        var silenceMs = 0
        val maxMs = cfg.maxRecordMs
        var totalMs = 0

        while (active && totalMs < maxMs) {
            val n = record?.read(chunk, 0, chunk.size) ?: -1
            if (n <= 0) break
            var sum = 0.0
            for (i in 0 until n) {
                val s = chunk[i].toDouble(); sum += s * s
            }
            val rms = Math.sqrt(sum / n)
            for (i in 0 until n) {
                out.write(chunk[i].toInt() and 0xFF)
                out.write((chunk[i].toInt() shr 8) and 0xFF)
            }
            totalMs += 100
            if (rms < cfg.threshold * 0.5) {
                silenceMs += 100
                if (silenceMs >= cfg.silenceMs && totalMs > 1000) break
            } else silenceMs = 0
        }
        val bytes = out.toByteArray()
        if (bytes.size < SAMPLE_RATE) return null  // 太短，丢弃
        // 转回 ShortArray 供 WAV 封装
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = (bytes[i * 2 + 1].toInt() and 0xFF) shl 8
            shorts[i] = (lo or hi).toShort()
        }
        return shorts
    }

    /** 封装 16kHz/16bit/单声道 WAV */
    private fun toWav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
        out.write("RIFF".toByteArray()); le32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(dataLen)
        for (s in pcm) le16(s.toInt())
        return out.toByteArray()
    }

    private fun http(path: String, body: ByteArray?, contentType: String, extra: Map<String, String> = emptyMap()): String? {
        return try {
            val conn = (URL("${cfg.haUrl}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                setRequestProperty("Authorization", "Bearer ${cfg.token}")
                setRequestProperty("Content-Type", contentType)
                for ((k, v) in extra) setRequestProperty(k, v)
                connectTimeout = 8000
                readTimeout = 60000
                if (body != null) doOutput = true
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code in 200..299) text else { Log.e(TAG, "HTTP $code: $text"); null }
        } catch (e: Exception) {
            Log.e(TAG, "http $path failed: ${e.message}")
            null
        }
    }

    /** 完整链路 */
    private fun pipeline(pcm: ShortArray) {
        val t0 = System.currentTimeMillis()
        try {
            // 1. STT
            notify("识别中…")
            val wav = toWav(pcm)
            val sttResp = http("/api/stt/${cfg.sttEngine}", wav, "audio/wav",
                mapOf("X-Speech-Content" to
                    "format=wav; codec=pcm; sample_rate=16000; bit_rate=16; channel=1; language=${cfg.language}"))
                ?: run { notify("识别失败"); return }
            val text = extractJson(sttResp, "text")?.trim() ?: ""
            if (text.isEmpty()) { notify("没听清"); return }
            lastText = text
            Log.i(TAG, "STT: $text (${System.currentTimeMillis() - t0}ms)")

            // 2. 去掉唤醒词前缀
            var query = text
            for (w in cfg.wakeWords) {
                if (query.contains(w)) { query = query.replace(w, "").trim(); break }
            }
            if (query.isEmpty()) { notify("请说指令"); return }

            // 3. 对话
            notify(query)
            val convResp = http("/api/conversation/process",
                """{"text":${jsonStr(query)},"language":"${cfg.language}","agent_id":"${cfg.conversationAgent}"}""".toByteArray(),
                "application/json") ?: run { notify("对话失败"); return }
            val speech = extractJson(convResp, "speech")?.trim() ?: ""
            if (speech.isEmpty()) { notify("没有回复"); return }
            lastReply = speech
            Log.i(TAG, "REPLY: $speech")

            // 4. TTS
            notify(speech)
            val ttsResp = http("/api/tts_get_url",
                """{"engine_id":"${cfg.ttsEngine}","message":${jsonStr(speech)},"cache":true}""".toByteArray(),
                "application/json") ?: return
            val mp3 = extractJson(ttsResp, "url") ?: return

            // 5. 播放
            play(mp3)
            Log.i(TAG, "done in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.e(TAG, "pipeline error: ${e.message}")
            notify("出错了")
        }
    }

    private fun play(url: String) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(url)
                setOnCompletionListener { notify("就绪") }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "play failed: ${e.message}")
        }
    }

    private fun extractJson(json: String, key: String): String? {
        // 轻量解析：找 "key":"..." 或 "key": "..."
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        var i = json.indexOf(':', idx + key.length + 2)
        if (i < 0) return null
        i++
        while (i < json.length && json[i] == ' ') i++
        if (i >= json.length) return null
        if (json[i] == '"') {
            i++
            val sb = StringBuilder()
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i + 1 < json.length) {
                    i++
                    when (json[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        else -> sb.append(json[i])
                    }
                } else sb.append(json[i])
                i++
            }
            return sb.toString()
        }
        // 数字或字面量
        val sb = StringBuilder()
        while (i < json.length && json[i] !in charArrayOf(',', '}', ' ')) {
            sb.append(json[i]); i++
        }
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.append("\"").toString()
    }

    override fun onDestroy() {
        active = false
        thread?.join(2000)
        try { player?.release() } catch (_: Exception) {}
        running = false
        super.onDestroy()
    }
}
