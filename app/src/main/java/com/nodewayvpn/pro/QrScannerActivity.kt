package com.nodetun.vpn

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Нативный Activity для сканирования QR-кодов.
 *
 * Использует CameraX для превью камеры и ML Kit Barcode Scanning для
 * распознавания QR-кодов. При обнаружении ссылки (vless://, olcrtc://)
 * завершает Activity с результатом через setResult().
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var barcodeScanner: BarcodeScanner
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanning = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        previewView = PreviewView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(previewView)

        val cameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        )
        if (cameraPermission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: Exception) {
                setResult(RESULT_CANCELED)
                finish()
                return@addListener
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val analyzer = object : ImageAnalysis.Analyzer {
                override fun analyze(imageProxy: ImageProxy) {
                    if (!scanning) {
                        imageProxy.close()
                        return
                    }
                    val mediaImage = imageProxy.image ?: run {
                        imageProxy.close()
                        return
                    }
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    barcodeScanner.process(image).addOnSuccessListener { barcodes ->
                        val barcode = barcodes.firstOrNull()
                        if (barcode != null && barcode.rawValue?.isNotEmpty() == true) {
                            scanning = false
                            imageProxy.close()
                            runOnUiThread {
                                val intent = Intent().putExtra("qr_result", barcode.rawValue!!)
                                setResult(RESULT_OK, intent)
                                finish()
                            }
                            return@addOnSuccessListener
                        }
                        imageProxy.close()
                    }.addOnFailureListener { e ->
                        // Игнорируем ошибки распознавания отдельных кадров
                        imageProxy.close()
                    }
                }
            }

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                scanning = true
            } catch (e: Exception) {
                setResult(RESULT_CANCELED)
                finish()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        scanning = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QR_RESULT = "qr_result"
    }
}
