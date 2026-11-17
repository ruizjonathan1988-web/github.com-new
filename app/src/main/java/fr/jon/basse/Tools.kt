package fr.jon.basse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// =====================================================================
// Analyse du son en sortie (spectre + détection du contenu)
// =====================================================================

object Analyzer {
    const val N_BANDS = 32
    private val _spectrum = MutableStateFlow(FloatArray(N_BANDS) { -90f })
    /** Niveau par bande (dB relatifs), 20 Hz – 20 kHz. */
    val spectrum: StateFlow<FloatArray> = _spectrum.asStateFlow()
    private val _guess = MutableStateFlow<String?>(null)
    /** Ce que l'analyse pense entendre (texte affiché). */
    val guess: StateFlow<String?> = _guess.asStateFlow()

    @Volatile var uiVisible = false
    private var viz: Visualizer? = null
    private var failedAt = 0L
    private var detector: ContentDetector? = null

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** À appeler régulièrement : démarre/arrête le Visualizer selon le besoin. */
    fun ensure(ctx: Context, want: Boolean) {
        if (!want || !hasPermission(ctx)) { stop(); return }
        if (viz != null) return
        if (System.currentTimeMillis() - failedAt < 60_000) return
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(2048)
            val rate = Visualizer.getMaxCaptureRate()
            val det = ContentDetector(rate / 1000f) { id, label ->
                _guess.value = label
                if (id != null) EqState.onContentGuess(ctx.applicationContext, id)
            }
            detector = det
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, wave: ByteArray?, sr: Int) {}
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, sr: Int) {
                    if (fft != null) onFft(fft, sr / 1000f)
                }
            }, rate, false, true)
            v.enabled = true
            viz = v
        } catch (e: Throwable) {
            _guess.value = "Analyse indisponible sur cet appareil"
            failedAt = System.currentTimeMillis()
            stop()
        }
    }

    fun stop() {
        viz?.let { runCatching { it.enabled = false; it.release() } }
        viz = null
        detector = null
    }

    private val edges = DoubleArray(N_BANDS + 1) { 20.0 * 1000.0.pow(it.toDouble() / N_BANDS) }

    private fun onFft(fft: ByteArray, fs: Float) {
        val n = fft.size // = captureSize ; bins 0..n/2
        val binHz = fs / n
        val mags = FloatArray(n / 2)
        for (k in 1 until n / 2) {
            val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
            mags[k] = re * re + im * im
        }
        val out = FloatArray(N_BANDS)
        for (b in 0 until N_BANDS) {
            val k0 = (edges[b] / binHz).toInt().coerceIn(1, n / 2 - 1)
            val k1 = (edges[b + 1] / binHz).toInt().coerceIn(k0, n / 2 - 1)
            var sum = 0f
            for (k in k0..k1) sum += mags[k]
            out[b] = (10 * log10(max(sum / (k1 - k0 + 1), 1e-3f).toDouble())).toFloat()
        }
        _spectrum.value = out
        fun band(lo: Double, hi: Double): Float {
            val k0 = (lo / binHz).toInt().coerceIn(1, n / 2 - 1); val k1 = (hi / binHz).toInt().coerceIn(k0, n / 2 - 1)
            var s = 0f; for (k in k0..k1) s += mags[k]; return s
        }
        detector?.push(band(35.0, 150.0), band(300.0, 3000.0), band(3000.0, 12000.0))
    }
}

/**
 * Détection très simple du contenu : tempo par autocorrélation de l'enveloppe du grave,
 * part de sub, et voix parlée (beaucoup de médiums, peu de grave, pas de pulsation).
 * Ne propose qu'une famille ; il faut deux avis identiques à 10 s d'écart, et un blanc après un choix manuel.
 */
class ContentDetector(private val rate: Float, private val onDecision: (String?, String) -> Unit) {
    private val size = (rate * 20).toInt().coerceAtLeast(60)
    private val onset = FloatArray(size)
    private val lowArr = FloatArray(size); private val midArr = FloatArray(size); private val highArr = FloatArray(size)
    private var idx = 0; private var filled = 0
    private var prevLow = 0f
    private var frames = 0
    private var lastGuess: String? = null
    private var lastSwitch = 0L
    private var silentFrames = 0
    private var gapSinceManual = false

    fun push(low: Float, mid: Float, high: Float) {
        val total = low + mid + high
        if (total < 50f) { // blanc entre deux morceaux
            silentFrames++
            if (silentFrames > rate * 1.2f) { filled = 0; lastGuess = null; gapSinceManual = true }
            return
        }
        silentFrames = 0
        val lv = log10(low + 1f)
        onset[idx] = max(0f, lv - prevLow); prevLow = lv
        lowArr[idx] = low; midArr[idx] = mid; highArr[idx] = high
        idx = (idx + 1) % size; if (filled < size) filled++
        frames++
        if (filled >= size * 3 / 4 && frames % (rate * 10).toInt().coerceAtLeast(1) == 0) evaluate()
    }

    private fun evaluate() {
        val n = filled
        val x = FloatArray(n) { onset[(idx - n + it + size) % size] }
        val mean = x.average().toFloat()
        for (i in x.indices) x[i] -= mean
        var e0 = 0f; for (v in x) e0 += v * v
        if (e0 <= 0f) return
        var bestLag = 0; var best = 0f
        val minLag = (rate * 60 / 200).toInt().coerceAtLeast(1)
        val maxLag = (rate * 60 / 70).toInt().coerceAtMost(n / 2)
        val ac = FloatArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var s = 0f; for (i in 0 until n - lag) s += x[i] * x[i + lag]
            ac[lag] = s / e0
            if (ac[lag] > best) { best = ac[lag]; bestLag = lag }
        }
        // affinage parabolique
        var lagF = bestLag.toFloat()
        if (bestLag in (minLag + 1) until maxLag) {
            val a = ac[bestLag - 1]; val b = ac[bestLag]; val c = ac[bestLag + 1]
            val d = a - 2 * b + c
            if (d != 0f) lagF += 0.5f * (a - c) / d
        }
        var bpm = if (lagF > 0) 60f * rate / lagF else 0f
        val low = lowArr.take(n).sum(); val mid = midArr.take(n).sum(); val high = highArr.take(n).sum()
        val tot = low + mid + high
        val bass = low / tot; val mids = mid / tot
        // demi-tempo (DnB entendue à 87) : on double si le grave est fort
        if (bpm in 70f..100f && bass > 0.25f) bpm *= 2f

        val (id, label) = when {
            best < 0.18f && bass < 0.12f && mids > 0.55f -> "video.series_dialogues" to "Voix parlée"
            best < 0.15f -> null to "Pas de pulsation nette"
            bpm in 158f..195f -> "dnb.drum_et_bass" to "≈ %.0f BPM · drum & bass / jungle".format(bpm)
            bpm in 145f..158f && bass > 0.3f -> "techno.hard_techno" to "≈ %.0f BPM · hard techno".format(bpm)
            bpm in 135f..145f && bass > 0.38f -> "bass.dubstep" to "≈ %.0f BPM · dubstep / bass".format(bpm)
            bpm in 135f..150f -> "trance.trance" to "≈ %.0f BPM · trance / psy".format(bpm)
            bpm in 127f..135f -> "techno.techno" to "≈ %.0f BPM · techno".format(bpm)
            bpm in 115f..127f -> "house.house" to "≈ %.0f BPM · house".format(bpm)
            bpm in 85f..115f && bass > 0.3f -> "hiphop.rap_fr" to "≈ %.0f BPM · hip-hop".format(bpm)
            bpm in 85f..115f -> "pop.pop" to "≈ %.0f BPM · pop".format(bpm)
            bpm > 195f -> "hard.hardcore" to "≈ %.0f BPM · hardcore".format(bpm)
            else -> "chill.trip_hop" to "≈ %.0f BPM · downtempo".format(bpm)
        }
        val now = System.currentTimeMillis()
        val manualRecent = EqState.lastManualAt > lastSwitch && !gapSinceManual
        val stable = id != null && id == lastGuess
        lastGuess = id
        if (stable && !manualRecent && now - lastSwitch > 30_000) {
            lastSwitch = now
            gapSinceManual = false
            onDecision(id, label)
        } else onDecision(null, label)
    }
}

// =====================================================================
// Exposition sonore (estimation)
// =====================================================================

data class ExposureState(val dosePct: Float = 0f, val levelDb: Float = 0f, val counting: Boolean = false)

object Exposure {
    private const val P = "basse_expo"
    private const val CH = "basse_expo"
    private val _state = MutableStateFlow(ExposureState())
    val state: StateFlow<ExposureState> = _state.asStateFlow()

    /** Courbe de volume média Android approximative : position (0-1) -> dB. */
    private fun volDb(v: Float): Float {
        if (v <= 0f) return -96f
        val pts = arrayOf(0.01f to -58f, 0.33f to -35f, 0.66f to -17f, 1f to 0f)
        if (v <= pts[0].first) return pts[0].second
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]; val (x1, y1) = pts[i]
            if (v <= x1) return y0 + (y1 - y0) * (v - x0) / (x1 - x0)
        }
        return 0f
    }

    /** Niveau maxi estimé : FT1 (98 dB/mW) sur BTR11 (≈45 mW) ≈ 114 dB. */
    private fun maxSpl(o: OutputInfo) = when (o.kind) {
        OutputKind.FIIO -> 114f
        OutputKind.WIRED -> 110f
        OutputKind.BLUETOOTH -> 105f
        else -> -1f
    }

    fun tick(ctx: Context, dtSec: Float) {
        val s = EqState.settings.value
        val o = EqState.output.value
        val am = ctx.getSystemService(AudioManager::class.java)
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        var dose = if (p.getString("day", "") == today) p.getFloat("dose", 0f) else 0f
        var warned = if (p.getString("day", "") == today) p.getInt("warned", 0) else 0
        val max = if (EqState.isCar(s, o)) -1f else maxSpl(o)
        val counting = s.exposureOn && max > 0 && am.isMusicActive
        var level = 0f
        if (counting) {
            val chain = EqState.currentChain(s, o)
            val pre = if (s.enabled) EqState.preampDb(s, chain).toFloat() + s.punch * 1.2f else 0f
            // un master électro fort tourne autour de -9 dBFS en moyenne
            level = max + volDb(EqState.volume.value) - 9f + pre
            if (level > 70f) {
                val allowed = 8f * 3600f / 2f.pow((level - 85f) / 3f)
                dose += dtSec / allowed
            }
        }
        p.edit().putString("day", today).putFloat("dose", dose).putInt("warned", warned).apply()
        _state.value = ExposureState(dose * 100f, level, counting)
        if (s.exposureOn) {
            val pct = dose * 100f
            if (pct >= 100f && warned < 2) { notify(ctx, "Dose sonore du jour atteinte", "Environ 100 % de la dose quotidienne recommandée (estimation). Une pause ou un volume plus bas protège vos oreilles."); warned = 2 }
            else if (pct >= 50f && warned < 1) { notify(ctx, "Moitié de la dose sonore du jour", "Environ 50 % de la dose quotidienne (estimation)."); warned = 1 }
            p.edit().putInt("warned", warned).apply()
        }
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Exposition sonore", NotificationManager.IMPORTANCE_DEFAULT))
        val n = android.app.Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).build()
        runCatching { nm.notify(29, n) }
    }
}

// =====================================================================
// Import AutoEq (ParametricEQ.txt)
// =====================================================================

object AutoEqImport {
    private val re = Regex("""Filter\s*\d+\s*:\s*ON\s+([A-Z]+)\s+Fc\s+([\d.]+)\s*Hz(?:\s+Gain\s+([-+\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Filter> = re.findAll(text).mapNotNull { m ->
        val t = when (m.groupValues[1].uppercase()) {
            "LSC", "LS", "LSQ" -> "LS"; "HSC", "HS", "HSQ" -> "HS"; "LP", "LPQ" -> "LP"; "HP", "HPQ" -> "HP"; "NO" -> "NO"; "PK", "PEQ" -> "PK"
            else -> return@mapNotNull null
        }
        Filter(t, m.groupValues[2].toDouble(), m.groupValues[3].toDoubleOrNull() ?: 0.0, m.groupValues[4].toDoubleOrNull() ?: 0.71)
    }.toList()
}

// =====================================================================
// Synchro Fold <-> box sur le Wi-Fi (réseau local, sans serveur)
// =====================================================================

object LanSync {
    private const val TYPE = "_basse._tcp."
    private val main = Handler(Looper.getMainLooper())

    /** Partage les réglages pendant 3 minutes. */
    fun share(ctx: Context, onStatus: (String) -> Unit) {
        val app = ctx.applicationContext
        val nsd = app.getSystemService(NsdManager::class.java)
        Thread {
            try {
                val server = ServerSocket(0)
                server.soTimeout = 1000
                val info = NsdServiceInfo().apply {
                    serviceName = "Basse " + android.os.Build.MODEL
                    serviceType = TYPE
                    port = server.localPort
                }
                val reg = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(i: NsdServiceInfo) { main.post { onStatus("Partage actif (3 min). Sur l'autre appareil : « Récupérer ».") } }
                    override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { main.post { onStatus("Échec du partage ($e)") } }
                    override fun onServiceUnregistered(i: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
                }
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
                val end = System.currentTimeMillis() + 180_000
                var sent = 0
                while (System.currentTimeMillis() < end) {
                    val sock = try { server.accept() } catch (e: java.net.SocketTimeoutException) { null } ?: continue
                    sock.use { c ->
                        val body = EqState.exportJson().toByteArray(Charsets.UTF_8)
                        c.getInputStream().bufferedReader().readLine()
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
                    }
                    sent++
                    main.post { onStatus("Réglages envoyés à un appareil.") }
                }
                runCatching { nsd.unregisterService(reg) }
                server.close()
                main.post { onStatus(if (sent > 0) "Partage terminé." else "Partage terminé, aucun appareil ne s'est connecté.") }
            } catch (e: Exception) {
                main.post { onStatus("Erreur de partage : ${e.message}") }
            }
        }.start()
    }

    /** Cherche un appareil qui partage et importe ses réglages. */
    @Suppress("DEPRECATION")
    fun receive(ctx: Context, onStatus: (String) -> Unit) {
        val app = ctx.applicationContext
        val nsd = app.getSystemService(NsdManager::class.java)
        var done = false
        lateinit var disc: NsdManager.DiscoveryListener
        disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { main.post { onStatus("Recherche d'un appareil Basse sur le Wi-Fi…") } }
            override fun onServiceFound(s: NsdServiceInfo) {
                if (done || !s.serviceType.startsWith("_basse")) return
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        if (done) return
                        done = true
                        runCatching { nsd.stopServiceDiscovery(disc) }
                        Thread {
                            val msg = try {
                                val url = URL("http://${i.host.hostAddress}:${i.port}/")
                                val c = url.openConnection() as HttpURLConnection
                                c.connectTimeout = 5000; c.readTimeout = 5000
                                val txt = c.inputStream.bufferedReader().use { it.readText() }
                                var ok = false
                                val lock = Object()
                                main.post { ok = EqState.importJson(app, txt); synchronized(lock) { lock.notifyAll() } }
                                synchronized(lock) { lock.wait(3000) }
                                if (ok) "Réglages récupérés depuis ${i.serviceName}." else "Réponse non reconnue."
                            } catch (e: Exception) { "Erreur : ${e.message}" }
                            main.post { onStatus(msg) }
                        }.start()
                    }
                })
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { main.post { onStatus("Recherche impossible ($e)") } }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        main.postDelayed({
            if (!done) { done = true; runCatching { nsd.stopServiceDiscovery(disc) }; onStatus("Aucun appareil trouvé. Lancez « Partager » sur l'autre, même Wi-Fi.") }
        }, 25_000)
    }
}

// =====================================================================
// Widget (écran d'accueil et écran externe du Fold)
// =====================================================================

class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        EqState.init(ctx)
        ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        fun refreshAll(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
                if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
            }
        }

        private fun views(ctx: Context): RemoteViews {
            val s = EqState.settings.value
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_style, EqState.find(s.presetId)?.second?.name ?: "—")
            v.setTextViewText(R.id.w_power, if (s.enabled) "ON" else "OFF")
            v.setInt(R.id.w_power, "setBackgroundResource", if (s.enabled) R.drawable.w_btn_on else R.drawable.w_btn)
            fun pi(action: String, code: Int) = PendingIntent.getForegroundService(
                ctx, code, Intent(ctx, EqService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_power, pi(EqService.ACTION_TOGGLE, 10))
            v.setOnClickPendingIntent(R.id.w_next, pi(EqService.ACTION_NEXT, 11))
            v.setOnClickPendingIntent(R.id.w_style, PendingIntent.getActivity(ctx, 12, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            return v
        }
    }
}

@Suppress("unused")
private fun rms(a: FloatArray) = sqrt(a.map { it * it }.average()).toFloat()
