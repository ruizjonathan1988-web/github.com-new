package fr.jon.basse

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlin.math.sqrt

/**
 * DynamicsProcessing (Android 9+) : préampli par canal (balance, volume fin), EQ 64 bandes
 * échantillonné depuis la courbe paramétrique, compresseur 3 bandes "Punch", limiteur final.
 */
class EqEngine {
    companion object {
        private const val TAG = "BasseEq"
        const val BANDS = 64
        private const val PRIORITY = Int.MAX_VALUE
        private val MBC_CUT = floatArrayOf(120f, 2000f, 20000f)
    }

    private val effects = LinkedHashMap<Int, DynamicsProcessing>()
    private val bandCutoffs = FloatArray(BANDS) { (20.0 * Math.pow(1000.0, (it + 1).toDouble() / BANDS)).toFloat() }
    private val bandGains = FloatArray(BANDS)
    private var preamp = 0f
    private var balance = 0f
    private var ceiling = -1f
    private var punch = 0
    var active = false
        private set

    fun setCurve(chain: List<Filter>, preampDb: Double, balance: Double, ceilingDb: Double, punch: Int) {
        val k = chain.map { Curve.coeffs(it) }
        for (i in 0 until BANDS) {
            val lo = if (i == 0) 10.0 else bandCutoffs[i - 1].toDouble()
            bandGains[i] = Curve.responseDb(k, sqrt(lo * bandCutoffs[i])).toFloat().coerceIn(-30f, 15f)
        }
        preamp = preampDb.toFloat()
        this.balance = balance.toFloat()
        ceiling = ceilingDb.toFloat()
        this.punch = punch.coerceIn(0, 3)
        effects.values.forEach { push(it) }
    }

    private fun buildEq(): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, BANDS)
        for (i in 0 until BANDS) eq.setBand(i, DynamicsProcessing.EqBand(true, bandCutoffs[i], bandGains[i]))
        return eq
    }

    private fun limiter() = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, ceiling, 0f)

    /** Punch : le grave est compressé avec une attaque lente (le transitoire du kick passe), puis remonté. */
    private fun mbcBand(i: Int): DynamicsProcessing.MbcBand {
        val on = punch > 0
        val (ratio, thr, makeup) = when (i) {
            0 -> Triple(floatArrayOf(1f, 2f, 3f, 4.5f)[punch], floatArrayOf(0f, -18f, -22f, -26f)[punch], floatArrayOf(0f, 2f, 3.5f, 5f)[punch])
            1 -> Triple(floatArrayOf(1f, 1.4f, 1.8f, 2.2f)[punch], floatArrayOf(0f, -16f, -18f, -20f)[punch], floatArrayOf(0f, 0.8f, 1.5f, 2f)[punch])
            else -> Triple(floatArrayOf(1f, 1.2f, 1.4f, 1.6f)[punch], floatArrayOf(0f, -18f, -20f, -22f)[punch], floatArrayOf(0f, 0.3f, 0.6f, 1f)[punch])
        }
        val attack = if (i == 0) 18f else 8f
        val release = if (i == 0) 140f else 90f
        return DynamicsProcessing.MbcBand(on, MBC_CUT[i], attack, release, ratio, thr, 6f, -90f, 1f, 0f, makeup)
    }

    private fun gainFor(ch: Int): Float {
        val cut = kotlin.math.abs(balance) * 6f
        return preamp - when { ch == 0 && balance > 0 -> cut; ch == 1 && balance < 0 -> cut; else -> 0f }
    }

    private fun push(dp: DynamicsProcessing) {
        try {
            for (ch in 0 until 2) dp.setInputGainbyChannel(ch, gainFor(ch))
            dp.setPreEqAllChannelsTo(buildEq())
            for (b in 0 until 3) dp.setMbcBandAllChannelsTo(b, mbcBand(b))
            dp.setLimiterAllChannelsTo(limiter())
            dp.enabled = active
        } catch (e: Exception) {
            Log.w(TAG, "push", e)
        }
    }

    fun attach(session: Int): Boolean {
        if (effects.containsKey(session)) return true
        return try {
            val b = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, true, 3, false, 0, true,
            ).setPreferredFrameDuration(10f)
                .setPreEqAllChannelsTo(buildEq())
                .setLimiterAllChannelsTo(limiter())
            val mbc = DynamicsProcessing.Mbc(true, true, 3)
            for (i in 0 until 3) mbc.setBand(i, mbcBand(i))
            b.setMbcAllChannelsTo(mbc)
            val dp = DynamicsProcessing(PRIORITY, session, b.build())
            runCatching { for (ch in 0 until 2) dp.setInputGainbyChannel(ch, gainFor(ch)) }
            dp.enabled = active
            effects[session] = dp
            true
        } catch (e: Throwable) {
            Log.w(TAG, "attach $session", e)
            false
        }
    }

    fun detach(session: Int) { effects.remove(session)?.let { runCatching { it.release() } } }

    fun setActive(on: Boolean) {
        active = on
        effects.values.forEach { runCatching { it.enabled = on } }
    }

    fun playerSessions(): Int = effects.keys.count { it != 0 }

    /** Sessions trouvées par le scanner : on s'accroche aux nouvelles, on lâche celles qui ont disparu. */
    private val scanned = HashSet<Int>()
    fun syncScanned(found: Set<Int>) {
        (scanned - found).forEach { detach(it) }
        scanned.retainAll(found)
        found.forEach { if (attach(it)) scanned += it }
    }
    fun hasGlobal(): Boolean = effects.containsKey(0)

    fun releaseAll() {
        effects.values.forEach { runCatching { it.release() } }
        effects.clear()
    }
}
