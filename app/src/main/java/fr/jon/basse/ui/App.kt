package fr.jon.basse.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.jon.basse.Curve
import fr.jon.basse.EngineStatus
import fr.jon.basse.EqSettings
import fr.jon.basse.EqState
import fr.jon.basse.Family
import fr.jon.basse.OutputInfo
import fr.jon.basse.Preset

data class UiCallbacks(
    val toggle: () -> Unit,
    val select: (String) -> Unit,
    val setMode: (video: Boolean) -> Unit,
    val update: ((EqSettings) -> EqSettings) -> Unit,
    val askMic: () -> Unit,
)

@Composable
fun BasseApp(settings: EqSettings, status: EngineStatus, output: OutputInfo, lastApp: String?, tick: Int, cb: UiCallbacks) {
    val cat = EqState.catalog
    val allFams = EqState.families(settings)
    val (activeFam, activePreset) = EqState.find(settings.presetId) ?: (cat.families.first() to cat.families.first().styles.first())
    var browseFamId by rememberSaveable { mutableStateOf(activeFam.id) }
    LaunchedEffect(activeFam.id) { browseFamId = activeFam.id }
    val browseFam = allFams.firstOrNull { it.id == browseFamId } ?: activeFam
    var testOpen by remember { mutableStateOf(false) }

    val c1 by animateColorAsState(browseFam.c1, tween(900), label = "c1")
    val c2 by animateColorAsState(browseFam.c2, tween(900), label = "c2")
    val accent by animateColorAsState(browseFam.accent, tween(900), label = "acc")

    val chain = remember(settings, output) { EqState.currentChain(settings, output) }
    val points = remember(chain) { Curve.sample(chain) }
    val preamp = remember(chain, settings) { EqState.preampDb(settings, chain) }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(
            c1, c2, accent, browseFam.anim,
            bpm = if (browseFam.id == activeFam.id) activePreset.bpm else browseFam.styles.firstOrNull()?.bpm ?: 0,
            enabled = settings.enabled, curve = points, modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 760.dp
            val cols = when { maxWidth >= 1200.dp -> 4; wide -> 3; maxWidth >= 420.dp -> 3; else -> 2 }
            val pc = PanelCtx(settings, status, output, lastApp, tick, activeFam, activePreset, points, preamp, chain, accent, cb) { testOpen = true }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    LazyColumn(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                        browser(settings, activePreset, browseFam, activeFam.isVideo, accent, cols, cb, null) { browseFamId = it }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
                        panels(pc, includeCurve = true)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)) {
                    browser(settings, activePreset, browseFam, activeFam.isVideo, accent, cols, cb, { CurveCard(pc) }) { browseFamId = it }
                    panels(pc, includeCurve = false)
                }
            }
        }
        if (testOpen) HearingTestOverlay(accent) { testOpen = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.browser(
    settings: EqSettings, activePreset: Preset, browseFam: Family, videoMode: Boolean,
    accent: Color, cols: Int, cb: UiCallbacks, curveCard: (@Composable () -> Unit)?, onBrowse: (String) -> Unit,
) {
    item { Header(settings, activePreset, accent, cb) }
    item { ModeSwitch(videoMode, accent, cb.setMode) }
    item { SearchBlock(settings, accent, cb) }
    if (settings.favorites.isNotEmpty()) item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
            items(settings.favorites, key = { "fav-$it" }) { id ->
                val found = EqState.find(id)
                if (found != null) {
                    val (f, p) = found
                    val sel = settings.presetId == id
                    Text("★ ${p.name}", color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.tv(f.accent, RoundedCornerShape(50)) { cb.select(id) }
                            .background(if (sel) f.accent else Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .border(1.dp, f.accent.copy(alpha = 0.7f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
    if (curveCard != null) item { curveCard() }
    val families = EqState.families(settings).filter { it.isVideo == videoMode }
    val parent = EqState.parentOf(settings.presetId)
    if (!videoMode) item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)) {
            items(families, key = { it.id }) { f ->
                val sel = f.id == browseFam.id
                Text(f.name, color = if (sel) Color.Black else Color.White, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp,
                    modifier = Modifier.tv(f.accent, RoundedCornerShape(50)) { onBrowse(f.id) }
                        .background(if (sel) f.accent else Glass, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
    }
    item {
        Text(browseFam.name.uppercase(), color = accent, fontSize = 12.sp, letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    }
    if (browseFam.id == "freeparty") item { WoojerTip(accent) }
    if (browseFam.id == "cx5") item { Cx5Tip(accent) }
    val rows = browseFam.styles.chunked(cols)
    rows.forEachIndexed { idx, row ->
        item(key = "row-${browseFam.id}-$idx") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { st ->
                    val mod = settings.overrides.containsKey(st.id) || settings.graphic.containsKey(st.id) ||
                        st.subs.any { settings.overrides.containsKey(it.id) || settings.graphic.containsKey(it.id) }
                    StyleCard(st, isActive = parent?.id == st.id, isExact = settings.presetId == st.id, modified = mod,
                        locked = st.id in settings.locked, accent = accent, modifier = Modifier.weight(1f)) { cb.select(st.id) }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        val open = row.firstOrNull { it.id == parent?.id && it.subs.isNotEmpty() }
        if (open != null) item(key = "subs-${open.id}") {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(12.dp),
            ) {
                Text("Sous-styles de ${open.name}", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    open.subs.forEach { sub ->
                        val sel = settings.presetId == sub.id
                        val mark = (if (settings.overrides.containsKey(sub.id) || settings.graphic.containsKey(sub.id)) "  ✎" else "") +
                            (if (sub.id in settings.locked) "  🔒" else "")
                        Column(
                            Modifier.tv(accent, RoundedCornerShape(14.dp)) { cb.select(sub.id) }
                                .background(if (sel) accent else Glass, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(sub.name + mark, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(sub.desc, color = if (sel) Color.Black.copy(alpha = 0.7f) else TextDim, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                        }
                    }
                }
            }
        }
    }
    item { Spacer(Modifier.height(8.dp)) }
}

@Composable
private fun SearchBlock(settings: EqSettings, accent: Color, cb: UiCallbacks) {
    var q by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Rechercher un style (neuro, tribe, séries…)", color = TextDim) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent, unfocusedBorderColor = GlassBorder, cursorColor = accent,
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedContainerColor = Color.Black.copy(alpha = 0.3f), unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
            ),
        )
        val nq = norm(q.trim())
        if (nq.length >= 2) {
            val res = remember(nq, settings.customs) {
                EqState.allPresets(settings).filter { (f, p) -> norm(p.name).contains(nq) || norm(p.desc).contains(nq) || norm(f.name).contains(nq) }.take(30)
            }
            if (res.isEmpty()) Hint("Aucun style trouvé.")
            res.forEach { (f, p) ->
                Row(
                    Modifier.fillMaxWidth().tv(f.accent, RoundedCornerShape(14.dp)) { cb.select(p.id); q = "" }
                        .background(if (settings.presetId == p.id) f.accent.copy(alpha = 0.5f) else Glass, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(f.accent))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${f.name} · ${p.desc}", color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun WoojerTip(accent: Color) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Le sub dans la poitrine : gilet Woojer", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Connectez le gilet et le BTR11, puis activez « Double audio » dans la sortie média Samsung pour les deux en même temps.",
                color = TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        ActionChip("Bluetooth", accent, filled = true) {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

@Composable
private fun Header(settings: EqSettings, preset: Preset, accent: Color, cb: UiCallbacks) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("BASSE", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
            Text("${EqState.catalog.count} réglages" + if (settings.schedActive) " · programmation en cours" else "", color = TextDim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            val fav = preset.id in settings.favorites
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(preset.name, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(if (fav) "★" else "☆", color = accent, fontSize = 22.sp,
                    modifier = Modifier.tv(accent, RoundedCornerShape(50)) { EqState.toggleFavorite(ctx, preset.id) }.padding(horizontal = 6.dp))
            }
            Text((if (preset.bpm > 0) "${preset.bpm} BPM · " else "") + preset.desc,
                color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            Modifier.size(62.dp).clip(CircleShape).background(if (on) accent else Color.White.copy(alpha = 0.12f))
                .border(2.dp, accent.copy(alpha = 0.6f + 0.4f * glow), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (on) "ON" else "OFF", color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp) }
    }
}

@Composable
private fun ModeSwitch(video: Boolean, accent: Color, onMode: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Glass).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(false to "♫  Musique", true to "▶  Cinéma & vidéo").forEach { (v, label) ->
            val sel = v == video
            Box(
                Modifier.weight(1f).tv(accent, RoundedCornerShape(50)) { onMode(v) }
                    .background(if (sel) accent else Color.Transparent, RoundedCornerShape(50)).padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun StyleCard(st: Preset, isActive: Boolean, isExact: Boolean, modified: Boolean, locked: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (isActive) Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.45f)))
    else Brush.linearGradient(listOf(Glass, Color.White.copy(alpha = 0.03f)))
    Column(
        modifier.height(104.dp).tv(accent, RoundedCornerShape(18.dp), onClick)
            .background(bg, RoundedCornerShape(18.dp))
            .border(1.dp, if (isActive) accent else GlassBorder, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(st.name, color = if (isActive) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (st.bpm > 0) "${st.bpm} BPM" else st.desc,
                color = if (isActive) Color.Black.copy(alpha = 0.7f) else TextDim, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (st.subs.isNotEmpty()) Text("+${st.subs.size}", color = if (isActive) Color.Black else accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (modified) Text("  ✎", color = if (isActive) Color.Black else accent, fontSize = 12.sp)
            if (locked) Text(" 🔒", fontSize = 11.sp)
            if (isExact) Text("  ●", color = Color.Black, fontSize = 11.sp)
        }
    }
}


@Composable
private fun Cx5Tip(accent: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Réglages conseillés sur l'écran Mazda", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Graves 0 et Aigus 0 (Basse s'en charge) · AudioPilot coupé (utilisez « Autoroute ») · Centerpoint coupé pour la musique, à essayer pour les films · ALC coupé · Balance et fader au centre.",
            color = TextDim, fontSize = 12.sp)
        Text("Ne couvrez pas les ouïes du coffre : le caisson y respire. Punch conseillé : Léger ou Moyen, jamais Fort (caisson de 13 cm).",
            color = TextDim, fontSize = 12.sp)
        Text("La correction CX-5 s'active toute seule quand le Bluetooth de la Mazda est connecté.", color = accent, fontSize = 12.sp)
    }
}
