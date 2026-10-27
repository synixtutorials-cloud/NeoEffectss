@file:OptIn(ExperimentalMaterial3Api::class)

package com.cusil.neoeffects

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

fun fmtMs(ms: Long): String {
    val m = ms / 60000L
    val s = (ms / 1000L) % 60L
    val d = (ms % 1000L) / 100L
    return String.format("%d:%02d.%d", m, s, d)
}

class UndoStack(private val project: Project, private val lib: List<NeoEffect>) {
    private val undos = ArrayList<String>()
    private val redos = ArrayList<String>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun snap(): String = ProjectStore.toJson(project).toString()

    private fun sync() {
        canUndo = undos.isNotEmpty()
        canRedo = redos.isNotEmpty()
    }

    fun push() {
        undos.add(snap())
        if (undos.size > 40) undos.removeAt(0)
        redos.clear()
        sync()
    }

    private fun restore(s: String) {
        val p2 = ProjectStore.fromJson(JSONObject(s), lib)
        project.clips.clear()
        project.clips.addAll(p2.clips)
        project.playheadMs = p2.playheadMs
        project.layers.clear()
        project.layers.addAll(p2.layers)
        project.beats.clear()
        project.beats.addAll(p2.beats)
    }

    fun undo(): Boolean {
        if (undos.isEmpty()) return false
        redos.add(snap())
        restore(undos.removeAt(undos.size - 1))
        sync()
        return true
    }

    fun redo(): Boolean {
        if (redos.isEmpty()) return false
        undos.add(snap())
        restore(redos.removeAt(redos.size - 1))
        sync()
        return true
    }
}

@Composable
fun NeoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape).background(if (selected) Color(0xFF3A0914) else NC.card2)
            .border(1.dp, if (selected) NC.red else NC.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, color = if (selected) NC.red else Color.White, fontSize = 12.sp, maxLines = 1) }
}

@Composable
fun ToolPill(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.clip(shape).background(NC.card2).border(1.dp, NC.border, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = if (enabled) NC.red else NC.dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) Color.White else NC.dim, fontSize = 12.sp)
    }
}

@Composable
fun ParamControl(inst: FxInst, p: NeoParam, clip: Item, project: Project, undo: UndoStack, modifier: Modifier = Modifier) {
    val off = (project.playheadMs - project.startOf(clip)).coerceIn(0L, clip.durMs)
    val v = inst.valueAt(p, off)
    val ks = inst.keys[p.id]
    val hasKeys = ks != null && ks.isNotEmpty()
    val onKey = ks != null && ks.any { abs(it.t - off) < 50L }
    val hi = maxOf(p.max, p.min + 0.01f)
    var drag by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(String.format("%.2f", v), color = NC.sub, fontSize = 11.sp)
            Text(
                if (onKey) " ◆" else " ◇", color = if (hasKeys) NC.red else NC.sub, fontSize = 15.sp,
                modifier = Modifier.clickable {
                    undo.push()
                    if (onKey) {
                        inst.values[p.id] = v
                        inst.removeKey(p.id, off)
                    } else {
                        inst.setKey(p.id, off, v)
                    }
                }.padding(horizontal = 6.dp)
            )
        }
        Slider(
            value = v.coerceIn(p.min, hi),
            onValueChange = { nv ->
                if (!drag) {
                    undo.push()
                    drag = true
                }
                if (hasKeys) inst.setKey(p.id, off, nv) else inst.values[p.id] = nv
            },
            onValueChangeFinished = { drag = false },
            valueRange = p.min..hi,
            colors = SliderDefaults.colors(thumbColor = NC.red, activeTrackColor = NC.red, inactiveTrackColor = Color(0xFF2A2C3A))
        )
    }
}

@Composable
fun ToolRail(tab: String, onPick: (String) -> Unit) {
    Column(
        Modifier.width(60.dp).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RailItem(Icons.Outlined.AutoAwesome, "Effects", tab == "effects") { onPick("effects") }
        RailItem(Icons.Outlined.Palette, "Filters", tab == "filters") { onPick("filters") }
        RailItem(Icons.Outlined.Tune, "Adjust", tab == "adjust") { onPick("adjust") }
        RailItem(Icons.Outlined.AutoFixHigh, "AI Enhance", false) { onPick("ai") }
        RailItem(Icons.Outlined.Speed, "Speed", tab == "speed") { onPick("speed") }
        RailItem(Icons.Outlined.MoreHoriz, "More", tab == "more") { onPick("more") }
    }
}

@Composable
fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Color(0xFF3A0914) else Color.Transparent)
            .border(1.dp, if (selected) NC.red else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (selected) NC.red else Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = if (selected) NC.red else Color.White, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
fun ClipBlock(c: Clip, selected: Boolean, zoom: Float) {
    val ctx = LocalContext.current
    LaunchedEffect(c) {
        if (c.thumb == null) {
            c.thumb = withContext(Dispatchers.IO) { loadThumb(ctx, c) }
        }
    }
    val w = maxOf(30f, c.durMs / 1000f * zoom).dp
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.width(w).fillMaxHeight().padding(horizontal = 0.5.dp).clip(shape)
            .background(Color(0xFF2A0A12))
            .border(if (selected) 2.dp else 1.dp, if (selected) NC.redGlow else Color(0xFF5A1626), shape)
    ) {
        val t = c.thumb
        if (t != null) {
            Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
        }
        val label = (if (c.fx.isNotEmpty()) "✦" + c.fx.size + " " else "") + fmtClock(c.durMs)
        Text(label, color = Color.White, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(3.dp))
    }
}

@Composable
fun TrackLabel(name: String, icon: ImageVector, hidden: Boolean, dimmed: Boolean, onEye: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.width(76.dp).height(34.dp).clip(shape).background(NC.card2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Visibility",
            tint = if (hidden) NC.dim else NC.sub,
            modifier = Modifier.padding(start = 4.dp).size(22.dp).clickable { onEye() }.padding(3.dp)
        )
        Icon(icon, name, tint = if (dimmed) NC.dim else NC.red, modifier = Modifier.padding(start = 2.dp).size(14.dp))
        Text(name, color = if (dimmed) NC.dim else Color.White, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
fun FollowPlayhead(project: Project, scroll: ScrollState, pxPerMs: Float, viewW: Int, playing: Boolean) {
    val x = project.playheadMs * pxPerMs
    LaunchedEffect((x / 40f).toInt(), playing) {
        if (playing && viewW > 0 && (x > scroll.value + viewW - 60 || x < scroll.value)) {
            scroll.scrollTo(maxOf(0, (x - viewW / 2f).toInt()))
        }
    }
}

@Composable
fun BottomTabs(tab: String, onPick: (String) -> Unit) {
    val items = listOf(
        Triple("edit", "Edit", Icons.Outlined.ContentCut),
        Triple("audio", "Audio", Icons.Outlined.MusicNote),
        Triple("text", "Text", Icons.Outlined.TextFields),
        Triple("overlay", "Overlay", Icons.Outlined.Layers),
        Triple("effects", "Effects", Icons.Outlined.AutoAwesome),
        Triple("transitions", "Transitions", Icons.Outlined.Compare),
        Triple("filters", "Filters", Icons.Outlined.Palette),
        Triple("adjust", "Adjust", Icons.Outlined.Tune),
        Triple("color", "Color", Icons.Outlined.Palette)
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (tb in items) {
            val sel = tab == tb.first
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.width(68.dp).clip(shape)
                    .background(if (sel) Color(0xFF3A0914) else Color.Transparent)
                    .border(1.dp, if (sel) NC.red else Color.Transparent, shape)
                    .clickable { onPick(tb.first) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(tb.third, tb.second, tint = if (sel) NC.red else Color.White, modifier = Modifier.size(20.dp))
                Text(tb.second, color = if (sel) NC.red else Color.White, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun EditPanel(
    project: Project, selectedId: Long, hasSel: Boolean,
    onSelect: (Clip) -> Unit, onAdd: () -> Unit, onSplit: () -> Unit, onDup: () -> Unit, onDelete: () -> Unit, onSpeed: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolPill(Icons.Outlined.ContentCut, "Split") { onSplit() }
            ToolPill(Icons.Outlined.ContentCopy, "Duplicate", hasSel) { onDup() }
            ToolPill(Icons.Outlined.Delete, "Delete", hasSel) { onDelete() }
            ToolPill(Icons.Outlined.Speed, "Trim / Speed", hasSel) { onSpeed() }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(project.clips.toList()) { c ->
                val shape = RoundedCornerShape(10.dp)
                val sel = c.id == selectedId
                Column(
                    Modifier.width(96.dp).clip(shape).background(NC.card2)
                        .border(1.dp, if (sel) NC.red else NC.border, shape).clickable { onSelect(c) }
                ) {
                    Box(Modifier.fillMaxWidth().height(54.dp).background(Brush.linearGradient(listOf(NC.redDeep, NC.bg)))) {
                        val t = c.thumb
                        if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(c.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    Text(fmtMs(c.durMs) + "s", color = NC.sub, fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
                }
            }
            item {
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier.width(96.dp).height(92.dp).clip(shape).background(NC.card2)
                        .border(1.dp, NC.border, shape).clickable { onAdd() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("+", color = Color.White, fontSize = 26.sp)
                    Text("Add Media", color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun FiltersPanel(clip: Item?, library: List<NeoEffect>, project: Project, undo: UndoStack) {
    if (clip == null) {
        Text("Select a clip first.", color = NC.sub, modifier = Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(library.filter { it.category == "Color" && it.id != "color_grade" && it.shader.isNotBlank() }) { e ->
                NeoChip(e.name, clip.fx.any { it.effect.id == e.id }) {
                    undo.push()
                    val ex = clip.fx.firstOrNull { it.effect.id == e.id }
                    if (ex != null) clip.fx.remove(ex) else clip.fx.add(FxInst(e))
                }
            }
        }
        for (inst in clip.fx.filter { it.effect.category == "Color" && it.effect.id != "color_grade" }) {
            Spacer(Modifier.height(8.dp))
            Text(inst.effect.name, color = NC.red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            for (p in inst.effect.params) ParamControl(inst, p, clip, project, undo)
        }
    }
}

@Composable
fun AdjustPanel(clip: Item?, library: List<NeoEffect>, project: Project, undo: UndoStack) {
    if (clip == null) {
        Text("Select a clip first.", color = NC.sub, modifier = Modifier.padding(16.dp))
        return
    }
    val inst = clip.fx.firstOrNull { it.effect.id == "color_adjust" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (inst == null) {
            val e = library.firstOrNull { it.id == "color_adjust" }
            ToolPill(Icons.Outlined.Tune, "Enable Adjust (brightness, contrast, saturation)", e != null) {
                if (e != null) {
                    undo.push()
                    clip.fx.add(FxInst(e))
                }
            }
        } else {
            for (p in inst.effect.params) ParamControl(inst, p, clip, project, undo)
        }
    }
}

@Composable
fun TrimPanel(c: Clip, project: Project, engine: PlaybackEngine, undo: UndoStack) {
    var drag by remember { mutableStateOf(false) }
    val sc = SliderDefaults.colors(thumbColor = NC.red, activeTrackColor = NC.red, inactiveTrackColor = Color(0xFF2A2C3A))
    fun start() {
        if (!drag) {
            undo.push()
            drag = true
        }
    }
    fun done() {
        drag = false
        engine.show(project.playheadMs)
    }
    if (c.isVideo) {
        Text("Start  " + fmtMs(c.inMs), color = Color.White, fontSize = 12.sp)
        Slider(
            value = c.inMs.toFloat(),
            onValueChange = {
                start()
                c.inMs = minOf(it.toLong(), c.outMs - 200L).coerceAtLeast(0L)
            },
            onValueChangeFinished = { done() },
            valueRange = 0f..maxOf(1f, c.srcDurMs.toFloat()), colors = sc
        )
        Text("End  " + fmtMs(c.outMs), color = Color.White, fontSize = 12.sp)
        Slider(
            value = c.outMs.toFloat(),
            onValueChange = {
                start()
                c.outMs = maxOf(it.toLong(), c.inMs + 200L).coerceAtMost(c.srcDurMs)
            },
            onValueChangeFinished = { done() },
            valueRange = 0f..maxOf(1f, c.srcDurMs.toFloat()), colors = sc
        )
    } else {
        Text("Duration  " + fmtMs(c.outMs), color = Color.White, fontSize = 12.sp)
        Slider(
            value = c.outMs.toFloat().coerceIn(500f, c.srcDurMs.toFloat()),
            onValueChange = {
                start()
                c.outMs = it.toLong().coerceAtLeast(500L)
            },
            onValueChangeFinished = { done() },
            valueRange = 500f..c.srcDurMs.toFloat(), colors = sc
        )
    }
    Text("Speed  x" + String.format("%.2f", c.speed), color = Color.White, fontSize = 12.sp)
    Slider(
        value = c.speed,
        onValueChange = {
            start()
            c.speed = (Math.round(it * 20f) / 20f).coerceIn(0.1f, 4f)
        },
        onValueChangeFinished = { done() },
        valueRange = 0.1f..4f, colors = sc
    )
    Text("Time remapping curves come in a later build.", color = NC.sub, fontSize = 11.sp)
}
