package com.github.kr328.clash.design.component

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.design.model.ProxyState
import kotlin.math.absoluteValue
import kotlin.math.max

class ProxyViewState(
    val config: ProxyViewConfig,
    proxy: Proxy,
    private val parent: ProxyState,
    private val link: ProxyState?
) {
    val paint = Paint()
    val rect = Rect()
    val path = Path()

    var title: String = ""
    var subtitle: String = ""
    var delayText: String = ""
    var background: Int = config.unselectedBackground
    var controls: Int = config.unselectedControl
    var delayAlpha: Float = 1f
        private set
    var delayOffset: Float = 0f
        private set

    var proxy: Proxy = proxy
        private set

    private var delay: Int = 0
    private var selected: Boolean = false
    private var parentNow: String = ""
    private var linkNow: String? = null

    private var lastFrameTime = System.currentTimeMillis()
    private var delayAnimationStartedAt = 0L

    fun updateProxy(proxy: Proxy, animateDelay: Boolean) {
        val delayChanged = this.proxy.delay != proxy.delay

        this.proxy = proxy

        if (delayChanged && animateDelay) {
            delayAnimationStartedAt = System.currentTimeMillis()
            delayAlpha = 0f
            delayOffset = config.textSize * DELAY_ANIMATION_OFFSET_FACTOR
        } else if (!animateDelay) {
            delayAnimationStartedAt = 0L
            delayAlpha = 1f
            delayOffset = 0f
        }
    }

    fun update(snap: Boolean): Boolean {
        val frameTime = System.currentTimeMillis()
        var invalidate = false

        if (proxy.isGroup) {
            title = proxy.name

            if (link == null) {
                subtitle = proxy.type
            } else {
                if (linkNow !== link.now) {
                    linkNow = link.now

                    subtitle = "%s(%s)".format(
                        proxy.type,
                        link.now.ifEmpty { "*" }
                    )
                }
            }
        } else {
            title = proxy.title
            subtitle = proxy.subtitle
        }

        if (delay != proxy.delay) {
            delay = proxy.delay
            delayText = if (proxy.delay in 0..Short.MAX_VALUE) proxy.delay.toString() else ""
        }

        if (delayAnimationStartedAt != 0L) {
            val progress = ((frameTime - delayAnimationStartedAt).toFloat() /
                    DELAY_ANIMATION_DURATION_MS.toFloat())
                .coerceIn(0f, 1f)
            val remaining = 1f - progress
            val easedProgress = 1f - remaining * remaining * remaining

            delayAlpha = easedProgress
            delayOffset = (1f - easedProgress) *
                    config.textSize * DELAY_ANIMATION_OFFSET_FACTOR

            if (progress < 1f) {
                invalidate = true
            } else {
                delayAnimationStartedAt = 0L
                delayAlpha = 1f
                delayOffset = 0f
            }
        }

        if (parentNow !== parent.now) {
            parentNow = parent.now
            selected = proxy.name == parent.now
        }

        controls = if (selected) config.selectedControl else config.unselectedControl

        if (snap) {
            background = if (selected) config.selectedBackground else config.unselectedBackground
        } else {
            val target = if (selected) config.selectedBackground else config.unselectedBackground

            if (background != target) {
                val sa = Color.alpha(background)
                val sr = Color.red(background)
                val sg = Color.green(background)
                val sb = Color.blue(background)

                val ta = Color.alpha(target)
                val tr = Color.red(target)
                val tg = Color.green(target)
                val tb = Color.blue(target)

                val da = ta - sa
                val dr = tr - sr
                val dg = tg - sg
                val db = tb - sb

                val max = max(
                    da.absoluteValue,
                    max(
                        dr.absoluteValue,
                        max(
                            dg.absoluteValue,
                            db.absoluteValue
                        )
                    )
                )

                val frameOffset = frameTime - lastFrameTime

                val colorOffset = (frameOffset / max.toFloat().coerceAtLeast(0.001f))
                    .coerceIn(0.0f, 1.0f)

                background = if (colorOffset > 0.999f) {
                    target
                } else {
                    Color.argb(
                        (sa + da * colorOffset).toInt(),
                        (sr + dr * colorOffset).toInt(),
                        (sg + dg * colorOffset).toInt(),
                        (sb + db * colorOffset).toInt()
                    )
                }

                invalidate = true
            }
        }

        lastFrameTime = frameTime

        return invalidate
    }

    private companion object {
        const val DELAY_ANIMATION_DURATION_MS = 220L
        const val DELAY_ANIMATION_OFFSET_FACTOR = 0.35f
    }
}
