package com.github.kr328.clash.design.preference

import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.databinding.PreferenceSwitchBinding
import com.github.kr328.clash.design.util.layoutInflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0

interface SwitchPreference : Preference {
    var icon: Drawable?
    var title: CharSequence?
    var summary: CharSequence?
    var listener: OnChangedListener?
}

fun PreferenceScreen.switch(
    value: KMutableProperty0<Boolean>,
    @DrawableRes icon: Int? = null,
    @StringRes title: Int? = null,
    @StringRes summary: Int? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    configure: SwitchPreference.() -> Unit = {},
): SwitchPreference {
    val binding = PreferenceSwitchBinding
        .inflate(context.layoutInflater, root, false)

    val impl = object : SwitchPreference {
        override val view: View
            get() = binding.root
        override var icon: Drawable?
            get() = binding.iconView.background
            set(value) {
                binding.iconView.background = value
            }
        override var title: CharSequence?
            get() = binding.titleView.text
            set(value) {
                binding.titleView.text = value
            }
        override var summary: CharSequence?
            get() = binding.summaryView.text
            set(value) {
                binding.summaryView.text = value
            }
        override var listener: OnChangedListener? = null
        override var enabled: Boolean
            get() = binding.root.isEnabled
            set(value) {
                binding.root.isEnabled = value
                binding.root.isFocusable = value
                binding.root.isClickable = value
                binding.root.alpha = if (value) 1.0f else 0.33f
            }

    }

    if (icon != null) {
        impl.icon = context.getDrawableCompat(icon)
    }

    if (title != null) {
        impl.title = context.getString(title)
    }

    if (summary != null) {
        impl.summary = context.getString(summary)
    }

    impl.configure()

    addElement(impl)

    launch(Dispatchers.Main) {
        val initialValue = withContext(Dispatchers.IO) {
            value.get()
        }

        fun persistChecked() {
            val checked = binding.switchView.isChecked

            this@switch.launch(Dispatchers.Main) {
                withContext(Dispatchers.IO) {
                    value.set(checked)
                }

                impl.listener?.onChanged()
            }
        }

        binding.switchView.apply {
            isChecked = initialValue

            if (onClick == null) {
                binding.root.setOnClickListener {
                    isChecked = !isChecked
                    persistChecked()
                }
            } else {
                // Row tap runs onClick (e.g. "update rules") instead of toggling; the
                // switch widget itself toggles. SwitchMaterial auto-flips isChecked on
                // tap, so persistChecked reads the already-toggled value.
                isClickable = true
                setOnClickListener {
                    persistChecked()
                }
                binding.root.setOnClickListener {
                    onClick()
                }
            }

            if (onLongClick != null) {
                binding.root.setOnLongClickListener {
                    onLongClick()
                    true
                }
                // Long-press landing directly on the switch widget is consumed by
                // the switch once it is clickable; mirror the action there too.
                binding.switchView.setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
    }

    return impl
}