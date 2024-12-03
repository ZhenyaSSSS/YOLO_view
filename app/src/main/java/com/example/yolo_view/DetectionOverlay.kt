package com.example.yolo_view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View

class DetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var rotationDegrees: Int = 0
    private var detections: List<Detection> = emptyList()
    private var previewRect: RectF = RectF() // Область обработки
    private val cropAreaPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.YELLOW
        strokeWidth = 2f
    }

    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    fun updateDetections(
        newDetections: List<Detection>,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ) {
        Log.d("DetectionOverlay", """
            Обновление детекций:
            - Размер изображения: ${imageWidth}x${imageHeight} (ratio: ${imageWidth.toFloat() / imageHeight})
            - Размер view: ${width}x${height} (ratio: ${width.toFloat() / height})
            - Количество детекций: ${newDetections.size}
            - Rotation: $rotationDegrees°
        """.trimIndent())

        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.rotationDegrees = rotationDegrees
        detections = newDetections

        // Вычисляем область обработки
        val targetRatio = imageWidth.toFloat() / imageHeight
        val previewRatio = width.toFloat() / height

        val newPreviewRect = if (previewRatio > targetRatio) {
            // Preview шире, чем нужно
            val cropWidth = (height * targetRatio).toInt()
            val x = (width - cropWidth) / 2f
            RectF(x, 0f, x + cropWidth, height.toFloat())
        } else {
            // Preview выше, чем нужно
            val cropHeight = (width / targetRatio).toInt()
            val y = (height - cropHeight) / 2
            RectF(0f, y.toFloat(), width.toFloat(), (y + cropHeight).toFloat())
        }

        // Логируем изменение области отрисовки
        Log.d("DetectionOverlay", """
            Область отрисовки:
            - Старая: $previewRect
            - Новая: $newPreviewRect
            - Изменение: ${if (previewRect != newPreviewRect) "Да" else "Нет"}
        """.trimIndent())

        previewRect = newPreviewRect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        Log.d("DetectionOverlay", """
            onDraw:
            - View размеры: ${width}x${height}
            - Область отрисовки: $previewRect
            - Изображение: ${imageWidth}x${imageHeight}
            - Детекций: ${detections.size}
            - Rotation: $rotationDegrees°
        """.trimIndent())

        if (imageWidth <= 0 || imageHeight <= 0) {
            Log.w("DetectionOverlay", "onDraw: некорректные размеры изображения")
            return
        }

        // Вычисляем параметры кропа
        val sourceRatio = imageWidth.toFloat() / imageHeight
        val targetRatio = YoloDetector.MODEL_INPUT_WIDTH.toFloat() / YoloDetector.MODEL_INPUT_HEIGHT

        val (cropWidth, cropHeight, cropX, cropY) = if (sourceRatio > targetRatio) {
            // Изображение шире, чем нужно
            val width = (imageHeight * targetRatio).toInt()
            val x = (imageWidth - width) / 2
            Quad(width, imageHeight, x, 0)
        } else {
            // Изображение выше, чем нужно
            val height = (imageWidth / targetRatio).toInt()
            val y = (imageHeight - height) / 2
            Quad(imageWidth, height, 0, y)
        }

        // Вычисляем масштаб для преобразования координат
        val scaleX = previewRect.width() / cropWidth.toFloat()
        val scaleY = previewRect.height() / cropHeight.toFloat()

        // Рисуем область обработки
        canvas.drawRect(previewRect, cropAreaPaint)

        for (detection in detections) {
            // Сначала преобразуем координаты относительно кропнутой области
            var relativeLeft = detection.boundingBox.left - cropX
            var relativeRight = detection.boundingBox.right - cropX
            var relativeTop = cropHeight - (detection.boundingBox.bottom - cropY)
            var relativeBottom = cropHeight - (detection.boundingBox.top - cropY)

            // Корректируем координаты в зависимости от вращения
            when (rotationDegrees) {
                90 -> {
                    val temp = relativeLeft
                    relativeLeft = relativeTop
                    relativeTop = imageHeight - relativeRight
                    relativeRight = relativeBottom
                    relativeBottom = imageHeight - temp
                }
                180 -> {
                    val tempLeft = relativeLeft
                    relativeLeft = imageWidth - relativeRight
                    relativeRight = imageWidth - tempLeft
                    val tempTop = relativeTop
                    relativeTop = imageHeight - relativeBottom
                    relativeBottom = imageHeight - tempTop
                }
                270 -> {
                    val temp = relativeLeft
                    relativeLeft = imageHeight - relativeBottom
                    relativeTop = temp
                    relativeRight = imageHeight - relativeTop
                    relativeBottom = relativeLeft
                }
                else -> {
                    // No rotation
                }
            }

            // Масштабируем координаты
            val scaledLeft = relativeLeft * scaleX
            val scaledRight = relativeRight * scaleX
            val scaledTop = relativeTop * scaleY
            val scaledBottom = relativeBottom * scaleY

            val invertedLeft = previewRect.width() - scaledRight
            val invertedRight = previewRect.width() - scaledLeft
            val invertedTop = previewRect.height() - scaledBottom
            val invertedBottom = previewRect.height() - scaledTop

            val mappedLeft = previewRect.left + invertedLeft
            val mappedRight = previewRect.left + invertedRight
            val mappedTop = previewRect.top + invertedTop
            val mappedBottom = previewRect.top + invertedBottom

            val scaledBox = RectF(
                mappedLeft,
                mappedTop,
                mappedRight,
                mappedBottom
            )

            Log.d("DetectionOverlay", """
                Отрисовка бокса:
                - Исходный: ${detection.boundingBox}
                - Относительный: [$relativeLeft, $relativeTop, $relativeRight, $relativeBottom]
                - Масштабированный: $scaledBox
                - Область кропа: [$cropX, $cropY, ${cropX + cropWidth}, ${cropY + cropHeight}]
                - Область обработки: $previewRect
                - Rotation: $rotationDegrees°
            """.trimIndent())

            // Рисуем бокс
            canvas.drawRect(scaledBox, boxPaint)

            // Рисуем текст
            val text = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)

            val padding = 8f
            val textBackground = RectF(
                scaledBox.left,
                scaledBox.top - textBounds.height() - padding * 2,
                scaledBox.left + textBounds.width() + padding * 2,
                scaledBox.top
            )
            canvas.drawRect(textBackground, backgroundPaint)
            canvas.drawText(
                text,
                scaledBox.left + padding,
                scaledBox.top - padding,
                textPaint
            )
        }

        // Дополнительное отображение области, получаемой нейросетью
        drawNeuralNetworkInputArea(canvas, cropX, cropY, cropWidth, cropHeight)
    }

    private fun drawNeuralNetworkInputArea(canvas: Canvas, cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int) {
        val nnPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.GREEN
            strokeWidth = 4f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 20f), 0f)
        }

        // Преобразуем координаты кропа в координаты view
        val scaleX = previewRect.width() / cropWidth.toFloat()
        val scaleY = previewRect.height() / cropHeight.toFloat()

        val left = previewRect.left
        val top = previewRect.top
        val right = previewRect.left + cropWidth * scaleX
        val bottom = previewRect.top + cropHeight * scaleY

        val nnRect = RectF(left, top, right, bottom)
        canvas.drawRect(nnRect, nnPaint)

        Log.d("DetectionOverlay", "Отрисовка области нейросети: $nnRect")
    }

    // Добавьте в начало класса:
    private data class Quad(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int
    )
}