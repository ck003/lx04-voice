package io.ck.lx04voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SetupActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var thrEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "LX04 Voice — 轻量语音触发"
            textSize = 20f
        })

        root.addView(TextView(this).apply {
            text = "唤醒词和识别都在 HA 服务端完成，本机只检测「有没有人说话」。"
            textSize = 12f
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(TextView(this).apply { text = "HA 地址"; textSize = 13f })
        urlEdit = EditText(this).apply {
            setText(cfg.haUrl); hint = "http://192.168.1.191:8123"; textSize = 14f
        }
        root.addView(urlEdit)

        root.addView(TextView(this).apply { text = "长期访问令牌"; textSize = 13f })
        tokenEdit = EditText(this).apply {
            setText(cfg.token); hint = "HA 个人资料 → 长期访问令牌"; textSize = 12f
        }
        root.addView(tokenEdit)

        root.addView(TextView(this).apply { text = "灵敏度阈值（越小越灵敏，默认 1200）"; textSize = 13f })
        thrEdit = EditText(this).apply {
            setText(cfg.threshold.toString()); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(thrEdit)

        val saveBtn = Button(this).apply { text = "保存配置" }
        saveBtn.setOnClickListener {
            cfg.haUrl = urlEdit.text.toString()
            cfg.token = tokenEdit.text.toString()
            thrEdit.text.toString().toIntOrNull()?.let { cfg.threshold = it }
            toast("已保存")
            refresh()
        }
        root.addView(saveBtn)

        startBtn = Button(this).apply { text = "启动监听" }
        startBtn.setOnClickListener {
            if (!cfg.isReady) { toast("请先填写 HA 地址和令牌"); return@setOnClickListener }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            } else {
                startService(Intent(this, ListenerService::class.java).apply {
                    action = ListenerService.ACTION_START
                })
                toast("监听已启动")
                refresh()
            }
        }
        root.addView(startBtn)

        val stopBtn = Button(this).apply { text = "停止监听" }
        stopBtn.setOnClickListener {
            startService(Intent(this, ListenerService::class.java).apply {
                action = ListenerService.ACTION_STOP
            })
            toast("已停止"); refresh()
        }
        root.addView(stopBtn)

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        root.addView(statusText)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val sb = StringBuilder()
        sb.append(if (ListenerService.running) "状态：监听中 ✅" else "状态：已停止")
        sb.append("\n配置：")
        sb.append(if (cfg.isReady) "完整 ✅" else "不完整 ❌")
        sb.append("\n阈值：").append(cfg.threshold)
        if (ListenerService.lastTriggerAt > 0) {
            val ago = (System.currentTimeMillis() - ListenerService.lastTriggerAt) / 1000
            sb.append("\n上次触发：").append(ago).append(" 秒前")
        }
        statusText.text = sb.toString()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
