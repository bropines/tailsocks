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

1. Open that device in **Peers** and tap **Manage this device**. It appears on devices that run TailSocks: the sock on the icon, the **TailSocks** tab.
2. Type the code and tap **Pair**, or **Scan the code**.

From then on the managed devices appear on the main screen, each with whether it is online. **Managed devices on the main screen** (Settings → Appearance & language) hides them. Pull a device's page down to read it again; on a TV, use the refresh button at the top.

## Who may do what

- **Owner** — a device of the same account as the managed one. Changes settings, runs actions, picks the exit node. Its pairing lasts until it is revoked.
- **Guest** — any other device. Sees the status and the settings, picks the exit node, nothing more. A guest pairing lasts 24 hours. Turning off **Let other people's devices pair as guests** keeps guests out.

### How the managed device tells them apart

When a device pairs or asks for anything, the managed device asks Tailscale whose device that is — the account (the e-mail) it is signed in with — and compares it with its own:

| The device that asks | It becomes |
|---|---|
| The same account, on a device without tags | owner |
| Another account — even a tailnet admin | guest, if guests are let in |
| A device with tags | guest, if guests are let in: a tagged device belongs to no person |
| A device shared in from another tailnet | guest, if guests are let in |

When the **managed** device itself has tags, it has no owner at all: everyone is a guest.

Nothing else counts. Being an admin of the tailnet, or a member of a group in its policy, gives no rights here: the app does not read them. To be an owner of a relative's tablet, sign it in to your account; full control can then be switched on at the tablet.

### Full control and the files

**Full control** is a switch on the managed device only, and asks before it turns on. It lets the owner offer the device as an exit node or a router. The owner can switch it off from afar, never on.

**Network access to files**, a switch next to it, decides the rest: the device's storage (sending any file back by Taildrop), shared folders, Taildrive and its proxy, the diagnostics report and the log. On, the owner gets them with full control; off, none of that is anyone's from afar, whatever full control says. It starts off — on only where full control was already on before 5.1.0 — and turns on only at the device, after its screen lock.

The managing device asks its own screen lock before it shows another device's storage, shared folders, log or report. One confirmation holds five minutes while that page is open; a device without a screen lock does not ask.

**Nobody, ever**, from afar:

- stop TailSocks for good
- sign out
- change the account or the login server
- read or set a password or a key
- change the control plane proxy or the censorship bypass
- touch Root Mode
- grant a permission
- turn full control on
- turn network access to files on

**What a paired device may do** on the managed device lists all of it.

### Full rights for a while

A guest can be given an owner's rights for 15 minutes, an hour, eight hours or until its pairing ends — a day at most. On a tagged device, where everyone is a guest, this is the only way to an owner's rights.

- **At the device**: **Give full rights for a while…** on the guest's row under **Paired devices**, pick how long, and confirm with the device's screen lock. **Full control too** adds full control for this guest and this time only.
- **From the guest**: **Ask for full rights** on the device's page. The question comes up at the device, as a notification if TailSocks is not on screen, and still takes its screen lock there. Nothing is given from afar.
- They end by themselves, or with **Take the rights back**. While they last, a notification on the device says who has them and until when.
- Rights given while **Network access to files** was off never include the files, even if it is turned on later. What nobody may do from afar stays out of reach with them too.
- **Let guests get full rights for a while** turns this off and ends the rights given. It is on by default and shown while guests are let in.

## On the managed device

Every change shows in a bar on top of its screen for a couple of seconds — who, and what. With the app not on screen it comes as a notification, *Managed from …*.

A change that could cut the link — the exit node, MagicDNS, routes, LAN access, VPN mode — waits for the managing device to confirm it still answers. Without that within a minute, the device puts the old value back itself.

**Revoke** or **Revoke all** ends pairings there; **Forget this device** does it from the managing side.

## Modes

Works in proxy, VPN (TUN) and Root Mode. VPN mode can be turned on from afar only once the VPN permission was given on the device itself. Root Mode is never changed from afar.

## When the device is not there to manage

A device can be managed only when this one **sees** it — the page then says *This device does not see …*. There are two reasons:

- **It is in another tailnet.** Pairing across tailnets is not possible.
- **The tailnet's access rules (ACL) keep it away.** Tailscale does not show a device at all to one its rules do not let reach it, in either direction. A relative's phone signed in to its own account in your tailnet is a usual case: if the policy lets that account reach only the internet, your devices do not see it either.

Remote management needs one thing from the policy: that the managing device may reach the managed one on **TCP port 28115**. For example, to manage the devices of the users in `group:guest-users` from those in `group:prod-admin`:

```jsonc
{
	"action": "accept",
	"src":    ["group:prod-admin"],
	"dst":    ["group:guest-users:28115"],
},
```

Nothing else is opened by it; replies come back on the same connection. In TailSocks's own policy editor the template [**TailSocks remote management**](/tailsocks/templates/#tailsocks-remote-management) writes it: choose **Who manages** and **Whose devices**, and it adds the rule and a test that keeps it. By hand it is a new access rule — **Who** `prod-admin`, **Where** `guest-users`, **Which ports** `28115` — and a test made **From the rule** keeps it from being lost by a later edit.

With a policy that allows everything (Tailscale's default), nothing has to be added.
