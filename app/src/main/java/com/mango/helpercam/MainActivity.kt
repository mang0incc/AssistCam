package com.mango.helpercam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mango.helpercam.socket.VideoClient
import com.mango.helpercam.socket.VideoServer
import com.mango.helpercam.ui.camera.CameraStreamPreview
import com.mango.helpercam.ui.qr.QrGenerator
import com.mango.helpercam.ui.qr.QrScannerView
import com.mango.helpercam.ui.theme.HelpercamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HelpercamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionWrapperScreen()
                }
            }
        }
    }
}

fun getDeviceIpAddress(): String {
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress) {
                    val sAddr = addr.hostAddress ?: continue
                    if (sAddr.indexOf(':') < 0 && (sAddr.startsWith("192.168.") || sAddr.startsWith("10.") || sAddr.startsWith("172."))) {
                        return sAddr
                    }
                }
            }
        }
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress) {
                    val sAddr = addr.hostAddress ?: continue
                    if (sAddr.indexOf(':') < 0) return sAddr
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "127.0.0.1"
}

@Composable
fun PermissionWrapperScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        RootScreen()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Kamera izni gereklidir.")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("İzin Ver")
                }
            }
        }
    }
}

@Composable
fun RootScreen() {
    var selectedMode by remember { mutableStateOf<String?>(null) }
    var dualRole by remember { mutableStateOf<String?>(null) }
    var connectedIp by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val myIp = remember { getDeviceIpAddress() }

    // GERİ TUSU YÖNETİMİ
    BackHandler(enabled = selectedMode != null) {
        if (dualRole != null) {
            dualRole = null
            connectedIp = null
        } else {
            selectedMode = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (selectedMode == null) {
            Button(onClick = { selectedMode = "single" }, modifier = Modifier.width(220.dp).padding(8.dp)) {
                Text("Tek Telefon")
            }
            Button(onClick = { selectedMode = "dual" }, modifier = Modifier.width(220.dp).padding(8.dp)) {
                Text("2 Telefon")
            }
        } else if (selectedMode == "dual" && dualRole == null) {
            Button(onClick = { dualRole = "server" }, modifier = Modifier.width(220.dp).padding(8.dp)) {
                Text("Ana Telefon (Görüntüyü Al)")
            }
            Button(onClick = { dualRole = "client" }, modifier = Modifier.width(220.dp).padding(8.dp)) {
                Text("Yardımcı Telefon (Kamerayı Gönder)")
            }
        } else if (selectedMode == "single") {
            CameraStreamPreview(modifier = Modifier.fillMaxSize()) { _, _, _ -> }
        } else if (dualRole == "server") {
            // ==========================================
            // ANA TELEFON (SERVER - GÖRÜNTÜYÜ ALAN)
            // ==========================================
            var isConnected by remember { mutableStateOf(false) }
            var liveBitmap by remember { mutableStateOf<Bitmap?>(null) }
            val mainScope = rememberCoroutineScope()

            DisposableEffect(Unit) {
                val outStream = ByteArrayOutputStream()
                var reusableBitmap: Bitmap? = null

                val server = VideoServer(port = 8888) { frameBytes, size, width, height ->
                    if (!isConnected) {
                        mainScope.launch { isConnected = true }
                    }

                    // GÜVENLİK DUVARI: Boyutlar saçmaysa çökmeyi engelle
                    if (width <= 0 || height <= 0 || width > 1920 || height > 1080) {
                        android.util.Log.e("VideoStream", "Geçersiz boyut algılandı: $width x $height. Frame atlandı.")
                        return@VideoServer
                    }

                    try {
                        outStream.reset()
                        val yuvImage = YuvImage(frameBytes, ImageFormat.NV21, width, height, null)
                        yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, outStream)
                        val jpegBytes = outStream.toByteArray()

                        try {
                            if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) {
                                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                            }

                            val opts = BitmapFactory.Options().apply {
                                inBitmap = reusableBitmap
                                inMutable = true
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }

                            val decodedBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                            if (decodedBmp != null) {
                                mainScope.launch {
                                    liveBitmap = decodedBmp
                                }
                            }
                        } catch (oom: OutOfMemoryError) {
                            android.util.Log.e("VideoStream", "RAM yetmedi (OOM). Frame atlandı.")
                        } catch (e: Exception) {
                            android.util.Log.e("VideoStream", "Bitmap hatası: ${e.message}")
                        }

                    } catch (e: Exception) {
                        android.util.Log.e("VideoStream", "Frame işlenirken hata: ${e.message}", e)
                    }
                }

                val job = mainScope.launch(Dispatchers.IO) {
                    server.start()
                }

                onDispose {
                    job.cancel()
                    server.stop()
                    reusableBitmap?.recycle()
                }
            }

            if (!isConnected) {
                val qrBitmap = remember(myIp) { QrGenerator.createQrBitmap(myIp) }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Yardımcı Telefonla Bu QR'ı Okutun", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(modifier = Modifier.size(220.dp), elevation = CardDefaults.cardElevation(8.dp)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "Server IP QR",
                                    modifier = Modifier.size(200.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Bağlantı Bekleniyor... (IP: $myIp)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (liveBitmap != null) {
                        Image(
                            bitmap = liveBitmap!!.asImageBitmap(),
                            contentDescription = "Canlı Akış",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("Görüntü işleniyor...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

        } else if (dualRole == "client") {
            // ==========================================
            // YARDIMCI TELEFON (CLIENT - GÖNDEREN)
            // ==========================================
            var videoClient by remember { mutableStateOf<VideoClient?>(null) }

            DisposableEffect(Unit) {
                onDispose {
                    videoClient?.disconnect()
                }
            }

            if (connectedIp == null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    QrScannerView(modifier = Modifier.fillMaxSize()) { scannedIp ->
                        connectedIp = scannedIp
                        coroutineScope.launch(Dispatchers.IO) {
                            val client = VideoClient(serverIp = scannedIp, port = 8888)
                            client.connect()
                            videoClient = client
                        }
                    }
                    Text(
                        text = "Ana Telefonun QR Kodunu Okutun",
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    var isSending by remember { mutableStateOf(false) }

                    CameraStreamPreview(modifier = Modifier.fillMaxSize()) { frameBytes, width, height ->
                        if (!isSending) {
                            isSending = true
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    videoClient?.sendFrame(frameBytes, width, height)
                                } finally {
                                    isSending = false
                                }
                            }
                        }
                    }
                    Text(
                        text = "Aktarım Yapılıyor -> $connectedIp",
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                    )
                }
            }
        }
    }
}