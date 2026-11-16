package fr.jon.basse

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.sqrt

/**
 * Applique la courbe via DynamicsProcessing (Android 9+).
 * La courbe paramétrique (filtres + Q) est échantillonnée sur 64 bandes logarithmiques,
 * un limiteur à -1 dBFS protège en plus de la préamplification automatique.
 */
class EqEngine {
    companion object {
        private const val TAG = "BasseEq"
        const val BANDS = 64
        private const val PRIORITY = Int.MAX_VALUE
    }

    private val effects = LinkedHashMap<Int, DynamicsProcessing>()
    private var bandCutoffs = FloatArray(BANDS)
    private var bandGains = FloatArray(BANDS)
    private var preamp = 0f
    private var balance = 0f
    private var ceiling = -1f
    var active = false
        private set

    init {
        // Bord haut de chaque bande : 20 Hz * 1000^((i+1)/N) => la dernière finit à 20 kHz
        for (i in 0 until BANDS) bandCutoffs[i] = (20.0 * Math.pow(1000.0, (i + 1).toDouble() / BANDS)).toFloat()
    }

    fun setCurve(chain: List<Filter>, keepVolume: Boolean = false, balance: Double = 0.0, ceilingDb: Double = -1.0) {
        this.balance = balance.toFloat()
        this.ceiling = ceilingDb.toFloat()
        val k = chain.map { Curve.coeffs(it) }
        for (i in 0 until BANDS) {
            val lo = if (i == 0) 10.0 else bandCutoffs[i - 1].toDouble()
            val center = sqrt(lo * bandCutoffs[i])
            bandGains[i] = Curve.responseDb(k, center).toFloat().coerceIn(-30f, 15f)
        }
        preamp = (Curve.autoPreamp(chain) * if (keepVolume) 0.35 else 1.0).toFloat()
        effects.values.forEach { push(it) }
    }

    private fun buildEq(): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, BANDS)
        for (i in 0 until BANDS) eq.setBand(i, DynamicsProcessing.EqBand(true, bandCutoffs[i], bandGains[i]))
        return eq
    }

    private fun limiter() = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, ceiling, 0f)

    private fun gainFor(ch: Int): Float {
        // balance > 0 : on baisse la gauche ; < 0 : on baisse la droite
        val cut = kotlin.math.abs(balance) * 6f
        return preamp - when { ch == 0 && balance > 0 -> cut; ch == 1 && balance < 0 -> cut; else -> 0f }
    }

    private fun push(dp: DynamicsProcessing) {
        try {
            for (ch in 0 until 2) dp.setInputGainbyChannel(ch, gainFor(ch))
            dp.setPreEqAllChannelsTo(buildEq())
            dp.setLimiterAllChannelsTo(limiter())
            dp.enabled = active
        } catch (e: Exception) {
            Log.w(TAG, "push", e)
        }
    }

    /** @return true si l'effet a pu être créé sur cette session. */
    fun attach(session: Int): Boolean {
        if (effects.containsKey(session)) return true
        return try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, true,
            ).setPreferredFrameDuration(10f)
                .setInputGainAllChannelsTo(preamp)
                .setPreEqAllChannelsTo(buildEq())
                .setLimiterAllChannelsTo(limiter())
                .build()
            val dp = DynamicsProcessing(PRIORITY, session, cfg)
            runCatching { for (ch in 0 until 2) dp.setInputGainbyChannel(ch, gainFor(ch)) }
            dp.enabled = active
            effects[session] = dp
            true
        } catch (e: Throwable) {
            Log.w(TAG, "attach $session", e)
            false
        }
    }

    fun detach(session: Int) {
        effects.remove(session)?.let { runCatching { it.release() } }
    }

    fun setActive(on: Boolean) {
        active = on
        effects.values.forEach { runCatching { it.enabled = on } }
    }

    /** Sessions de lecteurs (hors session globale 0). */
    fun playerSessions(): Int = effects.keys.count { it != 0 }

    fun hasGlobal(): Boolean = effects.containsKey(0)

    fun releaseAll() {
        effects.values.forEach { runCatching { it.release() } }
        effects.clear()
    }
}
