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
import java.security.MessageDigest
import java.util.Random

/**
 * 全程走 HA 语音助手。
 *
 * 本机只做两件事：
 *   1. 从麦克风读 16kHz/16bit/单声道 PCM，按 WebSocket 帧推给 HA
 *   2. 播放 HA 返回的 TTS mp3
 *
 * 唤醒词（openWakeWord）、STT、对话、TTS 全部在 HA 服务端（NAS）跑。
 * 本机不做任何 ML 推理。
 */
class ListenerService : Service() {

    companion object {
        private const val TAG = "LX04Voice"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000
        const val ACTION_START = "io.ck.lx04voice.START"
        const val ACTION_STOP = "io.ck.lx04voice.STOP"

        @Volatile var running = false; private set
        @Volatile var lastText = ""; private set
        @Volatile var lastReply = ""; private set
        @Volatile var lastError = ""; private set
        @Volatile var lastTriggerAt = 0L; private set
        @Volatile var state = "idle"; private set
    }

    private lateinit var cfg: Config
    private var thread: Thread? = null
    @Volatile private var active = false
    private var record: AudioRecord? = null
    private var player: MediaPlayer? = null
    private var ws: WebSocketClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        startForeground(NOTIF_ID, buildNotification("启动中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> if (!active) start()
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val ch = "lx04voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(ch, "语音", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, ch)
            .setContentTitle("LX04 语音助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        state = text
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun start() {
        active = true
        running = true
        thread = Thread { runLoop() }.also { it?.start() }
        Log.i(TAG, "service started")
    }

    /** 一个长连接，HA 侧持续等唤醒词；断了就重连 */
    private fun runLoop() {
        while (active) {
            try {
                notify("等待唤醒词")
                AssistSession(
                    haUrl = cfg.haUrl,
                    token = cfg.token,
                    pipeline = cfg.pipelineId,
                    language = cfg.language,
                    onState = ::notify,
                    onText = { lastText = it; lastTriggerAt = System.currentTimeMillis(); Log.i(TAG, "STT: $it") },
                    onReply = { lastReply = it; Log.i(TAG, "REPLY: $it") },
                    onTtsUrl = { play(it) },
                    onError = { lastError = it; Log.e(TAG, "err: $it") }
                ).run()
            } catch (e: Exception) {
                lastError = e.message ?: "unknown"
                Log.e(TAG, "session: ${e.message}")
            }
            if (active) {
                notify("重连中…")
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }
        running = false
    }

    private fun play(url: String) {
        try {
            player?.release()
            val full = if (url.startsWith("http")) url else cfg.haUrl + url
            player = MediaPlayer().apply {
                setDataSource(full)
                setOnCompletionListener { notify("等待唤醒词") }
                prepare()
                start()
            }
            notify("播放回复")
        } catch (e: Exception) {
            Log.e(TAG, "play: ${e.message}")
            notify("播放失败")
        }
    }

    override fun onDestroy() {
        active = false
        try { ws?.close() } catch (_: Exception) {}
        try { thread?.join(3000) } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        running = false
        super.onDestroy()
    }
}
