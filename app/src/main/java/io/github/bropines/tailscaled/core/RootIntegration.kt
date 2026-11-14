package io.github.bropines.tailscaled.core

import android.content.Context
import android.util.Log
import io.github.bropines.tailscaled.BuildConfig
import io.github.bropines.tailscaled.core.RootShell.rootLog
import io.github.bropines.tailscaled.core.RootShell.runSu
import io.github.bropines.tailscaled.core.RootUtils.TAG
import io.github.bropines.tailscaled.core.RootUtils.paths
import java.io.File

/**
 * Root Mode's footprint outside the app: the boot script in `service.d`
 * ([setServiceScriptInstalled], [serviceScriptState]) and the `tailscale` CLI
 * wrapper ([setTailscaleCliInstalled]), with the checks the Settings screen
 * reads ([rootIntegrationState]).
 */
internal object RootIntegration {
    fun setServiceScriptInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                // The daemon path is baked in, like the CLI wrapper's: the script
                // used to fall back to `find /data/app -name libtailscale.so`, which
                // would exec a same-named library from any other app as root.
                // BootReceiver re-installs the script on every app update, so the
                // path follows the install directory.
                // The version line goes in with it (RootBootScript), so Settings
                // can tell a copy an older build left behind.
                val scriptContent = RootBootScript.render(
                    bootScriptTemplate(context), context.packageName, bootScriptDaemonBin(context),
                    BuildConfig.VERSION_NAME
                )
                val tempFile = File(context.cacheDir, "tailscaled.sh").apply { writeText(scriptContent) }

                val cmd = """
                    mkdir -p "${RootPaths.SERVICE_D_DIR}"
                    cp "${tempFile.absolutePath}" "${paths.serviceScript}"
                    chmod 755 "${paths.serviceScript}"
                    rm -f "${tempFile.absolutePath}"
                """.trimIndent()

                runSu("service-script-install", cmd).ok
            } else {
                // This package's script only; the other build's is not ours.
                runSu("service-script-remove", "rm -f \"${paths.serviceScript}\"").ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage service.d script: ${e.message}", e)
            rootLog("ERROR", "Failed to manage autostart script: ${e.message}")
            false
        }
    }

    fun isServiceScriptInstalled(): Boolean {
        val res = runSu("service-script-check", "[ -f \"${paths.serviceScript}\" ] && echo 'exists'", timeoutMs = 10_000L)
        return res.ok && res.output.contains("exists")
    }

    private fun bootScriptTemplate(context: Context): String =
        context.assets.open("scripts/tailscaled.sh").bufferedReader().use { it.readText() }

    private fun bootScriptDaemonBin(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath

    /**
     * The installed boot script against the one this build would install: absent,
     * current, or outdated (another version, or no version line at all). Reads the
     * first lines of the file through `su` — `/data/adb` is root's alone.
     */
    fun serviceScriptState(context: Context): RootBootScript.State {
        val marker = "TAILSOCKS_SCRIPT_PRESENT"
        val res = runSu(
            "service-script-check",
            "[ -f \"${paths.serviceScript}\" ] && { echo '$marker'; head -n ${RootBootScript.HEADER_SEARCH_LINES} \"${paths.serviceScript}\"; }",
            timeoutMs = 10_000L,
            quiet = true
        )
        val head = if (res.ok && res.output.contains(marker)) res.output.substringAfter(marker) else null
        val expected = runCatching {
            RootBootScript.expectedVersion(bootScriptTemplate(context), context.packageName, bootScriptDaemonBin(context))
        }.getOrDefault("")
        return RootBootScript.state(head, expected)
    }

    /**
     * [serviceScriptState] and [isTailscaleCliInstalled] in one root shell, for
     * the boot receiver, which used to spend four (check and reinstall, twice)
     * inside its broadcast budget.
     */
    fun rootIntegrationState(context: Context): Pair<RootBootScript.State, Boolean> {
        val present = "TAILSOCKS_SCRIPT_PRESENT"
        val end = "TAILSOCKS_SCRIPT_END"
        val cli = "TAILSOCKS_CLI_PRESENT"
        val res = runSu(
            "root-integration-check",
            "[ -f \"${paths.serviceScript}\" ] && { echo '$present'; head -n ${RootBootScript.HEADER_SEARCH_LINES} \"${paths.serviceScript}\"; echo '$end'; }\n" +
                "{ ${paths.cliCheckScript()}; } | grep -q exists && echo '$cli'\n" +
                "true\n",
            timeoutMs = 10_000L,
            quiet = true
        )
        val head = if (res.ok && res.output.contains(present)) res.output.substringAfter(present).substringBefore(end) else null
        val expected = runCatching {
            RootBootScript.expectedVersion(bootScriptTemplate(context), context.packageName, bootScriptDaemonBin(context))
        }.getOrDefault("")
        return RootBootScript.state(head, expected) to (res.ok && res.output.contains(cli))
    }

    fun setTailscaleCliInstalled(context: Context, install: Boolean): Boolean {
        return try {
            if (install) {
                // The daemon binary is the CLI too (ts_include_cli); the wrapper
                // sets TS_BE_CLI so it runs as one.
                val cliBin = File(context.applicationInfo.nativeLibraryDir, "libtailscale.so").absolutePath
                val scriptContent = context.assets.open("scripts/tailscale_cli.sh").bufferedReader().use { it.readText() }
                    .replace("%PKG_NAME%", context.packageName)
                    .replace("%CLI_BIN%", cliBin)

                val tempFile = File(context.cacheDir, "tailscale_cli_wrapper.sh").apply {
                    writeText(scriptContent)
                }
                runSu("cli-install", paths.cliInstallScript(tempFile.absolutePath)).ok
            } else {
                // This package's wrapper only, from wherever it went.
                runSu("cli-remove", paths.cliRemoveScript()).ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manage CLI wrapper script: ${e.message}", e)
            rootLog("ERROR", "Failed to manage CLI wrapper: ${e.message}")
            false
        }
    }

    fun isTailscaleCliInstalled(): Boolean {
        val res = runSu("cli-check", paths.cliCheckScript(), timeoutMs = 10_000L)
        return res.ok && res.output.contains("exists")
    }
}
