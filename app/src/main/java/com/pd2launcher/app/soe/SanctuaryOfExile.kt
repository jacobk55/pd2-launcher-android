package com.pd2launcher.app.soe

import android.content.Context
import android.util.Log
import com.pd2launcher.app.DeviceCheck
import com.pd2launcher.app.GameFilesProvider
import com.pd2launcher.app.SessionPaths
import com.pd2launcher.app.SessionService
import com.pd2launcher.app.SessionStatus
import com.pd2launcher.app.control.Command
import com.pd2launcher.app.control.Game
import com.pd2launcher.app.control.Installation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Project Diablo 2: Sanctuary of Exile on top of the installed Project Diablo 2, the way the mod's
 * own Windows launcher does it: its release files replace Project Diablo 2's in `ProjectD2`, and
 * Project Diablo 2's updates are left alone while the mod is in place.
 *
 * The engine checks `ProjectD2` against its record of Project Diablo 2's release
 * (`ProjectD2/.pd2launcher/accepted-manifest.json`, an MD5 per file) and offers only Check files
 * when a file differs. So the install writes the mod's MD5s into that record for the files it
 * replaces; the engine then audits the installation as Ready and plays it. The files it replaced
 * and the record as it was are kept in the app's storage, and Restore puts them back.
 *
 * Every change waits until no engine job, game or official PD2 launcher runs, and ends with a new
 * audit (Check again).
 */
object SanctuaryOfExile {
    private const val TAG = "PD2Soe"
    private const val SOE_REPO = "Lukaszpg/PD2-Sanctuary-of-Exile"
    private const val SPP_REPO = "Lukaszpg/PD2-Single-Player-Plus-mod"
    private const val FILTERS = "https://raw.githubusercontent.com/Maaaaaarrk/HiimFilter-PD2-Filter/main/filtergroups/"
    private const val ROOFOO = "https://raw.githubusercontent.com/RoofooEvazan/Roofoo-s-PD2-Loot-Filter/main/Roofoo.filter"
    private const val MANIFEST = ".pd2launcher/accepted-manifest.json"
    private const val LOOT_FILTER = "loot.filter"

    /**
     * What goes on top of Project Diablo 2: Sanctuary of Exile (Standard or Damnation) or Single
     * Player+, each from its own repository's newest release, with the loot filter at
     * [filterUrl]: HiimFilter's for Sanctuary of Exile, which knows the mod's items, and Roofoo's (a Season 14 fork of Kryszard's)
     * loot filter for Single Player+.
     */
    enum class Mode(val label: String, val title: String, val repo: String, val filter: String, val filterUrl: String) {
        STANDARD("Standard", "Sanctuary of Exile", SOE_REPO, "Sancofexile.filter", FILTERS + "sancofexile/Sancofexile.filter"),
        DAMNATION("Damnation", "Sanctuary of Exile", SOE_REPO, "Sancofexile_Damnation.filter", FILTERS + "sancofexile/Sancofexile_Damnation.filter"),
        SINGLE_PLAYER_PLUS("Single Player+", "Single Player+", SPP_REPO, "Roofoo.filter", ROOFOO);

        val releaseApi: String get() = "https://api.github.com/repos/$repo/releases/latest"
        val isSoe: Boolean get() = repo == SOE_REPO
    }

    /** One file the mod puts into `ProjectD2`: [target] there, from the release asset named one of [assets]. */
    private data class Part(val target: String, val assets: List<String>, val required: Boolean = true)

    private fun parts(mode: Mode): List<Part> = if (mode == Mode.SINGLE_PLAYER_PLUS) listOf(
        // Its release carries the patched game DLL and the data; no maps or assets of its own.
        Part("ProjectDiablo.dll", listOf("ProjectDiablo.dll")),
        Part("pd2data.mpq", listOf("pd2data.mpq")),
        Part("BH.dll", listOf("BH.dll")),
    ) else listOf(
        Part("ProjectDiablo.dll", if (mode == Mode.DAMNATION) listOf("ProjectDiablo-Damnation.dll") else listOf("ProjectDiablo.dll")),
        Part("pd2data.mpq", if (mode == Mode.DAMNATION) listOf("pd2data-Damnation.mpq") else listOf("pd2data.mpq")),
        Part("BH.dll", listOf("BH.dll")),
        Part("pd2maps.mpq", listOf("pd2maps.mpq")),
        Part("pd2assets.mpq", listOf("pd2assets.mpq")),
        // The release carries the mod's D2Game.dll beside the others; older releases did not.
        Part("D2Game.dll", listOf("D2Game.dll"), required = false),
    )

    /** What is in place: [version] (the release's tag) in [mode], with the loot filter when [filter]. */
    data class Installed(val version: String, val mode: Mode, val filter: Boolean)

    sealed interface Work {
        data object Idle : Work
        data class Running(val step: String, val percent: Int? = null) : Work
        data class Done(val message: String) : Work
        data class Failed(val message: String) : Work
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _work = MutableStateFlow<Work>(Work.Idle)
    val work: StateFlow<Work> = _work.asStateFlow()

    /** The mod in place now, null when it is not (never installed, restored, or replaced by Check files). */
    private val _installed = MutableStateFlow<Installed?>(null)
    val installed: StateFlow<Installed?> = _installed.asStateFlow()

    private val _latest = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The newest release's tag of each mod's repository, once [checkLatest] read it. */
    val latest: StateFlow<Map<String, String>> = _latest.asStateFlow()

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // Again whenever the engine's audit changes: a Check files may have replaced the mod.
        scope.launch {
            SessionService.status
                .map { it.snapshot?.let { s -> Triple(s.gameFolder?.path, s.installation, s.activeJob) } }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /** [refresh] off the main thread, for screens. */
    fun refreshSoon() {
        scope.launch { refresh() }
    }

    /** The mod is in place: Play offers no Project Diablo 2 update, which would replace it. */
    fun active(): Boolean = _installed.value != null

    private fun root(context: Context) = File(context.filesDir, "soe")
    private fun stateFile(context: Context) = File(root(context), "state.json")
    private fun backupDir(context: Context) = File(root(context), "backup")
    private fun stagingDir(context: Context) = File(root(context), "staging")

    private fun projectD2(context: Context): File? {
        val paths = SessionPaths(DeviceCheck.dataDir(context))
        val game = GameFilesProvider.gameFolder(paths, SessionService.status.value.snapshot?.gameFolder?.path) ?: return null
        return File(game, "ProjectD2").takeIf { it.isDirectory }
    }

    /** Reads the record of what was installed and whether it is still in place. */
    @Synchronized
    fun refresh() {
        val context = appContext ?: return
        _installed.value = try {
            readState(context)?.let { state ->
                val project = projectD2(context) ?: return@let null
                val manifest = File(project, MANIFEST)
                if (manifest.isFile && sha256(manifest) == state.getString("manifest_sha256")) {
                    Installed(state.getString("version"), Mode.valueOf(state.getString("mode")), state.optBoolean("filter"))
                } else {
                    null
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "state unreadable: $error")
            null
        }
    }

    fun checkLatest(mode: Mode) {
        scope.launch {
            try {
                val tag = JSONObject(httpText(mode.releaseApi)).getString("tag_name")
                _latest.value = _latest.value + (mode.repo to tag)
            } catch (error: Exception) {
                Log.w(TAG, "latest release unreadable: $error")
            }
        }
    }

    fun dismiss() {
        if (_work.value !is Work.Running) _work.value = Work.Idle
    }

    /** Why the game files cannot change now, or null. */
    private fun blocked(status: SessionStatus, needReady: Boolean): String? {
        val snapshot = status.snapshot
        return when {
            status.engine != SessionStatus.Engine.CONNECTED || snapshot == null -> "The launcher is still starting. Try again in a moment."
            snapshot.game == Game.RUNNING || GameFilesProvider.gameStarting() -> "Stop the game first."
            snapshot.activeJob != null -> "Wait until the launcher's current task (install, update, official launcher) has finished."
            needReady && snapshot.installation == Installation.NEEDS_CLIENT ->
                "Project Diablo 2's files were changed (by the official PD2 launcher, say). Press Check files on Play first, then install the mod again."
            needReady && snapshot.installation != Installation.READY -> "Install Project Diablo 2 first: Play must be ready before the mod can go on top."
            else -> null
        }
    }

    fun install(mode: Mode, withFilter: Boolean) {
        val context = appContext ?: return
        if (_work.value is Work.Running) return
        _work.value = Work.Running("Checking…")
        scope.launch {
            _work.value = try {
                blocked(SessionService.status.value, needReady = !active())?.let { throw Refusal(it) }
                Work.Done(doInstall(context, mode, withFilter))
            } catch (refusal: Refusal) {
                Work.Failed(refusal.message!!)
            } catch (error: Exception) {
                Log.e(TAG, "install failed", error)
                Work.Failed("Install failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                refresh()
            }
        }
    }

    fun restore() {
        val context = appContext ?: return
        if (_work.value is Work.Running) return
        _work.value = Work.Running("Restoring Project Diablo 2…")
        scope.launch {
            _work.value = try {
                blocked(SessionService.status.value, needReady = false)?.let { throw Refusal(it) }
                doRestore(context)
                SessionService.command(Command.CheckAgain)
                Work.Done("Project Diablo 2's own files are back.")
            } catch (refusal: Refusal) {
                Work.Failed(refusal.message!!)
            } catch (error: Exception) {
                Log.e(TAG, "restore failed", error)
                Work.Failed("Restore failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                refresh()
            }
        }
    }

    private class Refusal(message: String) : Exception(message)

    private fun doInstall(context: Context, mode: Mode, withFilter: Boolean): String {
        // 1. The newest release and its files.
        val release = JSONObject(httpText(mode.releaseApi))
        val version = release.getString("tag_name")
        _latest.value = _latest.value + (mode.repo to version)
        val assets = release.getJSONArray("assets")
        val chosen = parts(mode).mapNotNull { part ->
            val asset = findAsset(assets, part.assets)
            if (asset == null && part.required) throw IOException("the release $version has no ${part.assets.first()}")
            asset?.let { part to it }
        }

        // 2. Download each into staging, checked against GitHub's size and SHA-256.
        val staging = stagingDir(context).apply { mkdirs() }
        val total = chosen.sumOf { it.second.getLong("size") }.coerceAtLeast(1)
        var done = 0L
        val staged = chosen.map { (part, asset) ->
            val name = asset.getString("name")
            val size = asset.getLong("size")
            val digest = asset.optString("digest").removePrefix("sha256:").lowercase().takeIf { it.length == 64 }
            val file = File(staging, part.target)
            if (!(file.isFile && file.length() == size && (digest == null || sha256(file) == digest))) {
                download(asset.getString("browser_download_url"), file) { bytes ->
                    _work.value = Work.Running("Downloading $name…", ((done + bytes) * 100 / total).toInt())
                }
                if (file.length() != size) throw IOException("$name: downloaded ${file.length()} bytes, expected $size")
                if (digest != null && sha256(file) != digest) throw IOException("$name: checksum does not match the release")
            }
            done += size
            part.target to file
        }
        val filterFile = if (withFilter) {
            _work.value = Work.Running("Downloading the loot filter…")
            File(staging, mode.filter).also { download(mode.filterUrl, it) {} }
        } else {
            null
        }

        // 3. The game folder may only change now: the checks again, after the downloads.
        _work.value = Work.Running("Installing…")
        blocked(SessionService.status.value, needReady = !active())?.let { throw Refusal(it) }
        val project = projectD2(context) ?: throw Refusal("No game folder: install Project Diablo 2 first.")
        val manifestFile = File(project, MANIFEST)
        if (!manifestFile.isFile) throw Refusal("The launcher's record of Project Diablo 2 is missing. Press Check files on Play first.")

        // 4. Keep what this replaces, unless a mod is in place already (an update or another mod):
        // then its files go back first, so nothing of the old mod stays beside the new one.
        val backups = backupDir(context)
        if (active()) revertFiles(project, backups)
        val keepAll = (Mode.entries.flatMap { parts(it).map(Part::target) } + LOOT_FILTER).distinct()
        if (!active()) {
            backups.deleteRecursively()
            backups.mkdirs()
            manifestFile.copyTo(File(backups, "accepted-manifest.json"), overwrite = true)
            val absent = JSONArray()
            for (name in keepAll) {
                val existing = find(project, name)
                if (existing != null) existing.copyTo(File(backups, name), overwrite = true) else absent.put(name)
            }
            File(backups, "absent.json").writeText(absent.toString())
        } else {
            // Files a newer release adds (D2Game.dll, say) were not kept by the first install.
            val absent = readAbsent(backups)
            for (name in keepAll) {
                if (!File(backups, name).exists() && name !in absent) {
                    val existing = find(project, name)
                    if (existing != null) existing.copyTo(File(backups, name)) else absent.add(name)
                }
            }
            File(backups, "absent.json").writeText(JSONArray(absent).toString())
        }

        // 5. Put the files in place and write their MD5s into the engine's record.
        val digests = mutableMapOf<String, String>()
        for ((target, file) in staged) {
            val placed = replace(project, target, file)
            digests[placed.name.lowercase()] = md5(placed)
        }
        // The controller buttons in the Thor's colours (A red, B yellow, Y green, X blue).
        val recoloured = recolourButtons(project)
        if (recoloured != null) digests[recoloured.name.lowercase()] = md5(recoloured)
        if (filterFile != null) {
            replace(project, LOOT_FILTER, filterFile)
            File(project, "filters/local").mkdirs()
            filterFile.copyTo(File(project, "filters/local/${mode.filter}"), overwrite = true)
        }
        val manifestText = patchManifest(manifestFile.readText(), digests)
        writeAtomically(manifestFile, manifestText.toByteArray())

        val state = JSONObject()
            .put("version", version)
            .put("mode", mode.name)
            .put("filter", filterFile != null)
            .put("manifest_sha256", sha256(manifestFile))
        writeAtomically(stateFile(context).also { it.parentFile!!.mkdirs() }, state.toString().toByteArray())
        staging.deleteRecursively()
        SessionService.command(Command.CheckAgain)
        return "${mode.title} $version${if (mode.isSoe) " (${mode.label})" else ""} is installed" +
            (if (recoloured != null) ", with the controller buttons in your Thor's colors" else "") + ". Press Play."
    }

    /** `pd2assets.mpq` of [project] with the recoloured button pictures, or null when it has none to change. */
    private fun recolourButtons(project: File): File? {
        val target = find(project, "pd2assets.mpq") ?: return null
        val temp = File(project, ".${target.name}.soe-tmp")
        try {
            target.copyTo(temp, overwrite = true)
            if (!ButtonColors.apply(temp)) return null
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return target
        } catch (error: Exception) {
            Log.w(TAG, "button colors skipped: $error")
            return null
        } finally {
            temp.delete()
        }
    }

    private fun doRestore(context: Context) {
        val backups = backupDir(context)
        val project = projectD2(context)
        if (active() && project != null && backups.isDirectory) revertFiles(project, backups)
        // Not in place any more (Check files replaced it): Project Diablo 2's files are already back.
        backups.deleteRecursively()
        stateFile(context).delete()
        stagingDir(context).deleteRecursively()
    }

    /** Project Diablo 2's own files and record, as kept at the first install; the backups stay. */
    private fun revertFiles(project: File, backups: File) {
        if (!backups.isDirectory) return
        for (name in readAbsent(backups)) find(project, name)?.delete()
        backups.listFiles().orEmpty()
            .filter { it.isFile && it.name != "absent.json" && it.name != "accepted-manifest.json" }
            .forEach { replace(project, it.name, it) }
        File(backups, "accepted-manifest.json").takeIf { it.isFile }?.let {
            writeAtomically(File(project, MANIFEST), it.readBytes())
        }
    }

    private fun readState(context: Context): JSONObject? =
        stateFile(context).takeIf { it.isFile }?.let { JSONObject(it.readText()) }

    private fun readAbsent(backups: File): MutableList<String> {
        val file = File(backups, "absent.json")
        if (!file.isFile) return mutableListOf()
        val array = JSONArray(file.readText())
        return MutableList(array.length()) { array.getString(it) }
    }

    private fun findAsset(assets: JSONArray, names: List<String>): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (names.any { it.equals(asset.getString("name"), ignoreCase = true) }) return asset
        }
        return null
    }

    /** The file of [project] named [name], whatever its letter case. */
    private fun find(project: File, name: String): File? =
        project.listFiles().orEmpty().firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }

    /** Replaces (or adds) [name] in [project] with a copy of [source], keeping an existing file's letter case. */
    private fun replace(project: File, name: String, source: File): File {
        val target = find(project, name) ?: File(project, name)
        writeAtomically(target) { out -> source.inputStream().use { it.copyTo(out) } }
        return target
    }

    /**
     * The engine's record with the MD5 of each file in [digests] (keyed by lower-case name)
     * replaced. Its form stays the engine's: `{"checksum": ["<md5>  <name>", ...]}`, in order.
     */
    internal fun patchManifest(text: String, digests: Map<String, String>): String {
        val json = JSONObject(text.removePrefix("﻿"))
        val lines = json.getJSONArray("checksum")
        val patched = JSONArray()
        for (i in 0 until lines.length()) {
            val line = lines.getString(i)
            val name = line.substring(34)
            val digest = digests[name.lowercase()]
            patched.put(if (digest != null) "$digest  $name" else line)
        }
        return JSONObject().put("checksum", patched).toString()
    }

    // ------------------------------------------------------------------ files and network

    private fun writeAtomically(target: File, bytes: ByteArray) = writeAtomically(target) { it.write(bytes) }

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val temp = File(target.parentFile, ".${target.name}.soe-tmp")
        temp.outputStream().use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun connect(url: String): HttpURLConnection {
        var location = url
        repeat(8) {
            val connection = URL(location).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "pd2-launcher-android-soe")
            if (location.startsWith("https://api.github.com/")) connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code in 300..399) {
                location = URL(URL(location), connection.getHeaderField("Location")).toString()
                connection.disconnect()
                return@repeat
            }
            if (code != 200) {
                connection.disconnect()
                throw IOException("$location answered HTTP $code")
            }
            return connection
        }
        throw IOException("too many redirects for $url")
    }

    private fun httpText(url: String): String = connect(url).let { c -> try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() } }

    private fun download(url: String, target: File, progress: (Long) -> Unit) {
        val connection = connect(url)
        try {
            val part = File(target.parentFile, "${target.name}.part")
            part.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                        if (total - reported > 1_000_000) {
                            reported = total
                            progress(total)
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("could not save ${target.name}")
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(algorithm: String, file: File): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File) = digest("SHA-256", file)
    private fun md5(file: File) = digest("MD5", file)
}
