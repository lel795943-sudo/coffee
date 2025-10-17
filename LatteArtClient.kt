package com.elibot.coffeeorderapp

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.*
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class LatteArtClient(private val app: LatteArtControlActivity) {
    companion object {
        private const val TAG = "LatteArtClient"
        const val RESIZE_SIZE = 800
        const val MAX_BASE64_LENGTH = 30000
        private const val TIMEOUT = 240000L
        private const val PRINT_TIMEOUT = 180000L
        private const val HEARTBEAT_INTERVAL = 2000L
    }

    private var ws: WebSocket? = null
    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
        .build()
    private var connected = false
    private var heartbeatJob: Job? = null
    private var receiveJob: Job? = null
    private val pendingRequests = mutableMapOf<Int, Pair<JSONObject, CompletableDeferred<JSONObject?>>>()
    private var requestId = 0
    private var printInProgress = false
    private var printResult: String? = null
    var machineStatus: String? = null
    var lastBase64: String? = null
    private var lastImagePath: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private suspend fun getLatestImagePath(): String? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED)
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT 1"
        var cursor: Cursor? = null
        try {
            cursor = app.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null, null, sortOrder
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    return@withContext it.getString(dataColumn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询最新图片失败: ${e.message}")
        } finally {
            cursor?.close()
        }
        null
    }

    fun connect(ip: String): Boolean {
        val wsUrl = "ws://$ip:8888/"
        mainHandler.post { app.logMessage("正在连接 $wsUrl...") }
        val request = Request.Builder().url(wsUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                connected = true
                mainHandler.post {
                    app.logMessage("✓ 连接成功")
                    app.updateConnectionStatus(true)
                }
                heartbeatJob = CoroutineScope(Dispatchers.IO).launch { sendHeartbeat() }
                receiveJob = CoroutineScope(Dispatchers.IO).launch { receiveHandler() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Raw WebSocket响应: $text")
                handleResponse(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                mainHandler.post {
                    app.logMessage("❌ 连接失败: ${t.message}")
                    app.updateConnectionStatus(false)
                }
                Log.e(TAG, "WebSocket失败", t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                mainHandler.post { app.updateConnectionStatus(false) }
            }
        }
        client.newWebSocket(request, listener)
        return true
    }

    fun disconnect() {
        connected = false
        heartbeatJob?.cancel()
        receiveJob?.cancel()
        ws?.close(1000, "Normal closure")
        ws = null
        mainHandler.post { app.updateConnectionStatus(false) }
        mainHandler.post { app.logMessage("已断开连接") }
    }

    private suspend fun sendHeartbeat() {
        while (connected) {
            try {
                ws?.send(JSONObject().put("code", 1).put("tag", 0).toString())
                delay(HEARTBEAT_INTERVAL)
            } catch (e: Exception) {
                mainHandler.post { app.logMessage("⚠️ 心跳发送异常: ${e.message}") }
                connected = false
                app.updateConnectionStatus(false)
                break
            }
        }
    }

    private suspend fun receiveHandler() {
        // 实际监听在 onMessage
    }

    private fun handleResponse(text: String) {
        try {
            val response = JSONObject(text)
            val reqId = response.optInt("request_id", -1)
            if (reqId != -1 && pendingRequests.containsKey(reqId)) {
                val (payload, deferred) = pendingRequests.remove(reqId)!!
                deferred.complete(response)
            }
            processResponse(response)
        } catch (e: JSONException) {
            Log.e(TAG, "JSON解析异常: $text", e)
            mainHandler.post { app.logMessage("❌ 响应解析失败: ${e.message} (raw: $text)") }
        } catch (e: Exception) {
            Log.e(TAG, "响应处理异常", e)
            mainHandler.post { app.logMessage("❌ 响应处理失败: ${e.message}") }
        }
    }

    private fun processResponse(response: JSONObject) {
        val code = response.optInt("code")
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        when (code) {
            1 -> processMachineStatusResponse(response)
            2 -> processPrintResponse(response)
            3 -> processPrintInfoResponse(response)
            4 -> processSettingsResponse(response)
            5 -> processTrayStatusResponse(response)
            6 -> processImageValidationResponse(response)
            99 -> processErrorResponse(response)
            else -> mainHandler.post { app.logMessage("⚠️ 未知响应: code=$code, tag=$tag, msg=$msg") }
        }
        if (msg.contains("打印完成") || msg.contains("Printing succeeded")) {
            printInProgress = false
            printResult = msg
            if (msg.contains("Printing succeeded")) app.sendToTcpClient("11")
        }
    }

    private fun processMachineStatusResponse(response: JSONObject) {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        val statusMap = mapOf(
            1 to "✓ 机器空闲可打印",
            2 to "⚠️ 可打印但需更换墨盒",
            3 to "⏳ 固件升级中",
            4 to "❌ 请保持在首页界面",
            5 to "⚙️ 机器运转中"
        )
        val status = statusMap[tag] ?: "未知状态: $tag"
        mainHandler.post { app.logMessage("$status - $msg") }
        machineStatus = status
        app.checkAndSend10IfNeeded()
    }

    private fun processPrintResponse(response: JSONObject) {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        val statusMap = mapOf(
            1 to "✓ 接收成功，开始处理数据",
            2 to "❌ 杯径错误",
            3 to "❌ 处理图片失败",
            4 to "⬆️ 托盘开始上升",
            5 to "⬆️ 托盘上升到顶端",
            6 to "⬇️ 托盘开始下降",
            7 to "⬇️ 托盘下降到低端",
            8 to "✅ 打印成功",
            9 to "⏱️ 打印超时失败",
            10 to "☕ 未检测到杯子",
            11 to "⚠️ 托盘卡住",
            12 to "🖨️ 墨盒上限>打印次数，请更换"
        )
        val status = statusMap[tag] ?: "未知打印状态: $tag"
        mainHandler.post { app.logMessage("$status - $msg") }
        if (tag in 8..12) {
            printResult = status
            printInProgress = false
        }
    }

    private fun processPrintInfoResponse(response: JSONObject): JSONObject? {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        val data = response.optJSONObject("data") ?: return null
        if (tag == 1) {
            val apiCount = data.optString("apiCount", "未知")
            val alertCount = data.optString("alertCount", "未知")
            val currentCount = data.optString("currentCount", "未知")
            mainHandler.post {
                app.logMessage("✓ 打印信息查询成功: $msg")
                app.logMessage("  总打印次数: $apiCount")
                app.logMessage("  墨盒提醒值: $alertCount")
                app.logMessage("  当前墨盒计数: $currentCount")
            }
            return data
        } else {
            mainHandler.post { app.logMessage("❌ 打印信息查询失败: tag=$tag, msg=$msg") }
            return null
        }
    }

    private fun processSettingsResponse(response: JSONObject) {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        when (tag) {
            1 -> mainHandler.post { app.logMessage("✓ 设置墨盒提醒值成功: $msg") }
            2 -> mainHandler.post { app.logMessage("✓ 设置当前打印次数成功: $msg") }
            3 -> mainHandler.post { app.logMessage("✓ 定高设置成功: $msg") }
            4 -> mainHandler.post { app.logMessage("✓ 定高开关设置成功: $msg") }
            5 -> mainHandler.post { app.logMessage("✓ 墨盒信息查询成功: $msg") }
            6 -> mainHandler.post { app.logMessage("✓ 墨盒管控设置成功: $msg") }
            7 -> mainHandler.post { app.logMessage("❌ 非管控模式设置失败: $msg") }
            else -> mainHandler.post { app.logMessage("⚠️ 未知设置响应: tag=$tag, msg=$msg") }
        }
    }

    private fun processTrayStatusResponse(response: JSONObject) {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        val statusMap = mapOf(
            4 to "⬆️ 托盘开始上升",
            5 to "⬆️ 托盘上升到顶端",
            6 to "⬇️ 托盘开始下降",
            7 to "⬇️ 托盘下降到低端",
            8 to "⚠️ 托盘卡住"
        )
        val status = statusMap[tag] ?: "未知托盘状态: $tag"
        mainHandler.post { app.logMessage("$status - $msg") }
    }

    private fun processImageValidationResponse(response: JSONObject) {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        if (tag == 0) {
            mainHandler.post { app.logMessage("⚠️ 未进行校验: $msg") }
        } else if (tag == 1) {
            mainHandler.post { app.logMessage("✓ 图片校验通过: $msg") }
        } else if (tag == 2) {
            mainHandler.post { app.logMessage("❌ 图片校验失败: $msg") }
        } else {
            mainHandler.post { app.logMessage("⚠️ 未知校验响应: tag=$tag, msg=$msg") }
        }
    }

    private fun processErrorResponse(response: JSONObject) {
        val tag = response.optInt("tag")
        val msg = response.optString("msg", "")
        if (tag == 1) {
            mainHandler.post { app.logMessage("❌ 连接终止，多客户端: $msg") }
        } else if (tag == 2) {
            mainHandler.post { app.logMessage("❌ JSON格式错误: $msg") }
        } else {
            mainHandler.post { app.logMessage("⚠️ 未知错误: tag=$tag, msg=$msg") }
        }
    }

    suspend fun sendRequest(payload: JSONObject, expectedCodes: List<Int>? = null): JSONObject? = withContext(Dispatchers.IO) {
        if (!connected) {
            mainHandler.post { app.logMessage("❌ 未连接服务器") }
            return@withContext null
        }

        val id = requestId++
        val deferred = CompletableDeferred<JSONObject?>()
        pendingRequests[id] = Pair(payload, deferred)

        payload.put("request_id", id)
        ws?.send(payload.toString())
        mainHandler.post { app.logMessage("📤 已发送请求: $payload") }

        try {
            val response = withTimeout(TIMEOUT) { deferred.await() }
            if (expectedCodes != null && response?.optInt("code") !in expectedCodes) {
                mainHandler.post { app.logMessage("⚠️ 收到非预期响应码: ${response?.optInt("code")}, 预期: $expectedCodes") }
            }
            return@withContext response
        } catch (e: TimeoutCancellationException) {
            mainHandler.post { app.logMessage("⏱️ 等待响应超时") }
            null
        } catch (e: Exception) {
            mainHandler.post { app.logMessage("❌ 请求异常: ${e.message}") }
            null
        }
    }

    suspend fun getMachineStatus(): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("code", 1); put("tag", 1) }
        val response = sendRequest(payload, listOf(1))
        response?.let { processMachineStatusResponse(it); machineStatus } ?: run {
            mainHandler.post { app.logMessage("❌ 查询机器状态失败") }
            null
        }
    }

    suspend fun validateImageSize(): Boolean = withContext(Dispatchers.IO) {
        if (lastBase64.isNullOrEmpty()) {
            mainHandler.post { app.logMessage("❌ 尚未准备好图片") }
            return@withContext false
        }
        val payload = JSONObject().apply {
            put("code", 6); put("tag", 1); put("data", JSONObject().put("base64Length", lastBase64!!.length))
        }
        val response = sendRequest(payload, listOf(6))
        response?.let {
            processImageValidationResponse(it)
            it.optInt("tag") == 1
        } ?: false
    }

    suspend fun sendPrintData(cupSize: Int): String? = withContext(Dispatchers.IO) {
        if (lastBase64.isNullOrEmpty()) {
            mainHandler.post { app.logMessage("❌ 尚未准备好图片") }
            return@withContext null
        }
        val payload = JSONObject().apply {
            put("code", 2); put("tag", 1)
            put("data", JSONObject().apply { put("size", cupSize); put("img", lastBase64) })
        }
        mainHandler.post { app.logMessage("🖨️ 发送打印请求...") }
        ws?.send(payload.toString())

        printInProgress = true
        printResult = null
        val startTime = System.currentTimeMillis()
        while (printInProgress) {
            if (System.currentTimeMillis() - startTime > PRINT_TIMEOUT) {
                mainHandler.post { app.logMessage("⏱️ 打印任务超时") }
                printInProgress = false
                return@withContext "超时"
            }
            delay(1000)
        }

        printResult
    }

    suspend fun preRaiseTray(): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("code", 2); put("tag", 13) }
        val response = sendRequest(payload, listOf(2, 5))
        response?.let { processTrayStatusResponse(it); it.optString("msg") } ?: null
    }

    suspend fun getPrintInfo(): JSONObject? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("code", 3); put("tag", 1) }
        val response = sendRequest(payload, listOf(3))
        response?.let { processPrintInfoResponse(it) } ?: null
    }

    suspend fun setInkAlert(alertCount: Int): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("code", 4); put("tag", 1)
            put("data", JSONObject().put("alertCount", alertCount))
        }
        val response = sendRequest(payload, listOf(4))
        response?.let { processSettingsResponse(response); response.optInt("tag") in listOf(1, 2, 3, 4, 5, 6) } ?: false
    }

    suspend fun setCurrentCount(currentCount: Int): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("code", 4); put("tag", 2)
            put("data", JSONObject().put("currentCount", currentCount))
        }
        val response = sendRequest(payload, listOf(4))
        response?.let { processSettingsResponse(response); response.optInt("tag") in listOf(1, 2, 3, 4, 5, 6) } ?: false
    }

    suspend fun setFixedHeight(height: Int): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("code", 4); put("tag", 3)
            put("data", JSONObject().put("height", height))
        }
        val response = sendRequest(payload, listOf(4))
        response?.let { processSettingsResponse(response); response.optInt("tag") in listOf(1, 2, 3, 4, 5, 6) } ?: false
    }

    suspend fun setHeightSwitch(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("code", 4); put("tag", 4)
            put("data", JSONObject().put("switch", enable))
        }
        val response = sendRequest(payload, listOf(4))
        response?.let { processSettingsResponse(response); response.optInt("tag") in listOf(1, 2, 3, 4, 5, 6) } ?: false
    }

    suspend fun getInkInfo(): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("code", 4); put("tag", 5) }
        val response = sendRequest(payload, listOf(4))
        response?.let { processSettingsResponse(response); response.optInt("tag") in listOf(1, 2, 3, 4, 5, 6) } ?: false
    }

    suspend fun setCartridgeControl(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("code", 4); put("tag", 6)
            put("data", JSONObject().put("isCartridgeControl", enable))
        }
        val response = sendRequest(payload, listOf(4))
        response?.let { processSettingsResponse(response); response.optInt("tag") in listOf(1, 2, 3, 4, 5, 6) } ?: false
    }

    suspend fun getTrayStatus(): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply { put("code", 5); put("tag", 1) }
        val response = sendRequest(payload, listOf(5))
        response?.let { processTrayStatusResponse(it); it.optString("msg") } ?: null
    }

    suspend fun prepareImage(customPath: String? = null): Boolean = withContext(Dispatchers.IO) {
        var success = false
        try {
            val imgPath = customPath ?: getLatestImagePath()
            if (imgPath.isNullOrEmpty()) {
                mainHandler.post { app.logMessage("❌ 错误：相册中没有图片文件") }
            } else {
                mainHandler.post { app.logMessage("🖼️ 使用图片: ${imgPath.substringAfterLast("/")}}") }
                val originalBitmap = BitmapFactory.decodeFile(imgPath)
                if (originalBitmap != null) {
                    var bitmap = originalBitmap

                    if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.hasAlpha()) {
                        val rgbBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                        val canvasTemp = Canvas(rgbBitmap)
                        canvasTemp.drawColor(Color.WHITE)
                        canvasTemp.drawBitmap(bitmap, 0f, 0f, null)
                        bitmap.recycle()
                        bitmap = rgbBitmap
                    }

                    val resized = Bitmap.createScaledBitmap(bitmap, RESIZE_SIZE, RESIZE_SIZE, true)
                    bitmap.recycle()
                    bitmap = resized

                    val output = Bitmap.createBitmap(RESIZE_SIZE, RESIZE_SIZE, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    canvas.drawColor(Color.WHITE)
                    val centerX = RESIZE_SIZE / 2f
                    val centerY = RESIZE_SIZE / 2f
                    val radius = RESIZE_SIZE / 2f
                    canvas.drawCircle(centerX, centerY, radius, paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                    paint.xfermode = null
                    bitmap.recycle()

                    val displayBitmap = output.copy(Bitmap.Config.ARGB_8888, false)
                    var quality = 95
                    var base64Data: String? = null
                    while (quality >= 10) {
                        val baos = ByteArrayOutputStream()
                        if (output.compress(Bitmap.CompressFormat.JPEG, quality, baos)) {
                            val bytes = baos.toByteArray()
                            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            val length = base64Data.length
                            mainHandler.post { app.logMessage("⚙️ 质量 ${quality}%, Base64 长度: $length") }
                            if (length <= MAX_BASE64_LENGTH) break
                        }
                        quality -= 5
                    }
                    output.recycle()

                    if (base64Data != null) {
                        lastBase64 = "data:image/png;base64,$base64Data"
                        lastImagePath = imgPath
                        mainHandler.post {
                            app.logMessage("✓ 图片准备完成，质量: $quality%, Base64 长度: ${lastBase64!!.length}")
                            app.displayImage(displayBitmap)
                        }
                        success = true
                    } else {
                        mainHandler.post { app.logMessage("❌ 无法将图片压缩至 ${MAX_BASE64_LENGTH} 字符以内") }
                    }
                    displayBitmap.recycle()
                } else {
                    mainHandler.post { app.logMessage("❌ 加载图片失败: $imgPath") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "图片处理异常", e)
            mainHandler.post { app.logMessage("❌ 图片处理错误: ${e.message}") }
        }
        success
    }
}