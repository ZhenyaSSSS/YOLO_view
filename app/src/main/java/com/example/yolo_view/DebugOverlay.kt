package com.example.yolo_view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View


class DebugOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var debugInfo: String = ""
    private val paint = Paint().apply {
        color = Color.GREEN
        textSize = 40f
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    fun updateDebugInfo(info: String) {
        debugInfo = info
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Разбиваем текст на строки и рисуем каждую отдельно
        var y = 50f
        debugInfo.lines().forEach { line ->
            canvas.drawText(line, 20f, y, paint)
            y += 45f
        }
    }
}