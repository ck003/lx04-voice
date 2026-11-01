package io.ck.lx04voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*

/**
 * 配置界面：只用 Android 框架 API，零第三方依赖（保证在 compileSdk 27 下能编译）。
 *
 * 唤醒词、STT、对话、TTS 全部由 HA 的 pipeline 决定，这里只填 HA 地址、令牌和 pipeline ID。
 */
class SetupActivity : Activity() {

    private lateinit var cfg: Config
    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var pipeEdit: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "LX04 语音助手"
            textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = "本机只负责推流麦克风和播放回复。唤醒词、识别、对话、合成全部在 HA 服务端跑。"
            textSize = 11f
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(TextView(this).apply { text = "HA 地址"; textSize = 12f })
        urlEdit = EditText(this).apply {
            setText(cfg.haUrl); hint = "http://192.168.1.191:8123"; textSize = 13f
        }
        root.addView(urlEdit)

        root.addView(TextView(this).apply { text = "长期访问令牌"; textSize = 12f })
        tokenEdit = EditText(this).apply {
            setText(cfg.token); hint = "HA 个人资料 → 长期访问令牌"; textSize = 11f
        }
        root.addView(tokenEdit)

        root.addView(TextView(this).apply { text = "Assist Pipeline ID"; textSize = 12f })
        pipeEdit = EditText(this).apply {
            setText(cfg.pipelineId); hint = "设置 → 语音助手 → 选择 pipeline"; textSize = 11f
        }
        root.addView(pipeEdit)

        root.addView(Button(this).apply {
            text = "保存配置"
            setOnClickListener {
                cfg.haUrl = urlEdit.text.toString()
                cfg.token = tokenEdit.text.toString()
                cfg.pipelineId = pipeEdit.text.toString()
                toast("已保存")
                refresh()
            }
        })

        root.addView(Button(this).apply {
            text = "启动"
            setOnClickListener {
                if (!cfg.isReady) { toast("请先填写 HA 地址和令牌"); return@setOnClickListener }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                } else {
                    startService(Intent(this@SetupActivity, ListenerService::class.java).apply {
                        action = ListenerService.ACTION_START
                    })
                    toast("已启动"); refresh()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "停止"
            setOnClickListener {
                startService(Intent(this@SetupActivity, ListenerService::class.java).apply {
                    action = ListenerService.ACTION_STOP
                })
                toast("已停止"); refresh()
            }
        })

        statusText = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad, 0, 0)
        }
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code == 100 && results.isNotEmpty() &&
            results[0] == PackageManager.PERMISSION_GRANTED) {
            startService(Intent(this, ListenerService::class.java).apply {
                action = ListenerService.ACTION_START
            })
            toast("已启动"); refresh()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val sb = StringBuilder()
        sb.append(if (ListenerService.running) "状态：运行中" else "状态：已停止")
        sb.append("\n配置：").append(if (cfg.isReady) "完整" else "不完整（需地址+令牌）")
        sb.append("\n当前：").append(ListenerService.state)
        if (ListenerService.lastText.isNotEmpty())
            sb.append("\n识别：").append(ListenerService.lastText)
        if (ListenerService.lastReply.isNotEmpty())
            sb.append("\n回复：").append(ListenerService.lastReply)
        if (ListenerService.lastError.isNotEmpty())
            sb.append("\n错误：").append(ListenerService.lastError)
        statusText.text = sb.toString()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
