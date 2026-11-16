package fr.jon.basse.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.jon.basse.Analyzer
import fr.jon.basse.EqState
import fr.jon.basse.Filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

// ---------------- Spectre ----------------

@Composable
fun SpectrumView(accent: Color, modifier: Modifier) {
    DisposableEffect(Unit) {
        Analyzer.uiVisible = true
        onDispose { Analyzer.uiVisible = false }
    }
    val spec by Analyzer.spectrum.collectAsState()
    val peak = remember { FloatArray(Analyzer.N_BANDS) }
    Canvas(modifier) {
        val n = spec.size
        val w = size.width / n
        val top = spec.maxOrNull()?.coerceAtLeast(20f) ?: 20f
        for (i in 0 until n) {
            val norm = ((spec[i] - (top - 50f)) / 50f).coerceIn(0f, 1f)
            peak[i] = max(peak[i] * 0.94f, norm)
            val h = size.height * norm
            drawRect(Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.15f)), startY = size.height - h, endY = size.height),
                topLeft = Offset(i * w + 2f, size.height - h), size = Size(w - 4f, h))
            drawRect(Color.White.copy(alpha = 0.6f), topLeft = Offset(i * w + 2f, size.height - size.height * peak[i] - 3f), size = Size(w - 4f, 3f))
        }
    }
}

// ---------------- Test auditif ----------------

private val TEST_FREQS = intArrayOf(125, 250, 500, 1000, 2000, 3000, 4000, 6000, 8000, 10000, 12000)

/** Seuil "normal" approximatif au casque, relatif à 1 kHz (dB). */
private val NORMAL_REL = floatArrayOf(21f, 12f, 5f, 0f, -2f, -4f, -4f, 2f, 8f, 10f, 12f)

private class ToneGen {
    @Volatile var freq = 1000.0
    @Volatile var levelDb = -90f
    @Volatile var running = true
    private val sr = 48000

    suspend fun run() = withContext(Dispatchers.Default) {
        val buf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        val frames = 960 // 20 ms
        val out = ShortArray(frames * 2)
        var phase = 0.0
        var amp = 0.0
        try {
            while (running && isActive) {
                val target = if (levelDb <= -89f) 0.0 else 10.0.pow(levelDb / 20.0)
                val inc = 2 * PI * freq / sr
                for (i in 0 until frames) {
                    amp += (target - amp) * 0.002
                    val v = (sin(phase) * amp * 32767).toInt().toShort()
                    out[2 * i] = v; out[2 * i + 1] = v
                    phase += inc
                    if (phase > 2 * PI) phase -= 2 * PI
                }
                track.write(out, 0, out.size)
            }
        } finally {
            runCatching { track.stop(); track.release() }
        }
    }
}

@Composable
fun HearingTestOverlay(accent: Color, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(-1) } // -1 intro, 0..n-1 test, n = résultat
    var level by remember { mutableFloatStateOf(-80f) }
    var heard by remember { mutableStateOf(false) }
    val thresholds = remember { FloatArray(TEST_FREQS.size) { -10f } }
    var result by remember { mutableStateOf<List<Filter>>(emptyList()) }
    val gen = remember { ToneGen() }

    DisposableEffect(Unit) {
        EqState.setTesting(true)
        onDispose { gen.running = false; EqState.setTesting(false) }
    }

    // Générateur de son : démarre au premier son et tourne jusqu'à la fin du test.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { step }.first { it >= 0 }
        gen.run()
    }

    // Déroulé du test : chaque son monte de 2 dB toutes les 350 ms jusqu'à ce que vous l'entendiez.
    LaunchedEffect(step) {
        if (step in TEST_FREQS.indices) {
            gen.levelDb = -90f
            delay(700)
            gen.freq = TEST_FREQS[step].toDouble()
            heard = false
            level = -80f
            while (!heard && level < -10f) {
                gen.levelDb = level
                delay(350)
                level += 2f
            }
            thresholds[step] = if (heard) level else -10f
            gen.levelDb = -90f
            step += 1
        } else if (step == TEST_FREQS.size) {
            gen.running = false
            val ref = thresholds[TEST_FREQS.indexOf(1000)]
            result = TEST_FREQS.indices.mapNotNull { i ->
                val loss = (thresholds[i] - ref) - NORMAL_REL[i]
                if (loss > 3f) Filter("PK", TEST_FREQS[i].toDouble(), (0.5 * loss).coerceAtMost(6.0), 1.4) else null
            }
        }
    }


    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Test auditif", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            when {
                step < 0 -> {
                    Text("Mettez le FT1, installez-vous au calme et réglez le volume à votre niveau habituel, plutôt modéré. " +
                        "Des sons purs vont monter doucement, du grave à l'aigu. Appuyez sur « J'entends » dès que vous percevez le son. " +
                        "Basse est mise en pause pendant le test (seule la correction du casque reste active).",
                        color = TextDim, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Hint("Ce test est indicatif et ne remplace pas un audiogramme chez un ORL.")
                    ActionChip("Commencer", accent, filled = true) { step = 0 }
                    ActionChip("Annuler", accent, onClick = onClose)
                }
                step < TEST_FREQS.size -> {
                    val f = TEST_FREQS[step]
                    Text(if (f >= 1000) "${f / 1000} kHz" else "$f Hz", color = accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Son ${step + 1} / ${TEST_FREQS.size}", color = TextDim)
                    Box(Modifier.fillMaxWidth().height(10.dp).background(Glass, RoundedCornerShape(5.dp))) {
                        Box(Modifier.fillMaxWidth(((level + 80f) / 70f).coerceIn(0f, 1f)).height(10.dp).background(accent, RoundedCornerShape(5.dp)))
                    }
                    Box(
                        Modifier.size(170.dp).tv(accent, RoundedCornerShape(50)) { heard = true }
                            .background(accent, CircleShape).border(4.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("J'entends", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 22.sp) }
                    ActionChip("Arrêter", accent, onClick = onClose)
                }
                else -> {
                    if (result.isEmpty()) Text("Rien à compenser : votre écoute est équilibrée avec ce casque.", color = Color.White, textAlign = TextAlign.Center)
                    else {
                        Text("Basse va remonter un peu :", color = Color.White)
                        result.forEach { r ->
                            Text("${if (r.f >= 1000) "${(r.f / 1000).toInt()} kHz" else "${r.f.toInt()} Hz"} : +%.1f dB".format(r.g), color = accent, fontWeight = FontWeight.Bold)
                        }
                        Hint("Compensation à moitié de l'écart mesuré, plafonnée à +6 dB, pour rester naturel.")
                    }
                    ActionChip("Appliquer", accent, filled = true) {
                        EqState.update(ctx) { it.copy(hearing = result, hearingOn = true) }
                        onClose()
                    }
                    ActionChip("Annuler", accent, onClick = onClose)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
