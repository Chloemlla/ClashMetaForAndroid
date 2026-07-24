package com.github.kr328.clash.design.svg

import android.content.Context
import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
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

    var illustration: UndrawIllustration by mutableStateOf(UndrawIllustration.VideoStreaming)

    @Composable
    override fun Content() {
        ClashUndrawTheme {
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
fun ClashUndrawTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme() ||
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val lightScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme()
    }
    val darkScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }
    val scheme = if (dark) darkScheme else lightScheme
    val fixed = FixedColorRoles.fromColorSchemes(lightColors = lightScheme, darkColors = darkScheme)

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalFixedColorRoles provides fixed) {
            content()
        }
    }
}
