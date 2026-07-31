package com.github.kr328.clash.design.svg

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.shouldUseDarkIllustrationColors
import com.github.kr328.clash.design.util.shouldUseDynamicColors
import com.github.kr328.clash.design.svg.drawablevectors.coder
import com.github.kr328.clash.design.svg.drawablevectors.download
import com.github.kr328.clash.design.svg.drawablevectors.videoFiles
import com.github.kr328.clash.design.svg.drawablevectors.videoSteaming

/**
 * Named undraw illustration kinds used in View-based empty states.
 */
enum class UndrawIllustration {
    Download,
    VideoFiles,
    VideoStreaming,
    Coder,
}

/**
 * Compose island hosting a theme-tinted undraw ImageVector.
 * Intended for ViewBinding empty states (connections / profiles).
 */
class EmptyStateIllustrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private val uiStore = UiStore(context)
    var illustration: UndrawIllustration by mutableStateOf(UndrawIllustration.VideoStreaming)

    @Composable
    override fun Content() {
        ClashUndrawTheme(
            useDynamicColors = uiStore.dynamicColors,
            darkMode = uiStore.darkMode,
        ) {
            val vector = when (illustration) {
                UndrawIllustration.Download -> DynamicColorImageVectors.download()
                UndrawIllustration.VideoFiles -> DynamicColorImageVectors.videoFiles()
                UndrawIllustration.VideoStreaming -> DynamicColorImageVectors.videoSteaming()
                UndrawIllustration.Coder -> DynamicColorImageVectors.coder()
            }
            UndrawIllustrationImage(imageVector = vector)
        }
    }
}

@Composable
fun UndrawIllustrationImage(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
) {
    Image(
        imageVector = imageVector,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .padding(horizontal = 24.dp)
            .clearAndSetSemantics { },
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun ClashUndrawTheme(
    useDynamicColors: Boolean = false,
    darkMode: DarkMode = DarkMode.Auto,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = shouldUseDarkIllustrationColors(darkMode, isSystemInDarkTheme())

    val dynamicColors = shouldUseDynamicColors(useDynamicColors)
    val lightScheme = if (dynamicColors) {
        dynamicLightColorScheme(context)
    } else {
        clashLightColorScheme(context)
    }
    val darkScheme = if (dynamicColors) {
        dynamicDarkColorScheme(context)
    } else {
        clashDarkColorScheme(context)
    }
    val scheme = if (dark) darkScheme else lightScheme
    val fixed = FixedColorRoles.fromColorSchemes(lightColors = lightScheme, darkColors = darkScheme)

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalFixedColorRoles provides fixed) {
            content()
        }
    }
}

private fun clashLightColorScheme(context: Context) =
    ContextCompat.getColor(context, R.color.color_clash_light).let { Color(it) }.let { clash ->
        lightColorScheme(
            primary = clash,
            onPrimary = Color.White,
            primaryContainer = clash,
            onPrimaryContainer = Color.White,
            secondary = clash,
            onSecondary = Color.White,
            secondaryContainer = clash,
            onSecondaryContainer = Color.White,
            tertiary = clash,
            onTertiary = Color.White,
            tertiaryContainer = clash,
            onTertiaryContainer = Color.White,
            inversePrimary = clash,
        )
    }

private fun clashDarkColorScheme(context: Context) =
    ContextCompat.getColor(context, R.color.color_clash_dark).let { Color(it) }.let { clash ->
        darkColorScheme(
            primary = clash,
            onPrimary = Color.White,
            primaryContainer = clash,
            onPrimaryContainer = Color.White,
            secondary = clash,
            onSecondary = Color.White,
            secondaryContainer = clash,
            onSecondaryContainer = Color.White,
            tertiary = clash,
            onTertiary = Color.White,
            tertiaryContainer = clash,
            onTertiaryContainer = Color.White,
            inversePrimary = clash,
        )
    }
