# Serve Redirect Bug — Debug Notes

**Status:** 🔴 Unresolved as of 2026-05-07  
**Branch:** `experimental/local-api`

---

## Symptom

When the user configures a **Redirect** handler in the Serve UI (e.g. redirect port 443 to
`https://therodev.com`), the rule appears to save but the config is immediately reset to the
previous state after the `refresh()` call in `saveConfig`.

The serve config ETag returned by `GET /localapi/v0/serve-config` after the save is **identical
to the ETag before the save**, proving the daemon's config did not change.

---

## Fixes Applied (may not be sufficient)

Two issues were identified and fixed in `ServeActivity.kt`:

### 1. Redirect URL without scheme (likely cause)
The daemon silently discards a redirect handler if the target has no scheme.
- **Before:** `HTTPHandler(redirect = target)` — e.g. `"therodev.com"`
- **After:** `HTTPHandler(redirect = if (target.startsWith("http")) target else "https://$target")`

### 2. `http = false` serialised as `"HTTP": false` in JSON
Gson serialised `Boolean(false)` as an explicit `false` field. The Tailscale daemon's
`ServeConfig` validation treats a `TCPPortHandler` with both `"HTTPS": true` and `"HTTP": false`
as conflicting and discards the entry.
- **Before:** `https = useHttps, http = !useHttps`  (when HTTPS: `true` + `false`)
- **After:** `https = if (useHttps) true else null, http = if (!useHttps) true else null`
  (only the active field is set; the other is absent from JSON)

---

## What to Investigate Next

### 1. Verify the JSON being sent
Add a log statement in `SetServeConfig` (Go side) to print `cleanJson` before the apply step:
```go
slog.Info("LocalAPI: SetServeConfig payload", "json", string(cleanJson))
```
This will show exactly what the daemon receives and whether the redirect rule is present.

### 2. Check the daemon's apply response body
Currently `SetServeConfig` logs the status code on error but the body may contain a more
specific validation error message. Check `slog.Error` output in the device logs after attempting
to save a redirect rule.

### 3. The ETag problem after Step 1 reset
In the log:
```
LocalAPI: Reset successful new_etag=    ← EMPTY
LocalAPI: ServeConfig [Step 2/2] if_match=  ← No If-Match header sent
```
The reset returns an empty ETag. With no `If-Match` on the apply, the daemon may silently
accept the request but apply it to an unexpected state. Consider fetching a fresh ETag via
`GET /localapi/v0/serve-config` between Step 1 and Step 2 instead of relying on the reset
response header.

### 4. Possible Tailscale daemon validation for node-scoped redirect
The node-scoped Web config uses host key `"*:443"`. For HTTPS serving on a node-scoped rule,
the daemon must provision a TLS certificate for the node's MagicDNS name. If the certificate
provisioning fails (e.g. ACME challenge), the daemon may discard the config. Check for
`cert(...)` log lines after the save attempt.

### 5. Consider using `tailscale serve --http 80 text:"test"` to verify node-scoped rules
Test with a simpler HTTP text handler first to isolate whether the issue is specific to
Redirect type or to node-scoped rules in general.

---

## Relevant Files

| File | Role |
|---|---|
| `appctr/localapi.go` | `SetServeConfig` — Go function that sends the config to the daemon |
| `app/.../ServeActivity.kt` | UI logic that builds the `ServeConfig` JSON and calls `setServeConfig` |
| `app/.../ServeModels.kt` | Kotlin data classes (make sure `@SerializedName` annotations match daemon JSON field names) |

---

## Key Log Pattern to Look For

When a redirect save succeeds, you should see:
```
LocalAPI: ServeConfig [Step 2/2] Applying new config
localapi: [POST] /localapi/v0/serve-config
serve: set redirect handler ...   ← THIS line must appear
```
If the `serve: set redirect handler` line is absent, the daemon rejected the redirect rule.
