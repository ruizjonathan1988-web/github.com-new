package fr.jon.basse

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process

// ---------- Sortie audio ----------

enum class OutputKind { SPEAKER, FIIO, BLUETOOTH, WIRED, TV, UNKNOWN }

data class OutputInfo(val kind: OutputKind = OutputKind.UNKNOWN, val name: String = "") {
    val label: String
        get() = when (kind) {
            OutputKind.SPEAKER -> "Haut-parleur du téléphone"
            OutputKind.FIIO -> "FiiO ${name.ifBlank { "Bluetooth" }} (FT1)"
            OutputKind.BLUETOOTH -> "Bluetooth : ${name.ifBlank { "appareil" }}"
            OutputKind.WIRED -> "Casque filaire / USB"
            OutputKind.TV -> "Sortie TV (HDMI)"
            OutputKind.UNKNOWN -> "Sortie inconnue"
        }
}

/** Surveille la sortie audio active (Android route le média vers le casque s'il y en a un). */
class OutputWatcher(ctx: Context, private val onChange: (OutputInfo) -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() { am.registerAudioDeviceCallback(cb, handler); refresh() }
    fun stop() { runCatching { am.unregisterAudioDeviceCallback(cb) } }

    fun refresh() { onChange(current()) }

    fun current(): OutputInfo {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (android.os.Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        if (bt != null) {
            val n = bt.productName?.toString().orEmpty()
            val fiio = n.contains("BTR", true) || n.contains("FiiO", true) || n.contains("FT1", true)
            return OutputInfo(if (fiio) OutputKind.FIIO else OutputKind.BLUETOOTH, n)
        }
        if (outs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
            }) return OutputInfo(OutputKind.WIRED)
        if (outs.any { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                (android.os.Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_HDMI_EARC) })
            return if (outs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) OutputInfo(OutputKind.SPEAKER) else OutputInfo(OutputKind.TV)
        if (outs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) return OutputInfo(OutputKind.SPEAKER)
        return OutputInfo()
    }
}

// ---------- Appli au premier plan ----------

object AppRules {
    const val MUSIC = "music"
    const val VIDEO = "video"
    const val GAMES = "video.jeux_et_emulation"
    const val NONE = "none"

    /** Règles intégrées : musique = dernier style musique, vidéo = dernier réglage vidéo. */
    val builtIn: Map<String, String> = buildMap {
        listOf(
            "com.spotify.music", "com.spotify.tv.android", "com.google.android.apps.youtube.music", "deezer.android.app",
            "com.soundcloud.android", "com.sec.android.app.music", "com.maxmpz.audioplayer", "com.qobuz.music",
            "com.aspiro.tidal", "com.amazon.mp3", "com.apple.android.music", "fm.mixcloud.mixcloud",
        ).forEach { put(it, MUSIC) }
        listOf(
            "com.google.android.youtube", "com.google.android.youtube.tv", "com.netflix.mediaclient", "com.netflix.ninja",
            "org.videolan.vlc", "org.jellyfin.mobile", "org.jellyfin.androidtv", "com.plexapp.android", "com.mxtech.videoplayer.ad",
            "com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.canal.android.canal",
            "fr.francetv.pluzz", "fr.m6.m6replay", "fr.tf1.mytf1", "com.molotov.app", "tv.twitch.android.app", "com.ufc.brazil.app",
            "is.xyz.mpv", "dev.anilbeesetti.nextplayer",
        ).forEach { put(it, VIDEO) }
        listOf(
            "com.retroarch", "com.retroarch.aarch64", "org.dolphinemu.dolphinemu", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
            "com.github.stenzek.duckstation", "xyz.aethersx2.android", "xyz.nethersx2.android", "com.swordfish.lemuroid",
            "com.winlator", "com.explusalpha.Snes9xPlus", "it.dbtecno.pizzaboypro", "org.citra.citra_emu", "io.github.lime3ds.android",
            "skyline.emu", "org.yuzu.yuzu_emu", "dev.eden.eden_emulator", "com.antutu.ABenchMark.emu", "me.magnum.melonds",
            "com.flycast.emulator", "info.cemu.cemu", "org.vita3k.emulator", "io.github.azahar_emu.azahar",
        ).forEach { put(it, GAMES) }
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageSettingsIntent() = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Ignorer : nous-mêmes, lanceurs, interface système. */
    fun ignored(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg == "android" || pkg.startsWith("com.android.systemui")) return true
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return home?.activityInfo?.packageName == pkg ||
            pkg.contains("launcher", true) || pkg == "com.google.android.tvlauncher" || pkg == "com.google.android.apps.tv.launcherx"
    }
}

/** Lit toutes les 2 s la dernière appli passée au premier plan. */
class ForegroundPoller(private val ctx: Context, private val onTick: () -> Unit = {}, private val onApp: (String) -> Unit) {
    private val usm = ctx.getSystemService(UsageStatsManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var last: String? = null
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            poll()
            handler.postDelayed(this, 2000)
        }
    }

    fun start() { handler.removeCallbacks(tick); handler.post(tick) }
    fun stop() { handler.removeCallbacks(tick) }

    private fun poll() {
        if (!AppRules.hasUsageAccess(ctx)) return
        val now = System.currentTimeMillis()
        val ev = runCatching { usm.queryEvents(now - 10_000, now) }.getOrNull() ?: return
        val e = UsageEvents.Event()
        var pkg: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == 1) pkg = e.packageName // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        }
        if (pkg != null && pkg != last) {
            last = pkg
            onApp(pkg)
        }
    }
}
