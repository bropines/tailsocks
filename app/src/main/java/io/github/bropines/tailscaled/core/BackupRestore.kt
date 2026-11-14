package io.github.bropines.tailscaled.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import appctr.Appctr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * The parts of the backup and restore in Settings that are not a screen: what
 * an export carries, which archive entries a restore may write and where, the
 * password between its dialog and the file picker, and the restart that makes
 * a full restore take effect.
 */
object BackupRestore {
    private const val TAG = "BackupRestore"

    /** Flattens a SharedPreferences snapshot into the JSON object an export carries. */
    fun prefsToJson(values: Map<String, *>): JsonObject = buildJsonObject {
        for ((k, v) in values) {
            when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Float -> put(k, v)
                is Set<*> -> putJsonArray(k) { v.forEach { add(it.toString()) } }
                null -> {}
                else -> put(k, v.toString())
            }
        }
    }

    /**
     * Reads back the String/Boolean entries of an exported preference object.
     *
     * Everything else is skipped: the global preference file only ever holds those
     * two types, and a value of any other shape in the file is not something this
     * build wrote.
     */
    fun jsonToPrefValues(obj: JsonObject): Map<String, Any> {
        val values = mutableMapOf<String, Any>()
        obj.forEach { (key, element) ->
            val prim = element as? JsonPrimitive ?: return@forEach
            when {
                // A quoted value is a string even if it reads like a bool.
                prim.isString -> values[key] = prim.content
                prim.booleanOrNull != null -> values[key] = prim.booleanOrNull!!
            }
        }
        return values
    }

    /**
     * Where a full backup's entry [name] may be restored to, or null when it may not.
     *
     * A backup may only restore what a backup contains: preference files, daemon
     * state directories and the sent-files history. Every other entry is refused.
     * The old prefix match accepted any `files/...` path, including
     * `control_proxy.env`, which the Root Mode boot script sourced as root, and
     * `files/../x` escapes. The three directories are expected canonical.
     */
    fun restoreTarget(name: String, prefsDir: File, statesDir: File, filesDir: File): File? {
        if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) return null
        val (base, target) = when {
            name == "files/sent_history.json" -> filesDir to File(filesDir, "sent_history.json")
            name.startsWith("shared_prefs/") && name.endsWith(".xml") && name.count { it == '/' } == 1 ->
                prefsDir to File(prefsDir, name.removePrefix("shared_prefs/"))
            name.startsWith("files/states/") ->
                statesDir to File(statesDir, name.removePrefix("files/states/"))
            else -> return null
        }
        val canonical = target.canonicalFile
        return canonical.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    /**
     * Every file entry of a decrypted full backup mapped to where it goes, or a
     * [SecurityException] naming the first entry outside the restorable set —
     * before anything is written: a half-applied restore is worse than none.
     */
    fun restoreTargets(zip: ByteArray, prefsDir: File, statesDir: File, filesDir: File): Map<String, File> {
        val targets = LinkedHashMap<String, File>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name != BackupFormat.MANIFEST_ENTRY) {
                    targets[entry.name] = restoreTarget(entry.name, prefsDir, statesDir, filesDir)
                        ?: throw SecurityException("Backup contains a file outside the restorable set: ${entry.name}")
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return targets
    }

    /** Writes the entries of [zip] to the [targets] [restoreTargets] allowed. */
    fun writeRestore(zip: ByteArray, targets: Map<String, File>) {
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val targetFile = targets[entry.name]
                // An archive from before backups left the daemon's netmap cache out
                // may still carry one: an old map is no state worth restoring.
                val cacheEntry = entry.name.startsWith("files/states/") &&
                    NetmapCache.isCachePath(entry.name.removePrefix("files/states/"))
                if (targetFile != null && !cacheEntry) {
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * The full backup's password between its dialog and the system's file picker.
     *
     * The password is typed first and the picker opens after it. Rotating the phone
     * inside the picker, or "Don't keep activities", recreates Settings, and a
     * password held in the screen's `remember` was gone when the picker answered:
     * the archive was sealed with an empty one, which the user's real password does
     * not open. Held here it survives a recreation of the screen in the same process;
     * it is never written to disk or to the saved-instance Bundle. A process the
     * system ended under the picker loses it, and the backup then asks again
     * rather than writing anything (see [take]).
     */
    object PasswordHold {
        @Volatile private var held: CharArray? = null

        fun put(password: CharArray) {
            clear()
            held = password
        }

        /** The held password, handed over once; null when there is none (or it was empty). */
        fun take(): CharArray? {
            val p = held
            held = null
            return p?.takeIf { it.isNotEmpty() }
        }

        fun clear() {
            held?.fill('\u0000')
            held = null
        }
    }

    /**
     * A full restore that has been written and waits for the app to restart.
     *
     * The restore copies preference files and daemon state into place under a live
     * process. `SharedPreferencesImpl` never re-reads a file it has loaded, so the
     * next `apply()` on any of those stores — the service's flags, the main screen's
     * profile writes, a widget — writes the old in-memory values back over the
     * restored file; a running daemon keeps its old state in memory and writes it
     * back the same way. So the restore asks for a restart, and [restartNow] makes
     * the restored files the last thing written before the process ends.
     *
     * Held in the process, like the password: when the system ends the process
     * before the user answers, that end is the restart, and nothing is pending.
     */
    object PendingRestart {
        private class Staged(val zip: ByteArray, val targets: Map<String, File>)

        @Volatile private var staged: Staged? = null

        val pending: Boolean get() = staged != null

        fun hold(zip: ByteArray, targets: Map<String, File>) {
            staged = Staged(zip, targets)
        }

        /** "Later": the restored files stay in place, and what happens to them is the user's to decide. */
        fun drop() {
            staged = null
        }

        /**
         * Stops what would write the old settings back, writes the restored files
         * again, and relaunches the app in a fresh process. Blocking: call it off
         * the main thread, from a scope that outlives the screen. Does not return
         * when it succeeds.
         *
         * The connection is stopped the way Stop stops it. In Root Mode the daemon
         * is a separate root process that outlives the app — and a Stop may leave it
         * running on purpose (*keep the daemon on stop*). Here it may not stay: the
         * restored node key and preferences are read by a daemon only when it starts,
         * and a daemon kept alive would go on as the old node, write its old state
         * over the restored one, and be adopted as it is by the relaunched app. So
         * it is stopped in every case, and its state handed back to the app the way
         * leaving Root Mode does. The relaunched app starts disconnected; the user
         * starts the connection from the main screen.
         */
        suspend fun restartNow(context: Context) {
            val app = context.applicationContext
            val s = staged ?: return
            val rootMode = GlobalSettings.isRootModeEnabled(app)
            if (ProxyState.isUserLetRunning(app) || Appctr.isRunning()) {
                awaitServiceStop(app)
            }
            if (rootMode) {
                RootUtils.stopRootDaemon("${app.filesDir.absolutePath}/tailscaled.sock", handBackTo = app)
            }
            // The stop above wrote its flags (desired_running, the routing records)
            // from the old in-memory values; the restored files go back on top of them,
            // and nothing in this process writes a preference after this line.
            try {
                writeRestore(s.zip, s.targets)
            } catch (e: Exception) {
                Log.e(TAG, "Rewriting the restored files before the restart failed", e)
            }
            staged = null
            relaunch(app)
        }

        /** Asks the service to stop and waits for its "STOP" broadcast, which it sends once the teardown is done. */
        private suspend fun awaitServiceStop(app: Context) {
            val done = CompletableDeferred<Unit>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) { done.complete(Unit) }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter("STOP"), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                app.startService(Intent(app, TailscaledService::class.java).setAction("STOP_ACTION"))
                // A root teardown runs `su` and may take seconds; the stop shell's own
                // limit is 30 s. Past that the restart goes ahead regardless.
                if (withTimeoutOrNull(30_000L) { done.await() } == null) {
                    Log.w(TAG, "The service did not report its stop in time; restarting anyway")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the service before the restart", e)
            } finally {
                runCatching { app.unregisterReceiver(receiver) }
            }
        }

        private fun relaunch(app: Context) {
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            val component = launch?.component
            if (component != null) {
                // NEW_TASK | CLEAR_TASK: the activity manager starts it in a new process
                // once this one is gone.
                app.startActivity(Intent.makeRestartActivityTask(component))
            }
            Runtime.getRuntime().exit(0)
        }
    }
}
