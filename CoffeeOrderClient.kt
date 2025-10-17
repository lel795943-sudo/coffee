package com.elibot.coffeeorderapp

import java.io.OutputStream
import java.net.Socket

class CoffeeOrderClient {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    // 判断是否已经连接
    fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }

    // 连接机器人 Socket 服务
    fun connectToServer(ip: String, port: Int) {
        if (!isConnected()) {  // ✅ 避免重复连接
            socket = Socket(ip, port)
            outputStream = socket?.getOutputStream()
        }
    }

    // 发送订单指令
    fun sendOrder(orderCode: String) {
        if (!isConnected()) throw IllegalStateException("未连接服务器")
        outputStream?.write(orderCode.toByteArray())
        outputStream?.flush()
    }

    // 断开连接
    fun disconnect() {
        outputStream?.close()
        socket?.close()
        outputStream = null
        socket = null
    }
}
