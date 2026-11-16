package fr.jon.basse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Fond animé qui dépend de la famille :
 * rings = ondes qui pulsent au BPM du style, waves = ondulations, bars = spectre,
 * particles = poussière lumineuse, cinema = salle obscure (bandes noires, faisceau du projecteur, grain).
 */
@Composable
fun AnimatedBackground(
    c1: Color, c2: Color, accent: Color,
    anim: String, bpm: Int, enabled: Boolean, curve: FloatArray,
    modifier: Modifier = Modifier,
) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = (it - start) / 1_000_000_000f }
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(c1, c2)))
        val energy = if (enabled) 1f else 0.35f
        val beat = if (bpm > 0) 60f / bpm else 1.6f
        val phase = (t / beat) % 1f
        when (anim) {
            "rings" -> rings(accent, phase, energy)
            "waves" -> waves(accent, t, energy)
            "bars" -> bars(accent, t, phase, energy, curve)
            "particles" -> particles(accent, t, energy)
            "cinema" -> cinema(accent, t, energy)
        }
        // vignette
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = center, radius = size.maxDimension * 0.75f))
    }
}

private fun DrawScope.rings(accent: Color, phase: Float, energy: Float) {
    val c = Offset(size.width * 0.5f, size.height * 0.32f)
    val maxR = size.maxDimension * 0.7f
    // 4 ondes décalées d'un temps chacune
    for (k in 0 until 4) {
        val p = (phase + k) / 4f
        val r = p * maxR
        val a = (1f - p) * 0.35f * energy
        drawCircle(accent.copy(alpha = a), r, c, style = Stroke(width = 2f + 10f * (1f - p)))
    }
    // coeur qui frappe sur le temps
    val kick = exp(-phase * 6f)
    drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.45f * energy * kick), Color.Transparent), c, 260f + 120f * kick),
        260f + 120f * kick, c,
    )
}

private fun DrawScope.waves(accent: Color, t: Float, energy: Float) {
    for (k in 0 until 3) {
        val path = Path()
        val baseY = size.height * (0.30f + 0.12f * k)
        val amp = (30f + 25f * k) * energy
        val speed = 0.25f + 0.12f * k
        var x = 0f
        path.moveTo(0f, baseY)
        while (x <= size.width) {
            val y = baseY + amp * sin((x / size.width * 2f * PI.toFloat() * (1.2f + k * 0.4f)) + t * speed * 2f * PI.toFloat())
            path.lineTo(x, y)
            x += 12f
        }
        drawPath(path, accent.copy(alpha = (0.28f - 0.07f * k) * energy), style = Stroke(width = 3f))
    }
}

private fun DrawScope.bars(accent: Color, t: Float, phase: Float, energy: Float, curve: FloatArray) {
    val n = 40
    val w = size.width / n
    val kick = exp(-phase * 5f)
    for (i in 0 until n) {
        val ci = if (curve.isEmpty()) 0f else curve[(i * (curve.size - 1)) / (n - 1)]
        val wobble = 0.5f + 0.5f * sin(t * (2.1f + i * 0.37f) + i)
        val h = size.height * 0.18f * energy * (0.35f + 0.45f * wobble + 0.25f * kick * (1f - i / n.toFloat())) * (1f + ci / 12f)
        drawRect(
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.05f)),
                startY = size.height - h, endY = size.height),
            topLeft = Offset(i * w + 2f, size.height - h), size = Size(w - 4f, h),
        )
    }
}

private fun hash(i: Int): Float {
    val x = sin(i * 12.9898f) * 43758.547f
    return x - floor(x)
}

private fun DrawScope.particles(accent: Color, t: Float, energy: Float) {
    for (i in 0 until 70) {
        val sp = 0.015f + 0.03f * hash(i + 7)
        val y = (1f - ((hash(i) + t * sp) % 1f)) * size.height
        val x = hash(i + 100) * size.width + 18f * sin(t * 0.5f + i)
        val tw = 0.5f + 0.5f * sin(t * (1f + hash(i + 3)) + i)
        drawCircle(accent.copy(alpha = 0.10f + 0.35f * tw * energy), 1.5f + 3f * hash(i + 50), Offset(x, y))
    }
}

private fun DrawScope.cinema(accent: Color, t: Float, energy: Float) {
    // faisceau de projecteur qui balaie doucement
    val src = Offset(size.width * 0.5f, -40f)
    val sweep = sin(t * 0.25f) * size.width * 0.18f
    val beam = Path().apply {
        moveTo(src.x - 20f, src.y)
        lineTo(src.x + 20f, src.y)
        lineTo(size.width * 0.5f + sweep + size.width * 0.45f, size.height)
        lineTo(size.width * 0.5f + sweep - size.width * 0.45f, size.height)
        close()
    }
    drawPath(beam, Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f * energy), Color.Transparent)))
    // grain
    val frame = (t * 24).toInt()
    for (i in 0 until 90) {
        val x = hash(i + frame * 131) * size.width
        val y = hash(i * 7 + frame * 17) * size.height
        drawCircle(Color.White.copy(alpha = 0.05f + 0.05f * hash(i + frame)), 1.2f, Offset(x, y))
    }
    // bandes noires 2.39:1
    val bar = abs(size.height - size.width / 2.39f) / 2f
    val b = bar.coerceIn(0f, size.height * 0.12f)
    drawRect(Color.Black, Offset.Zero, Size(size.width, b))
    drawRect(Color.Black, Offset(0f, size.height - b), Size(size.width, b))
}
