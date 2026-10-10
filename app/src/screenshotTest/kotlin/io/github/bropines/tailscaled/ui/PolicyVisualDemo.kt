package io.github.bropines.tailscaled.ui

import io.github.bropines.tailscaled.admin.api.ApiDevice
import io.github.bropines.tailscaled.admin.api.ApiUser
import io.github.bropines.tailscaled.admin.policy.visual.PolicyDraft
import io.github.bropines.tailscaled.admin.policy.visual.PolicyPath
import io.github.bropines.tailscaled.admin.policy.visual.VisualActions
import io.github.bropines.tailscaled.admin.policy.visual.VisualEnv

/**
 * Made-up data for the visual policy editor's previews: the author's policy with names
 * anonymised (the same text as the unit tests' sample.hujson — tabs, aligned values, trailing
 * commas, Russian comments), an invented tailnet whose devices carry its tags, and actions that
 * do nothing. Every slice's preview file draws from here.
 */
object PolicyVisualDemo {

    val text: String = """{
	// Отключение дефолтной DERP-ноды 28 (Helsinki / HEL)
	"derpMap": {
		"Regions": {
			"28": null,
		},
	},

	// --- 1. ОПРЕДЕЛЕНИЕ ГРУПП (USER-DEFINED GROUPS) ---
	"groups": {
		"group:prod-admin": [
			"alice@example.com",
			"bob@example.com",
		],
		"group:dev-admin": [
			"alice@example.com",
			"bob@example.com",
		],
		"group:guest-users": [
			"guest@example.com",
		],
	},

	"tagOwners": {
		// PRODUCTION
		"tag:master": ["group:prod-admin"],
		"tag:server": ["group:prod-admin"],

		// DEV / SANDBOX / LAB
		"tag:lab":           ["group:dev-admin"],
		"tag:reverse-proxy": ["group:dev-admin"],
		"tag:lab-proxy":     ["group:dev-admin"],
		"tag:homelab":       ["group:dev-admin"],
		"tag:android":       ["group:dev-admin"],
		"tag:taildrop":      ["group:dev-admin"],
		"tag:guest":         ["group:dev-admin"],

		// --- INFRASTRUCTURE ---
		"tag:exit-node": ["group:prod-admin", "group:dev-admin"],
		"tag:dns":       ["group:prod-admin", "group:dev-admin"],
	},

	"autoApprovers": {
		"exitNode": ["tag:exit-node"],
	},

	"acls": [
		// --- 0. ПРАВИЛА ДЛЯ АДМИНИСТРАТОРОВ (ИЗ ГРУПП) ---
		// Явный список вместо "*:*": гости в него не входят, поэтому
		// устройства админов не видны гостевым устройствам.
		{
			"action": "accept",
			"src":    ["group:prod-admin"],
			"dst": [
				"group:prod-admin:*",
				"group:dev-admin:*",
				"tag:master:*",
				"tag:server:*",
				"tag:lab:*",
				"tag:reverse-proxy:*",
				"tag:lab-proxy:*",
				"tag:homelab:*",
				"tag:android:*",
				"tag:taildrop:*",
				"tag:guest:*",
				"tag:exit-node:*",
				"tag:dns:*",
				"autogroup:internet:*",
			],
		},
		{
			"action": "accept",
			"src":    ["group:dev-admin"],
			"dst": [
				"tag:lab:*",
				"tag:reverse-proxy:*",
				"tag:lab-proxy:*",
				"tag:homelab:*",
				"tag:android:*",
				"tag:taildrop:*",
				"tag:guest:*",
				"tag:exit-node:*",
				"tag:dns:*",
				"autogroup:internet:*",
			],
		},

		// --- 1. PRODUCTION CORE ---
		{
			"action": "accept",
			"src":    ["tag:master"],
			"dst":    ["tag:master:*"],
		},
		{
			"action": "accept",
			"src":    ["tag:master"],
			"dst":    ["tag:server:*"],
		},
		{
			"action": "accept",
			"src":    ["tag:server"],
			"dst":    ["tag:master:80,443"],
		},
		{
			"action": "accept",
			"src":    ["tag:master", "tag:server"],
			"dst":    ["tag:exit-node:*", "autogroup:internet:*"],
		},

		// --- 2. DNS ПРАВИЛА ---
		{
			"action": "accept",
			"src":    ["*"],
			"dst":    ["tag:dns:53"],
		},
		{
			"action": "accept",
			"src":    ["group:prod-admin", "group:dev-admin"],
			"dst":    ["tag:dns:4000"],
		},

		// --- 3. DEV / SANDBOX / LAB AREA ---
		{
			"action": "accept",
			"src": [
				"tag:lab",
				"tag:reverse-proxy",
				"tag:lab-proxy",
				"tag:homelab",
				"tag:android",
			],
			"dst": [
				"tag:lab:*",
				"tag:reverse-proxy:*",
				"tag:lab-proxy:*",
				"tag:homelab:*",
				"tag:android:*",
				"tag:taildrop:*",
			],
		},
		{
			"action": "accept",
			"src":    ["tag:reverse-proxy"],
			"dst":    ["tag:homelab:80,443,8096,4566,56569,8680,8443,7777"],
		},

		// --- 4. GUESTS & OTHERS ---
		{
			"action": "accept",
			"src":    ["tag:guest"],
			"dst":    ["autogroup:internet:*", "tag:exit-node:*"],
		},
		// --- 4.2 ГОСТИ-ПОЛЬЗОВАТЕЛИ: только выход в интернет через exit-ноды ---
		// К самим нодам (ssh, панели) доступа нет: для выхода хватает autogroup:internet.
		{
			"action": "accept",
			"src":    ["group:guest-users"],
			"dst":    ["autogroup:internet:*"],
		},
		// --- 4.1 ACCESS FOR EXTERNAL USER ---
		{
			"action": "accept",
			"src":    ["carol@example.com"],
			"dst":    ["tag:lab:*"],
		},

		// --- 5. ANDROID SELF-ACCESS ---
		{
			"action": "accept",
			"src":    ["tag:android"],
			"dst":    ["tag:android:*"],
		},
	],

	"ssh": [
		{
			"action": "accept",
			"src":    ["group:prod-admin"],
			"dst": [
				"tag:master",
				"tag:server",
				"tag:lab",
				"tag:exit-node",
				"tag:dns",
			],
			"users": ["root", "autogroup:nonroot", "administrator", "mini", "worker"],
		},
		{
			"action": "accept",
			"src":    ["group:dev-admin"],
			"dst": [
				"tag:lab",
				"tag:reverse-proxy",
				"tag:lab-proxy",
				"tag:homelab",
				"tag:android",
				"tag:exit-node",
			],
			"users": ["root", "autogroup:nonroot", "administrator", "mini", "worker"],
		},
		{
			"action": "accept",
			"src":    ["tag:lab"],
			"dst":    ["tag:lab"],
			"users":  ["root", "worker", "autogroup:nonroot"],
		},
	],

	"nodeAttrs": [
		{
			"target": ["tag:taildrop", "tag:android", "tag:homelab"],
			"attr": [
				"cap:web-client",
				"cap:taildrop",
				"cap:advertise-services",
				"funnel",
				"drive:share",
				"drive:access",
			],
		},
		// Funnel только своим: гости не публикуют устройства под доменом тейлнета.
		{
			"target": ["group:prod-admin", "group:dev-admin"],
			"attr":   ["funnel"],
		},
	],

	"grants": [
		{
			"src": [
				"tag:homelab",
				"tag:android",
				"tag:taildrop",
				"group:prod-admin",
				"group:dev-admin",
			],
			"dst": ["tag:homelab", "tag:android", "tag:taildrop"],
			"app": {
				"tailscale.com/cap/drive": [
					{
						"shares": ["*"],
						"access": "rw",
					},
				],
			},
		},
	],

	// Сервер не примет политику, если гостю случайно откроется лишнее.
	"tests": [
		{
			"src":  "guest@example.com",
			"deny": [
				"tag:exit-node:22",
				"tag:master:443",
				"tag:server:22",
				"tag:homelab:80",
				"tag:dns:4000",
				"alice@example.com:22",
			],
		},
	],
}
"""

    val users: List<ApiUser> = listOf(
        ApiUser(id = "u1", displayName = "Alice", loginName = "alice@example.com", role = "owner", type = "member"),
        ApiUser(id = "u2", displayName = "Bob", loginName = "bob@example.com", role = "admin", type = "member"),
        ApiUser(id = "u3", displayName = "Guest", loginName = "guest@example.com", role = "member", type = "member"),
        ApiUser(id = "u4", displayName = "Carol", loginName = "carol@example.com", role = "member", type = "shared"),
    )

    val devices: List<ApiDevice> = listOf(
        ApiDevice(id = "1", nodeId = "n1", name = "alice-phone.tail1234.ts.net", user = "alice@example.com", os = "android", addresses = listOf("100.64.0.1")),
        ApiDevice(id = "2", nodeId = "n2", name = "bob-laptop.tail1234.ts.net", user = "bob@example.com", os = "linux", addresses = listOf("100.64.0.2")),
        ApiDevice(id = "3", nodeId = "n3", name = "guest-pc.tail1234.ts.net", user = "guest@example.com", os = "windows", addresses = listOf("100.64.0.3")),
        ApiDevice(id = "4", nodeId = "n4", name = "master.tail1234.ts.net", user = "alice@example.com", os = "linux", tags = listOf("tag:master"), addresses = listOf("100.64.0.10")),
        ApiDevice(id = "5", nodeId = "n5", name = "server-1.tail1234.ts.net", user = "alice@example.com", os = "linux", tags = listOf("tag:server"), addresses = listOf("100.64.0.11")),
        ApiDevice(id = "6", nodeId = "n6", name = "nas.tail1234.ts.net", user = "bob@example.com", os = "linux", tags = listOf("tag:homelab", "tag:taildrop"), addresses = listOf("100.64.0.20")),
        ApiDevice(id = "7", nodeId = "n7", name = "exit-fra.tail1234.ts.net", user = "alice@example.com", os = "linux", tags = listOf("tag:exit-node"), addresses = listOf("100.64.0.30")),
        ApiDevice(id = "8", nodeId = "n8", name = "dns-1.tail1234.ts.net", user = "alice@example.com", os = "linux", tags = listOf("tag:dns"), addresses = listOf("100.64.0.31")),
        ApiDevice(id = "9", nodeId = "n9", name = "lab-gpu.tail1234.ts.net", user = "bob@example.com", os = "linux", tags = listOf("tag:lab"), addresses = listOf("100.64.0.40")),
        ApiDevice(id = "10", nodeId = "n10", name = "old-pi.tail1234.ts.net", user = "bob@example.com", os = "linux", tags = listOf("tag:printer"), addresses = listOf("100.64.0.50")),
    )

    fun draft(policy: String = text): PolicyDraft = PolicyDraft(policy)

    /** An environment over [policy]; [base] is what the risks are counted against (the saved file). */
    fun env(
        policy: String = text,
        headscale: Boolean = false,
        canWrite: Boolean = true,
        base: String? = text,
        errors: Map<PolicyPath, List<String>> = emptyMap(),
        focus: PolicyPath? = null,
    ): VisualEnv = VisualEnv(
        draft = draft(policy),
        devices = devices,
        users = users,
        headscale = headscale,
        canWrite = canWrite,
        risks = VisualEnv.risks(base, policy),
        errors = errors,
        focus = focus,
    )

    /** Actions that do nothing: a preview draws one frame. */
    val actions: VisualActions = object : VisualActions {
        override fun edit(op: (String) -> String): Boolean = true
        override fun openJson(line: Int) {}
        override fun show(path: PolicyPath) {}
    }
}
