package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context) {
    var selectable: Boolean = false
    private var lastAccessibilityText: CharSequence? = null

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize
        }

        // Line height derived from font metrics, independent of the actual text content
        // (title, delay) so CJK / emoji / large system fonts are not clipped.
        val fontMetrics = state.paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidateOnAnimation()

        // Notify accessibility when the spoken summary changes (selection / delay / title).
        val accessibilityText = buildAccessibilityText(state)
        if (accessibilityText != lastAccessibilityText) {
            lastAccessibilityText = accessibilityText
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                drawRect(0f, 0f, width, height, paint)
            } else {
                val path = state.path

                path.reset()

                path.addRoundRect(
                    state.config.layoutPadding,
                    state.config.layoutPadding,
                    width - state.config.layoutPadding,
                    height - state.config.layoutPadding,
                    state.config.cardRadius,
                    state.config.cardRadius,
                    Path.Direction.CW,
                )

                // setShadowLayer is not honored for drawPath under hardware acceleration;
                // a real shadow would need elevation/outlineProvider. Leave the card flat.
                drawPath(path, paint)

                clipPath(path)
            }
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        paint.textSize = state.config.textSize

        // measure delay text bounds
        val delayCount = paint.breakText(
            state.delayText,
            false,
            (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                .coerceAtLeast(0f),
            null
        )

        state.paint.getTextBounds(state.delayText, 0, delayCount, state.rect)

        val delayWidth = state.rect.width()

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        // measure title text bounds
        val titleCount = paint.breakText(
            state.title,
            false,
            mainTextWidth,
            null,
        )

        // measure subtitle text bounds
        val subtitleCount = paint.breakText(
            state.subtitle,
            false,
            mainTextWidth,
            null,
        )

        // text draw measure
        val textOffset = (paint.descent() + paint.ascent()) / 2

        paint.reset()

        paint.textSize = state.config.textSize
        paint.isAntiAlias = true
        paint.color = state.controls

        // draw delay
        canvas.apply {
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = height / 2f - textOffset + state.delayOffset

            paint.alpha = (state.delayAlpha * 255).toInt()
            drawText(state.delayText, 0, delayCount, x, y, paint)
            paint.alpha = 255
        }

        // draw title
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f - textOffset

            drawText(state.title, 0, titleCount, x, y, paint)
        }

        // draw subtitle
        canvas.apply {
            val x = state.config.layoutPadding + state.config.contentPadding
            val y = state.config.layoutPadding +
                    (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset

            drawText(state.subtitle, 0, subtitleCount, x, y, paint)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)

        info.className = if (selectable) Button::class.java.name else View::class.java.name
        info.isClickable = selectable
        info.isSelected = selectable && state?.isSelected == true

        // Everything on this view is canvas-drawn, so expose the node's semantics as text
        // ("name, type, delay, selected state") for TalkBack instead of a bare "button".
        info.text = lastAccessibilityText ?: state?.let { buildAccessibilityText(it) }
    }

    private fun buildAccessibilityText(state: ProxyViewState): CharSequence {
        return buildString {
            append(state.title)
            if (state.subtitle.isNotEmpty()) {
                append(", ")
                append(state.subtitle)
            }
            append(", ")
            append(
                if (state.delayText.isEmpty()) {
                    context.getString(R.string.unavailable)
                } else {
                    context.getString(R.string.format_delay_milliseconds, state.delayText)
                }
            )
            if (selectable) {
                append(", ")
                append(
                    if (state.isSelected) {
                        context.getString(R.string.selected)
                    } else {
                        context.getString(R.string.not_selected)
                    }
                )
            }
        }
    }
}
