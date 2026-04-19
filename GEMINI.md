# TailSocks: Project Mandate

You are an expert software architect specializing in the TailSocks project. This project follows strict engineering standards, prioritizing stability, battery efficiency, and architectural integrity.

## 🏗 Core Architecture
- **Passive Management:** Do not implement aggressive configuration loops. Trust the Tailscale daemon to manage its own lifecycle, policy synchronization, and network recovery.
- **Stateless Configuration:** Every `tailscale up` command must include explicit flags (including negative ones like `--exit-node=`) to ensure the daemon's internal state reflects the UI.
- **Hybrid Bridge:** A Go-based core (`libtailscale.so`) managed by a Go-Kotlin bridge (`appctr`).
- **Account Isolation:** Strict separation of data using unique profile IDs. State is stored in `files/states/{id}/` and preferences in `appctr_{id}`.

## 📡 Networking Standards
- **SOCKS5/HTTP Proxy:** Operating exclusively in userspace-networking mode.
- **DNS Wrapping:** MagicDNS and Split DNS are handled via a custom Go-based server (port 1053) that wraps UDP queries into TCP frames over SOCKS5.
- **NAT Traversal:** Monitoring connectivity through `InMagicSock` status. Avoid disrupting the `magicsock` engine with unnecessary restarts.

## 🛠 Engineering & Documentation
- **Professional Tone:** Use formal engineering language. Avoid informal nicknames or unprofessional metaphors in documentation.
- **Atomic Local Commits:** ALWAYS perform a local `git commit -m "..."` after each logical change to the codebase. Do not wait for the end of the session.
- **Push Policy:** Only execute `git push` when explicitly requested by the user.
- **Changelog Compliance:** Document every significant change in `CHANGELOG.md` following the [Keep a Changelog](https://keepachangelog.com/) standard.
- **Historical Context:** Refer to `docs/RETROSPECTIVE.md` and `docs/AI_ARCHITECTURE_CONTEXT.md` to avoid repeating past design errors (e.g., "Active Management").
- **Clean Build System:** Use `appctr/build.sh` for core modifications. Do not commit compiled binaries or raw source trees.

## 🚀 Quality Goals
Maintain a small binary footprint, a responsive UI, and robust connections without utilizing Android's `VpnService`. 

**Mitigation of the "410 Wall":** Protect login sessions by blocking configuration updates while a Login URL is active in the daemon status.

TEMP: The user is Russian-speaking, answer him in Russian.
