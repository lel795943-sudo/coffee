package com.elibot.coffeeorderapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

class LatteArtControlActivity : AppCompatActivity() {
    private lateinit var client: LatteArtClient
    private lateinit var ipEntry: EditText
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button
    private lateinit var send1Btn: Button
    private lateinit var queryStatusBtn: Button
    private lateinit var prepareImageBtn: Button
    private lateinit var selectImageBtn: Button
    private lateinit var validateImageBtn: Button
    private lateinit var cupSizeEntry: EditText
    private lateinit var sendPrintBtn: Button
    private lateinit var preRaiseTrayBtn: Button
    private lateinit var getTrayStatusBtn: Button
    private lateinit var getMachineStatusBtn: Button
    private lateinit var getPrintInfoBtn: Button
    private lateinit var getInkInfoBtn: Button
    private lateinit var alertCountEntry: EditText
    private lateinit var setInkAlertBtn: Button
    private lateinit var currentCountEntry: EditText
    private lateinit var setCurrentCountBtn: Button
    private lateinit var heightEntry: EditText
    private lateinit var setFixedHeightBtn: Button
    private lateinit var heightSwitchCb: CheckBox
    private lateinit var cartridgeControlCb: CheckBox
    private lateinit var imageView: ImageView
    private lateinit var logText: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var tcpStatus: TextView

    private lateinit var sharedPreferences: SharedPreferences

    private var send10Pending = false
    private val tcpStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val clientsCount = intent?.getIntExtra("clients_count", 0) ?: 0
            updateTcpStatus(true, clientsCount)
        }
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("LatteArtControl", "Coroutine异常: ${throwable.message}", throwable)
        logMessage("❌ 操作异常: ${throwable.message}")
    }

    private val maxLogLines = 100

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            data?.data?.let { uri ->
                Log.d("LatteArtControl", "选中URI: $uri")
                if (checkPermission()) {
                    try {
                        val inputStream: InputStream? = contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            Log.d("LatteArtControl", "原始Bitmap加载成功: ${bitmap.width}x${bitmap.height}")
                            val byteArrayOutputStream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, byteArrayOutputStream)
                            val byteArray = byteArrayOutputStream.toByteArray()
                            client.lastBase64 = "data:image/png;base64,${Base64.encodeToString(byteArray, Base64.NO_WRAP)}"
                            logMessage("图片已准备，Base64长度: ${client.lastBase64?.length}")
                            displayImage(bitmap)
                        } else {
                            Log.e("LatteArtControl", "原始Bitmap加载失败")
                            logMessage("❌ Bitmap加载失败")
                        }
                    } catch (e: Exception) {
                        Log.e("LatteArtControl", "InputStream异常: ${e.message}", e)
                        logMessage("❌ 图片加载异常: ${e.message}")
                    }
                } else {
                    logMessage("❌ 存储权限未授予，请在设置中启用")
                }
            } ?: logMessage("❌ 无选中URI")
        } else {
            Log.d("LatteArtControl", "选择取消: resultCode=${result.resultCode}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_latte_art_control)
        client = LatteArtClient(this)

        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences("LatteArtSettings", MODE_PRIVATE)

        // 初始化控件 (假设 XML ID 对应)
        ipEntry = findViewById(R.id.ip_entry)
        connectBtn = findViewById(R.id.connect_btn)
        disconnectBtn = findViewById(R.id.disconnect_btn)
        send1Btn = findViewById(R.id.send_1_btn)
        queryStatusBtn = findViewById(R.id.query_status_btn)
        prepareImageBtn = findViewById(R.id.prepare_image_btn)
        selectImageBtn = findViewById(R.id.select_image_btn)
        validateImageBtn = findViewById(R.id.validate_image_btn)
        cupSizeEntry = findViewById(R.id.cup_size_entry)
        sendPrintBtn = findViewById(R.id.send_print_btn)
        preRaiseTrayBtn = findViewById(R.id.pre_raise_tray_btn)
        getTrayStatusBtn = findViewById(R.id.get_tray_status_btn)
        getMachineStatusBtn = findViewById(R.id.get_machine_status_btn)
        getPrintInfoBtn = findViewById(R.id.get_print_info_btn)
        getInkInfoBtn = findViewById(R.id.get_ink_info_btn)
        alertCountEntry = findViewById(R.id.alert_count_entry)
        setInkAlertBtn = findViewById(R.id.set_ink_alert_btn)
        currentCountEntry = findViewById(R.id.current_count_entry)
        setCurrentCountBtn = findViewById(R.id.set_current_count_btn)
        heightEntry = findViewById(R.id.height_entry)
        setFixedHeightBtn = findViewById(R.id.set_fixed_height_btn)
        heightSwitchCb = findViewById(R.id.height_switch_cb)
        cartridgeControlCb = findViewById(R.id.cartridge_control_cb)
        imageView = findViewById(R.id.image_view)
        logText = findViewById(R.id.log_text)
        connectionStatus = findViewById(R.id.connection_status)

        // 加载保存的参数
        loadPreferences()

        // 事件绑定
        connectBtn.setOnClickListener { connectServer() }
        disconnectBtn.setOnClickListener { disconnectServer() }
        send1Btn.setOnClickListener { send1ToTcp() }
        queryStatusBtn.setOnClickListener { queryStatusFor10() }
        prepareImageBtn.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.prepareImage() } }
        selectImageBtn.setOnClickListener { selectImage() }
        validateImageBtn.setOnClickListener { validateImage() }
        sendPrintBtn.setOnClickListener { sendPrintTask() }
        preRaiseTrayBtn.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.preRaiseTray() } }
        getTrayStatusBtn.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.getTrayStatus() } }
        getMachineStatusBtn.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.getMachineStatus() } }
        getPrintInfoBtn.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.getPrintInfo() } }
        getInkInfoBtn.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.getInkInfo() } }
        setInkAlertBtn.setOnClickListener { setInkAlert() }
        setCurrentCountBtn.setOnClickListener { setCurrentCount() }
        setFixedHeightBtn.setOnClickListener { setFixedHeight() }
        heightSwitchCb.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.setHeightSwitch(heightSwitchCb.isChecked) } }
        cartridgeControlCb.setOnClickListener { lifecycleScope.launch(coroutineExceptionHandler) { client.setCartridgeControl(cartridgeControlCb.isChecked) } }

        // 注册广播

        // 后退按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 请求权限
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 101)
    }

    private fun loadPreferences() {
        ipEntry.setText(sharedPreferences.getString("ip", "192.168.1.124"))
        cupSizeEntry.setText(sharedPreferences.getString("cup_size", "46"))
        alertCountEntry.setText(sharedPreferences.getString("alert_count", ""))
        currentCountEntry.setText(sharedPreferences.getString("current_count", ""))
        heightEntry.setText(sharedPreferences.getString("height", ""))
        heightSwitchCb.isChecked = sharedPreferences.getBoolean("height_switch", false)
        cartridgeControlCb.isChecked = sharedPreferences.getBoolean("cartridge_control", false)
    }

    override fun onPause() {
        super.onPause()
        savePreferences()
    }

    private fun savePreferences() {
        val editor = sharedPreferences.edit()
        editor.putString("ip", ipEntry.text.toString().trim())
        editor.putString("cup_size", cupSizeEntry.text.toString().trim())
        editor.putString("alert_count", alertCountEntry.text.toString().trim())
        editor.putString("current_count", currentCountEntry.text.toString().trim())
        editor.putString("height", heightEntry.text.toString().trim())
        editor.putBoolean("height_switch", heightSwitchCb.isChecked)
        editor.putBoolean("cartridge_control", cartridgeControlCb.isChecked)
        editor.putString("last_base64", client.lastBase64) // 保存图片的 Base64 数据
        editor.apply()
        logMessage("参数已自动保存")
    }

    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun connectServer() {
        val ip = ipEntry.text.toString().trim()
        if (ip.isNotEmpty()) {
            lifecycleScope.launch(coroutineExceptionHandler) { if (client.connect(ip)) logMessage("连接成功") }
        }
    }

    private fun disconnectServer() {
        client.disconnect()
        updateConnectionStatus(false)
        logMessage("已断开连接")
    }

    fun updateConnectionStatus(connected: Boolean) {
        connectionStatus.text = if (connected) "连接状态: 已连接" else "连接状态: 未连接"
        connectBtn.isEnabled = !connected
        disconnectBtn.isEnabled = connected
    }

    private fun send1ToTcp() {
        sendToTcpClient("1")
    }

    private fun queryStatusFor10() {
        send10Pending = true
        logMessage("查询机器状态，准备在空闲时发送10...")
        lifecycleScope.launch(coroutineExceptionHandler) {
            val status = client.getMachineStatus()
            if (status != null && status.contains("机器空闲可打印")) {  // FIX: contains
                checkAndSend10IfNeeded()
            }
        }
    }

    fun checkAndSend10IfNeeded() {
        if (send10Pending && !client.machineStatus.isNullOrEmpty() && client.machineStatus!!.contains("机器空闲可打印")) {
            send10ToTcp()
            send10Pending = false
        }
    }

    private fun send10ToTcp() {
        sendToTcpClient("10")
    }

    fun sendToTcpClient(message: String) {
        try {
            val intent = Intent("SEND_TCP_MESSAGE").apply {
                putExtra("message", message)
            }
            sendBroadcast(intent)
            logMessage("已发送 '$message' 给TCP客户端")
        } catch (e: Exception) {
            Log.e("LatteArtControl", "TCP广播发送失败: ${e.message}", e)
            logMessage("❌ TCP广播发送失败: ${e.message}")
        }
    }

    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        var path: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            if (cursor.moveToFirst()) path = cursor.getString(column)
        }
        return path
    }

    private fun sendPrintTask() {
        val cupSizeStr = cupSizeEntry.text.toString().trim()
        cupSizeStr.toIntOrNull()?.let { cupSize ->
            if (cupSize in 40..100) {
                lifecycleScope.launch(coroutineExceptionHandler) { client.sendPrintData(cupSize) }
            } else logMessage("❌ 杯径必须在40-100mm范围内")
        } ?: logMessage("❌ 请输入有效的杯径数值")
    }

    private fun setInkAlert() {
        alertCountEntry.text.toString().trim().toIntOrNull()?.let { count ->
            lifecycleScope.launch(coroutineExceptionHandler) { client.setInkAlert(count) }
        } ?: logMessage("❌ 请输入有效的提醒值")
    }

    private fun setCurrentCount() {
        currentCountEntry.text.toString().trim().toIntOrNull()?.let { count ->
            lifecycleScope.launch(coroutineExceptionHandler) { client.setCurrentCount(count) }
        } ?: logMessage("❌ 请输入有效的次数值")
    }

    private fun setFixedHeight() {
        heightEntry.text.toString().trim().toIntOrNull()?.let { height ->
            lifecycleScope.launch(coroutineExceptionHandler) { client.setFixedHeight(height) }
        } ?: logMessage("❌ 请输入有效的高度值")
    }

    private fun validateImage() {
        if (!client.lastBase64.isNullOrEmpty()) {
            lifecycleScope.launch(coroutineExceptionHandler) { client.validateImageSize() }
        } else logMessage("❌ 请先准备图片")
    }

    fun logMessage(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = "[$timestamp] $message\n"
        logText.append(newLog)

        if (logText.lineCount > maxLogLines) {
            val start = logText.layout.getLineStart(0)
            val end = logText.layout.getLineEnd(logText.lineCount - maxLogLines)
            val oldText = logText.text
            val newText = oldText.subSequence(end, oldText.length)
            logText.text = newText
        }

        val scrollAmount = logText.layout?.getLineTop(logText.lineCount) ?: 0
        logText.scrollTo(0, scrollAmount)
    }

    fun displayImage(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        Log.d("LatteArtControl", "图片显示在预览区: ${bitmap.width}x${bitmap.height}")
    }

    fun updateTcpStatus(connected: Boolean, clientsCount: Int = 0) {
        tcpStatus.text = if (connected) "TCP状态: 已连接 (${clientsCount}个客户端)" else "TCP状态: 未启动"
        send1Btn.isEnabled = connected
    }
}