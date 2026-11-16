package fr.jon.basse

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class EqSettings(
    val enabled: Boolean = false,
    val presetId: String = "dnb.drum_et_bass",
    val lastMusicId: String = "dnb.drum_et_bass",
    val lastVideoId: String = "video.cinema",
    val tweaks: UserTweaks = UserTweaks(),
    val globalMode: Boolean = true,
    /** Réglages modifiés par Jon, par identifiant de style. Absent = réglage d'origine. */
    val overrides: Map<String, List<Filter>> = emptyMap(),
)

data class EngineStatus(
    val globalOk: Boolean? = null,
    val sessions: Int = 0,
    val error: String? = null,
)

/** État partagé entre l'interface et le service, sauvegardé dans les préférences. */
object EqState {
    private const val P = "basse_prefs"
    lateinit var catalog: Catalog
        private set

    private val _settings = MutableStateFlow(EqSettings())
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    @Volatile private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        catalog = Catalog.load(ctx.applicationContext)
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val d = EqSettings()
        fun valid(id: String?, def: String) = if (catalog.find(id) != null) id!! else def
        _settings.value = EqSettings(
            enabled = p.getBoolean("enabled", d.enabled),
            presetId = valid(p.getString("preset", null), d.presetId),
            lastMusicId = valid(p.getString("lastMusic", null), d.lastMusicId),
            lastVideoId = valid(p.getString("lastVideo", null), d.lastVideoId),
            tweaks = UserTweaks(
                ft1 = p.getBoolean("ft1", true),
                bassDb = p.getFloat("bass", 0f).toDouble(),
                trebleDb = p.getFloat("treble", 0f).toDouble(),
            ),
            globalMode = p.getBoolean("global", true),
            overrides = decode(p.getString("overrides", null)),
        )
        ready = true
    }

    fun update(ctx: Context, f: (EqSettings) -> EqSettings) {
        _settings.update(f)
        val s = _settings.value
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putString("preset", s.presetId)
            .putString("lastMusic", s.lastMusicId)
            .putString("lastVideo", s.lastVideoId)
            .putBoolean("ft1", s.tweaks.ft1)
            .putFloat("bass", s.tweaks.bassDb.toFloat())
            .putFloat("treble", s.tweaks.trebleDb.toFloat())
            .putBoolean("global", s.globalMode)
            .putString("overrides", encode(s.overrides))
            .apply()
    }

    fun select(ctx: Context, id: String) {
        val fam = catalog.find(id)?.first ?: return
        update(ctx) {
            if (fam.isVideo) it.copy(presetId = id, lastVideoId = id) else it.copy(presetId = id, lastMusicId = id)
        }
    }

    fun setStatus(s: EngineStatus) { _status.value = s }

    /** Filtres du style : version modifiée si elle existe, sinon l'origine. */
    fun styleFilters(s: EqSettings, preset: Preset): List<Filter> = s.overrides[preset.id] ?: preset.filters

    /** Chaîne complète de filtres actuellement demandée. */
    fun currentChain(s: EqSettings = settings.value): List<Filter> {
        val preset = catalog.find(s.presetId)?.second ?: return emptyList()
        return Curve.chain(catalog.base, styleFilters(s, preset), s.tweaks)
    }

    private fun encode(m: Map<String, List<Filter>>): String {
        val o = JSONObject()
        for ((k, list) in m) o.put(k, JSONArray().apply {
            list.forEach { put(JSONObject().put("type", it.type).put("f", it.f).put("g", it.g).put("q", it.q)) }
        })
        return o.toString()
    }

    private fun decode(str: String?): Map<String, List<Filter>> {
        if (str.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(str)
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                (0 until a.length()).map { i ->
                    val f = a.getJSONObject(i)
                    Filter(f.getString("type"), f.getDouble("f"), f.getDouble("g"), f.getDouble("q"))
                }
            }
        }.getOrDefault(emptyMap())
    }
}
