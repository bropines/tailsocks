package io.github.bropines.tailscaled.admin.policy.visual

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R

/*
 * Ports in words and the chips that pick them: "all ports", "SSH (22)", "web (80, 443)" on
 * cards; All / SSH / HTTPS / Web / DNS / RDP / Other as chips in the editors. An ACL's
 * destination takes a port list, a test's destination exactly one port, a grant a list of
 * `ip` entries with an optional protocol each (`tcp:443`, `icmp:*`).
 */

/** The port chips of a test destination, which takes exactly one port. */
enum class SinglePort(val port: Int) { SSH(22), HTTP(80), HTTPS(443), DNS(53), RDP(3389) }

/** Presets a grant's `ip` list offers as chips: each one entry, so they combine. */
enum class IpPreset(val entries: List<String>) {
    ALL(listOf("*")), SSH(listOf("22")), HTTP(listOf("80")), HTTPS(listOf("443")), DNS(listOf("53")), RDP(listOf("3389")),
}

object PortWords {

    /** "80,443, 8096" → ["80", "443", "8096"]. */
    fun parts(ports: String): List<String> = ports.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** A port list as a person writes it: commas spaced, ranges with a dash. */
    fun display(ports: String): String = parts(ports).joinToString(", ") { it.replace('-', '–') }

    /** A long port list cut for a chip: "80, 443 +6"; up to three as [display] has them. */
    fun short(ports: String, keep: Int = 2): String {
        val all = parts(ports)
        if (all.size <= keep + 1) return display(ports)
        return all.take(keep).joinToString(", ") { it.replace('-', '–') } + " +" + (all.size - keep)
    }

    /** A protocol as a card shows it: names in capitals, numbers as they are. */
    fun proto(p: String): String = if (p.all { it.isDigit() }) p else p.uppercase()

    /** Whether [p] names a protocol a rule may use: a known name or an IANA number 0–255. */
    fun protoOk(p: String): Boolean = p.lowercase() in Selectors.PROTOCOLS || p.toIntOrNull()?.let { it in 0..255 } == true

    /** What is wrong with a grant's `ip` entry ("443", "tcp:443", "icmp:*", "80-90"), or null. */
    fun checkIp(entry: String): SelectorProblem? {
        val e = entry.trim()
        if (e.isEmpty()) return SelectorProblem.EMPTY
        val spec = Selectors.ipSpec(e)
        if (spec.proto != null && !protoOk(spec.proto)) return SelectorProblem.UNKNOWN_FORM
        return SelectorRules.checkPorts(spec.ports)
    }

    /** The one port of a test destination, or null when [ports] is not a single number in range. */
    fun single(ports: String?): Int? = ports?.trim()?.toIntOrNull()?.takeIf { it in 0..65535 }

    /** Whether the chip of [preset] shows as on for [ip]: all of its entries are there. */
    fun isOn(ip: List<String>, preset: IpPreset): Boolean = preset.entries.all { it in ip }

    /**
     * [ip] with [preset] switched: "All" replaces everything with `*`; any other chip drops `*`
     * (all ports would make it say nothing) and adds or removes its own entries, the rest kept.
     */
    fun toggle(ip: List<String>, preset: IpPreset): List<String> = when {
        isOn(ip, preset) -> ip - preset.entries.toSet()
        preset == IpPreset.ALL -> listOf("*")
        else -> (ip - "*") + preset.entries.filter { it !in ip }
    }

    /** Entries no preset chip stands for: shown as chips of their own. */
    fun custom(ip: List<String>): List<String> = ip.filter { e -> IpPreset.entries.none { e in it.entries } }
}

/** [ports] in words: "all ports", "SSH (22)", "web (80, 443)", or the list itself. */
fun portWords(ctx: Context, ports: String?): String {
    val p = ports ?: return ctx.getString(R.string.admin_pv_port_all)
    return when (Selectors.preset(p)) {
        PortPreset.ALL -> ctx.getString(R.string.admin_pv_port_all)
        PortPreset.SSH -> ctx.getString(R.string.admin_pv_port_ssh)
        PortPreset.HTTPS -> ctx.getString(R.string.admin_pv_port_https)
        PortPreset.WEB -> ctx.getString(R.string.admin_pv_port_web)
        PortPreset.HTTP -> ctx.getString(R.string.admin_pv_port_http)
        PortPreset.DNS -> ctx.getString(R.string.admin_pv_port_dns)
        PortPreset.RDP -> ctx.getString(R.string.admin_pv_port_rdp)
        null -> PortWords.display(p)
    }
}

/** [portWords] for a chip, where a long list is cut ([PortWords.short]). */
fun portWordsShort(ctx: Context, ports: String?): String =
    if (ports == null || Selectors.preset(ports) != null) portWords(ctx, ports) else PortWords.short(ports)

/** A grant's `ip` entry in words: "all ports", "HTTPS (443)", "TCP 8080", "ICMP", "UDP, all ports". */
fun ipWords(ctx: Context, entry: String): String {
    val spec = Selectors.ipSpec(entry)
    val proto = spec.proto ?: return portWords(ctx, spec.ports)
    val name = PortWords.proto(proto)
    return when {
        spec.ports == "*" && proto.equals("icmp", ignoreCase = true) -> name
        spec.ports == "*" -> ctx.getString(R.string.admin_pv_ip_proto_all, name)
        else -> ctx.getString(R.string.admin_pv_ip_proto_ports, name, PortWords.display(spec.ports))
    }
}

private fun presetLabel(ctx: Context, p: PortPreset): String = ctx.getString(
    when (p) {
        PortPreset.ALL -> R.string.admin_pv_chip_all
        PortPreset.SSH -> R.string.admin_pv_chip_ssh
        PortPreset.HTTPS -> R.string.admin_pv_chip_https
        PortPreset.WEB -> R.string.admin_pv_chip_web
        PortPreset.HTTP -> R.string.admin_pv_chip_http
        PortPreset.DNS -> R.string.admin_pv_chip_dns
        PortPreset.RDP -> R.string.admin_pv_chip_rdp
    }
)

/** The presets an ACL destination offers, in the order of the chips. */
private val ACL_PRESETS = listOf(PortPreset.ALL, PortPreset.SSH, PortPreset.HTTPS, PortPreset.WEB, PortPreset.DNS, PortPreset.RDP)

/**
 * Ports of one destination: preset chips and Other, which opens a field checked as it is
 * typed. [single] is a test's destination: one port, no "all", no lists. [ports] is the
 * value being built, not yet written; [onChange] gets every new value, valid or not, and the
 * caller writes it only once [portsProblem] says it is fine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortsEditor(ports: String, onChange: (String) -> Unit, single: Boolean = false, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val preset = if (single) null else Selectors.preset(ports)
    val singlePreset = if (single) SinglePort.entries.firstOrNull { it.port.toString() == ports.trim() } else null
    var other by remember { mutableStateOf(ports.isNotBlank() && preset == null && singlePreset == null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (single) {
                SinglePort.entries.forEach { p ->
                    SelectChip(selected = !other && singlePreset == p, onClick = { other = false; onChange(p.port.toString()) }, label = singlePortLabel(ctx, p))
                }
            } else {
                ACL_PRESETS.forEach { p ->
                    SelectChip(selected = !other && preset == p, onClick = { other = false; onChange(p.spec) }, label = presetLabel(ctx, p))
                }
            }
            SelectChip(selected = other, onClick = { other = true }, label = ctx.getString(R.string.admin_pv_chip_other))
        }
        if (other) {
            val problem = portsProblem(ports, single)
            OutlinedTextField(
                value = ports,
                onValueChange = { v -> onChange(v.filter { it.isDigit() || (!single && (it == ',' || it == '-' || it == ' ' || it == '*')) }) },
                label = { Text(ctx.getString(if (single) R.string.admin_pv_port_one else R.string.admin_pv_ports_list)) },
                isError = ports.isNotBlank() && problem != null,
                supportingText = { Text(if (ports.isNotBlank() && problem != null) VisualText.problem(ctx, problem) else ctx.getString(if (single) R.string.admin_pv_port_one_hint else R.string.admin_pv_ports_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = if (single) KeyboardType.Number else KeyboardType.Text, autoCorrectEnabled = false),
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun singlePortLabel(ctx: Context, p: SinglePort): String = when (p) {
    SinglePort.SSH -> ctx.getString(R.string.admin_pv_port_ssh)
    SinglePort.HTTP -> ctx.getString(R.string.admin_pv_port_http)
    SinglePort.HTTPS -> ctx.getString(R.string.admin_pv_port_https)
    SinglePort.DNS -> ctx.getString(R.string.admin_pv_port_dns)
    SinglePort.RDP -> ctx.getString(R.string.admin_pv_port_rdp)
}

/** What is wrong with [ports] for a destination ([single]: a test's), or null. */
fun portsProblem(ports: String, single: Boolean): SelectorProblem? = when {
    ports.isBlank() -> SelectorProblem.EMPTY
    single -> if (PortWords.single(ports) == null) SelectorProblem.NEEDS_ONE_PORT else null
    else -> SelectorRules.checkPorts(ports)
}

/**
 * A grant's ports and protocols (`ip`): preset chips that combine, a chip per entry no preset
 * stands for, and a field for one more (`8080`, `tcp:8443`, `udp:3478`, `icmp:*`). Empty
 * means the grant opens no network ports — only its app capabilities, if any.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IpField(title: String, ip: List<String>, env: VisualEnv, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var typed by remember { mutableStateOf("") }
    val t = typed.trim()
    val problem = t.takeIf { it.isNotEmpty() }?.let(PortWords::checkIp)
    fun add() {
        if (t.isNotEmpty() && problem == null && t !in ip) onChange((ip - "*") + t)
        typed = ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldTitle(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            IpPreset.entries.forEach { p ->
                val on = PortWords.isOn(ip, p)
                FilterChip(
                    selected = on,
                    enabled = env.editable,
                    onClick = { onChange(PortWords.toggle(ip, p)) },
                    label = { Text(ipPresetLabel(ctx, p)) },
                    leadingIcon = if (on) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                )
            }
            PortWords.custom(ip).forEach { e ->
                val words = ipWords(ctx, e)
                InputChip(
                    selected = false,
                    enabled = env.editable,
                    onClick = { onChange(ip - e) },
                    label = { Text(words, fontFamily = if (words == e) FontFamily.Monospace else null) },
                    trailingIcon = if (env.editable) ({ Icon(Icons.Default.Close, null, Modifier.size(18.dp)) }) else null,
                    modifier = Modifier.semantics { contentDescription = ctx.getString(R.string.admin_pv_remove, words) },
                )
            }
        }
        if (env.editable) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.replace(" ", "") },
                singleLine = true,
                label = { Text(ctx.getString(R.string.admin_pv_ip_other)) },
                placeholder = { Text("tcp:8443", fontFamily = FontFamily.Monospace) },
                isError = problem != null,
                supportingText = { Text(problem?.let { VisualText.problem(ctx, it) } ?: ctx.getString(R.string.admin_pv_ip_hint)) },
                trailingIcon = {
                    IconButton(onClick = ::add, enabled = t.isNotEmpty() && problem == null) { Icon(Icons.Default.Add, ctx.getString(R.string.admin_pv_add)) }
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun ipPresetLabel(ctx: Context, p: IpPreset): String = when (p) {
    IpPreset.ALL -> ctx.getString(R.string.admin_pv_chip_all)
    IpPreset.SSH -> ctx.getString(R.string.admin_pv_port_ssh)
    IpPreset.HTTP -> ctx.getString(R.string.admin_pv_port_http)
    IpPreset.HTTPS -> ctx.getString(R.string.admin_pv_port_https)
    IpPreset.DNS -> ctx.getString(R.string.admin_pv_port_dns)
    IpPreset.RDP -> ctx.getString(R.string.admin_pv_port_rdp)
}
