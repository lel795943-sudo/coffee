package com.elibot.coffeeorderapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 初始化Socket通信客户端
    private val coffeeOrderClient = CoffeeOrderClient()

    // 保存 IP 和端口（在 onResume 里更新）
    private var ip: String? = null
    private var port: Int = 8686

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 加载布局
        setContentView(R.layout.activity_main)

        // 复用按钮实例
        val btnAmericano = findViewById<Button>(R.id.button_americano)
        val btnLatte = findViewById<Button>(R.id.button_latte)
        val btnMocha = findViewById<Button>(R.id.button_mocha)
        val btnSettings = findViewById<Button>(R.id.button_settings)
        val btnLatteArt = findViewById<Button>(R.id.button_latte_art)

        // 日志：确认按钮是否成功获取
        Log.d("MainActivity", "Americano Button: $btnAmericano")
        Log.d("MainActivity", "Latte Button: $btnLatte")
        Log.d("MainActivity", "Mocha Button: $btnMocha")
        Log.d("MainActivity", "Settings Button: $btnSettings")
        Log.d("MainActivity", "LatteArt Button: $btnLatteArt")

        // 点击事件 - 美式
        btnAmericano.setOnClickListener {
            if (!isValidConfig()) return@setOnClickListener
            sendOrderToRobot(ip!!, port, "meishi")
        }

        // 点击事件 - 拿铁
        btnLatte.setOnClickListener {
            if (!isValidConfig()) return@setOnClickListener
            sendOrderToRobot(ip!!, port, "natie")
        }

        // 点击事件 - 摩卡
        btnMocha.setOnClickListener {
            if (!isValidConfig()) return@setOnClickListener
            sendOrderToRobot(ip!!, port, "moka")
        }

        // 点击事件 - 设置
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // FIX: 添加拉花控制点击事件
        btnLatteArt.setOnClickListener {
            val intent = Intent(this, LatteArtControlActivity::class.java)
            startActivity(intent)
        }
    }

    // 每次返回界面时读取最新配置
    override fun onResume() {
        super.onResume()
        val sharedPreferences = getSharedPreferences("Settings", MODE_PRIVATE)
        ip = sharedPreferences.getString("IP", "192.168.119.130")
        port = sharedPreferences.getString("PORT", "8686")?.toIntOrNull() ?: 8686
        Log.d("MainActivity", "当前配置：ip=$ip, port=$port")
    }

    /**
     * 校验配置是否有效
     */
    private fun isValidConfig(): Boolean {
        if (ip.isNullOrEmpty() || port <= 0 || port > 65535) {
            Toast.makeText(this, "IP或端口配置无效，请检查设置", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /**
     * 发送订单到机器人（Socket通信，子线程执行）
     */
    private fun sendOrderToRobot(ip: String, port: Int, orderCode: String) {
        Thread {
            try {
                // 如果未连接，才去连接一次
                coffeeOrderClient.connectToServer(ip, port)

                // 发送订单
                coffeeOrderClient.sendOrder(orderCode)

                runOnUiThread {
                    val orderName = when (orderCode) {
                        "meishi" -> "美式咖啡"
                        "natie" -> "拿铁咖啡"
                        "moka" -> "摩卡咖啡"
                        else -> "未知咖啡"
                    }
                    Toast.makeText(this@MainActivity, "${orderName}订单发送成功！", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "发送失败: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "发送失败：${e.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
