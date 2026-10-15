package io.github.bropines.tailscaled.admin.policy.templates

import io.github.bropines.tailscaled.admin.api.HttpResponse
import io.github.bropines.tailscaled.admin.api.TransportException
import io.github.bropines.tailscaled.core.AppVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/*
 * Where the templates come from, in three layers. The bundled set (assets/policy-templates,
 * copied from the repository's policy-templates/ at build time) is always there. The remote
 * set — INDEX_URL and the files it lists, each with its sha256 — is fetched over HTTPS at most
 * once in six hours when the catalog opens, or when asked; what of it checks out is kept in
 * the app's files as the cache, the last good remote set. A cached template stands in for a
 * bundled one of the same id only when its version is higher and the app is new enough for it
 * (minApp). A file that does not match its sha256, does not parse or breaks a rule
 * (TemplateRules) is skipped with a line in the log; nothing here throws at the editor.
 */

/** Where a template of the catalog came from. */
enum class TemplateOrigin { BUNDLED, CACHE }

data class LoadedTemplate(val template: PolicyTemplate, val origin: TemplateOrigin)

/**
 * The templates the editor offers, in the order the sets list them, and how the remote set was
 * last asked: [checkedAt] when, [updatedAt] when it last answered with a set, [error] why the
 * last time failed, [refreshing] while it is being asked.
 */
data class TemplateCatalog(
    val templates: List<LoadedTemplate> = emptyList(),
    val checkedAt: Long? = null,
    val updatedAt: Long? = null,
    val error: String? = null,
    val refreshing: Boolean = false,
) {
    fun byId(id: String): PolicyTemplate? = templates.firstOrNull { it.template.id == id }?.template

    /** What this backend is offered: on Headscale not the templates it does not take. */
    fun offered(headscale: Boolean): List<LoadedTemplate> =
        templates.filter { !headscale || it.template.headscale.supported != HeadscaleLevel.NONE }
}

/** Reads one file of a set by its name; null when the set has none. */
fun interface TemplateFiles {
    fun read(name: String): ByteArray?

    companion object {
        /** A set in a directory: the repository's policy-templates/, the cache. */
        fun dir(dir: File) = TemplateFiles { name -> File(dir, name).takeIf { it.isFile }?.readBytes() }
    }
}

/** One GET; [TransportException] when nothing answered. */
fun interface TemplateFetch {
    fun get(url: String): HttpResponse
}

/** The app's version, as a template's minApp is compared with it. */
data class AppIdentity(val versionName: String, val versionCode: Int)

/** Why a file of a set is not used. */
class TemplateFileException(message: String) : Exception(message)

class TemplateSource(
    private val bundled: TemplateFiles,
    private val cacheDir: File,
    private val fetch: TemplateFetch?,
    private val app: AppIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    @Serializable
    private data class Meta(val checkedAt: Long? = null, val updatedAt: Long? = null, val error: String? = null)

    /** A file of a set that checked out. */
    private class Entry(val index: IndexEntry, val bytes: ByteArray, val template: PolicyTemplate)

    private val setDir = File(cacheDir, "set")
    private val metaFile = File(cacheDir, META)

    /** The bundled set with the cached remote one over it: files only, no network. */
    @Synchronized
    fun local(): TemplateCatalog {
        val meta = readMeta()
        return TemplateCatalog(merge(read(bundled, "bundled"), read(TemplateFiles.dir(setDir), "cached")), meta.checkedAt, meta.updatedAt, meta.error)
    }

    /** Whether the remote set was asked for less than [REFRESH_MS] ago (or the clock went back). */
    fun fresh(): Boolean {
        val at = readMeta().checkedAt ?: return false
        val now = clock()
        return now >= at && now - at < REFRESH_MS
    }

    /**
     * Ask the remote set — unless it was asked less than six hours ago and not [force]d — and
     * keep what of it checks out as the cache. Blocking. The catalog it returns carries the
     * error when the set could not be read; the previous cache then stays as it was.
     */
    @Synchronized
    fun refresh(force: Boolean = false): TemplateCatalog {
        val f = fetch ?: return local()
        if (!force && fresh()) return local()
        val now = clock()
        val error = try {
            update(f)
            null
        } catch (e: TemplateFileException) {
            e.message
        } catch (e: TransportException) {
            e.message ?: "no answer"
        }
        val old = readMeta()
        writeMeta(Meta(checkedAt = now, updatedAt = if (error == null) now else old.updatedAt, error = error))
        error?.let { log("policy templates: the remote set was not read: $it") }
        return local()
    }

    private fun update(f: TemplateFetch) {
        val indexBytes = get(f, INDEX_URL, MAX_INDEX_BYTES)
        val index = try {
            TemplateJson.decodeFromString(TemplateIndex.serializer(), indexBytes.decodeToString())
        } catch (e: SerializationException) {
            throw TemplateFileException("index.json does not parse")
        } catch (e: IllegalArgumentException) {
            throw TemplateFileException("index.json does not parse")
        }
        if (index.schema !in 1..TemplateRules.SCHEMA) throw TemplateFileException("index.json is schema ${index.schema}")
        val base = INDEX_URL.substringBeforeLast('/') + "/"
        val have = read(bundled, "bundled").associateBy { it.template.id }
        val cached = read(TemplateFiles.dir(setDir), "cached").associateBy { it.index.sha256 }
        val accepted = mutableListOf<Entry>()
        var total = indexBytes.size
        for (e in index.templates.take(MAX_TEMPLATES)) {
            val reason = entryProblem(e)
            if (reason != null) { log("policy template ${e.file} skipped: $reason"); continue }
            // Nothing to gain from a version the bundled set has already.
            if ((have[e.id]?.template?.version ?: 0) >= e.version) continue
            val bytes = cached[e.sha256]?.bytes ?: try {
                get(f, base + e.file, MAX_FILE_BYTES)
            } catch (x: TemplateFileException) {
                log("policy template ${e.file} skipped: ${x.message}"); continue
            } catch (x: TransportException) {
                log("policy template ${e.file} skipped: ${x.message}"); continue
            }
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) { log("policy templates: the remote set is over $MAX_TOTAL_BYTES bytes; the rest is skipped"); break }
            entry(e, bytes, "remote")?.let { accepted += it }
        }
        writeSet(accepted)
    }

    private fun get(f: TemplateFetch, url: String, cap: Int): ByteArray {
        require(url.startsWith("https://")) { "templates are fetched over HTTPS only" }
        val r = f.get(url)
        if (r.status != 200) throw TemplateFileException("${url.substringAfterLast('/')}: HTTP ${r.status}")
        val bytes = r.body.encodeToByteArray()
        if (bytes.size > cap) throw TemplateFileException("${url.substringAfterLast('/')} is over $cap bytes")
        return bytes
    }

    /** What is wrong with an index entry itself, before its file is read. */
    private fun entryProblem(e: IndexEntry): String? = when {
        !FILE.matches(e.file) -> "file name \"${e.file}\""
        !SHA.matches(e.sha256) -> "sha256 \"${e.sha256}\""
        !TemplateRules.isId(e.id) -> "id \"${e.id}\""
        e.version < 1 -> "version ${e.version}"
        !allows(e.minApp) -> "needs app ${e.minApp?.content}"
        else -> null
    }

    /** The template in [bytes], if it is what [e] says it is and the app may use it. */
    private fun entry(e: IndexEntry, bytes: ByteArray, from: String): Entry? {
        fun skip(reason: String): Entry? {
            log("policy template ${e.file} ($from) skipped: $reason")
            return null
        }
        if (sha256(bytes) != e.sha256.lowercase()) return skip("its sha256 is not the one the index lists")
        val t = try {
            parse(bytes)
        } catch (x: TemplateFileException) {
            return skip(x.message.orEmpty())
        }
        if (t.id != e.id || t.version != e.version) return skip("it is ${t.id} ${t.version}, the index says ${e.id} ${e.version}")
        if (!allows(t.minApp)) return skip("it needs app ${t.minApp?.content}")
        return Entry(e, bytes, t)
    }

    /** Every entry of a set's index that checks out, in its order. */
    private fun read(files: TemplateFiles, from: String): List<Entry> {
        val raw = runCatching { files.read(INDEX) }.getOrNull() ?: return emptyList()
        val index = try {
            TemplateJson.decodeFromString(TemplateIndex.serializer(), raw.decodeToString())
        } catch (e: Exception) {
            log("policy templates: the $from index does not parse")
            return emptyList()
        }
        return index.templates.mapNotNull { e ->
            entryProblem(e)?.let { log("policy template ${e.file} ($from) skipped: $it"); return@mapNotNull null }
            val bytes = runCatching { files.read(e.file) }.getOrNull() ?: run { log("policy template ${e.file} ($from) is missing"); return@mapNotNull null }
            entry(e, bytes, from)
        }
    }

    /** The bundled set, with each cached template of a higher version in its place and new ones after. */
    private fun merge(bundled: List<Entry>, cached: List<Entry>): List<LoadedTemplate> {
        val out = LinkedHashMap<String, LoadedTemplate>()
        bundled.forEach { out[it.template.id] = LoadedTemplate(it.template, TemplateOrigin.BUNDLED) }
        cached.forEach { c ->
            val b = out[c.template.id]
            if (b == null || c.template.version > b.template.version) out[c.template.id] = LoadedTemplate(c.template, TemplateOrigin.CACHE)
        }
        return out.values.toList()
    }

    /** Whether this app may use a template that asks for [minApp]: a version name or a version code. */
    fun allows(minApp: JsonPrimitive?): Boolean {
        minApp ?: return true
        minApp.intOrNull?.takeIf { !minApp.isString }?.let { return app.versionCode >= it }
        val wanted = AppVersion.parse(minApp.content) ?: return false
        val mine = AppVersion.parse(app.versionName) ?: return false
        return mine >= wanted
    }

    // ---- the cache ----

    /** The new cache: written beside the old one, then put in its place. */
    private fun writeSet(entries: List<Entry>) {
        val tmp = File(cacheDir, "set.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        entries.forEach { File(tmp, it.index.file).writeBytes(it.bytes) }
        val index = TemplateIndex(TemplateRules.SCHEMA, entries.map { it.index })
        File(tmp, INDEX).writeText(TemplateJson.encodeToString(TemplateIndex.serializer(), index))
        val old = File(cacheDir, "set.old")
        old.deleteRecursively()
        if (setDir.exists() && !setDir.renameTo(old)) setDir.deleteRecursively()
        if (!tmp.renameTo(setDir)) throw TemplateFileException("the cache could not be written")
        old.deleteRecursively()
    }

    private fun readMeta(): Meta = runCatching { TemplateJson.decodeFromString(Meta.serializer(), metaFile.readText()) }.getOrDefault(Meta())

    private fun writeMeta(m: Meta) {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "$META.tmp")
        tmp.writeText(TemplateJson.encodeToString(Meta.serializer(), m))
        if (!tmp.renameTo(metaFile)) { metaFile.delete(); tmp.renameTo(metaFile) }
    }

    companion object {
        /** The remote set's index; the files it lists are read from beside it. */
        const val INDEX_URL = "https://bropines.github.io/tailsocks/templates/index.json"
        const val INDEX = "index.json"
        private const val META = "meta.json"

        /** How long a check of the remote set holds before opening the catalog asks again. */
        const val REFRESH_MS = 6 * 60 * 60 * 1000L

        const val MAX_INDEX_BYTES = 64 * 1024
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_TOTAL_BYTES = 1024 * 1024
        const val MAX_TEMPLATES = 100

        private val FILE = Regex("""[a-z0-9][a-z0-9-]{0,63}\.json""")
        private val SHA = Regex("""[0-9a-fA-F]{64}""")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

        /** A template file: parsed, then held to [TemplateRules]. */
        fun parse(bytes: ByteArray): PolicyTemplate {
            if (bytes.size > MAX_FILE_BYTES) throw TemplateFileException("over $MAX_FILE_BYTES bytes")
            val t = try {
                TemplateJson.decodeFromString(PolicyTemplate.serializer(), bytes.decodeToString())
            } catch (e: SerializationException) {
                throw TemplateFileException("does not parse: ${e.message?.lineSequence()?.firstOrNull()}")
            } catch (e: IllegalArgumentException) {
                throw TemplateFileException("does not parse: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            val problems = TemplateRules.problems(t)
            if (problems.isNotEmpty()) throw TemplateFileException(problems.joinToString("; "))
            return t
        }
    }
}
