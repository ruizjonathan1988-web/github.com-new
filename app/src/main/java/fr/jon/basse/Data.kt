package fr.jon.basse

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Filter(val type: String, val f: Double, val g: Double, val q: Double)

data class Preset(
    val id: String,
    val name: String,
    val bpm: Int,
    val desc: String,
    val filters: List<Filter>,
    val subs: List<Preset> = emptyList(),
)

data class Family(
    val id: String,
    val name: String,
    val c1: Color,
    val c2: Color,
    val accent: Color,
    val anim: String,
    val isVideo: Boolean,
    val styles: List<Preset>,
)

class Catalog(val base: List<Filter>, val families: List<Family>) {
    private val index: Map<String, Pair<Family, Preset>> = buildMap {
        for (f in families) for (s in f.styles) {
            put(s.id, f to s)
            for (sub in s.subs) put(sub.id, f to sub)
        }
    }

    fun find(id: String?): Pair<Family, Preset>? = id?.let { index[it] }

    /** Style parent d'un sous-style (ou lui-même). */
    fun parentOf(id: String): Preset? {
        val (fam, _) = index[id] ?: return null
        return fam.styles.firstOrNull { s -> s.id == id || s.subs.any { it.id == id } }
    }

    val count: Int get() = index.size

    companion object {
        fun load(ctx: Context): Catalog {
            val root = JSONObject(ctx.assets.open("styles.json").bufferedReader().use { it.readText() })
            val base = filters(root.getJSONArray("ft1_base"))
            val fams = root.getJSONArray("families").let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Family(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        c1 = color(o.getString("c1")),
                        c2 = color(o.getString("c2")),
                        accent = color(o.getString("accent")),
                        anim = o.getString("anim"),
                        isVideo = o.getString("kind") == "video",
                        styles = presets(o.getJSONArray("styles")),
                    )
                }
            }
            return Catalog(base, fams)
        }

        private fun color(hex: String) = Color(android.graphics.Color.parseColor(hex))

        private fun filters(a: JSONArray) = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Filter(o.getString("type"), o.getDouble("f"), o.getDouble("g"), o.getDouble("q"))
        }

        private fun presets(a: JSONArray): List<Preset> = (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Preset(
                id = o.getString("id"),
                name = o.getString("name"),
                bpm = o.getInt("bpm"),
                desc = o.getString("desc"),
                filters = filters(o.getJSONArray("filters")),
                subs = if (o.has("subs")) presets(o.getJSONArray("subs")) else emptyList(),
            )
        }
    }
}

/** Réglages utilisateur qui s'ajoutent au style. */
data class UserTweaks(val ft1: Boolean = true, val bassDb: Double = 0.0, val trebleDb: Double = 0.0)

object Curve {
    const val FS = 48000.0

    fun chain(base: List<Filter>, style: List<Filter>, t: UserTweaks): List<Filter> = buildList {
        if (t.ft1) addAll(base)
        addAll(style)
        if (t.bassDb != 0.0) add(Filter("LS", 80.0, t.bassDb, 0.7))
        if (t.trebleDb != 0.0) add(Filter("HS", 8000.0, t.trebleDb, 0.7))
    }

    /** Coefficients normalisés RBJ : b0,b1,b2,a1,a2. */
    fun coeffs(flt: Filter, fs: Double = FS): DoubleArray {
        val a = 10.0.pow(flt.g / 40.0)
        val w0 = 2.0 * PI * flt.f.coerceAtMost(fs * 0.45) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * flt.q)
        val sa = 2.0 * sqrt(a) * alpha
        val c = when (flt.type) {
            "LS" -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
                (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
            )
            "HS" -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
                (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
            )
            else -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
        }
        return doubleArrayOf(c[0] / c[3], c[1] / c[3], c[2] / c[3], c[4] / c[3], c[5] / c[3])
    }

    /** Gain en dB de la chaîne à la fréquence f. */
    fun responseDb(chain: List<DoubleArray>, f: Double, fs: Double = FS): Double {
        val w = 2.0 * PI * f / fs
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        var db = 0.0
        for (k in chain) {
            val nr = k[0] + k[1] * c1 + k[2] * c2
            val ni = -(k[1] * s1 + k[2] * s2)
            val dr = 1.0 + k[3] * c1 + k[4] * c2
            val di = -(k[3] * s1 + k[4] * s2)
            val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
            db += 10.0 * log10(mag2)
        }
        return db
    }

    /** Fréquences log de 20 Hz à 20 kHz. */
    fun logFreqs(n: Int): DoubleArray = DoubleArray(n) { 20.0 * 1000.0.pow(it / (n - 1.0)) }

    fun sample(filters: List<Filter>, n: Int = 128): FloatArray {
        val ch = filters.map { coeffs(it) }
        return logFreqs(n).map { responseDb(ch, it).toFloat() }.toFloatArray()
    }

    /** Préampli automatique : on retire le gain max (+0,5 dB de marge) pour ne jamais saturer. */
    fun autoPreamp(filters: List<Filter>): Double {
        val ch = filters.map { coeffs(it) }
        var peak = 0.0
        for (f in logFreqs(256)) peak = max(peak, responseDb(ch, f))
        return if (peak <= 0.0) 0.0 else -(peak + 0.5)
    }

    fun fmtHz(f: Double) = if (f >= 1000) {
        val k = f / 1000.0
        if (k % 1.0 == 0.0) "${k.toInt()} kHz" else "%.1f kHz".format(k)
    } else "${f.toInt()} Hz"

    @Suppress("unused")
    fun octaves(a: Double, b: Double) = ln(b / a) / ln(2.0)
}
