---
title: Remote management
description: Manage another device's TailSocks from this one, over the tailnet.
---

:::caution
Experimental: it may still change. Guest pairings have been tried on emulators only.
:::

One device's TailSocks can be managed from another over the tailnet — handy for a TV, or a relative's phone. Both run TailSocks, in the same tailnet. Off by default.

## Turn it on and pair

On the device to be managed:

1. Settings → Advanced → Experimental → **Remote management**.
2. Turn on **Allow remote management**.
3. **Show a pairing code**: six digits and a QR code. A code lives 10 minutes and works once.

On the managing device:

1. Open that device in **Peers** and tap **Manage this device**.
2. Type the code and tap **Pair**, or **Scan the code**.

From then on the managed devices appear on the main screen, each with whether it is online. **Managed devices on the main screen** (Settings → Appearance & language) hides them.

## Who may do what

- **Owner** — a device of the same account as the managed one. Changes settings, runs actions, picks the exit node.
- **Guest** — any other device. Sees the status and the settings, picks the exit node, nothing more. A guest pairing lasts 24 hours. Turning off **Let other people's devices pair as guests** keeps guests out.

**Full control** is a switch on the managed device only, and asks before it turns on. It adds, for the owner: the device's storage (sending any file back by Taildrop), shared folders, Taildrive and its proxy, offering exit node and subnet routes, the diagnostics report and the log. The owner can switch it off from afar, never on.

**Nobody, ever**, from afar:

- stop TailSocks for good
- sign out
- change the account or the login server
- read or set a password or a key
- change the control plane proxy or the censorship bypass
- touch Root Mode
- grant a permission
- turn full control on

**What a paired device may do** on the managed device lists all of it.

## On the managed device

Every change shows in a bar on top of its screen for a couple of seconds — who, and what. With the app not on screen it comes as a notification, *Managed from …*.

A change that could cut the link — the exit node, MagicDNS, routes, LAN access, VPN mode — waits for the managing device to confirm it still answers. Without that within a minute, the device puts the old value back itself.

**Revoke** or **Revoke all** ends pairings there; **Forget this device** does it from the managing side.

## Modes

Works in proxy, VPN (TUN) and Root Mode. VPN mode can be turned on from afar only once the VPN permission was given on the device itself. Root Mode is never changed from afar.

A device can be managed only from within its own tailnet.
