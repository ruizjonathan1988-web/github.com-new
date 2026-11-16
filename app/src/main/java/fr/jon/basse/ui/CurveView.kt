package fr.jon.basse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.log10

/** Courbe de réponse 20 Hz – 20 kHz, ±12 dB, qui se transforme en douceur d'un style à l'autre. */
@Composable
fun CurveView(points: FloatArray, accent: Color, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    val holder = remember { arrayOf(points, points) } // [from, to]
    if (!holder[1].contentEquals(points)) {
        // point de départ = ce qui est affiché maintenant
        val p = progress.value
        holder[0] = FloatArray(points.size) { i -> lerp(holder[0].getOrElse(i) { 0f }, holder[1].getOrElse(i) { 0f }, p) }
        holder[1] = points
    }
    LaunchedEffect(points.contentHashCode()) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)

    Canvas(modifier) {
        val range = 12f
        fun yOf(db: Float) = size.height * (0.5f - db.coerceIn(-range, range) / (2 * range))
        fun xOfHz(f: Float) = size.width * ((log10(f) - log10(20f)) / (log10(20000f) - log10(20f)))

        // grille
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) {
            val y = yOf(db)
            drawLine(Color.White.copy(alpha = if (db == 0f) 0.25f else 0.08f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        for ((f, txt) in listOf(50f to "50", 100f to "100", 500f to "500", 1000f to "1k", 5000f to "5k", 10000f to "10k")) {
            val x = xOfHz(f)
            drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, size.height), 1f)
            drawText(measurer, txt, Offset(x + 3f, size.height - 14.sp.toPx()), label)
        }

        val p = progress.value
        val from = holder[0]; val to = holder[1]
        val n = to.size
        if (n < 2) return@Canvas
        val path = Path()
        for (i in 0 until n) {
            val v = lerp(from.getOrElse(i) { 0f }, to[i], p)
            val x = size.width * i / (n - 1f)
            val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, yOf(0f)); lineTo(0f, yOf(0f)); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))))
        drawPath(path, accent.copy(alpha = 0.35f), style = Stroke(width = 10f, cap = StrokeCap.Round))
        drawPath(path, accent, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
