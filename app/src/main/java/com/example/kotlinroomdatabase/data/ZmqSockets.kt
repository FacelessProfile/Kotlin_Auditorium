package com.example.kotlinroomdatabase.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.zeromq.ZContext
import org.zeromq.ZMQ

class ZmqSockets(private val serverAddress: String) {

    suspend fun sendData(data: String): String = withContext(Dispatchers.IO) {
        var context: ZContext? = null
        var socket: ZMQ.Socket? = null

        try {
            context = ZContext()
            socket = context.createSocket(ZMQ.REQ)
            socket.setReceiveTimeOut(10000)
            socket.connect(serverAddress)

            val sendResult = socket.send(data.toByteArray(ZMQ.CHARSET), 0)
            if (!sendResult) {
                return@withContext "{\"status\":\"error\",\"message\":\"Send failed\"}"
            }

            val reply = socket.recv(0)
            if (reply == null) {
                return@withContext "{\"status\":\"error\",\"message\":\"No response\"}"
            }

            String(reply, ZMQ.CHARSET)

        } catch (e: Exception) {
            "{\"status\":\"error\",\"message\":\"${e.message}\"}"
        } finally {
            socket?.close()
            context?.close()
        }
    }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        var context: ZContext? = null
        var socket: ZMQ.Socket? = null

        try {
            context = ZContext()
            socket = context.createSocket(ZMQ.REQ)
            socket.setReceiveTimeOut(5000)

            Log.d("ZMQ_TEST", "Connecting to: $serverAddress")
            socket.connect(serverAddress)
            val testData = "{\"operation\":\"test\"}"
            val sendResult = socket.send(testData.toByteArray(ZMQ.CHARSET), 0)
            if (!sendResult) {
                return@withContext "Send failed"
            }

            val reply = socket.recv(0)
            if (reply == null) {
                return@withContext "No response"
            }

            val response = String(reply, ZMQ.CHARSET)
            "Connected: $response"

        } catch (e: Exception) {
            "Connection error: ${e.message}"
        } finally {
            socket?.close()
            context?.close()
        }
    }

}