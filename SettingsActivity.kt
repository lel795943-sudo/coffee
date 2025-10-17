package com.elibot.coffeeorderapp

// 1. 导入所有必要的 Android 框架类
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// 2. 确保类继承自 AppCompatActivity（关键！）
class SettingsActivity : AppCompatActivity() {

    // 3. 声明控件和客户端实例（若 CoffeeOrderClient 不存在，需先创建该类）
    private lateinit var connectionIndicator: View
    private lateinit var connectionStatus: TextView
    private val coffeeOrderClient = CoffeeOrderClient() // 确保该类在同一包下或已导入

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 加载布局（确保 activity_settings.xml 存在于 res/layout 目录）
        setContentView(R.layout.activity_settings)

        // 4. 获取 SharedPreferences 实例（用于保存 IP 和端口）
        val sharedPreferences: SharedPreferences = getSharedPreferences("Settings", MODE_PRIVATE)
        val editor: SharedPreferences.Editor = sharedPreferences.edit()

        // 5. 获取布局中的所有控件（确保 ID 与 activity_settings.xml 一致）
        val editTextIp: EditText = findViewById(R.id.editText_ip)
        val editTextPort: EditText = findViewById(R.id.editText_port)
        val buttonSave: Button = findViewById(R.id.button_save)
        val buttonTestConnection: Button = findViewById(R.id.button_test_connection)
        connectionIndicator = findViewById(R.id.connection_indicator)
        connectionStatus = findViewById(R.id.connection_status)

        // 6. 从 SharedPreferences 读取已保存的 IP 和端口，填充到输入框
        val savedIp = sharedPreferences.getString("IP", "192.168.1.200")
        val savedPort = sharedPreferences.getString("PORT", "5000")
        editTextIp.setText(savedIp)
        editTextPort.setText(savedPort)

        // 7. 保存按钮点击事件：保存 IP 和端口到 SharedPreferences
        buttonSave.setOnClickListener {
            val ip = editTextIp.text.toString().trim()
            val port = editTextPort.text.toString().trim()

            // 校验输入合法性
            if (ip.isEmpty() || port.isEmpty()) {
                Toast.makeText(this, "请输入有效的 IP 和端口", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存数据并返回主界面
            editor.putString("IP", ip)
            editor.putString("PORT", port)
            editor.apply() // 提交保存（异步，适合轻量数据）
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish() // 关闭当前界面，返回上一级（MainActivity）
        }

        // 8. 测试连接按钮点击事件：验证 IP 和端口是否能连接机器人
        buttonTestConnection.setOnClickListener {
            val ip = editTextIp.text.toString().trim()
            val port = editTextPort.text.toString().trim().toIntOrNull()

            // 校验输入（IP 非空 + 端口是合法数字）
            if (ip.isEmpty() || port == null || port !in 1..65535) {
                Toast.makeText(this, "请输入有效的 IP（如 192.168.1.100）和端口（1-65535）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 显示「正在连接」状态
            connectionIndicator.setBackgroundColor(getColor(R.color.gray))
            connectionStatus.text = "连接中..."

            // 9. 子线程执行网络操作（Android 禁止主线程网络请求）
            Thread {
                try {
                    // 尝试连接机器人
                    coffeeOrderClient.connectToServer(ip, port)
                    // 连接成功：切换到主线程更新 UI（UI 操作必须在主线程）
                    runOnUiThread {
                        connectionIndicator.setBackgroundColor(getColor(R.color.green))
                        connectionStatus.text = "连接成功"
                        Toast.makeText(this, "已成功连接到机器人！", Toast.LENGTH_SHORT).show()
                    }
                    coffeeOrderClient.disconnect() // 连接成功后断开（避免占用资源）

                } catch (e: Exception) {
                    // 连接失败：主线程提示错误
                    runOnUiThread {
                        connectionIndicator.setBackgroundColor(getColor(R.color.red))
                        connectionStatus.text = "连接失败"
                        Toast.makeText(this, "连接失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    // 10. 页面销毁时断开连接，避免内存泄漏
    override fun onDestroy() {
        super.onDestroy()
        coffeeOrderClient.disconnect()
    }
}