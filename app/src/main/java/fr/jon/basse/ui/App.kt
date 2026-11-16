package fr.jon.basse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.jon.basse.Curve
import fr.jon.basse.EngineStatus
import fr.jon.basse.EqSettings
import fr.jon.basse.EqState
import fr.jon.basse.Family
import fr.jon.basse.Preset

private val Glass = Color.White.copy(alpha = 0.07f)
private val GlassBorder = Color.White.copy(alpha = 0.12f)
private val TextDim = Color.White.copy(alpha = 0.62f)

/** Clic + anneau de focus visible pour la télécommande Android TV. */
@Composable
fun Modifier.tv(accent: Color, shape: RoundedCornerShape = RoundedCornerShape(16.dp), onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) 1.05f else 1f, label = "focus")
    return this
        .scale(s)
        .clip(shape)
        .border(if (focused) 3.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

data class UiCallbacks(
    val toggle: () -> Unit,
    val select: (String) -> Unit,
    val setMode: (video: Boolean) -> Unit,
    val update: ((EqSettings) -> EqSettings) -> Unit,
)

@Composable
fun BasseApp(settings: EqSettings, status: EngineStatus, cb: UiCallbacks) {
    val cat = EqState.catalog
    val (activeFam, activePreset) = cat.find(settings.presetId) ?: (cat.families.first() to cat.families.first().styles.first())
    var browseFamId by rememberSaveable { mutableStateOf(activeFam.id) }
    LaunchedEffect(activeFam.id) { browseFamId = activeFam.id }
    val browseFam = cat.families.firstOrNull { it.id == browseFamId } ?: activeFam
    val videoMode = activeFam.isVideo

    // Le thème suit la famille parcourue : il change dès qu'on change de style ou de mode.
    val c1 by animateColorAsState(browseFam.c1, tween(900), label = "c1")
    val c2 by animateColorAsState(browseFam.c2, tween(900), label = "c2")
    val accent by animateColorAsState(browseFam.accent, tween(900), label = "acc")

    val chain = remember(settings) { EqState.currentChain(settings) }
    val points = remember(chain) { Curve.sample(chain) }
    val preamp = remember(chain) { Curve.autoPreamp(chain) }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(
            c1, c2, accent, browseFam.anim,
            bpm = if (browseFam.id == activeFam.id) activePreset.bpm else browseFam.styles.first().bpm,
            enabled = settings.enabled, curve = points, modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 760.dp
            val cols = when {
                maxWidth >= 1200.dp -> 4
                wide -> 3
                maxWidth >= 420.dp -> 3
                else -> 2
            }
            val panel: @Composable (Modifier) -> Unit = { m ->
                SidePanel(settings, status, activeFam, activePreset, points, preamp, chain, accent, cb, m)
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Browser(settings, activePreset, browseFam, videoMode, accent, cols, cb,
                        onBrowse = { browseFamId = it }, header = true, panel = null, modifier = Modifier.weight(1.35f))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { panel(Modifier.fillMaxWidth()) }
                    }
                }
            } else {
                Browser(settings, activePreset, browseFam, videoMode, accent, cols, cb,
                    onBrowse = { browseFamId = it }, header = true, panel = panel,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Browser(
    settings: EqSettings, activePreset: Preset, browseFam: Family, videoMode: Boolean,
    accent: Color, cols: Int, cb: UiCallbacks, onBrowse: (String) -> Unit,
    header: Boolean, panel: (@Composable (Modifier) -> Unit)?, modifier: Modifier,
) {
    val cat = EqState.catalog
    val families = cat.families.filter { it.isVideo == videoMode }
    val parent = cat.parentOf(settings.presetId)
    val list = rememberLazyListState()

    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)) {
        if (header) item { Header(settings, activePreset, accent, cb) }
        item { ModeSwitch(videoMode, accent, cb.setMode) }
        if (panel != null) item { panel(Modifier.fillMaxWidth()) }
        if (!videoMode) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
                items(families, key = { it.id }) { f ->
                    val sel = f.id == browseFam.id
                    Text(
                        f.name,
                        color = if (sel) Color.Black else Color.White,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .tv(f.accent, RoundedCornerShape(50)) { onBrowse(f.id) }
                            .background(if (sel) f.accent else Glass, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }
        item {
            Text(browseFam.name.uppercase(), color = accent, fontSize = 12.sp, letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        val rows = browseFam.styles.chunked(cols)
        rows.forEachIndexed { idx, row ->
            item(key = "row-${browseFam.id}-$idx") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { st ->
                        StyleCard(st, isActive = parent?.id == st.id, isExact = settings.presetId == st.id,
                            modified = settings.overrides.containsKey(st.id) || st.subs.any { settings.overrides.containsKey(it.id) },
                            accent = accent, modifier = Modifier.weight(1f)) { cb.select(st.id) }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            val open = row.firstOrNull { it.id == parent?.id && it.subs.isNotEmpty() }
            if (open != null) item(key = "subs-${open.id}") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.35f)).border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                        .padding(12.dp),
                ) {
                    Text("Sous-styles de ${open.name}", color = TextDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        open.subs.forEach { sub ->
                            val sel = settings.presetId == sub.id
                            Column(
                                Modifier.tv(accent, RoundedCornerShape(14.dp)) { cb.select(sub.id) }
                                    .background(if (sel) accent else Glass, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(sub.name + if (settings.overrides.containsKey(sub.id)) "  ✎" else "",
                                    color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(sub.desc, color = if (sel) Color.Black.copy(alpha = 0.7f) else TextDim, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun Header(settings: EqSettings, preset: Preset, accent: Color, cb: UiCallbacks) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("BASSE", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text("FiiO FT1 · ${EqState.catalog.count} réglages", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(preset.name, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                (if (preset.bpm > 0) "${preset.bpm} BPM · " else "") + preset.desc,
                color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        PowerButton(settings.enabled, accent, cb.toggle)
    }
}

@Composable
private fun PowerButton(on: Boolean, accent: Color, onClick: () -> Unit) {
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(500), label = "glow")
    Box(
        Modifier.size(84.dp).tv(accent, RoundedCornerShape(50), onClick)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f * glow), Color.Transparent)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(62.dp).clip(CircleShape)
                .background(if (on) accent else Color.White.copy(alpha = 0.12f))
                .border(2.dp, accent.copy(alpha = 0.6f + 0.4f * glow), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (on) "ON" else "OFF", color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ModeSwitch(video: Boolean, accent: Color, onMode: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Glass).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(false to "♫  Musique", true to "▶  Cinéma & vidéo").forEach { (v, label) ->
            val sel = v == video
            Box(
                Modifier.weight(1f).tv(accent, RoundedCornerShape(50)) { onMode(v) }
                    .background(if (sel) accent else Color.Transparent, RoundedCornerShape(50))
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun StyleCard(st: Preset, isActive: Boolean, isExact: Boolean, modified: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (isActive) Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.45f)))
    else Brush.linearGradient(listOf(Glass, Color.White.copy(alpha = 0.03f)))
    Column(
        modifier.height(104.dp).tv(accent, RoundedCornerShape(18.dp), onClick)
            .background(bg, RoundedCornerShape(18.dp))
            .border(1.dp, if (isActive) accent else GlassBorder, RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(st.name, color = if (isActive) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    st.bpm > 0 -> "${st.bpm} BPM"
                    else -> st.desc
                },
                color = if (isActive) Color.Black.copy(alpha = 0.7f) else TextDim, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (st.subs.isNotEmpty()) Text("+${st.subs.size}", color = if (isActive) Color.Black else accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (modified) Text("  ✎", color = if (isActive) Color.Black else accent, fontSize = 12.sp)
            if (isExact) Text("  ●", color = Color.Black, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SidePanel(
    s: EqSettings, status: EngineStatus, fam: Family, preset: Preset, points: FloatArray, preamp: Double,
    chain: List<fr.jon.basse.Filter>, accent: Color, cb: UiCallbacks, modifier: Modifier,
) {
    var showPeq by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Courbe appliquée", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Préampli %.1f dB".format(preamp), color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            CurveView(points, accent, Modifier.fillMaxWidth().height(170.dp))
            Spacer(Modifier.height(6.dp))
            StatusLine(s, status, accent)
        }
        StyleEditor(s, preset, accent, cb)
        Card {
            ToggleRow("Correction FiiO FT1", "Corrige 250 Hz, le creux 3-5 kHz et le pic 7,5 kHz", s.tweaks.ft1, accent) {
                cb.update { st -> st.copy(tweaks = st.tweaks.copy(ft1 = it)) }
            }
            Stepper("Grave (80 Hz)", s.tweaks.bassDb, accent) { v -> cb.update { st -> st.copy(tweaks = st.tweaks.copy(bassDb = v)) } }
            Stepper("Aigus (8 kHz)", s.tweaks.trebleDb, accent) { v -> cb.update { st -> st.copy(tweaks = st.tweaks.copy(trebleDb = v)) } }
            Text("Avec des lunettes, le FT1 perd du grave : montez le Grave de 2 dB.", color = TextDim, fontSize = 11.sp)
            ActionChip("Tout remettre à zéro (tous les styles)", accent) {
                cb.update { st -> st.copy(overrides = emptyMap(), tweaks = fr.jon.basse.UserTweaks()) }
            }
        }
        Card {
            ToggleRow("Mode global", "Égalise tout le son du système (session 0). Sinon, seulement les lecteurs compatibles.", s.globalMode, accent) {
                cb.update { st -> st.copy(globalMode = it) }
            }
        }
        Card {
            Row(Modifier.fillMaxWidth().tv(accent) { showPeq = !showPeq }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Valeurs PEQ (FiiO Control / BTR13)", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (showPeq) "▲" else "▼", color = accent)
            }
            AnimatedVisibility(showPeq, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("Préampli : %.1f dB · %d filtres%s".format(preamp, chain.size, if (chain.size > 10) " (BTR13 : 10 max)" else ""),
                        color = TextDim, fontSize = 12.sp)
                    chain.forEachIndexed { i, f ->
                        val type = when (f.type) { "LS" -> "Low shelf"; "HS" -> "High shelf"; else -> "Peak" }
                        Text("${i + 1}. $type · ${Curve.fmtHz(f.f)} · %+.1f dB · Q %.1f".format(f.g, f.q),
                            color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text("Ne cumulez pas avec le PEQ du BTR13 : utilisez l'un ou l'autre.", color = TextDim, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Text("${fam.name} · ${preset.name}", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun StatusLine(s: EqSettings, st: EngineStatus, accent: Color) {
    val (dot, txt) = when {
        !s.enabled -> Color.Gray to "Égaliseur coupé"
        s.globalMode && st.globalOk == true -> accent to "Actif sur tout le son" + if (st.sessions > 0) " · ${st.sessions} lecteur(s)" else ""
        s.globalMode && st.globalOk == false -> Color(0xFFFFB020) to
            "Mode global refusé par l'appareil. Égalisés : lecteurs compatibles (${st.sessions})"
        else -> accent to "Lecteurs compatibles égalisés : ${st.sessions}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(txt, color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, GlassBorder, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().tv(accent) { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = TextDim, fontSize = 11.sp)
        }
        Switch(
            checked = value, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.Black),
        )
    }
}

@Composable
private fun Stepper(label: String, value: Double, accent: Color, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        StepBtn("−", accent) { onChange((value - 0.5).coerceAtLeast(-6.0)) }
        Text("%+.1f dB".format(value), color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(78.dp).padding(horizontal = 8.dp))
        StepBtn("+", accent) { onChange((value + 0.5).coerceAtMost(6.0)) }
    }
}

@Composable
private fun StepBtn(t: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).tv(accent, RoundedCornerShape(50), onClick).background(Glass, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}


// ---------- Éditeur du style ----------

private fun roundHz(f: Double): Double = when {
    f < 100 -> Math.round(f).toDouble()
    f < 1000 -> (Math.round(f / 5) * 5).toDouble()
    else -> (Math.round(f / 50) * 50).toDouble()
}

private fun typeName(t: String) = when (t) { "LS" -> "Grave (shelf)"; "HS" -> "Aigu (shelf)"; else -> "Bosse (peak)" }
private fun nextType(t: String) = when (t) { "PK" -> "LS"; "LS" -> "HS"; else -> "PK" }

@Composable
private fun StyleEditor(s: EqSettings, preset: Preset, accent: Color, cb: UiCallbacks) {
    var open by rememberSaveable { mutableStateOf(false) }
    val list = EqState.styleFilters(s, preset)
    val modified = s.overrides.containsKey(preset.id)
    fun save(newList: List<fr.jon.basse.Filter>) = cb.update { st -> st.copy(overrides = st.overrides + (preset.id to newList)) }
    fun edit(i: Int, f: (fr.jon.basse.Filter) -> fr.jon.basse.Filter) = save(list.mapIndexed { j, x -> if (j == i) f(x) else x })

    Card {
        Row(Modifier.fillMaxWidth().tv(accent) { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Régler « ${preset.name} »" + if (modified) "  ✎" else "", color = Color.White, fontWeight = FontWeight.Bold)
                Text(if (modified) "Modifié par vous" else "Réglage d'origine", color = TextDim, fontSize = 11.sp)
            }
            Text(if (open) "▲" else "▼", color = accent)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (list.isEmpty()) Text("Aucun filtre : le style est à plat (seule la correction FT1 s'applique si elle est active).",
                    color = TextDim, fontSize = 12.sp)
                list.forEachIndexed { i, f ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}.", color = accent, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(typeName(f.type), color = Color.White, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f).tv(accent, RoundedCornerShape(8.dp)) { edit(i) { it.copy(type = nextType(it.type)) } }
                                    .padding(4.dp))
                            StepBtn("✕", accent) { save(list.filterIndexed { j, _ -> j != i }) }
                        }
                        ValueRow("Fréquence", Curve.fmtHz(f.f), accent,
                            minus = { edit(i) { it.copy(f = roundHz((it.f / 1.122462).coerceAtLeast(20.0))) } },
                            plus = { edit(i) { it.copy(f = roundHz((it.f * 1.122462).coerceAtMost(20000.0))) } })
                        ValueRow("Gain", "%+.1f dB".format(f.g), accent,
                            minus = { edit(i) { it.copy(g = (it.g - 0.5).coerceAtLeast(-12.0)) } },
                            plus = { edit(i) { it.copy(g = (it.g + 0.5).coerceAtMost(12.0)) } })
                        ValueRow("Largeur (Q)", "%.1f".format(f.q), accent,
                            minus = { edit(i) { it.copy(q = ((it.q - if (it.q > 2) 0.5 else 0.1) * 10).let { v -> Math.round(v) / 10.0 }.coerceAtLeast(0.3)) } },
                            plus = { edit(i) { it.copy(q = ((it.q + if (it.q >= 2) 0.5 else 0.1) * 10).let { v -> Math.round(v) / 10.0 }.coerceAtMost(8.0)) } })
                    }
                }
                FlowRowActions(accent, canAdd = list.size < 8, modified = modified,
                    onAdd = { save(list + fr.jon.basse.Filter("PK", 1000.0, 0.0, 1.0)) },
                    onFlat = { save(emptyList()) },
                    onReset = { cb.update { st -> st.copy(overrides = st.overrides - preset.id) } })
                Text("Fréquence : 1/6 d'octave par appui. Q petit = bosse large, Q grand = correction étroite.",
                    color = TextDim, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(accent: Color, canAdd: Boolean, modified: Boolean, onAdd: () -> Unit, onFlat: () -> Unit, onReset: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canAdd) ActionChip("+ Ajouter un filtre", accent, onAdd)
        ActionChip("Mettre à plat", accent, onFlat)
        if (modified) ActionChip("↺ Revenir à l'origine", accent, onReset, filled = true)
    }
}

@Composable
private fun ActionChip(label: String, accent: Color, onClick: () -> Unit, filled: Boolean = false) {
    Text(
        label, color = if (filled) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.tv(accent, RoundedCornerShape(50), onClick)
            .background(if (filled) accent else Glass, RoundedCornerShape(50))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun ValueRow(label: String, value: String, accent: Color, minus: () -> Unit, plus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StepBtn("−", accent, minus)
        Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.width(92.dp).padding(horizontal = 8.dp))
        StepBtn("+", accent, plus)
    }
}
