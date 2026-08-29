package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.github.kr328.clash.design.util.resolveThemedColor
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Small, process-local traffic trend view used by the home screen.
 *
 * MainActivity already polls the live packed traffic value while visible, so this view keeps a
 * bounded minute of those ticks without adding a Binder API or another persistent store.
 */
class TrafficSparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private data class Sample(val upload: Long, val download: Long)

    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)
    private val uploadPath = Path()
    private val downloadPath = Path()
    private val uploadPaint = linePaint(
        context.resolveThemedColor(androidx.appcompat.R.attr.colorControlNormal),
    )
    private val downloadPaint = linePaint(
        context.resolveThemedColor(androidx.appcompat.R.attr.colorPrimary),
    )

    fun append(uploadBytesPerSecond: Long, downloadBytesPerSecond: Long) {
        if (samples.size == MAX_SAMPLES) {
            samples.removeFirst()
        }
        samples.addLast(
            Sample(
                upload = uploadBytesPerSecond.coerceAtLeast(0L),
                download = downloadBytesPerSecond.coerceAtLeast(0L),
            ),
        )
        invalidate()
    }

    fun clear() {
        if (samples.isEmpty()) return
        samples.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (samples.isEmpty() || width <= paddingLeft + paddingRight || height <= 0) return

        val values = samples.toList()
        val maximum = values.fold(1L) { current, sample ->
            max(current, max(sample.upload, sample.download))
        }
        val left = paddingLeft.toFloat() + pointRadius
        val right = (width - paddingRight).toFloat() - pointRadius
        if (right <= left) return
        val top = paddingTop.toFloat() + verticalInset
        val bottom = (height - paddingBottom).toFloat() - verticalInset
        val drawableHeight = (bottom - top).coerceAtLeast(1f)
        val step = if (values.size > 1) {
            (right - left) / (values.size - 1)
        } else {
            0f
        }

        uploadPath.reset()
        downloadPath.reset()
        values.forEachIndexed { index, sample ->
            val x = left + step * index
            val uploadY = bottom - drawableHeight * (sample.upload.toFloat() / maximum.toFloat())
            val downloadY = bottom - drawableHeight * (sample.download.toFloat() / maximum.toFloat())
            if (index == 0) {
                uploadPath.moveTo(x, uploadY)
                downloadPath.moveTo(x, downloadY)
            } else {
                uploadPath.lineTo(x, uploadY)
                downloadPath.lineTo(x, downloadY)
            }
        }

        if (values.size == 1) {
            val sample = values.first()
            canvas.drawCircle(left, sampleY(sample.upload), pointRadius, uploadPaint)
            canvas.drawCircle(left, sampleY(sample.download), pointRadius, downloadPaint)
        } else {
            canvas.drawPath(uploadPath, uploadPaint)
            canvas.drawPath(downloadPath, downloadPaint)
        }
    }

    private fun sampleY(value: Long): Float {
        val maximum = samples.fold(1L) { current, sample ->
            max(current, max(sample.upload, sample.download))
        }
        val top = paddingTop.toFloat() + verticalInset
        val bottom = (height - paddingBottom).toFloat() - verticalInset
        return bottom - (bottom - top).coerceAtLeast(1f) * (value.toFloat() / maximum.toFloat())
    }

    private fun linePaint(color: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    private val verticalInset: Float
        get() = resources.displayMetrics.density * 4f

    private val pointRadius: Float
        get() = resources.displayMetrics.density * 2f

    companion object {
        private const val MAX_SAMPLES = 60
    }
}
