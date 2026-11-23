package dev.omalauncher.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omalauncher.widgets.render.frost

/**
 * Apple iOS inspired Liquid Glass design system:
 * - Dynamic backdrop blur and refraction
 * - Specular edge lighting with dual-stop chromatic gradients
 * - Squircle continuous curvature
 * - Smooth spring physics
 */
object LiquidGlassTokens {
    val CornerLarge = RoundedCornerShape(28.dp)
    val CornerMedium = RoundedCornerShape(20.dp)
    val CornerSmall = RoundedCornerShape(14.dp)
    val Pill = CircleShape

    // Translucent glass backgrounds
    val GlassTintDark = Color(0x18FFFFFF)      // 10% white
    val GlassTintLight = Color(0x40FFFFFF)     // 25% white
    val GlassSurface = Color(0x12FFFFFF)      // 7% white

    // Specular highlight gradient (light reflecting off glass bezel)
    val SpecularBorderDark = Brush.linearGradient(
        colors = listOf(
            Color(0x66FFFFFF), // Top-left specular gleam (40% white)
            Color(0x22FFFFFF), // Mid reflection
            Color(0x0AFFFFFF), // Bottom-right shadow rim (4% white)
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    val SpecularBorderLight = Brush.linearGradient(
        colors = listOf(
            Color(0xAAFFFFFF),
            Color(0x44FFFFFF),
            Color(0x22FFFFFF),
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    // AgY Cyan Glow
    val AgyAccent = Color(0xFF00E5FF)
    val AgyGlow = Color(0x3300E5FF)

    // Gemini Violet / Blue Accent
    val GeminiAccent = Color(0xFF8AB4F8)
    val GeminiViolet = Color(0xFFC58AF9)

    // Samsung Bixby Blue Accent
    val BixbyAccent = Color(0xFF1E88E5)
}

/**
 * Applies full Apple-style Liquid Glass styling to any composable:
 * - Wallpaper frost blur
 * - Translucent glass tint fill
 * - Linear specular gradient border
 * - Gentle ambient shadow
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = LiquidGlassTokens.CornerMedium,
    frostRadius: Double = 22.0,
    borderWidth: Dp = 1.dp,
    tint: Color = LiquidGlassTokens.GlassTintDark,
    isLight: Boolean = false,
    elevation: Dp = 4.dp,
    corner: () -> Float = { 20f },
): Modifier {
    val frosted = if (frostRadius > 0) this.frost(frostRadius, corner) else this
    return frosted
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.25f),
            spotColor = Color.Black.copy(alpha = 0.15f),
        )
        .clip(shape)
        .background(tint, shape)
        .border(
            width = borderWidth,
            brush = if (isLight) LiquidGlassTokens.SpecularBorderLight else LiquidGlassTokens.SpecularBorderDark,
            shape = shape,
        )
}

/**
 * A floating liquid glass pill container, ideal for the prompt bar, dock, and assistant chips.
 */
@Composable
fun LiquidPill(
    modifier: Modifier = Modifier,
    shape: Shape = LiquidGlassTokens.Pill,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }

    LaunchedEffect(isPressed) {
        scale.animateTo(
            targetValue = if (isPressed) 0.96f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .then(clickModifier)
            .liquidGlass(shape = shape),
    ) {
        content()
    }
}
