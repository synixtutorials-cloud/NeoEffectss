@file:OptIn(ExperimentalMaterial3Api::class)

package com.cusil.neoeffects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PALETTE = listOf(
    0xFFE60A35.toInt(), -1, 0xFF000000.toInt(), 0xFFFFC21A.toInt(), 0xFFFF7A1A.toInt(), 0xFF2BE07A.toInt(),
    0xFF00D4FF.toInt(), 0xFF1F6BFF.toInt(), 0xFF9B2BFF.toInt(), 0xFFFF3B8D.toInt(), 0xFF888888.toInt()
)

val MESH_NAMES = listOf("None", "Cube", "Cylinder", "Sphere", "Torus", "Hex prism", "Octahedron", "Ring", "Cross", "Tunnel", "Block", "Star prism", "Flip")
val BLEND_NAMES = listOf("Normal", "Add", "Screen", "Multiply")

@Composable
fun USlider(label: String, value: Float, min: Float, max: Float, undo: UndoStack, fmt: String = "%.2f", onChange: (Float) -> Unit) {
    var drag by remember { mutableStateOf(false) }
    Column {
        Row {
            Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(String.format(fmt, value), color = NC.sub, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = {
                if (!drag) {
                    undo.push()
                    drag = true
                }
                onChange(it)
            },
            onValueChangeFinished = { drag = false },
            valueRange = min..max,
            colors = SliderDefaults.colors(thumbColor = NC.red, activeTrackColor = NC.red, inactiveTrackColor = Color(0xFF2A2C3A))
        )
    }
}

@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (c in PALETTE) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                    .border(if (selected == c) 3.dp else 1.dp, if (selected == c) NC.red else NC.border, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
fun EaseThumb(type: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(60.dp).clip(shape).background(NC.card2)
            .border(1.dp, if (selected) NC.red else NC.border, shape).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val path = Path()
            for (i in 0..24) {
                val t = i / 24f
                val v = if (type == 8) easeApply(8, t, floatArrayOf(0.42f, 0f, 0.58f, 1f)) else easeApply(type, t, null)
                val x = size.width * t
                val y = size.height * (1f - v.coerceIn(-0.3f, 1.3f) * 0.75f) - size.height * 0.12f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, NC.redGlow, style = Stroke(width = 3f))
        }
        Text(EASE_NAMES[type], color = Color.White, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
fun BezierEditor(onApply: (FloatArray) -> Unit) {
    val bz = remember { mutableStateListOf(0.42f, 0f, 0.58f, 1f) }
    Canvas(
        Modifier.size(150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0C12)).pointerInput(Unit) {
            var which = 0
            detectDragGestures(
                onDragStart = { pos ->
                    val x = pos.x / size.width
                    val y = 1f - pos.y / size.height
                    val d1 = (x - bz[0]) * (x - bz[0]) + (y - bz[1]) * (y - bz[1])
                    val d2 = (x - bz[2]) * (x - bz[2]) + (y - bz[3]) * (y - bz[3])
                    which = if (d1 <= d2) 0 else 1
                },
                onDragEnd = { onApply(floatArrayOf(bz[0], bz[1], bz[2], bz[3])) },
                onDragCancel = { onApply(floatArrayOf(bz[0], bz[1], bz[2], bz[3])) }
            ) { change, _ ->
                change.consume()
                bz[which * 2] = (change.position.x / size.width).coerceIn(0f, 1f)
                bz[which * 2 + 1] = (1f - change.position.y / size.height).coerceIn(-0.5f, 1.5f)
            }
        }
    ) {
        val w = size.width
        val h = size.height
        fun px(x: Float) = x * w
        fun py(y: Float) = h * (1f - (y + 0.5f) / 2f) * 0f + (1f - y) * h
        drawLine(Color(0xFF2A2C3A), Offset(0f, h), Offset(w, 0f), strokeWidth = 1f)
        val path = Path()
        for (i in 0..30) {
            val t = i / 30f
            val v = easeApply(8, t, floatArrayOf(bz[0], bz[1], bz[2], bz[3]))
            if (i == 0) path.moveTo(px(t), py(v)) else path.lineTo(px(t), py(v))
        }
        drawPath(path, NC.redGlow, style = Stroke(width = 4f))
        drawLine(Color(0x88FFFFFF), Offset(0f, h), Offset(px(bz[0]), py(bz[1])), strokeWidth = 2f)
        drawLine(Color(0x88FFFFFF), Offset(w, 0f), Offset(px(bz[2]), py(bz[3])), strokeWidth = 2f)
        drawCircle(Color.White, 7.dp.toPx(), Offset(px(bz[0]), py(bz[1])))
        drawCircle(Color.White, 7.dp.toPx(), Offset(px(bz[2]), py(bz[3])))
    }
}

@Composable
fun ParamGraph(item: Item, p: NeoParam, project: Project) {
    val ph = project.playheadMs - project.startOf(item)
    Canvas(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0C12))) {
        val n = 80
        val dur = maxOf(1L, item.durMs)
        val range = maxOf(0.0001f, p.max - p.min)
        val path = Path()
        for (i in 0..n) {
            val t = dur * i / n
            val v = item.tr.valueAt(p, t)
            val x = size.width * i / n
            val y = size.height * (1f - ((v - p.min) / range)).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, NC.redGlow, style = Stroke(width = 3f))
        val ks = item.tr.keys[p.id]
        if (ks != null) {
            for (k in ks) {
                drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * k.t / dur, size.height * (1f - ((k.v - p.min) / range)).coerceIn(0f, 1f)))
            }
        }
        val px = size.width * ph / dur
        drawLine(Color(0x88FFFFFF), Offset(px, 0f), Offset(px, size.height), 2f)
    }
}

@Composable
fun InspectorPanel(
    project: Project, item: Item, library: List<NeoEffect>, undo: UndoStack, engine: PlaybackEngine,
    tab: String, onTab: (String) -> Unit, beatMode: Boolean, onBeatMode: (Boolean) -> Unit,
    onOpenEffects: () -> Unit, onClose: () -> Unit, onMsg: (String) -> Unit, onAutoBeats: (Item) -> Unit
) {
    val layer = item as? Layer
    val clip = item as? Clip
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    val tabs = listOf(
        "trim" to "Trim", "blend" to "Blending", "time" to "Speed", "opacity" to (if (layer?.type == "audio") "Volume" else "Opacity"),
        "transform" to "Transform", "keys" to "Keyframes", "fill" to "Colour & Fill", "beats" to "Beats", "effects" to "Effects"
    )
    var showBz by remember { mutableStateOf(false) }
    var gp by remember { mutableStateOf(0) }
    var pulse by remember { mutableStateOf(0.15f) }
    val title = if (clip != null) clip.name else if (layer != null) (if (layer.type == "text") "Text: " + layer.text.take(14) else layer.name.ifEmpty { layer.type }) else ""

    Column(Modifier.fillMaxSize().clip(shape).background(NC.card).border(1.dp, NC.border, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconBtn(Icons.Outlined.Close, "Close", NC.sub, 34.dp) { onClose() }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in tabs) NeoChip(t.second, tab == t.first) { onTab(t.first) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            val off = (project.playheadMs - project.startOf(item)).coerceIn(0L, item.durMs)
            when (tab) {
                "trim" -> {
                    if (clip != null) {
                        TrimPanel(clip, project, engine, undo)
                    } else if (layer != null) {
                        USlider("Start (s)", layer.startMs / 1000f, 0f, project.totalMs / 1000f + 20f, undo) { layer.startMs = (it * 1000f).toLong() }
                        USlider("Length (s)", layer.lenMs / 1000f, 0.2f, maxOf(60f, layer.lenMs / 1000f), undo) { layer.lenMs = (it * 1000f).toLong() }
                    }
                }
                "blend" -> {
                    Text("Blend mode", color = NC.sub, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in BLEND_NAMES.indices) NeoChip(BLEND_NAMES[i], item.blend == i) {
                            undo.push()
                            item.blend = i
                        }
                    }
                }
                "time" -> {
                    if (clip != null) {
                        USlider("Speed", clip.speed, 0.1f, 4f, undo) {
                            clip.speed = (Math.round(it * 20f) / 20f).coerceIn(0.1f, 4f)
                            engine.show(project.playheadMs)
                        }
                        Text("Speed curves (time remap) are coming in a later build.", color = NC.sub, fontSize = 11.sp)
                    } else {
                        Text("Layers follow the timeline. Use Trim to change length. Text animation speed is in Colour & Fill.", color = NC.sub, fontSize = 12.sp)
                    }
                }
                "opacity" -> {
                    ParamControl(item.tr, TRANSFORM_FX.params[6], item, project, undo)
                    if (layer?.type == "audio") Text("For audio layers this slider is the volume. Add keyframes to fade it.", color = NC.sub, fontSize = 11.sp)
                }
                "transform" -> {
                    Text("Drag, pinch and rotate on the preview, or use the sliders. Use the ◇ buttons to animate.", color = NC.sub, fontSize = 11.sp)
                    for (i in 0..5) ParamControl(item.tr, TRANSFORM_FX.params[i], item, project, undo)
                    Text("3D shape", color = NC.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in MESH_NAMES.indices) NeoChip(MESH_NAMES[i], item.mesh == i) {
                            undo.push()
                            item.mesh = i
                        }
                    }
                    if (item.mesh != 0) USlider("Auto spin (deg/sec)", item.meshSpin, -360f, 360f, undo, "%.0f") { item.meshSpin = it }
                    if (layer != null) {
                        Text("Parent (follows a Null layer)", color = NC.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NeoChip("None", layer.parentId == 0L) {
                                undo.push()
                                layer.parentId = 0L
                            }
                            for (nl in project.layers.filter { it.type == "null" && it.id != layer.id }) {
                                NeoChip(nl.name.ifEmpty { "Null" }, layer.parentId == nl.id) {
                                    undo.push()
                                    layer.parentId = nl.id
                                }
                            }
                        }
                    }
                }
                "keys" -> {
                    val hasAny = item.tr.hasKeyAt(off) || item.fx.any { it.hasKeyAt(off) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolPill(Icons.Outlined.Add, "Key all transform") {
                            undo.push()
                            for (p in TRANSFORM_FX.params) item.tr.setKey(p.id, off, item.tr.valueAt(p, off))
                        }
                        ToolPill(Icons.Outlined.Delete, "Clear all keys") {
                            undo.push()
                            item.tr.keys.clear()
                            for (f in item.fx) f.keys.clear()
                        }
                    }
                    Text(
                        if (hasAny) "Easing for the keys at the playhead:" else "Move the playhead onto a key (◆) to change its easing.",
                        color = NC.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(EASE_NAMES.indices.toList()) { i ->
                            EaseThumb(i, false) {
                                if (i == 8) {
                                    showBz = true
                                } else if (hasAny) {
                                    undo.push()
                                    item.tr.setEaseAt(off, i, null)
                                    for (f in item.fx) f.setEaseAt(off, i, null)
                                } else {
                                    onMsg("Move the playhead onto a key first")
                                }
                            }
                        }
                    }
                    if (showBz) {
                        Text("Custom curve: drag the two handles", color = NC.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        BezierEditor { bz ->
                            if (hasAny) {
                                undo.push()
                                item.tr.setEaseAt(off, 8, bz)
                                for (f in item.fx) f.setEaseAt(off, 8, bz)
                            } else {
                                onMsg("Move the playhead onto a key first")
                            }
                        }
                    }
                    Text("Graph", color = NC.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in TRANSFORM_FX.params.indices) NeoChip(TRANSFORM_FX.params[i].name, gp == i) { gp = i }
                    }
                    Spacer(Modifier.height(4.dp))
                    ParamGraph(item, TRANSFORM_FX.params[gp], project)
                }
                "fill" -> {
                    if (layer == null) {
                        Text("For clips use Effects: Colourize, Tint, Gradient Map or the Color tab.", color = NC.sub, fontSize = 12.sp)
                    } else if (layer.type == "text") {
                        OutlinedTextField(
                            value = layer.text, onValueChange = { layer.text = it }, label = { Text("Text") },
                            maxLines = 3, colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        USlider("Size", layer.size, 0.03f, 0.4f, undo) { layer.size = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NeoChip("Bold", layer.bold) { layer.bold = !layer.bold }
                            NeoChip("Italic", layer.italic) { layer.italic = !layer.italic }
                        }
                        Text("Colour", color = NC.sub, fontSize = 12.sp)
                        ColorRow(layer.color) {
                            undo.push()
                            layer.color = it
                        }
                        USlider("Outline", layer.strokeW, 0f, 0.3f, undo) { layer.strokeW = it }
                        ColorRow(layer.stroke) {
                            undo.push()
                            layer.stroke = it
                        }
                        Text("Text animation", color = NC.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in LayerGfx.TEXT_MODES.indices) NeoChip(LayerGfx.TEXT_MODES[i], layer.textMode == i) {
                                undo.push()
                                layer.textMode = i
                            }
                        }
                        when (layer.textMode) {
                            1 -> USlider("Characters per second", layer.to, 1f, 40f, undo, "%.0f") { layer.to = it }
                            2 -> {
                                USlider("Count from", layer.from, 0f, 1000f, undo, "%.0f") { layer.from = it }
                                USlider("Count to", layer.to, 0f, 1000f, undo, "%.0f") { layer.to = it }
                            }
                            3 -> USlider("Start time (s)", layer.from, 0f, 600f, undo, "%.0f") { layer.from = it }
                            4 -> USlider("Reveal speed (chars/s)", layer.to, 1f, 40f, undo, "%.0f") { layer.to = it }
                            5 -> USlider("Spacing (% of text size)", layer.to, 0f, 60f, undo, "%.0f") { layer.to = it }
                        }
                    } else if (layer.type == "shape") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in LayerGfx.SHAPE_NAMES.indices) NeoChip(LayerGfx.SHAPE_NAMES[i], layer.shape == i) {
                                undo.push()
                                layer.shape = i
                            }
                        }
                        Text("Fill colour", color = NC.sub, fontSize = 12.sp)
                        ColorRow(layer.color) {
                            undo.push()
                            layer.color = it
                        }
                        USlider("Size", layer.size, 0.05f, 1.2f, undo) { layer.size = it }
                        USlider("Outline", layer.strokeW, 0f, 0.5f, undo) { layer.strokeW = it }
                        ColorRow(layer.stroke) {
                            undo.push()
                            layer.stroke = it
                        }
                    } else if (layer.type == "image") {
                        USlider("Size", layer.size, 0.05f, 1.5f, undo) { layer.size = it }
                    } else {
                        Text("Nothing to colour on this layer.", color = NC.sub, fontSize = 12.sp)
                    }
                }
                "beats" -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeoChip(if (beatMode) "Beat mode ON: tap the ruler" else "Beat mode: tap the ruler", beatMode) { onBeatMode(!beatMode) }
                        ToolPill(Icons.Outlined.Add, "Mark at playhead") {
                            val t = project.playheadMs
                            val near = project.beats.firstOrNull { Math.abs(it - t) < 120L }
                            val l = project.beats.toMutableList()
                            if (near != null) l.remove(near) else l.add(t)
                            l.sort()
                            project.beats.clear()
                            project.beats.addAll(l)
                        }
                        ToolPill(Icons.Outlined.Delete, "Clear beats") {
                            undo.push()
                            project.beats.clear()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolPill(Icons.Outlined.AutoAwesome, "Auto-detect beats") { onAutoBeats(item) }
                        ToolPill(Icons.Outlined.ContentCut, "Split clips on beats") {
                            undo.push()
                            for (b in project.beats.sorted()) project.splitClipAt(b)
                            engine.show(project.playheadMs)
                        }
                    }
                    USlider("Pulse size", pulse, 0.02f, 0.6f, undo) { pulse = it }
                    ToolPill(Icons.Outlined.Speed, "Pulse this on every beat") {
                        undo.push()
                        val start = project.startOf(item)
                        val base = item.tr.values["scale"] ?: 1f
                        var lastOff = -10000L
                        var n = 0
                        for (b in project.beats.sorted()) {
                            val o = b - start
                            if (o < 0L || o > item.durMs || o - lastOff < 320L) continue
                            item.tr.setKey("scale", maxOf(0L, o - 80L), base)
                            item.tr.setKey("scale", o, base * (1f + pulse))
                            item.tr.setKey("scale", o + 200L, base)
                            lastOff = o
                            n++
                        }
                        onMsg(if (n == 0) "No beats inside this item. Mark beats first." else "Added a pulse on " + n + " beats")
                    }
                    Text(project.beats.size.toString() + " beats marked", color = NC.sub, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                "effects" -> {
                    ToolPill(Icons.Outlined.AutoAwesome, "Add / edit effects") { onOpenEffects() }
                    Spacer(Modifier.height(6.dp))
                    if (item.fx.isEmpty()) Text("No effects yet.", color = NC.sub, fontSize = 12.sp)
                    for (f in item.fx.toList()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.effect.name, color = Color.White, modifier = Modifier.weight(1f))
                            Text("Remove", color = NC.red, fontSize = 12.sp, modifier = Modifier.clickable {
                                undo.push()
                                item.fx.remove(f)
                            }.padding(6.dp))
                        }
                    }
                }
            }
        }
    }
}
