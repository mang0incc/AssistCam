package com.mango.helpercam.ui.camera

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraStreamPreview(
    modifier: Modifier = Modifier,
    onFrameReady: (ByteArray, Int, Int) -> Unit
) {
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()

                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(30, 60)
                )

                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(1280, 720)) // OOM hatasını çözmek için 720p
                    .build()
                    .also {
                        it.setAnalyzer(executor) { imageProxy ->
                            try {
                                val width = imageProxy.width
                                val height = imageProxy.height
                                val nv21Bytes = imageProxy.toNv21ByteArray()
                                onFrameReady(nv21Bytes, width, height)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        context as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(context))

            previewView
        }
    )
}

fun ImageProxy.toNv21ByteArray(): ByteArray {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(width * height * 3 / 2)
    val yRaw = ByteArray(ySize)
    yBuffer.get(yRaw)

    var pos = 0
    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        System.arraycopy(yRaw, rowStart, nv21, pos, width)
        pos += width
    }

    val uRaw = ByteArray(uSize)
    val vRaw = ByteArray(vSize)
    uBuffer.get(uRaw)
    vBuffer.get(vRaw)

    val chromaHeight = height / 2
    val chromaWidth = width / 2

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            nv21[pos++] = vRaw[vIndex]
            nv21[pos++] = uRaw[uIndex]
        }
    }

    return nv21
}