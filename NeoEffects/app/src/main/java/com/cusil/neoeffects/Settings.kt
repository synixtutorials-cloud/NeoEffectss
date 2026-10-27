@file:OptIn(ExperimentalMaterial3Api::class)

package com.cusil.neoeffects

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("neo_prefs", Context.MODE_PRIVATE)
    fun updateAlerts(ctx: Context): Boolean = p(ctx).getBoolean("updateAlerts", true)
    fun setUpdateAlerts(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("updateAlerts", v).apply()
    fun lastCheck(ctx: Context): Long = p(ctx).getLong("lastCheck", 0L)
    fun setLastCheck(ctx: Context, t: Long) = p(ctx).edit().putLong("lastCheck", t).apply()
}

class UpdateInfo(val version: String, val notes: String, val url: String)

object Updater {
    const val REPO = "cusilAi/Neo-Effects"

    fun currentVersion(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    private fun parts(v: String): List<Int> = v.split(".").map { s -> s.filter { it.isDigit() }.toIntOrNull() ?: 0 }

    fun newer(a: String, b: String): Boolean {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = if (i < x.size) x[i] else 0
            val q = if (i < y.size) y[i] else 0
            if (p != q) return p > q
        }
        return false
    }

    fun check(ctx: Context): Pair<UpdateInfo?, String> {
        return try {
            val conn = URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "NEOeffects")
            val code = conn.responseCode
            if (code == 404) {
                Pair(null, "No release has been published yet")
            } else if (code != 200) {
                Pair(null, "Update server error ($code)")
            } else {
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val ver = o.optString("tag_name", "").removePrefix("v")
                var url = o.optString("html_url", "")
                val assets = o.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name", "").endsWith(".apk")) {
                            url = a.optString("browser_download_url", url)
                            break
                        }
                    }
                }
                val cur = currentVersion(ctx)
                if (ver.isNotEmpty() && newer(ver, cur)) {
                    Pair(UpdateInfo(ver, o.optString("body", ""), url), "Update available")
                } else {
                    Pair(null, "You are up to date (version $cur)")
                }
            }
        } catch (e: Exception) {
            Pair(null, "Could not check for updates (no internet?)")
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NC.card,
        title = { Text("Update available: " + info.version, color = Color.White) },
        text = { Text(if (info.notes.isBlank()) "A new version of NEOeffects is ready." else info.notes.take(600), color = NC.sub) },
        confirmButton = {
            TextButton(onClick = {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                }
                onDismiss()
            }) { Text("Download", color = NC.red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later", color = NC.sub) } }
    )
}

fun packUris(ctx: Context, json: JSONObject, zos: ZipOutputStream, map: HashMap<String, String>, prefix: String) {
    fun one(o: JSONObject) {
        val uri = o.optString("uri", "")
        if (uri.isEmpty()) return
        val ex = map[uri]
        if (ex != null) {
            o.put("uri", ex)
            return
        }
        val ins0 = try { ctx.contentResolver.openInputStream(Uri.parse(uri)) } catch (e: Exception) { null }
        if (ins0 == null) return
        val ins = ins0
        val rel = prefix + "media/" + (map.size + 1) + "_" + safeName(o.optString("name", "file"))
        zos.putNextEntry(ZipEntry(rel))
        ins.use { it.copyTo(zos) }
        zos.closeEntry()
        map[uri] = rel
        o.put("uri", rel)
    }
    val cs = json.optJSONArray("clips")
    if (cs != null) for (i in 0 until cs.length()) one(cs.getJSONObject(i))
    val ls = json.optJSONArray("layers")
    if (ls != null) {
        for (i in 0 until ls.length()) {
            val lo = ls.getJSONObject(i)
            val t = lo.optString("type", "")
            if (t == "image" || t == "audio") one(lo)
        }
    }
}

fun rewriteMedia(o: JSONObject, base: File) {
    fun fix(a: JSONArray?) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            val u = x.optString("uri", "")
            if (u.startsWith("media/")) x.put("uri", Uri.fromFile(File(base, u)).toString())
        }
    }
    fix(o.optJSONArray("clips"))
    fix(o.optJSONArray("layers"))
}

fun backupTo(ctx: Context, out: OutputStream, includeMedia: Boolean): String {
    return try {
        val zos = ZipOutputStream(BufferedOutputStream(out))
        val map = HashMap<String, String>()
        var np = 0
        var ne = 0
        for (f in ProjectStore.projectsDir(ctx).listFiles() ?: emptyArray<File>()) {
            if (f.name.endsWith(".json")) {
                val o = JSONObject(f.readText())
                if (includeMedia) packUris(ctx, o, zos, map, "")
                zos.putNextEntry(ZipEntry("projects/" + f.name))
                zos.write(o.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                np++
            } else if (f.name.endsWith(".jpg")) {
                zos.putNextEntry(ZipEntry("projects/" + f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        for (f in File(ctx.filesDir, "effects").listFiles() ?: emptyArray<File>()) {
            if (f.name.endsWith(".neofx")) {
                zos.putNextEntry(ZipEntry("effects/" + f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                ne++
            }
        }
        for (f in File(ctx.filesDir, "presets").listFiles() ?: emptyArray<File>()) {
            if (f.name.endsWith(".json")) {
                zos.putNextEntry(ZipEntry("presets/" + f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        zos.putNextEntry(ZipEntry("meta.json"))
        zos.write(("{\"app\":\"NEOeffects\",\"version\":\"" + Updater.currentVersion(ctx) + "\"}").toByteArray())
        zos.closeEntry()
        zos.finish()
        zos.flush()
        "Backed up " + np + " projects and " + ne + " effects" + (if (includeMedia) " with media" else "")
    } catch (e: Exception) {
        "Backup failed: " + (e.message ?: "unknown error")
    }
}

fun restoreFrom(ctx: Context, input: InputStream): String {
    return try {
        val tmp = File(ctx.filesDir, "imports/backup_" + System.currentTimeMillis())
        tmp.mkdirs()
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory && !name.contains("..")) {
                    val f = File(tmp, name)
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        var np = 0
        var ne = 0
        val pdir = ProjectStore.projectsDir(ctx)
        for (f in File(tmp, "projects").listFiles() ?: emptyArray<File>()) {
            if (f.name.endsWith(".json")) {
                val o = JSONObject(f.readText())
                rewriteMedia(o, tmp)
                File(pdir, f.name).writeText(o.toString())
                np++
            } else if (f.name.endsWith(".jpg")) {
                f.copyTo(File(pdir, f.name), true)
            }
        }
        val edir = File(ctx.filesDir, "effects")
        edir.mkdirs()
        for (f in File(tmp, "effects").listFiles() ?: emptyArray<File>()) {
            f.copyTo(File(edir, f.name), true)
            ne++
        }
        val prdir = File(ctx.filesDir, "presets")
        prdir.mkdirs()
        for (f in File(tmp, "presets").listFiles() ?: emptyArray<File>()) f.copyTo(File(prdir, f.name), true)
        "Restored " + np + " projects and " + ne + " effects. Restart the app to see everything."
    } catch (e: Exception) {
        "Restore failed: " + (e.message ?: "not a NEOeffects backup")
    }
}

@Composable
fun SettingsRow(title: String, sub: String, onClick: (() -> Unit)? = null, right: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(NC.card).border(1.dp, NC.border, shape)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() }).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (sub.isNotEmpty()) Text(sub, color = NC.sub, fontSize = 11.sp)
        }
        right()
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onImportEffects: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf(Prefs.updateAlerts(ctx)) }
    var includeMedia by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                msg = "Creating backup..."
                msg = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { backupTo(ctx, it, includeMedia) } ?: "Could not write the file"
                    } catch (e: Exception) {
                        "Backup failed: " + (e.message ?: "")
                    }
                }
                busy = false
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                msg = "Restoring..."
                msg = withContext(Dispatchers.IO) {
                    try {
                        val ins = ctx.contentResolver.openInputStream(uri)
                        if (ins == null) "Could not read the file" else ins.use { restoreFrom(ctx, it) }
                    } catch (e: Exception) {
                        "Restore failed: " + (e.message ?: "")
                    }
                }
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRow("App version", "NEOeffects", null) { Text(Updater.currentVersion(ctx), color = NC.sub) }
            SettingsRow("Update alerts", "Tell me when a new version is released", null) {
                Switch(
                    checked = alerts,
                    onCheckedChange = {
                        alerts = it
                        Prefs.setUpdateAlerts(ctx, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = NC.red)
                )
            }
            SettingsRow("Check for updates now", "Looks for a newer release on GitHub", {
                if (!busy) {
                    scope.launch {
                        busy = true
                        msg = "Checking..."
                        val r = withContext(Dispatchers.IO) { Updater.check(ctx) }
                        busy = false
                        msg = r.second
                        update = r.first
                    }
                }
            }) { Text("›", color = Color.White, fontSize = 22.sp) }

            Spacer(Modifier.height(6.dp))
            Text("Cloud backup", color = NC.red, fontWeight = FontWeight.Bold)
            Text(
                "Saves your projects and effects into one file. When the phone asks where to save it, choose Google Drive (or any cloud app) to keep it online.",
                color = NC.sub, fontSize = 12.sp
            )
            SettingsRow("Include media files", "Bigger file, but projects open fully on a new phone", null) {
                Switch(checked = includeMedia, onCheckedChange = { includeMedia = it }, colors = SwitchDefaults.colors(checkedTrackColor = NC.red))
            }
            SettingsRow("Back up now", "Create a backup file", {
                if (!busy) backupLauncher.launch("NEOeffects-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip")
            }) { Text("›", color = Color.White, fontSize = 22.sp) }
            SettingsRow("Restore from backup", "Pick a NEOeffects backup file (from Drive or storage)", {
                if (!busy) restoreLauncher.launch(arrayOf("*/*"))
            }) { Text("›", color = Color.White, fontSize = 22.sp) }

            Spacer(Modifier.height(6.dp))
            SettingsRow("Import effects", "Add custom effects or make one with AI", { onImportEffects() }) { Text("›", color = Color.White, fontSize = 22.sp) }
            if (msg.isNotEmpty()) Text(msg, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Text("NEOeffects · Edit · Create · Inspire", color = NC.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
    val u = update
    if (u != null) UpdateDialog(u) { update = null }
}
