package com.example.yolo_view
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import android.util.Log

class YoloDetector(private val context: Context) {
    companion object {
        const val MODEL_INPUT_WIDTH = 640
        const val MODEL_INPUT_HEIGHT = 640 // Было 480, изменяем на 640
    }

    private var interpreter: Interpreter? = null
    private val modelInputSize = 640 // Стандартный размер для YOLOv8
    private var numClasses = 0 // Будет определено при загрузке модели
    private var labels = listOf<String>() // Метки классов

    fun loadModel(modelPath: String, customLabels: List<String>? = null) {
        try {
            val file = File(modelPath)
            if (!file.exists()) {
                throw IllegalStateException("Файл модели не найден: $modelPath")
            }

            val options = Interpreter.Options().apply {
                setNumThreads(1)
            }

            interpreter = Interpreter(file, options)

            // Добавляем подробную информацию о тензорах
            val inputTensor = interpreter?.getInputTensor(0)
            val outputTensor = interpreter?.getOutputTensor(0)

            val debugInfo = """
                Input tensor: 
                - shape: ${inputTensor?.shape()?.contentToString()}
                - dataType: ${inputTensor?.dataType()}
                Output tensor:
                - shape: ${outputTensor?.shape()?.contentToString()}
                - dataType: ${outputTensor?.dataType()}
            """.trimIndent()

            Log.d("YOLODetector", debugInfo)

            // Получаем информацию о форме выходных данных
            val outputShape = outputTensor?.shape()

            // Определяем количество классов из формы выходного тензора
            // YOLOv8 выход: [1, num_classes + 4, num_boxes]
            numClasses = (outputShape?.get(1) ?: 84) - 4

            // Используем пользовательские метки или создаем числовы метки
            labels = customLabels?.take(numClasses) ?:
                    List(numClasses) { "class_$it" }

            Log.d("YOLODetector", "Model loaded successfully. Classes: $numClasses")
            Log.d("YOLODetector", "Output shape: ${outputShape?.contentToString()}")
            Log.d("YOLODetector", "Labels: $labels")
        } catch (e: Exception) {
            Log.e("YOLODetector", "Error loading model", e)
            throw RuntimeException("Ошибка загрузки модели: ${e.message}")
        }
    }

    fun detect(imageProxy: ImageProxy): List<Detection> {
        val interpreter = interpreter ?: run {
            Log.w("YOLODetector", "Interpreter не инициализирован")
            return emptyList()
        }

        try {
            // Преобразуем imageProxy в Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)

            // Выполняем центрированный кроп с нужным соотношением сторон
            val centerCroppedBitmap = getCenterCrop(bitmap, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT)

            // Масштабируем изображение до размера модели
            val scaledBitmap = Bitmap.createScaledBitmap(
                centerCroppedBitmap,
                MODEL_INPUT_WIDTH,
                MODEL_INPUT_HEIGHT,
                true
            )

            val inputArray = preprocessImage(scaledBitmap)
            val outputArray = Array(1) { Array(numClasses + 4) { FloatArray(8400) } }

            interpreter.run(inputArray, outputArray)

            // Преобразуем координаты обратно с учетом кропа
            val detections = postprocessDetections(outputArray[0], centerCroppedBitmap.width, centerCroppedBitmap.height)

            return detections
        } catch (e: Exception) {
            Log.e("YOLODetector", "Ошибка при детекции", e)
            return emptyList()
        }
    }

    private fun getCenterCrop(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val targetRatio = targetWidth.toFloat() / targetHeight // Теперь это 1:1

        val cropWidth: Int
        val cropHeight: Int
        val x: Int
        val y: Int

        if (sourceRatio > targetRatio) {
            // Изображение шире, чем нужно
            cropHeight = sourceHeight
            cropWidth = (sourceHeight * targetRatio).toInt()
            x = (sourceWidth - cropWidth) / 2
            y = 0
        } else {
            // Изображение выше, чем нужно
            cropWidth = sourceWidth
            cropHeight = (sourceWidth / targetRatio).toInt()
            x = 0
            y = (sourceHeight - cropHeight) / 2
        }

        return Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight)
    }


    private fun postprocessDetections(
        outputArray: Array<FloatArray>,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val confidenceThreshold = 0.1f

        try {
            Log.d("YOLODetector", "Начало postprocess. Размер outputArray: ${outputArray.size}")

            // Проверяем первые значения
            for (i in 0 until minOf(4, outputArray.size)) {
                val maxVal = outputArray[i].maxOrNull() ?: 0f
                Log.d("YOLODetector", "Layer $i max value: $maxVal")
            }

            // Проверяем начения классов
            for (i in 4 until outputArray.size) {
                val maxVal = outputArray[i].maxOrNull() ?: 0f
                if (maxVal > 0.1f) {  // Логируем все значения выше 0.1f
                    Log.d("YOLODetector", "Class ${i-4} max confidence: $maxVal")
                }
            }

            for (i in 0 until 8400) {
                var maxClassScore = 0f
                var maxClassIndex = 0

                // Проверяем все классы для этого бокса
                for (j in 4 until outputArray.size) {
                    val score = outputArray[j][i]
                    if (score > maxClassScore) {
                        maxClassScore = score
                        maxClassIndex = j - 4
                    }
                }

                if (maxClassScore > confidenceThreshold) {
                    // Получаем координаты в пикселях
                    val x = outputArray[0][i] * 640
                    val y = outputArray[1][i] * 640
                    val w = outputArray[2][i] * 640
                    val h = outputArray[3][i] * 640

                    // Преобразуем центр и размеры в координаты углов
                    val left = (x - w/2f)
                    val top = (y - h/2f)
                    val right = (x + w/2f)
                    val bottom = (y + h/2f)

                    detections.add(
                        Detection(
                            label = labels[maxClassIndex],
                            confidence = maxClassScore,
                            boundingBox = RectF(left, top, right, bottom)
                        )
                    )
                }
            }

            Log.d("YOLODetector", "Найдено детекций до NMS: ${detections.size}")
            val finalDetections = nms(detections, 0.45f)
            Log.d("YOLODetector", "Детекций после NMS: ${finalDetections.size}")
            return finalDetections

        } catch (e: Exception) {
            Log.e("YOLODetector", "Ошибка в postprocess", e)
            Log.e("YOLODetector", "Stack trace: ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    private fun nms(detections: List<Detection>, nmsThreshold: Float): List<Detection> {
        // Сортируем по уверенности по убыванию
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<Detection>()

        for (detection in sortedDetections) {
            var overlap = false

            // Проверяем перекрытие с уже выбранными боксами
            for (selected in selectedDetections) {
                val intersection = calculateIntersection(detection.boundingBox, selected.boundingBox)
                val union = calculateUnion(detection.boundingBox, selected.boundingBox)

                if (intersection / union > nmsThreshold) {
                    overlap = true
                    break
                }
            }

            if (!overlap) {
                selectedDetections.add(detection)
            }
        }

        return selectedDetections
    }

    private fun calculateIntersection(box1: RectF, box2: RectF): Float {
        val left = maxOf(box1.left, box2.left)
        val top = maxOf(box1.top, box2.top)
        val right = minOf(box1.right, box2.right)
        val bottom = minOf(box1.bottom, box2.bottom)

        if (left < right && top < bottom) {
            return (right - left) * (bottom - top)
        }
        return 0f
    }

    private fun calculateUnion(box1: RectF, box2: RectF): Float {
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        return area1 + area2 - calculateIntersection(box1, box2)
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


    private fun preprocessImage(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_WIDTH, MODEL_INPUT_HEIGHT, true)
        val input = Array(1) {
            Array(modelInputSize) {
                Array(modelInputSize) {
                    FloatArray(3)
                }
            }
        }

        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE

        for (x in 0 until modelInputSize) {
            for (y in 0 until modelInputSize) {
                val pixel = scaledBitmap.getPixel(x, y)
                input[0][x][y][0] = Color.red(pixel) / 255.0f
                input[0][x][y][1] = Color.green(pixel) / 255.0f
                input[0][x][y][2] = Color.blue(pixel) / 255.0f

                minVal = minOf(minVal, input[0][x][y][0], input[0][x][y][1], input[0][x][y][2])
                maxVal = maxOf(maxVal, input[0][x][y][0], input[0][x][y][1], input[0][x][y][2])
            }
        }

        Log.d("YOLODetector", "Input preprocessing - Min: $minVal, Max: $maxVal")
        return input
    }
}