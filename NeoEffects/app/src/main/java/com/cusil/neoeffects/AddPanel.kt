@file:OptIn(ExperimentalMaterial3Api::class)

package com.cusil.neoeffects

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun AddTabBtn(glyph: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(70.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, fontSize = 22.sp, color = if (selected) NC.red else Color.White)
        Text(label, fontSize = 11.sp, color = if (selected) NC.red else Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun BigAction(title: String, sub: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(NC.card2).border(1.dp, if (enabled) NC.red else NC.border, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp)
    ) {
        Text(title, color = if (enabled) Color.White else NC.dim, fontWeight = FontWeight.Bold)
        Text(sub, color = NC.sub, fontSize = 11.sp)
    }
}

@Composable
fun AddPanel(
    tab: String, onTab: (String) -> Unit, onClose: () -> Unit,
    onShape: (Int) -> Unit, onPickImage: () -> Unit, onPickVideo: () -> Unit, onPickAudio: () -> Unit, onExtract: () -> Unit,
    onNull: () -> Unit, onText: () -> Unit, onDraw: () -> Unit, onApplyTemplate: (String) -> Unit,
    presets: List<String>, onApplyPreset: (String) -> Unit
) {
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Row(Modifier.fillMaxWidth().height(330.dp).clip(shape).background(NC.card).border(1.dp, NC.border, shape)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
                AddTabBtn("◇", "Shape", tab == "shape") { onTab("shape") }
                AddTabBtn("🖼", "Media", tab == "media") { onTab("media") }
                AddTabBtn("♪", "Audio", tab == "audio") { onTab("audio") }
                AddTabBtn("⬡", "Object", tab == "object") { onTab("object") }
                AddTabBtn("▤", "Template", tab == "template") { onTab("template") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                when (tab) {
                    "shape" -> LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        gridItems((0 until LayerGfx.SHAPE_NAMES.size).toList()) { k ->
                            Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(NC.card2).clickable { onShape(k) },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                                    val r = size.minDimension / 2f * 0.92f
                                    val path = LayerGfx.shapePath(k, size.width / 2f, size.height / 2f, r)
                                    if (k == 3 || k == 12) {
                                        drawPath(path, Color(0xFFC8C8D8), style = Stroke(width = r * 0.14f))
                                    } else {
                                        drawPath(path, Color(0xFFC8C8D8))
                                    }
                                }
                            }
                        }
                    }
                    "media" -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigAction("Photo / image", "Adds an image layer you can move, scale, rotate and animate") { onPickImage() }
                        BigAction("Video", "Adds a clip to Video 1 (the main track)") { onPickVideo() }
                        Text("Video overlays on Video 2 are coming in a later build.", color = NC.sub, fontSize = 11.sp)
                    }
                    "audio" -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigAction("Add audio file", "Music, voice or sound effects from your phone") { onPickAudio() }
                        BigAction("Extract audio", "Pick a video (or any file with sound) and use only its audio") { onExtract() }
                    }
                    "object" -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigAction("Null object", "Invisible layer. Set other layers' Parent to it to move them together") { onNull() }
                        BigAction("Camera", "Coming soon", false) { }
                        BigAction("Empty group", "Coming soon", false) { }
                        BigAction("Element / project", "Coming soon", false) { }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("Templates (applied to the selected clip or layer)", color = NC.sub, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            items(TEMPLATES) { t -> TemplateCard(t, Modifier.width(110.dp)) { onApplyTemplate(t.id) } }
                        }
                        Text("My presets", color = NC.sub, fontSize = 12.sp)
                        if (presets.isEmpty()) {
                            Text("Save one from the ⋯ menu > Save preset.", color = NC.dim, fontSize = 11.sp)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in presets) NeoChip(p, false) { onApplyPreset(p) }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.width(88.dp).fillMaxHeight().background(NC.card2),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AddTabBtn("✏️", "Freehand", false) { onDraw() }
            AddTabBtn("A", "Text", false) { onText() }
            AddTabBtn("✕", "Close", false) { onClose() }
        }
    }
}

@Composable
fun TextAddDialog(onDismiss: () -> Unit, onAdd: (String, Int) -> Unit) {
    var text by remember { mutableStateOf("Your text") }
    var color by remember { mutableStateOf(-1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NC.card,
        title = { Text("Add text", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 4, colors = fieldColors())
                Spacer(Modifier.height(8.dp))
                ColorRow(color) { color = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onAdd(text, color) }) { Text("Add", color = NC.red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = NC.sub) } }
    )
}

class DStroke(val color: Int, val w: Float) {
    val pts = mutableStateListOf<Offset>()
}

@Composable
fun DrawDialog(project: Project, onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<DStroke>() }
    var color by remember { mutableStateOf(-1) }
    var width by remember { mutableStateOf(10f) }
    var cw by remember { mutableStateOf(1) }
    var ch by remember { mutableStateOf(1) }
    val ca = project.width.toFloat() / project.height.toFloat()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(NC.bg).padding(8.dp)) {
            Text("Freehand drawing", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.aspectRatio(ca).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0xFF14151C))
                        .onSizeChanged {
                            cw = maxOf(1, it.width)
                            ch = maxOf(1, it.height)
                        }
                        .pointerInput(color, width) {
                            detectDragGestures(onDragStart = { pos ->
                                val s = DStroke(color, width)
                                s.pts.add(pos)
                                strokes.add(s)
                            }) { change, _ ->
                                change.consume()
                                strokes.lastOrNull()?.pts?.add(change.position)
                            }
                        }
                ) {
                    for (s in strokes) {
                        if (s.pts.size < 2) continue
                        val p = Path()
                        p.moveTo(s.pts[0].x, s.pts[0].y)
                        for (i in 1 until s.pts.size) p.lineTo(s.pts[i].x, s.pts[i].y)
                        drawPath(p, Color(s.color), style = Stroke(width = s.w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            ColorRow(color) { color = it }
            Text("Brush size " + width.toInt(), color = NC.sub, fontSize = 11.sp)
            Slider(
                value = width, onValueChange = { width = it }, valueRange = 2f..60f,
                colors = SliderDefaults.colors(thumbColor = NC.red, activeTrackColor = NC.red, inactiveTrackColor = Color(0xFF2A2C3A))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }) { Text("Undo", color = Color.White) }
                TextButton(onClick = { strokes.clear() }) { Text("Clear", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = NC.sub) }
                Button(
                    onClick = {
                        val d = LayerGfx.canvasDims(project, 1024)
                        val bmp = Bitmap.createBitmap(d[0], d[1], Bitmap.Config.ARGB_8888)
                        val c = AndroidCanvas(bmp)
                        val sc = d[0].toFloat() / cw
                        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
                        paint.style = AndroidPaint.Style.STROKE
                        paint.strokeCap = AndroidPaint.Cap.ROUND
                        paint.strokeJoin = AndroidPaint.Join.ROUND
                        for (s in strokes) {
                            if (s.pts.size < 2) continue
                            val p = AndroidPath()
                            p.moveTo(s.pts[0].x * sc, s.pts[0].y * sc)
                            for (i in 1 until s.pts.size) p.lineTo(s.pts[i].x * sc, s.pts[i].y * sc)
                            paint.color = s.color
                            paint.strokeWidth = s.w * sc
                            c.drawPath(p, paint)
                        }
                        onDone(bmp)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NC.red)
                ) { Text("Add to project", color = Color.White) }
            }
        }
    }
}
