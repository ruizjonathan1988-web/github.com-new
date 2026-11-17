package fr.jon.basse.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.jon.basse.Analyzer
import fr.jon.basse.AppRules
import fr.jon.basse.AutoEqImport
import fr.jon.basse.Curve
import fr.jon.basse.EngineStatus
import fr.jon.basse.EqSettings
import fr.jon.basse.EqState
import fr.jon.basse.Exposure
import fr.jon.basse.Family
import fr.jon.basse.Filter
import fr.jon.basse.HpProfile
import fr.jon.basse.LanSync
import fr.jon.basse.OutputInfo
import fr.jon.basse.Preset

class PanelCtx(
    val s: EqSettings, val status: EngineStatus, val output: OutputInfo, val lastApp: String?, val tick: Int,
    val fam: Family, val preset: Preset, val points: FloatArray, val preamp: Double, val chain: List<Filter>,
    val accent: Color, val cb: UiCallbacks, val openTest: () -> Unit,
)

fun LazyListScope.panels(p: PanelCtx, includeCurve: Boolean) {
    if (includeCurve) item { CurveCard(p) }
    item { StyleEditorCard(p) }
    item { SoundCard(p) }
    item { HeadphoneCard(p) }
    item { AutoCard(p) }
    item { ScheduleCard(p) }
    item { ExposureCard(p) }
    item { PrepareCard(p.accent) }
    item { SyncCard(p) }
    item { DetailCard(p) }
    item { Spacer(Modifier.height(30.dp)) }
}

// ---------------- Courbe + spectre ----------------

@Composable
fun CurveCard(p: PanelCtx) {
    var spectrum by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (spectrum) "Spectre en direct" else "Courbe appliquée", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Préampli %.1f dB".format(p.preamp), color = TextDim, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            if (spectrum && Analyzer.hasPermission(ctx)) SpectrumView(p.accent, Modifier.fillMaxWidth().height(170.dp))
            else CurveView(p.points, p.accent, Modifier.fillMaxWidth().height(170.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { StatusLine(p) }
            ActionChip(if (spectrum) "Courbe" else "Spectre", p.accent) {
                if (!spectrum && !Analyzer.hasPermission(ctx)) p.cb.askMic()
                spectrum = !spectrum
            }
        }
        if (spectrum && !Analyzer.hasPermission(ctx)) Hint("Le spectre a besoin de l'autorisation micro (rien n'est enregistré, Basse lit seulement le son qui sort).", Warn)
        else if (spectrum && !p.s.enabled) Hint("Allumez Basse (ON) pour voir le spectre.")
    }
}

@Composable
private fun StatusLine(p: PanelCtx) {
    val s = p.s; val st = p.status
    val (dot, txt) = when {
        !s.enabled -> Color.Gray to "Égaliseur coupé"
        EqState.pausedBySpeaker(s, p.output) -> Warn to "En pause : son sur le haut-parleur"
        s.globalMode && st.globalOk == true -> p.accent to "Actif sur tout le son" + if (st.sessions > 0) " · ${st.sessions} lecteur(s)" else ""
        s.globalMode && st.globalOk == false -> Warn to "Mode global refusé. Lecteurs compatibles : ${st.sessions}"
        else -> p.accent to "Lecteurs compatibles égalisés : ${st.sessions}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(txt, color = TextDim, fontSize = 12.sp)
    }
}

// ---------------- Éditeur du style ----------------

private fun roundHz(f: Double): Double = when {
    f < 100 -> Math.round(f).toDouble()
    f < 1000 -> (Math.round(f / 5) * 5).toDouble()
    else -> (Math.round(f / 50) * 50).toDouble()
}

fun typeName(t: String) = when (t) {
    "LS" -> "Grave (shelf)"; "HS" -> "Aigu (shelf)"; "HP" -> "Coupe-bas"; "LP" -> "Coupe-haut"; "NO" -> "Encoche (coupe étroite)"
    else -> "Bosse (peak)"
}
private fun nextType(t: String) = when (t) { "PK" -> "LS"; "LS" -> "HS"; "HS" -> "HP"; "HP" -> "LP"; "LP" -> "NO"; else -> "PK" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleEditorCard(p: PanelCtx) {
    val ctx = LocalContext.current
    val s = p.s; val preset = p.preset; val accent = p.accent
    val list = EqState.styleFilters(s, preset)
    val modified = s.overrides.containsKey(preset.id) || s.graphic.containsKey(preset.id)
    val locked = preset.id in s.locked
    var graphic by rememberSaveable { mutableStateOf(false) }
    fun save(newList: List<Filter>) = p.cb.update { st -> st.copy(overrides = st.overrides + (preset.id to newList)) }
    fun edit(i: Int, f: (Filter) -> Filter) = save(list.mapIndexed { j, x -> if (j == i) f(x) else x })

    FoldCard("Régler « ${preset.name} »" + (if (modified) "  ✎" else "") + (if (locked) "  🔒" else ""),
        if (locked) "Verrouillé" else if (modified) "Modifié par vous" else "Réglage d'origine", accent) {
        ToggleRow("Verrouiller ce réglage", "Évite de le modifier par erreur (pratique à la télécommande)", locked, accent) { EqState.toggleLock(ctx, preset.id) }
        if (locked) return@FoldCard
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Glass).padding(4.dp)) {
            listOf(false to "Précis", true to "10 curseurs").forEach { (g, label) ->
                Box(Modifier.weight(1f).tv(accent, RoundedCornerShape(50)) { graphic = g }
                    .background(if (graphic == g) accent else Color.Transparent, RoundedCornerShape(50)).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (graphic == g) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
        if (graphic) {
            GraphicEditor(p)
        } else {
            if (list.isEmpty()) Hint("Aucun filtre : le style est à plat.")
            list.forEachIndexed { i, f ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", color = accent, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(typeName(f.type), color = Color.White, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).tv(accent, RoundedCornerShape(8.dp)) { edit(i) { it.copy(type = nextType(it.type)) } }.padding(4.dp))
                        StepBtn("✕", accent) { save(list.filterIndexed { j, _ -> j != i }) }
                    }
                    ValueRow("Fréquence", Curve.fmtHz(f.f), accent,
                        minus = { edit(i) { it.copy(f = roundHz((it.f / 1.122462).coerceAtLeast(20.0))) } },
                        plus = { edit(i) { it.copy(f = roundHz((it.f * 1.122462).coerceAtMost(20000.0))) } })
                    if (f.type in setOf("PK", "LS", "HS")) ValueRow("Gain", "%+.1f dB".format(f.g), accent,
                        minus = { edit(i) { it.copy(g = (it.g - 0.5).coerceAtLeast(-12.0)) } },
                        plus = { edit(i) { it.copy(g = (it.g + 0.5).coerceAtMost(12.0)) } })
                    ValueRow("Largeur (Q)", "%.1f".format(f.q), accent,
                        minus = { edit(i) { it.copy(q = (Math.round((it.q - if (it.q > 2) 0.5 else 0.1) * 10) / 10.0).coerceAtLeast(0.3)) } },
                        plus = { edit(i) { it.copy(q = (Math.round((it.q + if (it.q >= 2) 0.5 else 0.1) * 10) / 10.0).coerceAtMost(8.0)) } })
                }
            }
            Hint("Fréquence : 1/6 d'octave par appui. Q petit = bosse large, Q grand = correction étroite. Touchez le nom pour changer de type.")
        }
        val isCustom = preset.id.startsWith("perso.")
        if (isCustom) {
            var name by remember(preset.id) { mutableStateOf(preset.name) }
            OutlinedTextField(value = name, onValueChange = { name = it; if (it.isNotBlank()) EqState.renameCustom(ctx, preset.id, it.trim()) },
                label = { Text("Nom du réglage") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!graphic && list.size < 8) ActionChip("+ Ajouter un filtre", accent) { save(list + Filter("PK", 1000.0, 0.0, 1.0)) }
            ActionChip("Mettre à plat", accent) { p.cb.update { st -> st.copy(overrides = st.overrides + (preset.id to emptyList()), graphic = st.graphic - preset.id) } }
            if (modified) ActionChip("↺ Revenir à l'origine", accent, filled = true) {
                p.cb.update { st -> st.copy(overrides = st.overrides - preset.id, graphic = st.graphic - preset.id) }
            }
            ActionChip("⧉ Dupliquer en réglage perso", accent) { EqState.duplicate(ctx, preset) }
            if (isCustom) ActionChip("Supprimer ce réglage", accent) { EqState.deleteCustom(ctx, preset.id) }
        }
    }
}

@Composable
private fun GraphicEditor(p: PanelCtx) {
    val id = p.preset.id
    val g = p.s.graphic[id] ?: List(10) { 0.0 }
    fun set(i: Int, v: Double) = p.cb.update { st ->
        val ng = g.toMutableList().also { it[i] = v.coerceIn(-12.0, 12.0) }
        if (ng.all { kotlin.math.abs(it) < 0.05 }) st.copy(graphic = st.graphic - id) else st.copy(graphic = st.graphic + (id to ng))
    }
    Hint("Les curseurs s'ajoutent au réglage précis. Pratique pour une retouche rapide.")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EqState.GRAPHIC_FREQS.forEachIndexed { i, f ->
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Glass).padding(vertical = 6.dp, horizontal = 4.dp)) {
                StepBtn("+", p.accent) { set(i, g[i] + 1.0) }
                // barre
                Box(Modifier.width(10.dp).height(70.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    val h = (kotlin.math.abs(g[i]) / 12.0 * 35).dp
                    Box(Modifier.width(10.dp).height(h).padding(0.dp).background(p.accent, RoundedCornerShape(5.dp))
                        .align(if (g[i] >= 0) Alignment.TopCenter else Alignment.BottomCenter).padding(0.dp))
                }
                Text("%+.0f".format(g[i]), color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                StepBtn("−", p.accent) { set(i, g[i] - 1.0) }
                Text(if (f >= 1000) "${(f / 1000).toInt()}k" else "${f.toInt()}", color = TextDim, fontSize = 11.sp)
            }
        }
    }
}

// ---------------- Son ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundCard(p: PanelCtx) {
    val s = p.s; val a = p.accent; val cb = p.cb
    FoldCard("Son", "Grave, aigus, Punch, volume fin, balance, protection", a, startOpen = true) {
        ValueRow("Grave (80 Hz)", "%+.1f dB".format(s.tweaks.bassDb), a,
            minus = { cb.update { st -> st.copy(tweaks = st.tweaks.copy(bassDb = (st.tweaks.bassDb - 0.5).coerceAtLeast(-6.0))) } },
            plus = { cb.update { st -> st.copy(tweaks = st.tweaks.copy(bassDb = (st.tweaks.bassDb + 0.5).coerceAtMost(6.0))) } })
        ValueRow("Aigus (8 kHz)", "%+.1f dB".format(s.tweaks.trebleDb), a,
            minus = { cb.update { st -> st.copy(tweaks = st.tweaks.copy(trebleDb = (st.tweaks.trebleDb - 0.5).coerceAtLeast(-6.0))) } },
            plus = { cb.update { st -> st.copy(tweaks = st.tweaks.copy(trebleDb = (st.tweaks.trebleDb + 0.5).coerceAtMost(6.0))) } })
        Text("Punch (compresseur)", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Glass).padding(4.dp)) {
            listOf("Coupé", "Léger", "Moyen", "Fort").forEachIndexed { i, label ->
                Box(Modifier.weight(1f).tv(a, RoundedCornerShape(50)) { cb.update { st -> st.copy(punch = i) } }
                    .background(if (s.punch == i) a else Color.Transparent, RoundedCornerShape(50)).padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (s.punch == i) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
        Hint("Le grave est compressé avec une attaque lente : le kick garde son impact et le sub devient plus dense, comme une façade de sound system.")
        ValueRow("Volume fin", "%.1f dB".format(s.fineVolDb), a,
            minus = { cb.update { st -> st.copy(fineVolDb = (st.fineVolDb - 0.5).coerceAtLeast(-30.0)) } },
            plus = { cb.update { st -> st.copy(fineVolDb = (st.fineVolDb + 0.5).coerceAtMost(0.0)) } })
        Hint("Pour régler entre deux crans du volume Android, par demi-décibel.")
        ValueRow("Balance", when {
            s.balance > 0.01 -> "D %.0f %%".format(s.balance * 100)
            s.balance < -0.01 -> "G %.0f %%".format(-s.balance * 100)
            else -> "Centre"
        }, a,
            minus = { cb.update { st -> st.copy(balance = (Math.round((st.balance - 0.1) * 10) / 10.0).coerceAtLeast(-1.0)) } },
            plus = { cb.update { st -> st.copy(balance = (Math.round((st.balance + 0.1) * 10) / 10.0).coerceAtMost(1.0)) } })
        ValueRow("Plafond de sécurité", "%.0f dB".format(s.ceilingDb), a,
            minus = { cb.update { st -> st.copy(ceilingDb = (st.ceilingDb - 1).coerceAtLeast(-15.0)) } },
            plus = { cb.update { st -> st.copy(ceilingDb = (st.ceilingDb + 1).coerceAtMost(-1.0)) } })
        ToggleRow("Loudness automatique", "À volume bas, remonte le sub (jusqu'à +6 dB) et les aigus", s.loudness, a) { cb.update { st -> st.copy(loudness = it) } }
        ToggleRow("Garder le volume", "Préampli réduit, le limiteur gère les pics", s.keepVolume, a) { cb.update { st -> st.copy(keepVolume = it) } }
        ToggleRow("Mode global", "Tout le son du système. Sinon : seulement les lecteurs compatibles", s.globalMode, a) { cb.update { st -> st.copy(globalMode = it) } }
        ActionChip("Tout remettre à zéro (tous les styles)", a) {
            cb.update { st -> st.copy(overrides = emptyMap(), graphic = emptyMap(), tweaks = fr.jon.basse.UserTweaks(), punch = 0, fineVolDb = 0.0, balance = 0.0, ceilingDb = -1.0) }
        }
    }
}

// ---------------- Casque & oreilles ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadphoneCard(p: PanelCtx) {
    val ctx = LocalContext.current
    val s = p.s; val a = p.accent; val o = p.output
    val corr = EqState.correctionFor(s, o)
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        msg = runCatching {
            val txt = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val fl = AutoEqImport.parse(txt)
            if (fl.isEmpty()) "Aucun filtre AutoEq trouvé dans ce fichier." else {
                val name = (uri.lastPathSegment ?: "Casque").substringAfterLast('/').substringBeforeLast('.').replace("ParametricEQ", "").trim().ifBlank { "Casque importé" }
                val prof = HpProfile("hp." + System.currentTimeMillis(), name, fl)
                p.cb.update { st -> st.copy(profiles = st.profiles + prof) }
                "Profil « $name » importé (${fl.size} filtres)."
            }
        }.getOrDefault("Lecture impossible.")
    }
    FoldCard("Casque & oreilles", "Sortie : ${o.label} · correction ${EqState.correctionName(s, corr)}", a) {
        ToggleRow("Correction du casque", "Coupée = aucune correction, sur toutes les sorties", s.tweaks.ft1, a) {
            p.cb.update { st -> st.copy(tweaks = st.tweaks.copy(ft1 = it)) }
        }
        ToggleRow("FT1 automatique", "Sans choix ci-dessous : FT1 avec le FiiO ou un casque filaire, rien ailleurs", s.ft1Auto, a) {
            p.cb.update { st -> st.copy(ft1Auto = it) }
        }
        Text("Pour cette sortie (${o.label})", color = TextDim, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val choices = listOf(EqState.FT1, EqState.CX5, EqState.NONE) + s.profiles.map { it.id }
            choices.forEach { id ->
                ActionChip(EqState.correctionName(s, id), a, filled = corr == id) {
                    p.cb.update { st -> st.copy(deviceCorrection = st.deviceCorrection + (o.key to id)) }
                }
            }
            if (s.deviceCorrection.containsKey(o.key)) ActionChip("Auto", a) { p.cb.update { st -> st.copy(deviceCorrection = st.deviceCorrection - o.key) } }
        }
        ActionChip("Importer un profil AutoEq (.txt)", a) { runCatching { pick.launch(arrayOf("text/plain", "*/*")) } }
        Hint("Sur github.com/jaakkopasanen/AutoEq, cherchez votre casque et téléchargez « ParametricEQ.txt ».")
        s.profiles.forEach { pr ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pr.name} · ${pr.filters.size} filtres", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                StepBtn("✕", a) {
                    p.cb.update { st -> st.copy(profiles = st.profiles - pr, deviceCorrection = st.deviceCorrection.filterValues { it != pr.id }) }
                }
            }
        }
        msg?.let { Hint(it, a) }
        Spacer(Modifier.height(4.dp))
        Text("Test auditif", color = Color.White, fontWeight = FontWeight.SemiBold)
        Hint(if (s.hearing.isEmpty()) "Basse joue des sons fréquence par fréquence et compense ce que vous entendez moins bien. 3 minutes, au calme, casque sur la tête."
        else "Compensation en place : ${s.hearing.size} fréquence(s) remontée(s).")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(if (s.hearing.isEmpty()) "Lancer le test" else "Refaire le test", a, filled = true, onClick = p.openTest)
            if (s.hearing.isNotEmpty()) {
                ActionChip(if (s.hearingOn) "Compensation : ON" else "Compensation : OFF", a) { p.cb.update { st -> st.copy(hearingOn = !st.hearingOn) } }
                ActionChip("Effacer", a) { p.cb.update { st -> st.copy(hearing = emptyList()) } }
            }
        }
    }
}

// ---------------- Automatique ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoCard(p: PanelCtx) {
    val ctx = LocalContext.current
    val s = p.s; val a = p.accent; val o = p.output
    val access = remember(p.tick, s.autoApp) { AppRules.hasUsageAccess(ctx) }
    val guess by Analyzer.guess.collectAsState()
    fun ruleLabel(r: String?) = when (r) {
        null -> "aucune règle"
        AppRules.MUSIC -> "Musique (dernier style)"
        AppRules.VIDEO -> "Vidéo (dernier réglage)"
        AppRules.NONE -> "ne rien changer"
        else -> EqState.find(r)?.second?.name ?: r
    }
    FoldCard("Automatique", "Appareil, appli, contenu", a) {
        // Appareil
        Text("Avec ${o.label}", color = Color.White, fontWeight = FontWeight.SemiBold)
        val dp = s.devicePreset[o.key]
        Hint(if (dp != null) "Style à la connexion : ${EqState.find(dp)?.second?.name ?: "?"}" else "Aucun style associé à cet appareil.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("→ ${p.preset.name} à la connexion", a, filled = true) { p.cb.update { st -> st.copy(devicePreset = st.devicePreset + (o.key to p.preset.id)) } }
            if (dp != null) ActionChip("Retirer", a) { p.cb.update { st -> st.copy(devicePreset = st.devicePreset - o.key) } }
        }
        val car = EqState.isCar(s, o)
        ToggleRow("Cet appareil est une voiture ou une enceinte",
            if (EqState.isMazda(o)) "Mazda détectée automatiquement : correction CX-5 Bose, pas de compteur d'exposition"
            else "Pas de correction casque ni de compteur d'exposition pour cette sortie", car, a) { v ->
            p.cb.update { st -> st.copy(carDevices = if (v) (st.carDevices + o.key).distinct() else st.carDevices - o.key) }
        }
        ToggleRow("Pause sur le haut-parleur", "Basse s'efface quand le son sort du téléphone", s.pauseOnSpeaker, a) { p.cb.update { st -> st.copy(pauseOnSpeaker = it) } }

        // Appli
        ToggleRow("Bascule selon l'appli", "Spotify → musique, YouTube/VJOD → vidéo, émulateurs → jeux", s.autoApp, a) { p.cb.update { st -> st.copy(autoApp = it) } }
        if (s.autoApp && !access) {
            Hint("Il faut autoriser Basse dans « Accès aux données d'utilisation ».", Warn)
            ActionChip("Autoriser l'accès", a, filled = true) {
                runCatching { ctx.startActivity(AppRules.usageSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        val last = p.lastApp
        if (last != null) {
            val name = remember(last) { AppRules.label(ctx, last) }
            val current = s.appRules[last] ?: AppRules.builtIn[last]
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dernière appli : $name", color = Color.White, fontWeight = FontWeight.SemiBold)
                Hint("Règle : ${ruleLabel(current)}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun set(r: String) = p.cb.update { st -> st.copy(appRules = st.appRules + (last to r)) }
                    ActionChip("→ ${p.preset.name}", a, filled = true) { set(p.preset.id) }
                    ActionChip("→ Musique", a) { set(AppRules.MUSIC) }
                    ActionChip("→ Vidéo", a) { set(AppRules.VIDEO) }
                    ActionChip("Ne rien changer", a) { set(AppRules.NONE) }
                }
            }
        } else if (s.autoApp && access) Hint("Ouvrez une appli (VJOD, un émulateur…) puis revenez ici pour lui choisir un réglage.")
        s.appRules.forEach { (pkg, r) ->
            val name = remember(pkg) { AppRules.label(ctx, pkg) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name → ${ruleLabel(r)}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                StepBtn("✕", a) { p.cb.update { st -> st.copy(appRules = st.appRules - pkg) } }
            }
        }

        // Contenu
        ToggleRow("Selon le contenu (expérimental)", "Écoute le tempo, le sub et la voix. Change de famille entre deux morceaux, garde votre sous-style", s.autoContent, a) {
            if (it && !Analyzer.hasPermission(ctx)) p.cb.askMic()
            p.cb.update { st -> st.copy(autoContent = it) }
        }
        if (s.autoContent) {
            if (!Analyzer.hasPermission(ctx)) Hint("Autorisez le micro : Basse lit le son qui sort, rien n'est enregistré.", Warn)
            else Hint("Entendu : " + (guess ?: if (s.enabled) "en écoute…" else "allumez Basse"))
        }
    }
}

// ---------------- Programmation (option) ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(p: PanelCtx) {
    val s = p.s; val a = p.accent; val sc = s.schedule
    fun upd(f: (fr.jon.basse.Schedule) -> fr.jon.basse.Schedule) = p.cb.update { st -> st.copy(schedule = f(st.schedule)) }
    FoldCard("Programmation horaire", if (sc.enabled) "Active de ${hhmm(sc.startMin)} à ${hhmm(sc.endMin)}" else "Option désactivée", a) {
        ToggleRow("Activer la programmation", "Passe sur un réglage à heure fixe, puis revient au précédent", sc.enabled, a) { v -> upd { it.copy(enabled = v) } }
        if (sc.enabled) {
            ValueRow("Début", hhmm(sc.startMin), a, minus = { upd { it.copy(startMin = (it.startMin - 15 + 1440) % 1440) } }, plus = { upd { it.copy(startMin = (it.startMin + 15) % 1440) } })
            ValueRow("Fin", hhmm(sc.endMin), a, minus = { upd { it.copy(endMin = (it.endMin - 15 + 1440) % 1440) } }, plus = { upd { it.copy(endMin = (it.endMin + 15) % 1440) } })
            Hint("Réglage pendant la plage : ${EqState.find(sc.presetId)?.second?.name ?: "?"}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("Nuit", a, filled = sc.presetId == "video.nuit") { upd { it.copy(presetId = "video.nuit") } }
                ActionChip("Doux longue écoute", a, filled = sc.presetId == "universel.doux_longue_ecoute") { upd { it.copy(presetId = "universel.doux_longue_ecoute") } }
                ActionChip("Style actuel : ${p.preset.name}", a, filled = sc.presetId == p.preset.id) { upd { it.copy(presetId = p.preset.id) } }
            }
            ToggleRow("Loudness pendant la plage", "Utile la nuit à faible volume", sc.loudness, a) { v -> upd { it.copy(loudness = v) } }
            Hint("Fonctionne quand Basse est allumée.")
        }
    }
}

// ---------------- Exposition ----------------

@Composable
private fun ExposureCard(p: PanelCtx) {
    val e by Exposure.state.collectAsState()
    val a = p.accent
    val pct = e.dosePct
    val col = when { pct >= 100 -> Color(0xFFFF4D4D); pct >= 50 -> Warn; else -> a }
    FoldCard("Exposition sonore du jour", "%.0f %% de la dose recommandée (estimation)".format(pct), a) {
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Glass)) {
            Box(Modifier.fillMaxWidth((pct / 100f).coerceIn(0f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(col))
        }
        Hint(if (e.counting) "Niveau estimé maintenant : %.0f dB".format(e.levelDb) else "Compté quand de la musique passe au casque et que Basse est allumée.")
        Hint("Repère : 100 % = 85 dB pendant 8 h ; chaque +3 dB divise le temps par deux. Estimation calculée pour le FT1 sur BTR11, à prendre comme un ordre de grandeur.")
        ToggleRow("Compter et prévenir", "Notification à 50 % et à 100 %", p.s.exposureOn, a) { p.cb.update { st -> st.copy(exposureOn = it) } }
    }
}

// ---------------- Synchro & sauvegarde ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncCard(p: PanelCtx) {
    val ctx = LocalContext.current
    val a = p.accent
    var msg by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) msg = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(EqState.exportJson().toByteArray()) }
            "Réglages sauvegardés."
        }.getOrDefault("Échec de la sauvegarde.")
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) msg = runCatching {
            val txt = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            if (EqState.importJson(ctx, txt)) "Réglages restaurés." else "Fichier non reconnu."
        }.getOrDefault("Lecture impossible.")
    }
    FoldCard("Synchro Fold ↔ box & sauvegarde", "Mêmes réglages partout", a) {
        Hint("Sur le même Wi-Fi : « Partager » sur l'appareil qui a les bons réglages, puis « Récupérer » sur l'autre.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Partager sur le Wi-Fi", a, filled = true) { LanSync.share(ctx) { msg = it } }
            ActionChip("Récupérer depuis le Wi-Fi", a) { LanSync.receive(ctx) { msg = it } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("Sauvegarder dans un fichier", a) { runCatching { save.launch("basse-reglages.json") } }
            ActionChip("Restaurer un fichier", a) { runCatching { load.launch(arrayOf("application/json", "*/*")) } }
        }
        msg?.let { Hint(it, a) }
    }
}

// ---------------- Détail ----------------

@Composable
private fun DetailCard(p: PanelCtx) {
    FoldCard("Détail des filtres", "Préampli %.1f dB · %d filtres".format(p.preamp, p.chain.size), p.accent) {
        p.chain.forEachIndexed { i, f ->
            Text("${i + 1}. ${typeName(f.type)} · ${Curve.fmtHz(f.f)} · %+.1f dB · Q %.1f".format(f.g, f.q), color = Color.White, fontSize = 13.sp)
        }
        Text("${p.fam.name} · ${p.preset.name}", color = TextDim, fontSize = 11.sp)
    }
}

// ---------------- Préparer le téléphone ----------------

private data class PrepItem(val id: String, val title: String, val how: String, val open: (android.content.Context) -> Boolean)

private fun launchPkg(ctx: android.content.Context, pkg: String): Boolean {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: return false
    return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
}

private fun startAny(ctx: android.content.Context, vararg intents: Intent): Boolean {
    for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return true
    return false
}

private val prepItems = listOf(
    PrepItem("samsung", "Effets sonores du téléphone",
        "Qualité et effets sonores : égaliseur à plat, Dolby Atmos et Adapt Sound coupés. Sur la box : Paramètres > Son, effets coupés.") { ctx ->
        startAny(ctx, Intent().setClassName("com.sec.android.app.soundalive", "com.sec.android.app.soundalive.SAControlPanelActivity"),
            Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
    },
    PrepItem("spotify", "Spotify", "Paramètres > Lecture : égaliseur coupé, normalisation sur Normal. Qualité : Lossless ou Très élevée.") { launchPkg(it, "com.spotify.music") },
    PrepItem("ytmusic", "YouTube Music", "Paramètres > Qualité audio : Élevée / Toujours élevée.") { launchPkg(it, "com.google.android.apps.youtube.music") },
    PrepItem("youtube", "YouTube", "Pendant une vidéo : roue dentée > Paramètres supplémentaires > Volume stable désactivé.") { ctx ->
        launchPkg(ctx, "com.google.android.youtube") || launchPkg(ctx, "com.google.android.youtube.tv")
    },
)

@Composable
private fun PrepareCard(accent: Color) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("basse_prep", android.content.Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prepItems.filter { prefs.getBoolean(it.id, false) }.map { it.id }.toSet()) }
    var missing by remember { mutableStateOf<String?>(null) }
    FoldCard("Préparer mon téléphone", "${done.size}/${prepItems.size} réglages faits", accent) {
        prepItems.forEach { item ->
            val isDone = item.id in done
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isDone) "✓" else "○", color = if (isDone) accent else TextDim, fontWeight = FontWeight.Black,
                        modifier = Modifier.tv(accent, RoundedCornerShape(50)) {
                            done = if (isDone) done - item.id else done + item.id
                            prefs.edit().putBoolean(item.id, !isDone).apply()
                        }.padding(6.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    ActionChip("Ouvrir", accent, filled = true) { missing = if (item.open(ctx)) null else "${item.title} n'est pas installé ou ne s'ouvre pas ici." }
                }
                Hint(item.how)
            }
        }
        missing?.let { Hint(it, Warn) }
        Hint("Touchez le rond pour cocher. Android ne permet pas à une appli de changer ces réglages à votre place.")
    }
}
