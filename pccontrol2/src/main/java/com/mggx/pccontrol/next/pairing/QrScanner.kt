package com.mggx.pccontrol.next.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class CameraPermissionState { GRANTED, DENIED }
fun cameraPermissionState(granted: Boolean) = if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED

@Composable
fun PairingScannerLauncher(kind: PairingQrKind, label: String = "ESCANEAR CÓDIGO", onValid: (ValidatedQr) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) open = true else error = "La cámara fue denegada. Podés habilitarla en Ajustes o usar la alternativa manual."
    }
    Button(onClick = {
        error = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open = true
        else permission.launch(Manifest.permission.CAMERA)
    }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PairingCameraScreen(kind, onClose = { open = false }, onValid = { open = false; onValid(it) })
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun PairingCameraScreen(kind: PairingQrKind, onClose: () -> Unit, onValid: (ValidatedQr) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
    val completed = remember { AtomicBoolean(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PreviewView(it).also { view -> previewView = view } }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Apuntá la cámara al código MGGX", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
            error?.let { Text(it, color = MaterialTheme.colorScheme.errorContainer) }
            OutlinedButton(onClick = onClose) { Text("CANCELAR") }
        }
    }

    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { imageProxy ->
            val image = imageProxy.image
            if (image == null || completed.get()) { imageProxy.close(); return@setAnalyzer }
            scanner.process(InputImage.fromMediaImage(image, imageProxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    val raw = codes.firstNotNullOfOrNull { it.rawValue } ?: return@addOnSuccessListener
                    validatePairingQr(raw, kind).onSuccess { valid ->
                        if (completed.compareAndSet(false, true)) onValid(valid)
                    }.onFailure { error = it.message ?: "Código inválido" }
                }.addOnFailureListener { error = "No se pudo leer el código. Intentá nuevamente." }
                .addOnCompleteListener { imageProxy.close() }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdownNow() } }
}

fun qrBitmap(payload: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

@Composable fun PairingQr(payload: String, modifier: Modifier = Modifier.fillMaxWidth().height(280.dp)) {
    val bitmap = remember(payload) { qrBitmap(payload) }
    Image(bitmap.asImageBitmap(), contentDescription = "Código QR de vinculación", modifier = modifier)
}
