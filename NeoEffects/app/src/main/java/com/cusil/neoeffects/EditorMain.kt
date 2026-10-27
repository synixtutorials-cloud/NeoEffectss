@file:OptIn(ExperimentalMaterial3Api::class)

package com.cusil.neoeffects

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object ItemClipboard {
    var layers: List<Layer> = emptyList()
    var style: Item? = null
}

fun pasteStyle(src: Item, dst: Item) {
    dst.fx.clear()
    for (f in src.fx) dst.fx.add(f.copyInst(0L))
    for ((k, v) in src.tr.values) dst.tr.values[k] = v
    dst.tr.keys.clear()
    for ((k, list) in src.tr.keys) dst.tr.keys[k] = list.map { Key(it.t, it.v, it.ease, it.bz) }
    dst.blend = src.blend
    dst.mesh = src.mesh
    dst.meshSpin = src.meshSpin
}

object Presets {
    private fun dir(ctx: android.content.Context): File {
        val d = File(ctx.filesDir, "presets")
        d.mkdirs()
        return d
    }

    fun list(ctx: android.content.Context): List<String> =
        (dir(ctx).listFiles() ?: emptyArray<File>()).filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.sorted()

    fun save(ctx: android.content.Context, item: Item, name: String) {
        val tmp = Project("preset", "preset")
        val l = Layer(newClipId(), "null")
        pasteStyle(item, l)
        tmp.layers.add(l)
        File(dir(ctx), safeName(name) + ".json").writeText(ProjectStore.toJson(tmp).toString())
    }

    fun apply(ctx: android.content.Context, name: String, dst: Item, lib: List<NeoEffect>): Boolean {
        return try {
            val p = ProjectStore.fromJson(JSONObject(File(dir(ctx), name + ".json").readText()), lib)
            val src = p.layers.firstOrNull()
            if (src == null) false else {
                pasteStyle(src, dst)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

fun bumpParam(inst: FxInst, id: String, off: Long, f: (Float) -> Float) {
    val p = inst.effect.params.firstOrNull { it.id == id } ?: return
    val nv = f(inst.valueAt(p, off)).coerceIn(p.min, p.max)
    val ks = inst.keys[id]
    if (ks != null && ks.isNotEmpty()) inst.setKey(id, off, nv) else inst.values[id] = nv
}

@Composable
fun ChainFeeder(project: Project, gl: GlEngine, bypassGrade: Boolean, layerMax: Int) {
    val ctx = LocalContext.current
    val ph = project.playheadMs
    val st = computeRenderState(ctx, project, ph, bypassGrade, layerMax)
    SideEffect {
        gl.setState(st)
        gl.timeSec = ph / 1000f
    }
}

@Composable
fun TransportBar(
    project: Project, engine: PlaybackEngine, undo: UndoStack, item: Item?,
    onZoom: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit,
    onKey: () -> Unit, menu: @Composable () -> Unit
) {
    val keyActive = item != null && item.tr.hasKeyAt((project.playheadMs - project.startOf(item)).coerceIn(0L, item.durMs))
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(54.dp)) {
            Text(fmtClock(project.playheadMs), color = Color.White, fontSize = 12.sp)
            Text(fmtClock(project.totalMs), color = NC.sub, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        IconBtn(Icons.Outlined.SkipPrevious, "Previous", size = 32.dp) { onPrev() }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable { if (engine.playing) engine.pause() else engine.play() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (engine.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconBtn(Icons.Outlined.SkipNext, "Next", size = 32.dp) { onNext() }
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { onKey() },
            contentAlignment = Alignment.Center
        ) { Text(if (keyActive) "◆" else "◇", color = NC.red, fontSize = 20.sp) }
        menu()
        Spacer(Modifier.weight(1f))
        IconBtn(Icons.Outlined.ZoomIn, "Timeline zoom", NC.sub, 30.dp) { onZoom() }
        IconBtn(Icons.Outlined.Undo, "Undo", if (undo.canUndo) Color.White else NC.dim, 30.dp) { onUndo() }
        IconBtn(Icons.Outlined.Redo, "Redo", if (undo.canRedo) Color.White else NC.dim, 30.dp) { onRedo() }
    }
}

@Composable
fun Ruler(project: Project, zoom: Float, onSeek: (Long, Boolean) -> Unit, pxPerMs: Float) {
    val step = if (zoom >= 100f) 1 else if (zoom >= 50f) 2 else if (zoom >= 25f) 5 else 10
    val secs = (project.totalMs / 1000L).toInt() + 12
    Box(
        Modifier.fillMaxWidth().height(20.dp)
            .drawBehind {
                for (b in project.beats) {
                    val x = 8.dp.toPx() + b * pxPerMs
                    val path = Path()
                    path.moveTo(x - 4.dp.toPx(), 0f)
                    path.lineTo(x + 4.dp.toPx(), 0f)
                    path.lineTo(x, 9.dp.toPx())
                    path.close()
                    drawPath(path, Color(0xFFFF4DA6))
                }
            }
            .pointerInput(zoom) {
                var lastT = 0L
                detectHorizontalDragGestures(
                    onDragEnd = { onSeek(lastT, false) },
                    onDragCancel = { onSeek(lastT, false) }
                ) { change, _ ->
                    change.consume()
                    lastT = ((change.position.x - 8.dp.toPx()) / pxPerMs).toLong().coerceAtLeast(0L)
                    onSeek(lastT, true)
                }
            }
    ) {
        var s = 0
        while (s <= secs) {
            val x = (8f + s * zoom).dp
            Box(Modifier.offset(x = x, y = 13.dp).width(1.dp).height(6.dp).background(NC.border))
            Text(fmtClock(s * 1000L), color = NC.sub, fontSize = 8.sp, modifier = Modifier.offset(x = x + 2.dp, y = 2.dp))
            s += step
        }
    }
}

@Composable
fun LayerBlock(l: Layer, selected: Boolean, zoom: Float) {
    val w = maxOf(24f, l.lenMs / 1000f * zoom).dp
    val col = when (l.type) {
        "text" -> Color(0xFF5A1626)
        "audio" -> Color(0xFF0E4A4A)
        "null" -> Color(0xFF3A3A48)
        else -> Color(0xFF3A1A5A)
    }
    val glyph = when (l.type) {
        "text" -> "T"
        "audio" -> "♪"
        "null" -> "◻"
        "shape" -> "◆"
        else -> "▣"
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.offset(x = (8f + l.startMs / 1000f * zoom).dp).width(w).fillMaxHeight().padding(horizontal = 0.5.dp)
            .clip(shape).background(col)
            .border(if (selected) 2.dp else 1.dp, if (selected) NC.redGlow else Color(0x66FFFFFF), shape)
    ) {
        Text(glyph + " " + (if (l.type == "text") l.text else l.name), color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(3.dp))
    }
}

@Composable
fun TimelineBlock(
    project: Project, selClip: Long, selLayers: Set<Long>, zoom: Float, playing: Boolean, hidden: Set<Int>, compact: Boolean,
    onSeek: (Long, Boolean) -> Unit, onTap: (Int, Long) -> Unit, onEye: (Int) -> Unit, onPlus: (Int) -> Unit
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val pxPerMs = with(density) { (zoom / 1000f).dp.toPx() }
    val names = listOf("Video 1", "Video 2", "Overlay", "Text", "Audio")
    val icons = listOf(Icons.Outlined.Movie, Icons.Outlined.VideoLibrary, Icons.Outlined.Layers, Icons.Outlined.TextFields, Icons.Outlined.MusicNote)
    val tracks = if (compact) 1 else 5
    val contentW = ((project.totalMs / 1000f) * zoom + 200f).dp
    val totalH = (20 + tracks * 38).dp
    var viewW by remember { mutableStateOf(0) }

    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.width(80.dp).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.height(20.dp))
            for (i in 0 until tracks) {
                TrackLabel(names[i], icons[i], hidden.contains(i), i != 0) { onEye(i) }
            }
        }
        Box(Modifier.weight(1f).height(totalH).onSizeChanged { viewW = it.width }.horizontalScroll(scroll)) {
            Box(
                Modifier.width(contentW).height(totalH).pointerInput(project.clips.size, project.layers.size, zoom, tracks) {
                    detectTapGestures { off ->
                        val t = ((off.x - 8.dp.toPx()) / pxPerMs).toLong().coerceAtLeast(0L)
                        if (off.y < 24.dp.toPx()) {
                            onTap(-1, t)
                        } else {
                            onTap(((off.y - 24.dp.toPx()) / 38.dp.toPx()).toInt().coerceIn(0, tracks - 1), t)
                        }
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Ruler(project, zoom, onSeek, pxPerMs)
                    Row(Modifier.height(34.dp)) {
                        Spacer(Modifier.width(8.dp))
                        for (c in project.clips) ClipBlock(c, c.id == selClip, zoom)
                    }
                    for (i in 1 until tracks) {
                        Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0C12))) {
                            for (l in project.layers) {
                                if (l.track == i) LayerBlock(l, selLayers.contains(l.id), zoom)
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .offset { IntOffset((8.dp.toPx() + project.playheadMs * pxPerMs).toInt() - 1, 0) }
                        .width(2.dp).height(totalH)
                        .drawBehind {
                            drawLine(NC.redGlow, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = size.width)
                            drawCircle(NC.redGlow, radius = 5.dp.toPx(), center = Offset(size.width / 2f, 6.dp.toPx()))
                        }
                )
            }
        }
        Column(Modifier.width(38.dp).padding(start = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(Modifier.height(20.dp))
            for (i in 0 until tracks) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(NC.card2)
                        .border(1.dp, NC.border, RoundedCornerShape(8.dp)).clickable { onPlus(i) },
                    contentAlignment = Alignment.Center
                ) { Text("+", color = Color.White, fontSize = 18.sp) }
            }
        }
    }
    FollowPlayhead(project, scroll, pxPerMs, viewW, playing)
}

@Composable
fun ExportOverlay(progress: Float, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(32.dp).clip(RoundedCornerShape(18.dp)).background(NC.card)
                .border(1.dp, NC.red, RoundedCornerShape(18.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Exporting video", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text((progress * 100).toInt().toString() + "%", color = NC.red, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, color = NC.red, trackColor = Color(0xFF2A2C3A), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Keep the app open. Long videos take a few minutes.", color = NC.sub, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onCancel) { Text("Cancel", color = NC.red) }
        }
    }
}

@Composable
fun EditorScreen(
    project: Project,
    library: List<NeoEffect>,
    startTab: String,
    onExit: () -> Unit,
    onPro: () -> Unit,
    onImportEffects: () -> Unit
) {
    val ctx = LocalContext.current
    val gl = remember { GlEngine() }
    val engine = remember { PlaybackEngine(ctx, project, gl) }
    val undo = remember { UndoStack(project, library) }
    val scope = rememberCoroutineScope()
    var selClipId by remember { mutableStateOf(project.clips.firstOrNull()?.id ?: -1L) }
    var selLayerIds by remember { mutableStateOf(setOf<Long>()) }
    var primaryLayer by remember { mutableStateOf(0L) }
    var tab by remember { mutableStateOf(startTab) }
    var inspect by remember { mutableStateOf(false) }
    var insTab by remember { mutableStateOf("transform") }
    var addTab by remember { mutableStateOf("") }
    var beatMode by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf<Layer?>(null) }
    var showDraw by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var shaderErr by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(40f) }
    var full by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var layerMenu by remember { mutableStateOf(false) }
    var hiddenTracks by remember { mutableStateOf(setOf<Int>()) }
    var activeFx by remember { mutableStateOf("") }
    var bypass by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var expProgress by remember { mutableStateOf(0f) }
    var presetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var presetList by remember { mutableStateOf(Presets.list(ctx)) }
    var lastPush by remember { mutableStateOf(0L) }
    val cancelFlag = remember { AtomicBoolean(false) }

    remember {
        gl.onSurface = { s -> engine.onSurface(s) }
        gl.onError = { m -> shaderErr = m }
        0
    }

    SideEffect {
        gl.canvasAspect = project.width.toFloat() / project.height.toFloat()
        gl.bgMode = project.bgMode
        gl.maxSide = when (project.previewQ) {
            0 -> 720
            2 -> 1920
            else -> 1280
        }
        gl.hidden = hiddenTracks.contains(0)
    }
    LaunchedEffect(project.bgUri, project.bgMode) {
        if (project.bgMode == 4 && project.bgUri.isNotEmpty()) {
            val b = withContext(Dispatchers.IO) {
                try { loadBitmap(ctx, Uri.parse(project.bgUri), 1024) } catch (e: Exception) { null }
            }
            gl.setBgBitmap(b)
        } else {
            gl.setBgBitmap(null)
        }
    }
    LaunchedEffect(project.autosave) {
        while (project.autosave) {
            delay(30000L)
            try { ProjectStore.save(ctx, project) } catch (e: Exception) { }
        }
    }

    BackHandler {
        if (exporting) {
            cancelFlag.set(true)
        } else if (addTab.isNotEmpty()) {
            addTab = ""
        } else if (inspect) {
            inspect = false
        } else if (tab == "effects" || tab == "color") {
            tab = "edit"
        } else {
            onExit()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) engine.pause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { ProjectStore.save(ctx, project) } catch (e: Exception) { }
            engine.releaseAll()
        }
    }
    LaunchedEffect(Unit) { engine.show(project.playheadMs) }
    LaunchedEffect(engine.playing) {
        if (engine.playing) {
            var last = withFrameNanos { it }
            while (engine.playing) {
                val now = withFrameNanos { it }
                engine.tick((now - last) / 1_000_000L)
                last = now
            }
        }
    }

    val selLayer = project.layers.firstOrNull { it.id == primaryLayer }
    val selClip = project.clips.firstOrNull { it.id == selClipId }
    val selItem: Item? = selLayer ?: selClip
    val sheet = tab == "effects" || tab == "color"

    fun selectClip(c: Clip) {
        selClipId = c.id
        primaryLayer = 0L
        selLayerIds = emptySet()
    }
    fun selectLayer(l: Layer) {
        primaryLayer = l.id
        selLayerIds = setOf(l.id)
        selClipId = -1L
    }
    fun clearSel() {
        selClipId = -1L
        primaryLayer = 0L
        selLayerIds = emptySet()
        inspect = false
    }
    fun relStart(it0: Item): Long = (project.playheadMs - project.startOf(it0)).coerceIn(0L, it0.durMs)

    fun addToMain(uris: List<Uri>) {
        undo.push()
        var added = false
        for (u in uris) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            val pr = probeMedia(ctx, u)
            if (pr == null) {
                msg = "Could not read that file"
                continue
            }
            val c = Clip(newClipId(), u.toString(), pr.isVideo, pr.durMs, pr.name)
            if (!pr.isVideo) c.outMs = 3000L
            applyTemplate(project.templateId, c, library)
            project.clips.add(c)
            selectClip(c)
            added = true
        }
        if (added) engine.show(project.playheadMs)
    }
    fun newLayer(type: String, track: Int, len: Long): Layer {
        val l = Layer(newClipId(), type)
        l.track = track
        l.startMs = project.playheadMs
        l.lenMs = len
        return l
    }
    fun addLayer(l: Layer, tabAfter: String) {
        undo.push()
        project.layers.add(l)
        selectLayer(l)
        inspect = true
        insTab = tabAfter
        addTab = ""
        engine.show(project.playheadMs)
    }
    fun addImageLayer(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val l = newLayer("image", 1, 3000L)
        l.uri = uri.toString()
        l.size = 0.9f
        l.name = "Image"
        addLayer(l, "transform")
    }
    fun addAudio(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val dur = audioDurationMs(ctx, uri)
        if (dur <= 0L) {
            msg = "Could not read that audio file"
            return
        }
        val l = newLayer("audio", 4, dur.coerceIn(500L, 900000L))
        l.uri = uri.toString()
        l.name = "Audio"
        addLayer(l, "opacity")
    }
    fun extractFrom(uri: Uri) {
        scope.launch {
            busy = "Extracting audio..."
            val out = File(ctx.filesDir, "audio_" + System.currentTimeMillis() + ".m4a")
            val ok = withContext(Dispatchers.IO) { extractAudio(ctx, uri, out) }
            busy = ""
            if (!ok) {
                msg = "That file has no audio track (or its audio format cannot be extracted)."
                out.delete()
            } else {
                addAudio(Uri.fromFile(out))
                msg = "Audio extracted and added"
            }
        }
    }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addToMain(uris) }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) addImageLayer(uri) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) addAudio(uri) }
    val extractLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) extractFrom(uri) }

    fun doSplit() {
        undo.push()
        if (project.splitAtPlayhead()) engine.show(project.playheadMs) else msg = "Move the playhead inside a clip first"
    }
    fun doDuplicate() {
        val s = selItem ?: return
        undo.push()
        if (s is Clip) {
            val n = s.duplicate(newClipId(), 0L)
            project.clips.add(project.clips.indexOf(s) + 1, n)
            selectClip(n)
        } else if (s is Layer) {
            val ids = ArrayList<Long>()
            for (l in project.layers.filter { selLayerIds.contains(it.id) }) {
                val n = l.duplicate(newClipId(), 300L)
                project.layers.add(n)
                ids.add(n.id)
            }
            if (ids.isNotEmpty()) {
                primaryLayer = ids.last()
                selLayerIds = ids.toSet()
            }
        }
        engine.show(project.playheadMs)
    }
    fun doDelete() {
        val s = selItem ?: return
        undo.push()
        if (s is Clip) {
            project.clips.remove(s)
        } else {
            val del = project.layers.filter { selLayerIds.contains(it.id) }
            project.layers.removeAll(del)
        }
        clearSel()
        engine.show(project.playheadMs)
    }
    fun doUndo() {
        if (undo.undo()) engine.show(project.playheadMs)
    }
    fun doRedo() {
        if (undo.redo()) engine.show(project.playheadMs)
    }
    fun jump(dir: Int) {
        engine.pause()
        var t = 0L
        val starts = ArrayList<Long>()
        for (c in project.clips) {
            starts.add(t)
            t += c.durMs
        }
        starts.add(t)
        val ph = project.playheadMs
        val target = if (dir > 0) (starts.firstOrNull { it > ph + 50L } ?: t) else (starts.lastOrNull { it < ph - 50L } ?: 0L)
        engine.show(target)
    }
    fun toggleKeys() {
        val s = selItem
        if (s == null) {
            msg = "Select a clip or layer first"
            return
        }
        val off = relStart(s)
        undo.push()
        val has = s.tr.hasKeyAt(off)
        for (p in TRANSFORM_FX.params) {
            val v = s.tr.valueAt(p, off)
            if (has) {
                s.tr.values[p.id] = v
                s.tr.removeKey(p.id, off)
            } else {
                s.tr.setKey(p.id, off, v)
            }
        }
        msg = if (has) "Keyframes removed" else "Keyframes added at this time"
    }
    fun toggleBeat(t: Long) {
        val near = project.beats.firstOrNull { Math.abs(it - t) < 120L }
        val l = project.beats.toMutableList()
        if (near != null) l.remove(near) else l.add(t)
        l.sort()
        project.beats.clear()
        project.beats.addAll(l)
    }
    fun autoBeats(itm: Item) {
        var uri = ""
        var base = 0L
        var inMs = 0L
        var speed = 1f
        if (itm is Layer && itm.type == "audio") {
            uri = itm.uri
            base = itm.startMs
        } else if (itm is Clip && itm.isVideo) {
            uri = itm.uri
            base = project.startOf(itm)
            inMs = itm.inMs
            speed = itm.speed
        } else {
            msg = "Select an audio layer or a video clip first"
            return
        }
        scope.launch {
            busy = "Finding beats..."
            val bs = withContext(Dispatchers.IO) { detectBeats(ctx, Uri.parse(uri)) }
            busy = ""
            undo.push()
            val all = project.beats.toMutableList()
            var n = 0
            for (b in bs) {
                val t = base + ((b - inMs) / speed).toLong()
                if (t >= base && t <= base + itm.durMs) {
                    all.add(t)
                    n++
                }
            }
            all.sort()
            project.beats.clear()
            project.beats.addAll(all.distinct())
            msg = if (n == 0) "No clear beats found in this audio" else "Marked " + n + " beats"
        }
    }
    fun selectAll() {
        val ids = project.layers.map { it.id }
        if (ids.isEmpty()) {
            msg = "There are no layers yet"
            return
        }
        selLayerIds = ids.toSet()
        primaryLayer = ids.last()
        selClipId = -1L
        inspect = false
    }
    fun copyOp() {
        val s = selItem ?: return
        ItemClipboard.style = s
        ItemClipboard.layers = project.layers.filter { selLayerIds.contains(it.id) }.map { it.duplicate(newClipId(), 0L) }
        msg = "Copied"
    }
    fun pasteLayerOp() {
        val src = ItemClipboard.layers
        if (src.isEmpty()) {
            msg = "Copy a layer first"
            return
        }
        undo.push()
        val minStart = src.minOf { it.startMs }
        val ids = ArrayList<Long>()
        for (l in src) {
            val n = l.duplicate(newClipId(), 0L)
            n.startMs = project.playheadMs + (l.startMs - minStart)
            project.layers.add(n)
            ids.add(n.id)
        }
        primaryLayer = ids.last()
        selLayerIds = ids.toSet()
        selClipId = -1L
    }
    fun pasteStyleOp() {
        val src = ItemClipboard.style
        val dst = selItem
        if (src == null || dst == null) {
            msg = "Copy a layer or clip first"
            return
        }
        undo.push()
        pasteStyle(src, dst)
        msg = "Style pasted"
    }
    fun startExport() {
        if (exporting) return
        if (project.totalMs <= 0L) {
            msg = "Add a clip or layer first"
            return
        }
        engine.pause()
        try { ProjectStore.save(ctx, project) } catch (e: Exception) { }
        exporting = true
        expProgress = 0f
        cancelFlag.set(false)
        msg = ""
        scope.launch {
            val tmp = File(ctx.cacheDir, "export_" + System.currentTimeMillis() + ".mp4")
            val res = withContext(Dispatchers.Default) {
                Exporter.run(ctx, project, tmp, 0, { p -> expProgress = p }, cancelFlag)
            }
            if (res.isEmpty()) {
                msg = withContext(Dispatchers.IO) { saveVideoToGallery(ctx, tmp, safeName(project.name) + "_" + System.currentTimeMillis()) }
            } else {
                msg = res
            }
            tmp.delete()
            exporting = false
            engine.show(project.playheadMs)
        }
    }
    fun shareProject() {
        scope.launch {
            busy = "Packing project..."
            val r = withContext(Dispatchers.IO) { exportNeoproj(ctx, project) }
            busy = ""
            msg = r
        }
    }
    fun onTapTimeline(track: Int, t: Long) {
        engine.pause()
        if (track < 0) {
            if (beatMode) toggleBeat(t) else engine.show(minOf(t, project.totalMs))
            return
        }
        if (track == 0) {
            if (t < project.clipsMs) {
                val c = project.clipAt(t)?.first
                if (c != null) {
                    selectClip(c)
                    inspect = true
                }
            } else {
                clearSel()
            }
        } else {
            val l = project.layers.lastOrNull { it.track == track && t >= it.startMs && t < it.startMs + it.lenMs }
            if (l != null) {
                selectLayer(l)
                inspect = true
            } else {
                clearSel()
            }
        }
        engine.show(minOf(t, project.totalMs))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(NC.bg)) {
            if (!full) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(Icons.Outlined.ArrowBack, "Back", size = 36.dp) { onExit() }
                    NeoLogo(small = true)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 100.dp)) {
                        Text(project.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(project.resLabel + " · " + project.fps + "fps", color = NC.sub, fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    ProChip(onPro)
                    Button(
                        onClick = { startExport() },
                        colors = ButtonDefaults.buttonColors(containerColor = NC.red),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text("Export", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Box {
                        IconBtn(Icons.Outlined.MoreVert, "Menu", size = 34.dp) { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Save project") }, onClick = {
                                menuOpen = false
                                ProjectStore.save(ctx, project)
                                msg = "Saved"
                            })
                            DropdownMenuItem(text = { Text("Share project file (.neoproj)") }, onClick = {
                                menuOpen = false
                                shareProject()
                            })
                            DropdownMenuItem(text = { Text("Fullscreen preview") }, onClick = {
                                menuOpen = false
                                full = true
                            })
                            DropdownMenuItem(text = { Text("Import effects") }, onClick = {
                                menuOpen = false
                                onImportEffects()
                            })
                        }
                    }
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = 150.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(6.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
                    GlPreview(gl, Modifier.fillMaxSize())
                    ChainFeeder(project, gl, bypass, 1024)
                    Text(
                        project.resLabel + "  ·  " + project.fps + "fps", color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconBtn(if (full) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Fullscreen", size = 34.dp) { full = !full }
                    }
                    if (project.clips.isEmpty() && project.layers.isEmpty()) {
                        Text("Tap  +  to add media", color = NC.sub, modifier = Modifier.align(Alignment.Center))
                    }
                    if (busy.isNotEmpty()) {
                        Text(busy, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000)).padding(10.dp))
                    }
                    if (engine.error.isNotEmpty()) {
                        Text(engine.error, color = Color(0xFFFF6B6B), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                    }
                    if (inspect && insTab == "transform" && selItem != null && !full) {
                        Box(
                            Modifier.matchParentSize().pointerInput(selItem.id) {
                                detectTransformGestures { _, pan, zoomF, rot ->
                                    val itm = selItem
                                    val now = System.currentTimeMillis()
                                    if (now - lastPush > 700L) undo.push()
                                    lastPush = now
                                    val off = relStart(itm)
                                    val ca = project.width.toFloat() / project.height.toFloat()
                                    val bw = size.width.toFloat()
                                    val bh = size.height.toFloat()
                                    var rw = bw
                                    var rh = bh
                                    if (ca > bw / bh) rh = bw / ca else rw = bh * ca
                                    bumpParam(itm.tr, "posx", off) { v -> v + pan.x / (rw / 2f) }
                                    bumpParam(itm.tr, "posy", off) { v -> v - pan.y / (rh / 2f) }
                                    if (zoomF != 1f) bumpParam(itm.tr, "scale", off) { v -> v * zoomF }
                                    if (rot != 0f) bumpParam(itm.tr, "rotz", off) { v -> v + rot }
                                }
                            }
                        )
                    }
                }
                if (!full) {
                    ToolRail(tab) { k ->
                        if (k == "ai") {
                            msg = "AI Enhance: open Home > Quality Boost"
                        } else if ((k == "effects") && selItem == null) {
                            msg = "Tap a clip or layer in the timeline first"
                        } else {
                            tab = if (tab == k) "edit" else k
                        }
                    }
                }
            }

            TransportBar(
                project, engine, undo, selItem,
                onZoom = { zoom = if (zoom >= 120f) 20f else zoom * 2f },
                onPrev = { jump(-1) }, onNext = { jump(1) },
                onUndo = { doUndo() }, onRedo = { doRedo() },
                onKey = { toggleKeys() },
                menu = {
                    Box {
                        IconBtn(Icons.Outlined.MoreHoriz, "Layer menu", Color.White, 32.dp) { layerMenu = true }
                        DropdownMenu(expanded = layerMenu, onDismissRequest = { layerMenu = false }) {
                            DropdownMenuItem(text = { Text("Select all layers") }, onClick = {
                                layerMenu = false
                                selectAll()
                            })
                            if (selItem != null) {
                                DropdownMenuItem(text = { Text("Duplicate layer") }, onClick = {
                                    layerMenu = false
                                    doDuplicate()
                                })
                                DropdownMenuItem(text = { Text("Copy layer") }, onClick = {
                                    layerMenu = false
                                    copyOp()
                                })
                                DropdownMenuItem(text = { Text("Paste layer") }, onClick = {
                                    layerMenu = false
                                    pasteLayerOp()
                                })
                                DropdownMenuItem(text = { Text("Paste style") }, onClick = {
                                    layerMenu = false
                                    pasteStyleOp()
                                })
                                DropdownMenuItem(text = { Text("Save preset") }, onClick = {
                                    layerMenu = false
                                    presetName = "Preset " + (presetList.size + 1)
                                    presetDialog = true
                                })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                    layerMenu = false
                                    doDelete()
                                })
                            }
                        }
                    }
                }
            )

            if (!full) {
                if (shaderErr.isNotEmpty()) {
                    Text("Shader error: " + shaderErr, color = Color(0xFFFF6B6B), fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 12.dp))
                }
                TimelineBlock(
                    project, selClipId, selLayerIds, zoom, engine.playing, hiddenTracks, sheet,
                    onSeek = { t, fast ->
                        engine.pause()
                        engine.show(minOf(t, project.totalMs), fast)
                    },
                    onTap = { track, t -> onTapTimeline(track, t) },
                    onEye = { i -> hiddenTracks = if (hiddenTracks.contains(i)) hiddenTracks - i else hiddenTracks + i },
                    onPlus = { i ->
                        when (i) {
                            0 -> mediaLauncher.launch(arrayOf("video/*", "image/*"))
                            1 -> addTab = "media"
                            2 -> addTab = "shape"
                            3 -> textDialog = newLayer("text", 3, 3000L)
                            else -> addTab = "audio"
                        }
                    }
                )

                if (sheet) {
                    Box(Modifier.weight(1.45f).fillMaxWidth()) {
                        if (tab == "effects") {
                            EffectsSheet(selItem, library, project, undo, activeFx, { activeFx = it }, { tab = "edit" })
                        } else {
                            ColorGradePanel(selItem, library, undo, bypass, { bypass = it }, { tab = "edit" })
                        }
                    }
                } else {
                    BottomTabs(tab) { k ->
                        when (k) {
                            "audio" -> addTab = "audio"
                            "text" -> textDialog = newLayer("text", 3, 3000L)
                            "overlay" -> addTab = "shape"
                            "transitions" -> msg = "Tap Effects > Wipe for wipe and dissolve transitions"
                            "effects" -> if (selItem == null) msg = "Tap a clip or layer in the timeline first" else tab = "effects"
                            else -> tab = k
                        }
                    }
                    val insItem = selItem
                    Box(Modifier.fillMaxWidth().height(if (inspect && insItem != null) 250.dp else 176.dp)) {
                        if (inspect && insItem != null) {
                            InspectorPanel(
                                project, insItem, library, undo, engine, insTab, { insTab = it },
                                beatMode, { beatMode = it }, { tab = "effects" }, { inspect = false },
                                { msg = it }, { autoBeats(it) }
                            )
                        } else {
                            when (tab) {
                                "edit" -> EditPanel(
                                    project, selClipId, selClip != null,
                                    onSelect = { c ->
                                        selectClip(c)
                                        engine.pause()
                                        engine.show(project.startOf(c) + 1L)
                                    },
                                    onAdd = { mediaLauncher.launch(arrayOf("video/*", "image/*")) },
                                    onSplit = { doSplit() }, onDup = { doDuplicate() }, onDelete = { doDelete() },
                                    onSpeed = { if (selClip != null) inspect = true; insTab = "trim" }
                                )
                                "filters" -> FiltersPanel(selItem, library, project, undo)
                                "adjust" -> AdjustPanel(selItem, library, project, undo)
                                "speed" -> {
                                    if (selClip == null) {
                                        Text("Select a clip first.", color = NC.sub, modifier = Modifier.padding(16.dp))
                                    } else {
                                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                                            TrimPanel(selClip, project, engine, undo)
                                        }
                                    }
                                }
                                "more" -> Row(
                                    Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ToolPill(Icons.Outlined.MoreHoriz, "Share project file") { shareProject() }
                                    ToolPill(Icons.Outlined.Fullscreen, "Fullscreen") { full = true }
                                }
                                else -> Text("Pick a clip or layer in the timeline to edit it.", color = NC.sub, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
                if (msg.isNotEmpty() && !sheet) {
                    Text(msg, color = NC.sub, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }
        }

        if (addTab.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                AddPanel(
                    tab = addTab, onTab = { addTab = it }, onClose = { addTab = "" },
                    onShape = { kind ->
                        val l = newLayer("shape", 2, 3000L)
                        l.shape = kind
                        l.size = 0.4f
                        l.color = 0xFFE60A35.toInt()
                        l.name = LayerGfx.SHAPE_NAMES[kind]
                        addLayer(l, "fill")
                    },
                    onPickImage = { imageLauncher.launch(arrayOf("image/*")) },
                    onPickVideo = { mediaLauncher.launch(arrayOf("video/*")) },
                    onPickAudio = { audioLauncher.launch(arrayOf("audio/*", "video/*")) },
                    onExtract = { extractLauncher.launch(arrayOf("video/*", "image/*", "audio/*")) },
                    onNull = {
                        val l = newLayer("null", 2, 3000L)
                        l.name = "Null"
                        addLayer(l, "transform")
                    },
                    onText = {
                        addTab = ""
                        textDialog = newLayer("text", 3, 3000L)
                    },
                    onDraw = {
                        addTab = ""
                        showDraw = true
                    },
                    onApplyTemplate = { id ->
                        val s = selItem
                        if (s == null) {
                            msg = "Select a clip or layer first, then pick a template"
                        } else {
                            undo.push()
                            applyTemplate(id, s, library)
                            msg = "Template applied"
                            addTab = ""
                        }
                    },
                    presets = presetList,
                    onApplyPreset = { name ->
                        val s = selItem
                        if (s == null) {
                            msg = "Select a clip or layer first"
                        } else {
                            undo.push()
                            msg = if (Presets.apply(ctx, name, s, library)) "Preset applied" else "Could not load that preset"
                            addTab = ""
                        }
                    }
                )
            }
        }

        val td = textDialog
        if (td != null) {
            TextAddDialog(
                onDismiss = { textDialog = null },
                onAdd = { text, color ->
                    td.text = text
                    td.color = color
                    td.size = 0.1f
                    td.name = "Text"
                    textDialog = null
                    addLayer(td, "fill")
                }
            )
        }
        if (showDraw) {
            DrawDialog(
                project = project,
                onDismiss = { showDraw = false },
                onDone = { bmp ->
                    showDraw = false
                    val f = File(ctx.filesDir, "drawing_" + System.currentTimeMillis() + ".png")
                    try {
                        java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        val l = newLayer("image", 2, 3000L)
                        l.uri = Uri.fromFile(f).toString()
                        l.size = 1f
                        l.name = "Drawing"
                        addLayer(l, "transform")
                    } catch (e: Exception) {
                        msg = "Could not save the drawing"
                    }
                }
            )
        }
        if (presetDialog) {
            AlertDialog(
                onDismissRequest = { presetDialog = false },
                containerColor = NC.card,
                title = { Text("Save preset", color = Color.White) },
                text = { OutlinedTextField(value = presetName, onValueChange = { presetName = it }, singleLine = true, colors = fieldColors()) },
                confirmButton = {
                    TextButton(onClick = {
                        val s = selItem
                        if (s != null && presetName.isNotBlank()) {
                            Presets.save(ctx, s, presetName.trim())
                            presetList = Presets.list(ctx)
                            msg = "Preset saved. Find it in Add > Template"
                        }
                        presetDialog = false
                    }) { Text("Save", color = NC.red) }
                },
                dismissButton = { TextButton(onClick = { presetDialog = false }) { Text("Cancel", color = NC.sub) } }
            )
        }
        if (exporting) ExportOverlay(expProgress) { cancelFlag.set(true) }
    }
}
