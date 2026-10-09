package io.github.bropines.tailscaled.admin.devices

import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.RequestIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

enum class BulkTagMode {
    /** The chosen tags join the ones each device has. */
    ADD,
    /** The chosen tags come off; a device never loses its last tag here. */
    REMOVE,
    /** Each device gets exactly the chosen tags. */
    REPLACE,
}

/** Why a selected device is left alone. */
enum class BulkSkip {
    /** Shared in from another tailnet: not this tailnet's to change. */
    SHARED,
    UNCHANGED,
    /** It would lose every tag, which hands it to a user again: the web console's job, not a bulk edit's. */
    LAST_TAG,
}

/** One device in the dry run: its tags before and after, or why it is skipped. */
data class BulkTagRow(val device: ApiDevice, val before: List<String>, val after: List<String>, val skip: BulkSkip?) {
    val changes: Boolean get() = skip == null

    /** An untagged device getting tags: its user stops owning it, which cannot simply be undone. */
    val takesOwnership: Boolean get() = changes && before.isEmpty() && after.isNotEmpty()
}

enum class BulkState {
    PENDING,
    VERIFIED,
    /** Sent; the re-read could not run. */
    APPLIED,
    /** Sent; the re-read shows other tags. */
    MISMATCH,
    FAILED,
    /** Not sent: an earlier device failed in a way every later one would too. */
    NOT_SENT,
}

data class BulkOutcome(
    val deviceId: String,
    val name: String,
    val state: BulkState,
    val error: Throwable? = null,
    val requestIds: List<String> = emptyList(),
)

/**
 * Tag changes over several devices: the dry run first ([plan]), then the changes one device at a
 * time ([apply]), each re-read, each with its own result. Only the changes go out; skipped and
 * unchanged devices are never sent.
 */
object BulkTags {
    private val NAME = Regex("^[A-Za-z0-9_-]+$")

    /** "server" or "tag:server" as "tag:server"; null for what is not a tag name. */
    fun normalize(tag: String): String? {
        val t = tag.trim()
        val name = if (t.startsWith("tag:")) t.removePrefix("tag:") else t
        return if (NAME.matches(name)) "tag:$name" else null
    }

    fun plan(devices: List<ApiDevice>, mode: BulkTagMode, tags: List<String>): List<BulkTagRow> {
        val chosen = tags.mapNotNull(::normalize).distinct()
        return devices.map { d ->
            val before = d.tags
            val after = when (mode) {
                BulkTagMode.ADD -> (before + chosen).distinct()
                BulkTagMode.REMOVE -> before.filterNot { it in chosen }
                BulkTagMode.REPLACE -> chosen
            }
            val skip = when {
                d.isShared -> BulkSkip.SHARED
                after.toSet() == before.toSet() -> BulkSkip.UNCHANGED
                before.isNotEmpty() && after.isEmpty() -> BulkSkip.LAST_TAG
                else -> null
            }
            BulkTagRow(d, before, if (skip == null) after else before, skip)
        }
    }

    /**
     * Sends the changing rows one by one, re-reads each device, and reports every result to
     * [onOutcome] as it lands. A refused credential, a rate limit or a lost connection stops the
     * run: the devices after it are reported as not sent rather than tried in vain. Request ids
     * go to the caller's [RequestIds] too, so the batch's own audit record carries them.
     */
    suspend fun apply(
        backend: AdminBackend,
        rows: List<BulkTagRow>,
        onOutcome: (BulkTagRow, BulkOutcome) -> Unit = { _, _ -> },
    ): List<BulkOutcome> {
        val outer = currentCoroutineContext()[RequestIds]
        var stop: Throwable? = null
        return rows.filter { it.changes }.map { row ->
            val d = row.device
            val outcome = stop?.let { BulkOutcome(d.pathId, d.shortName, BulkState.NOT_SENT, it) } ?: run {
                val ids = RequestIds()
                val result = try {
                    withContext(ids) { backend.setDeviceTags(d.pathId, row.after) }
                    val verified = try {
                        withContext(ids) { backend.getDevice(d.pathId).tags.toSet() == row.after.toSet() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    BulkOutcome(
                        d.pathId, d.shortName,
                        when (verified) {
                            true -> BulkState.VERIFIED
                            false -> BulkState.MISMATCH
                            null -> BulkState.APPLIED
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (stopsTheRun(e)) stop = e
                    BulkOutcome(d.pathId, d.shortName, BulkState.FAILED, e)
                }
                ids.ids.forEach { outer?.add(it) }
                result.copy(requestIds = ids.ids)
            }
            onOutcome(row, outcome)
            outcome
        }
    }

    private fun stopsTheRun(e: Exception): Boolean =
        e is AdminApiException.Unauthorized || e is AdminApiException.Forbidden ||
            e is AdminApiException.RateLimited || e is AdminApiException.Network || e is AdminApiException.PaymentRequired

    /** True when every device took the change and the re-read says so; false when one did not; null when unknown. */
    fun verified(outcomes: List<BulkOutcome>): Boolean? = when {
        outcomes.any { it.state == BulkState.FAILED || it.state == BulkState.MISMATCH || it.state == BulkState.NOT_SENT } -> false
        outcomes.all { it.state == BulkState.VERIFIED } -> true
        else -> null
    }
}
