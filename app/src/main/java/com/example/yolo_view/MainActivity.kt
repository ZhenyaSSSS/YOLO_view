package com.example.yolo_view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.os.Build
import android.provider.Settings
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors
import android.util.Log
import com.example.yolo_view.databinding.ActivityMainBinding
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.ImageFormat
import android.util.Size
import androidx.camera.core.AspectRatio
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var yoloDetector: YoloDetector

    companion object {
        private const val REQUEST_SELECT_MODEL = 1
        private const val SETTINGS_REQUEST_CODE = 2023
    }

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(
                this,
                "Необходимы разрешения для использования приложения",
                Toast.LENGTH_LONG
            ).show()
            checkAndRequestPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        yoloDetector = YoloDetector(this)

        binding.loadModelButton.setOnClickListener {
            selectModel()
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (isVivoDevice()) {
            Log.d("PermissionCheck", "Device is Vivo")
            requiredPermissions.forEach { permission ->
                val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                Log.d("PermissionCheck", "$permission: $isGranted")
            }
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            Log.d("PermissionCheck", "All permissions granted, starting camera")
            startCamera()
        } else {
            Log.d("PermissionCheck", "Missing permissions: ${permissionsToRequest.joinToString()}")
            if (isVivoDevice()) {
                val shouldShowRationale = permissionsToRequest.any {
                    shouldShowRequestPermissionRationale(it)
                }

                if (shouldShowRationale) {
                    openAppSettings()
                } else {
                    permissionLauncher.launch(permissionsToRequest)
                }
            } else {
                permissionLauncher.launch(permissionsToRequest)
            }
        }
    }

    private fun isVivoDevice(): Boolean {
        return Build.BRAND.lowercase().contains("vivo") ||
                Build.MANUFACTURER.lowercase().contains("vivo")
    }

    private fun isAllPermissionsGrantedForVivo(): Boolean {
        return requiredPermissions.all { permission ->
            try {
                val result = ContextCompat.checkSelfPermission(this, permission)
                result == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun openAppSettings() {
        Toast.makeText(
            this,
            "Пожалуйста, предоставьте все разрешения в настройках приложения",
            Toast.LENGTH_LONG
        ).show()

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivityForResult(intent, SETTINGS_REQUEST_CODE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SETTINGS_REQUEST_CODE -> {
                checkAndRequestPermissions()
            }
            REQUEST_SELECT_MODEL -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        val inputStream = contentResolver.openInputStream(uri)
                        val file = File(filesDir, "model.tflite")

                        inputStream?.use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        yoloDetector.loadModel(
                            file.absolutePath
                        )
                        Toast.makeText(this, "Модель загружена", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun selectModel() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_SELECT_MODEL)
    }
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - 4.0 / 3.0) <= abs(previewRatio - 16.0 / 9.0)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    private fun startCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()

                    val screenAspectRatio = aspectRatio(binding.viewFinder.width, binding.viewFinder.height)

                    val preview = Preview.Builder()
                        .setTargetResolution(Size(binding.viewFinder.width, binding.viewFinder.height))
                        .build()

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setTargetResolution(Size(binding.viewFinder.width, binding.viewFinder.height))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                processImage(imageProxy)
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )

                    preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)

                    binding.viewFinder.scaleX = 1.0f

                    Log.d("CameraStart", "Camera started successfully")
                } catch (e: Exception) {
                    Log.e("CameraStart", "Failed to start camera", e)
                    Toast.makeText(this, "Ошибка запуска камеры: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("CameraStart", "Failed to get camera provider", e)
            Toast.makeText(this, "Ошибка инициализации камеры: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()
    private var fps = 0f

    private fun processImage(imageProxy: ImageProxy) {
        try {
            Log.d("ImageProcessing", """
                Размеры на этапе обработки:
                - ImageProxy: ${imageProxy.width}x${imageProxy.height}
                - ViewFinder: ${binding.viewFinder.width}x${binding.viewFinder.height}
                - DetectionOverlay: ${binding.detectionOverlay.width}x${binding.detectionOverlay.height}
                - Rotation: ${imageProxy.imageInfo.rotationDegrees}°
                - Crop Target: ${YoloDetector.MODEL_INPUT_WIDTH}x${YoloDetector.MODEL_INPUT_HEIGHT}
            """.trimIndent())

            frameCount++
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFpsUpdate >= 1000) {
                fps = frameCount * 1000f / (currentTime - lastFpsUpdate)
                frameCount = 0
                lastFpsUpdate = currentTime
            }

            val detections = yoloDetector.detect(imageProxy)

            runOnUiThread {
                binding.detectionOverlay.updateDetections(
                    detections,
                    YoloDetector.MODEL_INPUT_WIDTH,
                    YoloDetector.MODEL_INPUT_HEIGHT,
                    imageProxy.imageInfo.rotationDegrees // Pass rotation degrees here
                )

                val debugInfo = buildString {
                    appendLine("FPS: %.1f".format(fps))
                    appendLine("Preview: ${binding.viewFinder.width}x${binding.viewFinder.height}")
                    appendLine("Overlay: ${binding.detectionOverlay.width}x${binding.detectionOverlay.height}")
                    appendLine("Image: ${imageProxy.width}x${imageProxy.height}")
                    appendLine("Model Input: ${YoloDetector.MODEL_INPUT_WIDTH}x${YoloDetector.MODEL_INPUT_HEIGHT}")
                    appendLine("Rotation: ${imageProxy.imageInfo.rotationDegrees}°")
                    appendLine("Детекций: ${detections.size}")
                }
                binding.debugOverlay.updateDebugInfo(debugInfo)
            }
        } catch (e: Exception) {
            runOnUiThread {
                binding.debugOverlay.updateDebugInfo(
                    "Ошибка: ${e.message}\n${e.stackTraceToString()}"
                )
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        // Handle rotation
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        return bitmap
    }
}