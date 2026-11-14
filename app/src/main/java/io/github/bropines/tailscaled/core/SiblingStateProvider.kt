package io.github.bropines.tailscaled.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

/**
 * Answers the other build of the app ([SiblingBuild]) with one row about this
 * one: whether it runs, its mode, three listening ports and a short hash of the
 * node's stable ID. Read-only — `query()` and nothing else.
 *
 * Exported without a permission, because the release and the dev build are
 * signed with different keys and a signature permission cannot join them. So
 * the row is built to be harmless in anyone's hands, and in addition it is
 * handed only to the sibling's package: the caller's uid must own it. Package
 * names are unique on a device, so another app cannot pass for the sibling.
 *
 * Cheap on purpose: preferences, one state-file read, and the liveness check
 * [ProxyState] already does (a socket connect in Root Mode, no root shell). It
 * may run in a process Android has just started for it, where the bridge is not
 * loaded yet; [ProxyState.isActualRunning] answers there too.
 */
class SiblingStateProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (!callerIsSibling()) return null
        val state = SiblingBuild.localState(ctx, withLiveness = true)
        return MatrixCursor(SiblingBuild.COLUMNS, 1).apply { addRow(state.toRow()) }
    }

    private fun callerIsSibling(): Boolean {
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return false
        val sibling = SiblingBuild.siblingPackage(ctx.packageName)
        return ctx.packageManager.getPackagesForUid(uid)?.contains(sibling) == true
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
