package com.github.kr328.clash.design.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.kr328.clash.design.ui.Insets

fun View.setOnInsertsChangedListener(adaptLandscape: Boolean = true, listener: (Insets) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, compat ->
        // Include display cutout so edge-to-edge layouts clear notches / camera holes
        // on Android 15–17 devices (mandatory edge-to-edge when targeting API 36+).
        val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val insets = compat.getInsets(typeMask)

        val rInsets = if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_LTR) {
            Insets(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom,
            )
        } else {
            Insets(
                insets.right,
                insets.top,
                insets.left,
                insets.bottom,
            )
        }

        listener(if (adaptLandscape) rInsets.landscape(v.context) else rInsets)

        compat
    }

    ViewCompat.requestApplyInsets(this)
}
