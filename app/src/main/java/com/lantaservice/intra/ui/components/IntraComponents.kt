package com.lantaservice.intra.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.lantaservice.intra.ui.theme.IntraColors
import kotlin.math.sin

/**
 * Full-screen IntraService sky-blue gradient with subtle animated wave ribbons
 * echoing intra.lantaservice.com/themes/img/intraservice/login.png.
 */
@Composable
fun IntraWaveBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "intra_waves")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    IntraColors.BrandBlueDeep,
                    IntraColors.BrandBlueMid,
                    IntraColors.BrandCyan
                ),
                start = Offset(0f, 0f),
                end = Offset(900f, 1800f)
            )
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val baseY = h * 0.44f

            // Multi-strand fine ribbon curves matching the classic Intra login wave motif
            for (strand in 0..7) {
                val offset = strand * 11f
                val alpha = 0.09f + (strand % 3) * 0.03f
                val path = Path()
                val steps = 28
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = w * t
                    val wave1 = sin(t * 5.2f + phase + strand * 0.16f) * (28f + strand * 3f)
                    val wave2 = sin(t * 2.4f - phase * 0.6f) * 24f
                    val y = baseY + offset + wave1 + wave2 + (t - 0.5f) * 48f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = alpha),
                    style = Stroke(width = 1.6f, cap = StrokeCap.Round)
                )
            }

            // Soft bottom luminous glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * 0.85f, h * 0.12f),
                    radius = w * 0.65f
                ),
                radius = w * 0.65f,
                center = Offset(w * 0.85f, h * 0.12f)
            )
        }
        content()
    }
}

/**
 * Compact header backdrop matching IntraService top navigation gradient.
 */
@Composable
fun IntraHeaderBanner(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    IntraColors.BrandBlueDeep,
                    IntraColors.BrandBlueMid,
                    IntraColors.BrandCyan
                ),
                start = Offset(0f, 0f),
                end = Offset(1200f, 400f)
            )
        )
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            for (strand in 0..4) {
                val path = Path()
                val steps = 20
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = w * t
                    val y = h * 0.72f + strand * 7f + sin(t * 4.5f + strand * 0.25f) * 14f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.10f),
                    style = Stroke(width = 1.4f, cap = StrokeCap.Round)
                )
            }
        }
        content()
    }
}

/**
 * Vector emblem echoing the green-and-white shield on intra.lantaservice.com/img/login.gif.
 */
@Composable
fun IntraShieldBadge(
    size: Dp = 30.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        val outerShield = Path().apply {
            moveTo(w * 0.50f, h * 0.06f)
            lineTo(w * 0.90f, h * 0.22f)
            lineTo(w * 0.90f, h * 0.52f)
            cubicTo(w * 0.90f, h * 0.76f, w * 0.72f, h * 0.90f, w * 0.50f, h * 0.96f)
            cubicTo(w * 0.28f, h * 0.90f, w * 0.10f, h * 0.76f, w * 0.10f, h * 0.52f)
            lineTo(w * 0.10f, h * 0.22f)
            close()
        }
        drawPath(outerShield, color = IntraColors.EmeraldDeep)

        val innerShield = Path().apply {
            moveTo(w * 0.50f, h * 0.13f)
            lineTo(w * 0.83f, h * 0.27f)
            lineTo(w * 0.83f, h * 0.52f)
            cubicTo(w * 0.83f, h * 0.72f, w * 0.68f, h * 0.84f, w * 0.50f, h * 0.89f)
            cubicTo(w * 0.32f, h * 0.84f, w * 0.17f, h * 0.72f, w * 0.17f, h * 0.52f)
            lineTo(w * 0.17f, h * 0.27f)
            close()
        }
        drawPath(
            innerShield,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF34D07E), IntraColors.EmeraldSuccess),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
        )

        val checkPath = Path().apply {
            moveTo(w * 0.34f, h * 0.53f)
            lineTo(w * 0.46f, h * 0.65f)
            lineTo(w * 0.69f, h * 0.39f)
        }
        drawPath(
            checkPath,
            color = Color.White,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    isRepaired: Boolean = false,
    isOverdue: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bgColor: Color
    val fgColor: Color
    when {
        isRepaired -> {
            bgColor = IntraColors.EmeraldTint
            fgColor = IntraColors.EmeraldDeep
        }
        isOverdue -> {
            bgColor = IntraColors.ErrorTint
            fgColor = IntraColors.ErrorRed
        }
        text.contains("ожид", ignoreCase = true) ||
            text.contains("пауз", ignoreCase = true) -> {
            bgColor = IntraColors.WarningTint
            fgColor = IntraColors.WarningAmber
        }
        else -> {
            bgColor = IntraColors.SkyTint
            fgColor = IntraColors.SkyText
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = bgColor
    ) {
        Text(
            text = text,
            color = fgColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun PriorityDotAndLabel(priorityName: String) {
    if (priorityName.isBlank()) return
    val color = when {
        priorityName.contains("крит", ignoreCase = true) ||
            priorityName.contains("выс", ignoreCase = true) ||
            priorityName.contains("сроч", ignoreCase = true) -> IntraColors.ErrorRed
        priorityName.contains("сред", ignoreCase = true) ||
            priorityName.contains("норм", ignoreCase = true) -> IntraColors.WarningAmber
        else -> IntraColors.BrandPrimary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = priorityName,
            style = MaterialTheme.typography.labelMedium,
            color = IntraColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ShimmerTicketCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )
    val placeholderColor = IntraColors.SubtleBorder.copy(alpha = alpha)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = IntraColors.SurfaceWhite,
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .width(68.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor)
                )
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(placeholderColor)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor)
            )
        }
    }
}

data class ViewerPhoto(
    val title: String,
    val model: Any
)

@Composable
fun FullscreenPhotoViewer(
    photo: ViewerPhoto,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
        ) {
            AsyncImage(
                model = photo.model,
                contentDescription = photo.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.4f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 4.5f)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = photo.title,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.55f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                }
            }
        }
    }
}
