package fr.jon.basse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Glass = Color.White.copy(alpha = 0.07f)
val GlassBorder = Color.White.copy(alpha = 0.12f)
val TextDim = Color.White.copy(alpha = 0.62f)
val Warn = Color(0xFFFFB020)

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

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, GlassBorder, RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Carte repliable. */
@Composable
fun FoldCard(title: String, subtitle: String, accent: Color, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Card {
        Row(Modifier.fillMaxWidth().tv(accent) { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) Text(subtitle, color = TextDim, fontSize = 11.sp)
            }
            Text(if (open) "▲" else "▼", color = accent)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp), content = content)
        }
    }
}

@Composable
fun ToggleRow(title: String, sub: String, value: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().tv(accent) { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            if (sub.isNotBlank()) Text(sub, color = TextDim, fontSize = 11.sp)
        }
        Switch(checked = value, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.Black))
    }
}

@Composable
fun ValueRow(label: String, value: String, accent: Color, minus: () -> Unit, plus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        StepBtn("−", accent, minus)
        Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.width(96.dp).padding(horizontal = 8.dp))
        StepBtn("+", accent, plus)
    }
}

@Composable
fun StepBtn(t: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).tv(accent, RoundedCornerShape(50), onClick).background(Glass, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun ActionChip(label: String, accent: Color, filled: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (filled) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.tv(accent, RoundedCornerShape(50), onClick)
            .background(if (filled) accent else Glass, RoundedCornerShape(50))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun Hint(text: String, color: Color = TextDim) = Text(text, color = color, fontSize = 12.sp)

fun norm(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "")

fun hhmm(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
