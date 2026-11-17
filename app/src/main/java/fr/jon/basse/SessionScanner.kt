package fr.jon.basse

import android.content.Context
import android.content.pm.PackageManager

/**
 * Trouve les sessions audio des lecteurs en cours (Spotify, YouTube Music…) quand ils ne les signalent pas.
 * Nécessite l'autorisation DUMP, accordée une seule fois depuis un ordinateur :
 *   adb shell pm grant fr.jon.basse android.permission.DUMP
 * (même principe que Wavelet).
 */
object SessionScanner {
    const val ADB_CMD = "adb shell pm grant fr.jon.basse android.permission.DUMP"

    fun granted(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    private val sessionRe = Regex("""session(?:Id)?[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val uidRe = Regex("""u/pid:(\d+)/""")

    /** Sessions des lecteurs média actifs (hors Basse). */
    fun scan(ctx: Context): Set<Int> {
        if (!granted(ctx)) return emptySet()
        val myUid = android.os.Process.myUid()
        val out = HashSet<Int>()
        // 1) Configurations de lecture du service audio
        run("dumpsys", "audio")?.lineSequence()?.forEach { line ->
            if (!line.contains("AudioPlaybackConfiguration")) return@forEach
            if (!line.contains("state:started")) return@forEach
            if (!(line.contains("USAGE_MEDIA") || line.contains("USAGE_GAME") || line.contains("USAGE_UNKNOWN"))) return@forEach
            val uid = uidRe.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (uid == myUid) return@forEach
            sessionRe.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > 0) out += it }
        }
        // 2) Pistes d'AudioFlinger (colonne Session), si la première source ne donne rien
        if (out.isEmpty()) run("dumpsys", "media.audio_flinger")?.let { parseFlinger(it, out) }
        return out
    }

    private fun parseFlinger(txt: String, out: MutableSet<Int>) {
        var col = -1
        for (raw in txt.lineSequence()) {
            val t = raw.trim().split(Regex("\\s+"))
            if (t.contains("Session") && (t.contains("Active") || t.contains("Client"))) { col = t.indexOf("Session"); continue }
            if (col < 0) continue
            if (raw.isBlank() || !raw.startsWith(" ")) { col = -1; continue }
            // les lignes de piste ont parfois un marqueur en tête (« F », « * ») : on aligne sur la fin si besoin
            val v = t.getOrNull(col)?.toIntOrNull() ?: t.getOrNull(col + 1)?.toIntOrNull()
            val active = t.contains("yes") || t.contains("A")
            if (v != null && v > 0 && active) out += v
        }
    }

    private fun run(vararg cmd: String): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val txt = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        if (txt.contains("Permission Denial")) null else txt
    }.getOrNull()
}
