# Serve Redirect Bug — Debug Notes

**Status:** ✅ Resolved (2026-05-07)  
**Branch:** `experimental/local-api`

---

## Symptom

When the user configured a **Redirect** handler in the Serve UI (e.g. redirect port 443 to
`https://therodev.com`), the rule appeared to save but the config was immediately reset to the
previous state after the `refresh()` call in `saveConfig`.

---

## Fixes Applied

### 1. Redirect URL without scheme (UI)
The daemon silently discards a redirect handler if the target has no scheme.
- **Fix:** `HTTPHandler` now auto-prepends `https://` if no scheme is present in `ServeActivity.kt`.

### 2. `http = false` serialised as `"HTTP": false` (UI)
The Tailscale daemon's validation treats a `TCPPortHandler` with both `"HTTPS": true` and `"HTTP": false`
as conflicting and discards the entry.
- **Fix:** Use `if (flag) true else null` in Kotlin models so Gson omits the inactive field from JSON.

### 3. Key Inconsistency (UI)
Node-scoped rules were sometimes saved as `":port"` but rendered as `"*:port"`, leading to UI desync.
- **Fix:** Normalized all node-scoped keys to `"*:port"` and service-scoped to `"fqdn:port"` in `ServeActivity.kt`.

### 4. Robust ETag Handling (Go/Bridge)
The Step 1 reset POST often returned an empty ETag, causing the subsequent Step 2 apply to fail or act unpredictably without an `If-Match` header.
- **Fix:** Added `fetchCurrentEtag` in `appctr/localapi.go` to manually retrieve a fresh ETag via `GET` if the reset response doesn't provide one.

### 5. Log Visibility (Go/Bridge)
Added explicit `fmt.Printf` for the JSON payload in `SetServeConfig` to bypass any `slog` filters and allow verifying the exact content sent to the daemon via `adb logcat`.

---

## Verification

With these fixes, Redirect handlers and Funnel toggles persist correctly across refreshes. The daemon correctly receives the normalized `*:port` keys and valid `TCPPortHandler` JSON.

---

## Relevant Files

| File | Role |
|---|---|
| `appctr/localapi.go` | `SetServeConfig`, `fetchCurrentEtag` |
| `app/.../ServeActivity.kt` | Key normalization, scheme prepending, edit logic |
| `app/.../ServeModels.kt` | JSON field annotations |
