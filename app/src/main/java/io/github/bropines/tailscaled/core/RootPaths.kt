package io.github.bropines.tailscaled.core

import io.github.bropines.tailscaled.BuildConfig

/**
 * The files Root Mode puts outside the app's sandbox, for one package.
 *
 * The release app and the debug build (`.dev`, which installs alongside it)
 * used to write the same root-side paths, so whichever ran last owned them: on
 * 2026-10-14 the debug build replaced the release app's CLI wrapper, and its
 * boot-time refresh would have done the same to the boot script and the env
 * file. The release package keeps the names it always had — users have them,
 * the docs describe them, and the boot script's version line is a digest of
 * the script that names them. Any other package gets a suffix taken from its
 * name: the debug build installs `tailscale-dev`, `service.d/tailscaled-dev.sh`,
 * the module `tailscaled-dev` and `/data/adb/tailsocks-dev/`.
 *
 * What both still share is the kernel side, which no file name can separate:
 * table 53, the priority 100/190/200 rules, the fwmark bits, the
 * `TAILSOCKS_MARK`/`TAILSOCKS_DNS` chains, `tailscale0`, the bind mount over
 * `/system/etc/hosts` and the `rp_filter` sysctl with its saved value
 * ([RP_FILTER_ORIG]). That is one ruleset per device, and only one root daemon
 * can hold `tailscale0` at a time; the names of the ruleset stay the release's.
 */
class RootPaths private constructor(val packageName: String, val suffix: String) {

    /** The command a shell types: `tailscale`, or `tailscale-dev` for the debug build. */
    val cliName = "tailscale$suffix"

    /** Written directly when `/system` can be remounted read-write (rare since system-as-root). */
    val systemCli = "/system/bin/$cliName"

    /** Written directly when `/product/bin` can be remounted read-write. */
    val productCli = "/product/bin/$cliName"

    /** Always written: outside every PATH, but reachable by its full path. */
    val serviceDCli = "$SERVICE_D_DIR/$cliName"

    /** The Magisk/KernelSU/APatch module that overlays [systemCli] from the next boot on. */
    val moduleId = "tailscaled$suffix"
    val moduleDir = "$MODULES_DIR/$moduleId"
    val moduleCli = "$moduleDir/system/bin/$cliName"

    /** Every place the CLI wrapper goes, in the order Settings checks them. */
    val cliPaths: List<String> = listOf(systemCli, serviceDCli, moduleCli, productCli)

    /** The boot script `late_start` runs. */
    val serviceScript = "$SERVICE_D_DIR/tailscaled$suffix.sh"

    /** Root-owned home of everything the boot script consumes; the app uid cannot write here. */
    val envDir = "/data/adb/tailsocks$suffix"
    val envFile = "$envDir/control_proxy.env"

    /** The copy of `/system/etc/hosts` with the tailnet names, bind-mounted over the original. */
    val hostsDir = "/data/adb/tailshosts$suffix"
    val hostsFile = "$hostsDir/hosts"

    /** The module's `module.prop`; the release's is the text it has always been. */
    val moduleProp: String = buildString {
        append("id=").append(moduleId).append('\n')
        append("name=TailSocks CLI Integration")
        if (suffix.isNotEmpty()) append(" (").append(packageName).append(')')
        append('\n')
        append("version=v1.0\n")
        append("versionCode=100\n")
        append("author=TailSocks\n")
        append("description=Tailscale CLI binary overlay\n")
    }

    /**
     * Copies the rendered wrapper at [source] to every place it goes. The
     * service.d copy and the module always succeed on a rooted device; the two
     * partitions only when they can be remounted, which is why each is tried
     * and none is required.
     */
    fun cliInstallScript(source: String): String = buildString {
        append("mkdir -p \"$SERVICE_D_DIR\"\n")
        append("cp \"$source\" \"$serviceDCli\"\n")
        append("chmod 755 \"$serviceDCli\"\n")

        append("mkdir -p \"$moduleDir/system/bin\"\n")
        append("rm -f \"$moduleDir/disable\" \"$moduleDir/remove\"\n")
        append("cat > \"$moduleDir/module.prop\" <<'TAILSOCKS_MODULE_EOF'\n")
        append(moduleProp)
        append("TAILSOCKS_MODULE_EOF\n")
        append("cp \"$source\" \"$moduleCli\"\n")
        append("chmod 755 \"$moduleCli\"\n")
        append("chcon u:object_r:system_file:s0 \"$moduleCli\" 2>/dev/null || true\n")

        append("if mount -o remount,rw /product/bin 2>/dev/null; then\n")
        append("    cp \"$source\" \"$productCli\" 2>/dev/null && chmod 755 \"$productCli\" && chcon u:object_r:system_file:s0 \"$productCli\" 2>/dev/null\n")
        append("    mount -o remount,ro /product/bin 2>/dev/null || true\n")
        append("fi\n")
        append("if mount -o remount,rw /system 2>/dev/null; then\n")
        append("    cp \"$source\" \"$systemCli\" 2>/dev/null && chmod 755 \"$systemCli\" 2>/dev/null\n")
        append("    mount -o remount,ro /system 2>/dev/null || true\n")
        append("fi\n")
        append("rm -f \"$source\"\n")
    }

    /** Removes this package's wrapper from every place it goes, and nothing else. */
    fun cliRemoveScript(): String = """
        rm -f "$systemCli" "$serviceDCli" "$moduleCli"
        mount -o remount,rw /product/bin 2>/dev/null && rm -f "$productCli" && mount -o remount,ro /product/bin 2>/dev/null || true
    """.trimIndent()

    /** Prints `exists` when this package's wrapper is in any of its places. */
    fun cliCheckScript(): String =
        "(" + cliPaths.joinToString(" || ") { "[ -f \"$it\" ]" } + ") && echo 'exists'"

    companion object {
        /** The release package: the one whose paths carry no suffix. */
        const val RELEASE_PACKAGE = "io.github.bropines.tailscaled"

        const val SERVICE_D_DIR = "/data/adb/service.d"
        const val MODULES_DIR = "/data/adb/modules"

        /**
         * The saved pre-TailSocks value of `all/rp_filter`. Not per package: it
         * records a device-wide sysctl that belongs to the shared ruleset, so
         * whichever package removes the ruleset must find it and put the value
         * back. It has always lived in the release's root directory.
         */
        const val RP_FILTER_ORIG = "/data/adb/tailsocks/rp_filter.orig"

        private val unsafe = Regex("[^a-z0-9]+")

        /**
         * The suffix of [packageName]'s root-side names: empty for the release
         * package, `-dev` for `io.github.bropines.tailscaled.dev`, and for any
         * other package its name after the release's prefix — or the whole
         * name, when it is not one of ours — lowercased with every run of other
         * characters turned into one `-`. The result is safe in a shell word, a
         * file name and a module id.
         */
        fun suffixFor(packageName: String): String {
            if (packageName == RELEASE_PACKAGE) return ""
            val own = packageName.removePrefix("$RELEASE_PACKAGE.")
            val tag = own.lowercase().replace(unsafe, "-").trim('-')
            return "-" + tag.ifEmpty { "other" }
        }

        fun forPackage(packageName: String): RootPaths = RootPaths(packageName, suffixFor(packageName))

        /** The paths of the package this build is. */
        val current: RootPaths by lazy { forPackage(BuildConfig.APPLICATION_ID) }
    }
}
