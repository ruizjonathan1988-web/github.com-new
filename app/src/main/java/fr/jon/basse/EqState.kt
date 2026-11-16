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
    /** Change de réglage selon l'appli au premier plan. */
    val autoApp: Boolean = false,
    /** Règles perso : paquet -> "music", "video" ou identifiant de style. */
    val appRules: Map<String, String> = emptyMap(),
    /** Correction FT1 seulement quand le FiiO (ou un casque filaire) est branché. */
    val ft1Auto: Boolean = true,
    /** Basse en pause sur le haut-parleur du téléphone. */
    val pauseOnSpeaker: Boolean = true,
    /** Remonte sub et aigus quand le volume est bas. */
    val loudness: Boolean = false,
    /** Préampli réduit : on garde le niveau, le limiteur gère les pics. */
    val keepVolume: Boolean = false,
    val favorites: List<String> = emptyList(),
    /** Balance -1 (gauche) .. +1 (droite), ±6 dB au maximum. */
    val balance: Double = 0.0,
    /** Plafond du limiteur en dBFS (protection). */
    val ceilingDb: Double = -1.0,
    /** Réglages perso créés par Jon. */
    val customs: List<Preset> = emptyList(),
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
    @Volatile private var customsCache: List<Preset> = emptyList()

    private fun persoFamily(list: List<Preset>) = Family(
        "perso", "Mes réglages", androidx.compose.ui.graphics.Color(0xFF2A1E00), androidx.compose.ui.graphics.Color(0xFF0A0700),
        androidx.compose.ui.graphics.Color(0xFFFFD166), "rings", false, list,
    )

    /** Recherche dans les réglages perso puis dans le catalogue. */
    fun find(id: String?): Pair<Family, Preset>? {
        if (id == null) return null
        customsCache.firstOrNull { it.id == id }?.let { return persoFamily(customsCache) to it }
        return catalog.find(id)
    }

    fun parentOf(id: String): Preset? = customsCache.firstOrNull { it.id == id } ?: catalog.parentOf(id)

    fun families(s: EqSettings): List<Family> =
        if (s.customs.isEmpty()) catalog.families else listOf(persoFamily(s.customs)) + catalog.families

    /** Copie le réglage courant (avec vos modifs) en réglage perso et le sélectionne. */
    fun duplicate(ctx: Context, from: Preset) {
        val s = settings.value
        val id = "perso." + System.currentTimeMillis()
        val p = Preset(id, from.name + " perso", from.bpm, "Réglage perso basé sur ${from.name}", styleFilters(s, from))
        update(ctx) { it.copy(customs = it.customs + p) }
        select(ctx, id)
    }

    fun renameCustom(ctx: Context, id: String, name: String) =
        update(ctx) { st -> st.copy(customs = st.customs.map { if (it.id == id) it.copy(name = name) else it }) }

    fun deleteCustom(ctx: Context, id: String) {
        update(ctx) { st ->
            val rest = st.customs.filter { it.id != id }
            st.copy(customs = rest, overrides = st.overrides - id, favorites = st.favorites - id,
                presetId = if (st.presetId == id) st.lastMusicId.takeIf { it != id } ?: "dnb.drum_et_bass" else st.presetId,
                lastMusicId = if (st.lastMusicId == id) "dnb.drum_et_bass" else st.lastMusicId)
        }
    }

    private fun encodeCustoms(list: List<Preset>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().put("id", p.id).put("name", p.name).put("bpm", p.bpm).put("desc", p.desc)
                .put("filters", JSONObject(encode(mapOf("x" to p.filters))).getJSONArray("x")))
        }
    }.toString()

    private fun decodeCustoms(str: String?): List<Preset> = runCatching {
        val a = JSONArray(str ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val fl = decode(JSONObject().put("x", o.getJSONArray("filters")).toString())["x"].orEmpty()
            Preset(o.getString("id"), o.getString("name"), o.optInt("bpm", 0), o.optString("desc"), fl)
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        catalog = Catalog.load(ctx.applicationContext)
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val d = EqSettings()
        customsCache = decodeCustoms(p.getString("customs", null))
        fun valid(id: String?, def: String) = if (find(id) != null) id!! else def
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
            autoApp = p.getBoolean("autoApp", false),
            appRules = runCatching {
                val o = JSONObject(p.getString("appRules", "{}")!!)
                o.keys().asSequence().associateWith { o.getString(it) }
            }.getOrDefault(emptyMap()),
            ft1Auto = p.getBoolean("ft1Auto", true),
            pauseOnSpeaker = p.getBoolean("pauseSpeaker", true),
            loudness = p.getBoolean("loudness", false),
            keepVolume = p.getBoolean("keepVolume", false),
            favorites = p.getString("favorites", "")!!.split(",").filter { find(it) != null },
            balance = p.getFloat("balance", 0f).toDouble(),
            ceilingDb = p.getFloat("ceiling", -1f).toDouble(),
            customs = customsCache,
        )
        ready = true
    }

    fun update(ctx: Context, f: (EqSettings) -> EqSettings) {
        _settings.update(f)
        val s = _settings.value
        customsCache = s.customs
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
            .putBoolean("autoApp", s.autoApp)
            .putString("appRules", JSONObject(s.appRules as Map<*, *>).toString())
            .putBoolean("ft1Auto", s.ft1Auto)
            .putBoolean("pauseSpeaker", s.pauseOnSpeaker)
            .putBoolean("loudness", s.loudness)
            .putBoolean("keepVolume", s.keepVolume)
            .putString("favorites", s.favorites.joinToString(","))
            .putFloat("balance", s.balance.toFloat())
            .putFloat("ceiling", s.ceilingDb.toFloat())
            .putString("customs", encodeCustoms(s.customs))
            .apply()
    }

    fun select(ctx: Context, id: String) {
        val fam = find(id)?.first ?: return
        update(ctx) {
            if (fam.isVideo) it.copy(presetId = id, lastVideoId = id) else it.copy(presetId = id, lastMusicId = id)
        }
    }

    fun setStatus(s: EngineStatus) { _status.value = s }

    private val _output = MutableStateFlow(OutputInfo())
    val output: StateFlow<OutputInfo> = _output.asStateFlow()
    fun setOutput(o: OutputInfo) { _output.value = o }

    /** Dernière appli détectée au premier plan (hors Basse et lanceur). */
    private val _lastApp = MutableStateFlow<String?>(null)
    val lastApp: StateFlow<String?> = _lastApp.asStateFlow()

    /** Appelé par le service quand une appli passe au premier plan. */
    fun onForegroundApp(ctx: Context, pkg: String) {
        if (AppRules.ignored(ctx, pkg)) return
        _lastApp.value = pkg
        val s = settings.value
        if (!s.autoApp) return
        val rule = s.appRules[pkg] ?: AppRules.builtIn[pkg] ?: return
        if (rule == AppRules.NONE) return
        val target = when (rule) {
            AppRules.MUSIC -> s.lastMusicId
            AppRules.VIDEO -> s.lastVideoId
            else -> rule
        }
        if (find(target) == null || target == s.presetId) return
        // on ne touche pas aux "derniers" choisis : la bascule ne doit pas écraser vos préférences
        update(ctx) { it.copy(presetId = target) }
    }

    /** La correction FT1 s'applique-t-elle avec cette sortie ? */
    fun ft1Effective(s: EqSettings, o: OutputInfo): Boolean = when {
        !s.tweaks.ft1 -> false
        !s.ft1Auto -> true
        else -> o.kind == OutputKind.FIIO || o.kind == OutputKind.WIRED || o.kind == OutputKind.UNKNOWN
    }

    fun pausedBySpeaker(s: EqSettings, o: OutputInfo) = s.pauseOnSpeaker && o.kind == OutputKind.SPEAKER

    /** Filtres du style : version modifiée si elle existe, sinon l'origine. */
    fun styleFilters(s: EqSettings, preset: Preset): List<Filter> = s.overrides[preset.id] ?: preset.filters

    /** Chaîne complète de filtres actuellement demandée. */
    fun currentChain(s: EqSettings = settings.value, o: OutputInfo = output.value, vol: Float = volume.value): List<Filter> {
        val preset = find(s.presetId)?.second ?: return emptyList()
        val base = Curve.chain(catalog.base, styleFilters(s, preset), s.tweaks.copy(ft1 = ft1Effective(s, o)))
        val l = loudnessDb(s, vol)
        return if (l <= 0.05) base else base + listOf(Filter("LS", 70.0, l, 0.7), Filter("HS", 10000.0, l * 0.5, 0.7))
    }

    /** 0 dB au-dessus de 60 % du volume, jusqu'à +6 dB de grave au plus bas. */
    fun loudnessDb(s: EqSettings, vol: Float): Double =
        if (!s.loudness) 0.0 else (((0.6f - vol) / 0.6f).coerceIn(0f, 1f) * 6f).toDouble()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    fun setVolume(v: Float) { if (kotlin.math.abs(v - _volume.value) > 0.01f) _volume.value = v }

    fun toggleFavorite(ctx: Context, id: String) = update(ctx) {
        it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id)
    }

    /** Passe au favori suivant (tuile / notification). */
    fun nextFavorite(ctx: Context) {
        val s = settings.value
        if (s.favorites.isEmpty()) return
        val i = s.favorites.indexOf(s.presetId)
        select(ctx, s.favorites[(i + 1) % s.favorites.size])
    }

    // ---------- Sauvegarde ----------
    fun exportJson(): String {
        val s = settings.value
        return JSONObject()
            .put("app", "basse").put("version", 1)
            .put("overrides", JSONObject(encode(s.overrides)))
            .put("appRules", JSONObject(s.appRules as Map<*, *>))
            .put("favorites", JSONArray(s.favorites))
            .put("customs", JSONArray(encodeCustoms(s.customs)))
            .put("balance", s.balance).put("ceiling", s.ceilingDb)
            .put("bass", s.tweaks.bassDb).put("treble", s.tweaks.trebleDb).put("ft1", s.tweaks.ft1)
            .toString(1)
    }

    fun importJson(ctx: Context, text: String): Boolean = runCatching {
        val o = JSONObject(text)
        require(o.optString("app") == "basse")
        val ov = decode(o.getJSONObject("overrides").toString())
        val rules = o.getJSONObject("appRules").let { r -> r.keys().asSequence().associateWith { r.getString(it) } }
        val fav = o.getJSONArray("favorites").let { a -> (0 until a.length()).map { a.getString(it) } }.filter { find(it) != null || it.startsWith("perso.") }
        val cus = decodeCustoms(o.optJSONArray("customs")?.toString())
        customsCache = cus
        update(ctx) {
            it.copy(customs = cus, overrides = ov, appRules = rules, favorites = fav,
                balance = o.optDouble("balance", 0.0), ceilingDb = o.optDouble("ceiling", -1.0),
                tweaks = it.tweaks.copy(bassDb = o.optDouble("bass", 0.0), trebleDb = o.optDouble("treble", 0.0), ft1 = o.optBoolean("ft1", true)))
        }
        true
    }.getOrDefault(false)

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
