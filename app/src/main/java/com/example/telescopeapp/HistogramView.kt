package com.example.telescopeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.argb(180, 255, 255, 255) // Semi-transparent white
        style = Paint.Style.FILL
    }
    
    private val bgPaint = Paint().apply {
        color = Color.argb(80, 0, 0, 0) // Semi-transparent black background
        style = Paint.Style.FILL
    }

    private var histogramData: IntArray? = null

    fun setHistogramData(data: IntArray) {
        histogramData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val data = histogramData ?: return
        if (data.isEmpty()) return

        val maxCount = data.maxOrNull() ?: 1
        if (maxCount == 0) return

        val barWidth = width.toFloat() / data.size
        
        for (i in data.indices) {
            val barHeight = (data[i].toFloat() / maxCount) * height
            val left = i * barWidth
            val top = height - barHeight
            val right = left + barWidth
            val bottom = height.toFloat()
            
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}
