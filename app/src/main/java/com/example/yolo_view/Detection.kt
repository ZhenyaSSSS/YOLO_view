package com.example.yolo_view
import android.graphics.RectF
data class Detection(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val debugInfo: String = "" // Добавляем поле для отладочной информации 4
)