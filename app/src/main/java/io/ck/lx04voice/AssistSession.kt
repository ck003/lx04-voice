package io.ck.lx04voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Random

/**
 * 最小 WebSocket 客户端（只实现本场景需要的部分）：
 *  - 文本帧发送 JSON
 *  - 二进制帧发送 PCM
 *  - 解析服务端文本帧
 *
 * 不引入 okhttp 等依赖，保证在 compileSdk 27 上能编译。
 */
class AssistSession(
    private val haUrl: String,
    private val token: String,
    private val pipeline: String,
    private val language: String,
    private val onState: (String) -> Unit,
    private val onText: (String) -> Unit,
    private val onReply: (String) -> Unit,
    private val onTtsUrl: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object { private const val TAG = "LX04Voice" }

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: InputStream? = null
    private var din: DataInputStream? = null
    private var msgId = 0
    private var handlerId = 1
    private var audio: AudioRecord? = null
    private var sentFrames = 0
    private var lastLogAt = 0L

    fun run() {
        val uri = URI(haUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/websocket")
        val host = uri.host
        val port = if (uri.port > 0) uri.port else 8123
        socket = Socket(host, port).apply { soTimeout = 120000 }
        out = socket!!.getOutputStream()
        inp = socket!!.getInputStream()

        handshake(host, uri.path)
        onState("已连接")

        // 认证
        readText()
        Log.i(TAG, "auth: token len=${token.length} start=${token.take(12)} end=${token.takeLast(12)}")
        sendText("""{"type":"auth","access_token":"$token"}""")
        val authResp = readText() ?: ""
        Log.i(TAG, "authResp raw: ${authResp.take(200)}")
        if (!authResp.contains("auth_ok")) {
            onError("认证失败: ${authResp.take(120)}")
            close(); return
        }

        // 启动 pipeline，从 wake_word 开始
        handlerId = 1
        msgId = 100
        val runMsg = """{"id":${++msgId},"type":"assist_pipeline/run","start_stage":"wake_word","end_stage":"tts","input":{"sample_rate":16000,"timeout":300},"pipeline":"$pipeline"}"""
        Log.i(TAG, "run: $runMsg")
        sendText(runMsg)

        // 先不录音，等收到 wake_word-start 再开始推流
        var audioStarted = false

        while (true) {
            val raw = try { readText() } catch (e: Exception) { break }
            if (raw == null) break
            if (raw.isBlank()) continue

            val obj = try { JSONObject(raw) } catch (_: Exception) { continue }
            val type = obj.optString("type")
            Log.i(TAG, "msg: ${raw.take(120)}")

            if (type == "result") {
                Log.i(TAG, "result: success=${obj.optBoolean("success")}")
                if (!obj.optBoolean("success")) {
                    val err = obj.optJSONObject("error")
                    onError("pipeline: ${err?.optString("message") ?: "unknown"}")
                    break
                }
                // HA 的 result 只是命令接收确认，真正的 event 在后续帧里，继续读
                continue
            }
            if (type != "event") continue

            val ev = obj.optJSONObject("event") ?: continue
            val evType = ev.optString("type")
            val data = ev.optJSONObject("data") ?: JSONObject()

            when (evType) {
                "run-start" -> {
                    handlerId = data.optJSONObject("runner_data")
                        ?.optInt("stt_binary_handler_id", 1) ?: 1
                    onState("等待唤醒词")
                }
                "wake_word-start" -> {
                    onState("等待唤醒词")
                    if (!audioStarted) { audioStarted = true; startAudio() }
                }
                "wake_word-end" -> {
                    val ww = data.optJSONObject("wake_word_output")
                    onState("已唤醒 ${ww?.optString("wake_word_phrase") ?: ""}")
                }
                "stt-start" -> onState("识别中…")
                "stt-end" -> {
                    val so = data.optJSONObject("stt_output")
                    val t = so?.optString("text")?.trim() ?: ""
                    if (t.isNotEmpty()) onText(t)
                }
                "intent-end" -> {
                    val resp = data.optJSONObject("intent_output")?.optJSONObject("response")
                    val sp = resp?.optJSONObject("speech")?.optJSONObject("plain")
                    val s = sp?.optString("speech")?.trim() ?: ""
                    if (s.isNotEmpty()) onReply(s)
                }
                "tts-end" -> {
                    // 先停录音再放音，否则自己的 TTS 会把自己再次唤醒
                    stopAudio()
                    val t = data.optJSONObject("tts_output")?.optString("url")
                    if (!t.isNullOrEmpty()) onTtsUrl(t)
                }
                "run-end" -> {
                    Log.i(TAG, "run ended")
                    break
                }
                "error" -> {
                    onError("pipeline: ${data.optString("code")}")
                }
            }
        }

        stopAudio()
        close()
    }

    private fun startAudio() {
        // 先释放可能残留的旧录音会话，否则两个 AudioRecord 会互相抢占麦克风
        stopAudio()
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, 3200)

            // 依次尝试音源：CAMCORDER 是 MTK 上通常最"干净"的路径
            val sources = listOf(
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT
            )
            var chosen = -1
            for (src in sources) {
                try {
                    val rec = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize * 4)
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        audio = rec
                        chosen = src
                        Log.i(TAG, "audio source=$src initialized")
                        break
                    } else rec.release()
                } catch (e: Exception) {
                    Log.w(TAG, "source $src failed: ${e.message}")
                }
            }
            if (chosen < 0) { onError("麦克风初始化失败"); return }

            audio?.startRecording()
            Thread {
                val buf = ShortArray(1600)  // 100ms
                while (audio?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = audio?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) continue
                    val bytes = ByteArray(n * 2)
                    var sum = 0.0
                    var peak = 0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        sum += v.toDouble() * v
                        if (Math.abs(v) > peak) peak = Math.abs(v)
                    }
                    sendBinary(bytes, n * 2)
                    sentFrames++
                    val now = System.currentTimeMillis()
                    if (now - lastLogAt > 2000) {
                        lastLogAt = now
                        val rms = Math.sqrt(sum / n)
                        Log.i(TAG, "audio frames=$sentFrames src=$chosen rms=${"%.0f".format(rms)} peak=$peak")
                    }
                }
            }.also { it.isDaemon = true }.start()
        } catch (e: Exception) {
            onError("麦克风失败: ${e.message}")
        }
    }

    private fun stopAudio() {
        try { audio?.stop() } catch (_: Exception) {}
        try { audio?.release() } catch (_: Exception) {}
        audio = null
    }

    private fun handshake(host: String, path: String) {
        val key = ByteArray(16).also { Random().nextBytes(it) }
        val b64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
        val req = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $b64\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        out?.write(req.toByteArray())
        out?.flush()

        // 读到空行为止（用同一个 din，避免丢字节）
        val d = din ?: DataInputStream(inp).also { din = it }
        while (true) {
            val line = readLine(d) ?: throw IOException("handshake closed")
            if (line.isEmpty()) break
        }
    }

    private fun readLine(din: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = din.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.endsWith("\r")) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun sendText(text: String) = sendFrame(0x1, text.toByteArray())

    private fun sendBinary(data: ByteArray, len: Int) {
        // 首字节是 handler id
        val payload = ByteArray(len + 1)
        payload[0] = handlerId.toByte()
        System.arraycopy(data, 0, payload, 1, len)
        sendFrame(0x2, payload)
    }

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        try {
            val o = out ?: return
            val mask = ByteArray(4)
            Random().nextBytes(mask)

            o.write(0x80 or opcode)
            val len = payload.size
            // 客户端帧必须设置 MASK 位 (0x80)
            when {
                len < 126 -> o.write(0x80 or len)
                len < 65536 -> {
                    o.write(0x80 or 126); o.write((len shr 8) and 0xFF); o.write(len and 0xFF)
                }
                else -> {
                    o.write(0x80 or 127)
                    for (i in 7 downTo 0) o.write((len.toLong() shr (8 * i)).toInt() and 0xFF)
                }
            }
            o.write(mask)
            // 掩码加密 payload
            val masked = ByteArray(len)
            for (i in 0 until len) {
                masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            o.write(masked)
            o.flush()
        } catch (e: Exception) {
            Log.e(TAG, "send frame: ${e.message}")
        }
    }

    private fun readText(): String? {
        // 复用同一个 DataInputStream（底层流有状态，每次 new 会丢字节）
        val din = din ?: DataInputStream(inp).also { din = it }
        val b1 = din.read()
        if (b1 == -1) return null
        val opcode = b1 and 0x0F
        val b2 = din.read()
        if (b2 == -1) return null
        val masked = (b2 and 0x80) != 0
        var len = b2 and 0x7F
        if (len == 126) {
            len = din.readUnsignedShort()
        } else if (len == 127) {
            len = din.readLong().toInt()
        }
        val mask = if (masked) ByteArray(4).also { din.readFully(it) } else null
        val payload = ByteArray(len)
        din.readFully(payload)
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return when (opcode) {
            0x1 -> String(payload, Charsets.UTF_8)   // text
            0x8 -> { close(); null }                  // close
            0x9 -> { sendFrame(0xA, payload); "" }    // ping -> pong
            0xA -> ""                                  // pong
            else -> ""
        }
    }

    fun close() {
        try { stopAudio() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; inp = null
    }
}
