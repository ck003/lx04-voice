package io.ck.lx04voice

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.app.*
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务：只做 VAD（音量检测），不做任何 ML 推理。
 *
 * 检测到有人在说话 → 调用 HA 的 Assist，唤醒词识别和 ASR 全部在服务端完成。
 * 这样 MT8167A 的 CPU 占用接近于 0。
 */
class ListenerService : Service() {

    companion object {
        private const val TAG = "LX04Voice"
        private const val CH = Notification::class.java.name
        private const val NOTIF_ID = 1001
        const val ACTION_START = "io.ck.lx04voice.START"
        const val ACTION_STOP = "io.ck.lx04voice.STOP"
        @Volatile var running = false
            private set
        @Volatile var lastTriggerAt = 0L
            private set
    }

    private lateinit var cfg: Config
    private var thread: Thread? = null
    @Volatile private var active = false
    private var record: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (!active) start()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "lx04voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "语音监听",
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("LX04 语音监听")
            .setContentText("正在等待语音")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun start() {
        active = true
        running = true
        thread = Thread { loop() }.also { it?.start() }
        Log.i(TAG, "listener started")
    }

    private fun loop() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, sampleRate / 10 * 2)  // ~100ms

        try {
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
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

            // RMS
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
                    lastFire = now
                    lastTriggerAt = now
                    overSince = 0L
                    fire()
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

    /** 调 HA Assist：服务端做唤醒词校验 + ASR + 回复 */
    private fun fire() {
        Thread {
            try {
                val url = URL("${cfg.haUrl}/api/services/assist_satellite/trigger_assist")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer ${cfg.token}")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 15000
                }
                // 触发设备侧的 Assist 会话（HA 会通过 VACA 或自己的 TTS 回应）
                val body = """{"entity_id":"assist_satellite.vaca_xiaomi_lx04_a9aa"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "assist trigger -> $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "assist trigger failed: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        active = false
        thread?.join(2000)
        running = false
        super.onDestroy()
    }
}
