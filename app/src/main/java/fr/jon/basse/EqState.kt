package fr.jon.basse

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Profil de correction de casque (FT1 intégré, ou importé depuis AutoEq). */
data class HpProfile(val id: String, val name: String, val filters: List<Filter>)

/** Programmation horaire (option, désactivée par défaut). Minutes depuis minuit. */
data class Schedule(
    val enabled: Boolean = false,
    val startMin: Int = 22 * 60,
    val endMin: Int = 7 * 60,
    val presetId: String = "video.nuit",
    val loudness: Boolean = true,
)

data class EqSettings(
    val enabled: Boolean = false,
    val presetId: String = "dnb.drum_et_bass",
    val lastMusicId: String = "dnb.drum_et_bass",
    val lastVideoId: String = "video.cinema",
    val tweaks: UserTweaks = UserTweaks(),
    val globalMode: Boolean = true,
    /** Réglages modifiés par Jon, par identifiant de style. Absent = réglage d'origine. */
    val overrides: Map<String, List<Filter>> = emptyMap(),
    val autoApp: Boolean = false,
    /** paquet -> "music", "video", "none" ou identifiant de style. */
    val appRules: Map<String, String> = emptyMap(),
    /** Correction FT1 seulement quand le FiiO (ou un casque filaire) est branché. */
    val ft1Auto: Boolean = true,
    val pauseOnSpeaker: Boolean = true,
    val loudness: Boolean = false,
    val keepVolume: Boolean = false,
    val favorites: List<String> = emptyList(),
    val balance: Double = 0.0,
    val ceilingDb: Double = -1.0,
    val customs: List<Preset> = emptyList(),
    /** Compresseur multibande : 0 = coupé, 1 léger, 2 moyen, 3 fort. */
    val punch: Int = 0,
    /** Volume fin (atténuation par demi-dB). */
    val fineVolDb: Double = 0.0,
    val locked: List<String> = emptyList(),
    val profiles: List<HpProfile> = emptyList(),
    /** clé de sortie -> "ft1", "none" ou id de profil. */
    val deviceCorrection: Map<String, String> = emptyMap(),
    /** clé de sortie -> style appliqué à la connexion. */
    val devicePreset: Map<String, String> = emptyMap(),
    /** Compensation issue du test auditif. */
    val hearing: List<Filter> = emptyList(),
    val hearingOn: Boolean = true,
    /** Mode curseurs : 10 gains par style. */
    val graphic: Map<String, List<Double>> = emptyMap(),
    val schedule: Schedule = Schedule(),
    val schedActive: Boolean = false,
    val schedPrevPreset: String? = null,
    val schedPrevLoud: Boolean = false,
    val exposureOn: Boolean = true,
    /** Réglage automatique selon le contenu (expérimental). */
    val autoContent: Boolean = false,
    /** Sorties marquées "voiture ou enceinte" (pas de compteur d'exposition, pas de correction casque). */
    val carDevices: List<String> = emptyList(),
)

data class EngineStatus(val globalOk: Boolean? = null, val sessions: Int = 0, val error: String? = null)

object EqState {
    private const val P = "basse_prefs"
    const val FT1 = "ft1"
    const val NONE = "none"
    const val CX5 = "cx5"
    const val CX5_DEFAULT_STYLE = "cx5.reference_voiture"
    val GRAPHIC_FREQS = doubleArrayOf(31.0, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    lateinit var catalog: Catalog
        private set

    private val _settings = MutableStateFlow(EqSettings())
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()
    private val _output = MutableStateFlow(OutputInfo())
    val output: StateFlow<OutputInfo> = _output.asStateFlow()
    private val _lastApp = MutableStateFlow<String?>(null)
    val lastApp: StateFlow<String?> = _lastApp.asStateFlow()
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()
    /** Vrai pendant le test auditif : Basse s'efface. */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    @Volatile private var ready = false
    @Volatile private var customsCache: List<Preset> = emptyList()

    // ---------- Catalogue + réglages perso ----------

    private fun persoFamily(list: List<Preset>) = Family(
        "perso", "Mes réglages", androidx.compose.ui.graphics.Color(0xFF2A1E00), androidx.compose.ui.graphics.Color(0xFF0A0700),
        androidx.compose.ui.graphics.Color(0xFFFFD166), "rings", false, list,
    )

    fun find(id: String?): Pair<Family, Preset>? {
        if (id == null) return null
        customsCache.firstOrNull { it.id == id }?.let { return persoFamily(customsCache) to it }
        return catalog.find(id)
    }

    fun parentOf(id: String): Preset? = customsCache.firstOrNull { it.id == id } ?: catalog.parentOf(id)

    fun families(s: EqSettings): List<Family> =
        if (s.customs.isEmpty()) catalog.families else listOf(persoFamily(s.customs)) + catalog.families

    /** Tous les réglages, à plat, pour la recherche. */
    fun allPresets(s: EqSettings): List<Pair<Family, Preset>> = families(s).flatMap { f ->
        f.styles.flatMap { st -> listOf(f to st) + st.subs.map { f to it } }
    }

    fun duplicate(ctx: Context, from: Preset) {
        val s = settings.value
        val id = "perso." + System.currentTimeMillis()
        val p = Preset(id, from.name + " perso", from.bpm, "Réglage perso basé sur ${from.name}", styleFilters(s, from))
        update(ctx) { it.copy(customs = it.customs + p, graphic = it.graphic[from.id]?.let { g -> it.graphic + (id to g) } ?: it.graphic) }
        select(ctx, id)
    }

    fun renameCustom(ctx: Context, id: String, name: String) =
        update(ctx) { st -> st.copy(customs = st.customs.map { if (it.id == id) it.copy(name = name) else it }) }

    fun deleteCustom(ctx: Context, id: String) = update(ctx) { st ->
        val def = "dnb.drum_et_bass"
        st.copy(
            customs = st.customs.filter { it.id != id }, overrides = st.overrides - id, favorites = st.favorites - id,
            graphic = st.graphic - id, locked = st.locked - id,
            presetId = if (st.presetId == id) (st.lastMusicId.takeIf { it != id } ?: def) else st.presetId,
            lastMusicId = if (st.lastMusicId == id) def else st.lastMusicId,
        )
    }

    // ---------- Persistance (tout en JSON) ----------

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        catalog = Catalog.load(ctx.applicationContext)
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val raw = p.getString("s2", null)
        var s = if (raw != null) runCatching { fromJson(JSONObject(raw), EqSettings()) }.getOrDefault(EqSettings()) else legacy(p)
        customsCache = s.customs
        fun valid(id: String?, def: String) = if (find(id) != null) id!! else def
        s = s.copy(
            presetId = valid(s.presetId, "dnb.drum_et_bass"),
            lastMusicId = valid(s.lastMusicId, "dnb.drum_et_bass"),
            lastVideoId = valid(s.lastVideoId, "video.cinema"),
            favorites = s.favorites.filter { find(it) != null },
        )
        _settings.value = s
        ready = true
    }

    fun update(ctx: Context, f: (EqSettings) -> EqSettings) {
        _settings.update(f)
        val s = _settings.value
        customsCache = s.customs
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("s2", toJson(s).toString()).apply()
        Widget.refreshAll(ctx)
    }

    private fun legacy(p: android.content.SharedPreferences): EqSettings {
        val d = EqSettings()
        return d.copy(
            enabled = p.getBoolean("enabled", false),
            presetId = p.getString("preset", d.presetId)!!,
            lastMusicId = p.getString("lastMusic", d.lastMusicId)!!,
            lastVideoId = p.getString("lastVideo", d.lastVideoId)!!,
            tweaks = UserTweaks(p.getBoolean("ft1", true), p.getFloat("bass", 0f).toDouble(), p.getFloat("treble", 0f).toDouble()),
            globalMode = p.getBoolean("global", true),
            overrides = runCatching { filterMap(JSONObject(p.getString("overrides", "{}")!!)) }.getOrDefault(emptyMap()),
            autoApp = p.getBoolean("autoApp", false),
            appRules = runCatching { strMap(JSONObject(p.getString("appRules", "{}")!!)) }.getOrDefault(emptyMap()),
            ft1Auto = p.getBoolean("ft1Auto", true),
            pauseOnSpeaker = p.getBoolean("pauseSpeaker", true),
            loudness = p.getBoolean("loudness", false),
            keepVolume = p.getBoolean("keepVolume", false),
            favorites = p.getString("favorites", "")!!.split(",").filter { it.isNotBlank() },
            balance = p.getFloat("balance", 0f).toDouble(),
            ceilingDb = p.getFloat("ceiling", -1f).toDouble(),
            customs = runCatching { presets(JSONArray(p.getString("customs", "[]")!!)) }.getOrDefault(emptyList()),
        )
    }

    private fun fJson(list: List<Filter>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("type", it.type).put("f", it.f).put("g", it.g).put("q", it.q)) }
    }

    private fun filters(a: JSONArray?): List<Filter> = if (a == null) emptyList() else (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Filter(o.getString("type"), o.getDouble("f"), o.getDouble("g"), o.getDouble("q"))
    }

    private fun filterMap(o: JSONObject?): Map<String, List<Filter>> =
        o?.keys()?.asSequence()?.associateWith { filters(o.getJSONArray(it)) } ?: emptyMap()

    private fun strMap(o: JSONObject?): Map<String, String> =
        o?.keys()?.asSequence()?.associateWith { o.getString(it) } ?: emptyMap()

    private fun strList(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun presets(a: JSONArray?): List<Preset> = if (a == null) emptyList() else (0 until a.length()).map {
        val o = a.getJSONObject(it)
        Preset(o.getString("id"), o.getString("name"), o.optInt("bpm", 0), o.optString("desc"), filters(o.optJSONArray("filters")))
    }

    fun toJson(s: EqSettings): JSONObject = JSONObject()
        .put("app", "basse").put("version", 2)
        .put("enabled", s.enabled).put("presetId", s.presetId).put("lastMusicId", s.lastMusicId).put("lastVideoId", s.lastVideoId)
        .put("ft1", s.tweaks.ft1).put("bass", s.tweaks.bassDb).put("treble", s.tweaks.trebleDb)
        .put("globalMode", s.globalMode)
        .put("overrides", JSONObject().apply { s.overrides.forEach { (k, v) -> put(k, fJson(v)) } })
        .put("autoApp", s.autoApp).put("appRules", JSONObject(s.appRules as Map<*, *>))
        .put("ft1Auto", s.ft1Auto).put("pauseOnSpeaker", s.pauseOnSpeaker)
        .put("loudness", s.loudness).put("keepVolume", s.keepVolume)
        .put("favorites", JSONArray(s.favorites)).put("balance", s.balance).put("ceilingDb", s.ceilingDb)
        .put("customs", JSONArray().apply {
            s.customs.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("bpm", p.bpm).put("desc", p.desc).put("filters", fJson(p.filters))) }
        })
        .put("punch", s.punch).put("fineVolDb", s.fineVolDb).put("locked", JSONArray(s.locked))
        .put("profiles", JSONArray().apply { s.profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("filters", fJson(it.filters))) } })
        .put("deviceCorrection", JSONObject(s.deviceCorrection as Map<*, *>))
        .put("devicePreset", JSONObject(s.devicePreset as Map<*, *>))
        .put("hearing", fJson(s.hearing)).put("hearingOn", s.hearingOn)
        .put("graphic", JSONObject().apply { s.graphic.forEach { (k, v) -> put(k, JSONArray(v)) } })
        .put("schedule", JSONObject().put("enabled", s.schedule.enabled).put("start", s.schedule.startMin)
            .put("end", s.schedule.endMin).put("preset", s.schedule.presetId).put("loudness", s.schedule.loudness))
        .put("schedActive", s.schedActive).put("schedPrevPreset", s.schedPrevPreset ?: "").put("schedPrevLoud", s.schedPrevLoud)
        .put("exposureOn", s.exposureOn).put("autoContent", s.autoContent)
        .put("carDevices", JSONArray(s.carDevices))

    fun fromJson(o: JSONObject, base: EqSettings): EqSettings {
        val sc = o.optJSONObject("schedule")
        return base.copy(
            enabled = o.optBoolean("enabled", base.enabled),
            presetId = o.optString("presetId", base.presetId),
            lastMusicId = o.optString("lastMusicId", base.lastMusicId),
            lastVideoId = o.optString("lastVideoId", base.lastVideoId),
            tweaks = UserTweaks(o.optBoolean("ft1", true), o.optDouble("bass", 0.0), o.optDouble("treble", 0.0)),
            globalMode = o.optBoolean("globalMode", true),
            overrides = filterMap(o.optJSONObject("overrides")),
            autoApp = o.optBoolean("autoApp", false),
            appRules = strMap(o.optJSONObject("appRules")),
            ft1Auto = o.optBoolean("ft1Auto", true),
            pauseOnSpeaker = o.optBoolean("pauseOnSpeaker", true),
            loudness = o.optBoolean("loudness", false),
            keepVolume = o.optBoolean("keepVolume", false),
            favorites = strList(o.optJSONArray("favorites")),
            balance = o.optDouble("balance", 0.0),
            ceilingDb = o.optDouble("ceilingDb", -1.0),
            customs = presets(o.optJSONArray("customs")),
            punch = o.optInt("punch", 0),
            fineVolDb = o.optDouble("fineVolDb", 0.0),
            locked = strList(o.optJSONArray("locked")),
            profiles = o.optJSONArray("profiles")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { HpProfile(it.getString("id"), it.getString("name"), filters(it.optJSONArray("filters"))) } }
            } ?: emptyList(),
            deviceCorrection = strMap(o.optJSONObject("deviceCorrection")),
            devicePreset = strMap(o.optJSONObject("devicePreset")),
            hearing = filters(o.optJSONArray("hearing")),
            hearingOn = o.optBoolean("hearingOn", true),
            graphic = o.optJSONObject("graphic")?.let { g ->
                g.keys().asSequence().associateWith { k -> g.getJSONArray(k).let { a -> (0 until a.length()).map { a.getDouble(it) } } }
            } ?: emptyMap(),
            schedule = if (sc == null) Schedule() else Schedule(
                sc.optBoolean("enabled", false), sc.optInt("start", 22 * 60), sc.optInt("end", 7 * 60),
                sc.optString("preset", "video.nuit"), sc.optBoolean("loudness", true),
            ),
            schedActive = o.optBoolean("schedActive", false),
            schedPrevPreset = o.optString("schedPrevPreset", "").ifBlank { null },
            schedPrevLoud = o.optBoolean("schedPrevLoud", false),
            exposureOn = o.optBoolean("exposureOn", true),
            autoContent = o.optBoolean("autoContent", false),
            carDevices = strList(o.optJSONArray("carDevices")),
        )
    }

    fun exportJson(): String = toJson(settings.value).toString(1)

    /** Restaure / synchronise : on garde l'état ON/OFF local. */
    fun importJson(ctx: Context, text: String): Boolean = runCatching {
        val o = JSONObject(text)
        require(o.optString("app") == "basse")
        val cur = settings.value
        val n = if (o.optInt("version", 1) >= 2) fromJson(o, cur) else cur.copy(
            overrides = filterMap(o.optJSONObject("overrides")), appRules = strMap(o.optJSONObject("appRules")),
            favorites = strList(o.optJSONArray("favorites")), customs = presets(o.optJSONArray("customs")),
            balance = o.optDouble("balance", 0.0), ceilingDb = o.optDouble("ceiling", -1.0),
            tweaks = UserTweaks(o.optBoolean("ft1", true), o.optDouble("bass", 0.0), o.optDouble("treble", 0.0)),
        )
        customsCache = n.customs
        update(ctx) { n.copy(enabled = cur.enabled, schedActive = cur.schedActive) }
        true
    }.getOrDefault(false)

    // ---------- Sélection ----------

    /** Instant du dernier choix manuel : l'analyse du contenu attend un blanc (changement de morceau) avant d'agir. */
    @Volatile var lastManualAt = 0L

    fun select(ctx: Context, id: String) {
        lastManualAt = System.currentTimeMillis()
        val fam = find(id)?.first ?: return
        update(ctx) { if (fam.isVideo) it.copy(presetId = id, lastVideoId = id) else it.copy(presetId = id, lastMusicId = id) }
    }

    fun toggleFavorite(ctx: Context, id: String) = update(ctx) {
        it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id)
    }

    fun nextFavorite(ctx: Context) {
        val s = settings.value
        if (s.favorites.isEmpty()) return
        val i = s.favorites.indexOf(s.presetId)
        select(ctx, s.favorites[(i + 1) % s.favorites.size])
    }

    fun toggleLock(ctx: Context, id: String) = update(ctx) { it.copy(locked = if (id in it.locked) it.locked - id else it.locked + id) }

    // ---------- Contexte : sortie, appli, volume, horaire ----------

    fun setStatus(s: EngineStatus) { _status.value = s }
    fun setVolume(v: Float) { if (kotlin.math.abs(v - _volume.value) > 0.01f) _volume.value = v }
    fun setTesting(on: Boolean) { _testing.value = on }

    /** Test d'effet : coupe fortement les aigus pendant quelques secondes, impossible à rater si Basse agit bien. */
    private val _fxTest = MutableStateFlow(false)
    val fxTest: StateFlow<Boolean> = _fxTest.asStateFlow()
    fun runFxTest(seconds: Long = 5) {
        _fxTest.value = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ _fxTest.value = false }, seconds * 1000)
    }

    /** Change de sortie : applique le style associé à cet appareil s'il y en a un. */
    fun setOutput(o: OutputInfo, ctx: Context? = null) {
        val old = _output.value
        _output.value = o
        if (ctx != null && old.key != o.key && old.kind != OutputKind.UNKNOWN || ctx != null && old.kind == OutputKind.UNKNOWN && o.kind != OutputKind.UNKNOWN && old.key != o.key) {
            val st = settings.value
            val id = st.devicePreset[o.key] ?: if (isMazda(o)) CX5_DEFAULT_STYLE else null
            if (id != null && find(id) != null && id != st.presetId) select(ctx, id)
        }
    }

    fun onForegroundApp(ctx: Context, pkg: String) {
        if (AppRules.ignored(ctx, pkg)) return
        _lastApp.value = pkg
        val s = settings.value
        if (!s.autoApp || s.schedActive) return
        val rule = s.appRules[pkg] ?: AppRules.builtIn[pkg] ?: return
        if (rule == AppRules.NONE) return
        val target = when (rule) { AppRules.MUSIC -> s.lastMusicId; AppRules.VIDEO -> s.lastVideoId; else -> rule }
        if (find(target) == null || target == s.presetId) return
        update(ctx) { it.copy(presetId = target) }
    }

    /** Proposition de l'analyse du contenu : change seulement de famille, garde votre sous-style si la famille est la même. */
    fun onContentGuess(ctx: Context, styleId: String) {
        val s = settings.value
        if (!s.autoContent || s.schedActive) return
        val target = find(styleId) ?: return
        val cur = find(s.presetId) ?: return
        if (cur.first.id == target.first.id) return
        // en mode vidéo choisi à la main, on ne bascule vers la musique que si l'analyse est sûre (géré par l'appelant)
        update(ctx) { if (target.first.isVideo) it.copy(presetId = styleId) else it.copy(presetId = it.lastMusicId.takeIf { m -> find(m)?.first?.id == target.first.id } ?: styleId) }
    }

    fun inWindow(sc: Schedule, minuteOfDay: Int): Boolean =
        if (sc.startMin <= sc.endMin) minuteOfDay in sc.startMin until sc.endMin
        else minuteOfDay >= sc.startMin || minuteOfDay < sc.endMin

    /** Appelé toutes les 2 s par le service. */
    fun tickSchedule(ctx: Context) {
        val s = settings.value
        val cal = java.util.Calendar.getInstance()
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val want = s.schedule.enabled && inWindow(s.schedule, m)
        if (want && !s.schedActive) {
            val target = if (find(s.schedule.presetId) != null) s.schedule.presetId else s.presetId
            update(ctx) {
                it.copy(schedActive = true, schedPrevPreset = it.presetId, schedPrevLoud = it.loudness, presetId = target,
                    loudness = if (it.schedule.loudness) true else it.loudness)
            }
        } else if (!want && s.schedActive) {
            update(ctx) {
                it.copy(schedActive = false, presetId = it.schedPrevPreset?.takeIf { p -> find(p) != null } ?: it.presetId,
                    loudness = it.schedPrevLoud, schedPrevPreset = null)
            }
        }
    }

    // ---------- Construction de la courbe ----------

    private val MAZDA = Regex("mazda|cx-?5", RegexOption.IGNORE_CASE)

    /** La sortie est-elle le système de la Mazda ? (nom Bluetooth) */
    fun isMazda(o: OutputInfo) = o.kind == OutputKind.CAR || (o.kind == OutputKind.BLUETOOTH && MAZDA.containsMatchIn(o.name))

    /** Voiture ou enceinte : détectée (Mazda) ou marquée à la main. */
    fun isCar(s: EqSettings, o: OutputInfo) = isMazda(o) || o.key in s.carDevices

    /** Correction choisie pour cette sortie : "ft1", "cx5", "none" ou id de profil. */
    fun correctionFor(s: EqSettings, o: OutputInfo): String {
        if (!s.tweaks.ft1) return NONE
        s.deviceCorrection[o.key]?.let { return it }
        if (isMazda(o)) return CX5
        if (o.key in s.carDevices) return NONE
        if (!s.ft1Auto) return FT1
        return if (o.kind == OutputKind.FIIO || o.kind == OutputKind.WIRED || o.kind == OutputKind.UNKNOWN) FT1 else NONE
    }

    fun correctionName(s: EqSettings, id: String) = when (id) {
        FT1 -> "FiiO FT1"
        CX5 -> "Mazda CX-5 Bose"
        NONE -> "Aucune"
        else -> s.profiles.firstOrNull { it.id == id }?.name ?: "Profil supprimé"
    }

    private fun correctionFilters(s: EqSettings, id: String): List<Filter> = when (id) {
        FT1 -> catalog.base
        CX5 -> catalog.cx5Base
        NONE -> emptyList()
        else -> s.profiles.firstOrNull { it.id == id }?.filters.orEmpty()
    }

    /** Ancien nom gardé pour l'interface. */
    fun ft1Effective(s: EqSettings, o: OutputInfo) = correctionFor(s, o) != NONE

    fun pausedBySpeaker(s: EqSettings, o: OutputInfo) = s.pauseOnSpeaker && o.kind == OutputKind.SPEAKER

    fun styleFilters(s: EqSettings, preset: Preset): List<Filter> = s.overrides[preset.id] ?: preset.filters

    fun graphicFilters(s: EqSettings, id: String): List<Filter> {
        val g = s.graphic[id] ?: return emptyList()
        return g.mapIndexedNotNull { i, v -> if (kotlin.math.abs(v) < 0.05) null else Filter("PK", GRAPHIC_FREQS[i], v, 1.41) }
    }

    fun currentChain(s: EqSettings = settings.value, o: OutputInfo = output.value, vol: Float = volume.value): List<Filter> {
        val preset = find(s.presetId)?.second ?: return emptyList()
        return buildList {
            addAll(correctionFilters(s, correctionFor(s, o)))
            if (s.hearingOn) addAll(s.hearing)
            addAll(styleFilters(s, preset))
            addAll(graphicFilters(s, preset.id))
            if (s.tweaks.bassDb != 0.0) add(Filter("LS", 80.0, s.tweaks.bassDb, 0.7))
            if (s.tweaks.trebleDb != 0.0) add(Filter("HS", 8000.0, s.tweaks.trebleDb, 0.7))
            val l = loudnessDb(s, vol)
            if (l > 0.05) { add(Filter("LS", 70.0, l, 0.7)); add(Filter("HS", 10000.0, l * 0.5, 0.7)) }
        }
    }

    /** Pendant le test auditif : seulement la correction du casque. */
    fun testChain(s: EqSettings, o: OutputInfo): List<Filter> = correctionFilters(s, correctionFor(s, o))

    fun loudnessDb(s: EqSettings, vol: Float): Double =
        if (!s.loudness) 0.0 else (((0.6f - vol) / 0.6f).coerceIn(0f, 1f) * 6f).toDouble()

    /** Préampli réellement appliqué (dB). */
    fun preampDb(s: EqSettings, chain: List<Filter>): Double =
        Curve.autoPreamp(chain) * (if (s.keepVolume) 0.35 else 1.0) + s.fineVolDb
}
