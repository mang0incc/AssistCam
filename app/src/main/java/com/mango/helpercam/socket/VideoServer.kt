package com.mango.helpercam.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class VideoServer(private val port: Int = 8888, private val onFrameReceived: (ByteArray, Int, Int, Int) -> Unit) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var isRunning = false

    private val maxFrameSize = 1920 * 1080 * 2
    private val reusableBuffer = ByteArray(maxFrameSize)

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
                receiveBufferSize = 1024 * 1024
            }
            isRunning = true
            while (isRunning) {
                clientSocket = serverSocket?.accept()
                val inputStream: InputStream? = clientSocket?.getInputStream()

                val headerBuffer = ByteArray(12) // 12 Byte Header
                while (isRunning && inputStream != null) {
                    if (!readFully(inputStream, headerBuffer, 12)) break

                    val width = ((headerBuffer[0].toInt() and 0xFF) shl 24) or
                            ((headerBuffer[1].toInt() and 0xFF) shl 16) or
                            ((headerBuffer[2].toInt() and 0xFF) shl 8) or
                            (headerBuffer[3].toInt() and 0xFF)

                    val height = ((headerBuffer[4].toInt() and 0xFF) shl 24) or
                            ((headerBuffer[5].toInt() and 0xFF) shl 16) or
                            ((headerBuffer[6].toInt() and 0xFF) shl 8) or
                            (headerBuffer[7].toInt() and 0xFF)

                    val size = ((headerBuffer[8].toInt() and 0xFF) shl 24) or
                            ((headerBuffer[9].toInt() and 0xFF) shl 16) or
                            ((headerBuffer[10].toInt() and 0xFF) shl 8) or
                            (headerBuffer[11].toInt() and 0xFF)

                    // GÜVENLİK KONTROLÜ: Boyutlar absürt geldiyse çökmesini engelle
                    if (width <= 0 || height <= 0 || width > 3840 || height > 2160 || size <= 0 || size > maxFrameSize) {
                        android.util.Log.e("VideoServer", "Hatalı veri boyutu yakalandı! w=$width, h=$height, size=$size")
                        break
                    }

                    var totalRead = 0
                    while (totalRead < size) {
                        val currentRead = inputStream.read(reusableBuffer, totalRead, size - totalRead)
                        if (currentRead == -1) break
                        totalRead += currentRead
                    }

                    if (totalRead == size) {
                        onFrameReceived(reusableBuffer, size, width, height)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoServer", "Sunucu Hatası: ${e.message}", e)
        } finally {
            stop()
        }
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val read = inputStream.read(buffer, offset, length - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    fun stop() {
        isRunning = false
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}