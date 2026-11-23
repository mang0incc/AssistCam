package com.mango.helpercam.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.Socket

class VideoClient(private val serverIp: String, private val port: Int = 8888) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val sendMutex = Mutex() // Aynı anda sadece 1 yazma işlemine izin verir

    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            socket = Socket(serverIp, port).apply {
                sendBufferSize = 1024 * 1024
                tcpNoDelay = true
            }
            outputStream = socket?.getOutputStream()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun sendFrame(data: ByteArray, width: Int, height: Int) = withContext(Dispatchers.IO) {
        sendMutex.withLock { // Kilit mekanizması
            try {
                val out = outputStream ?: return@withLock
                val size = data.size

                // 12 Baytlık Header: [Genişlik:4][Yükseklik:4][Veri Boyutu:4]
                out.write(width shr 24); out.write(width shr 16); out.write(width shr 8); out.write(width)
                out.write(height shr 24); out.write(height shr 16); out.write(height shr 8); out.write(height)
                out.write(size shr 24); out.write(size shr 16); out.write(size shr 8); out.write(size)

                // Ham veri
                out.write(data, 0, size)
                out.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
    }
}