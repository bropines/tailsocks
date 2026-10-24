---
title: Files
description: Send files with Taildrop and share folders with Taildrive.
---

Both live on the main screen's **Files**, in two tabs.

## Taildrop

### Sending

- From any app: Share → TailSocks → **Send to…**, then pick a device. Devices that cannot receive are shown greyed out, with the reason.
- In the app: Files → TailDrop → **Send to a device**. Tap a device, pick files.
- From a device in **Peers**: **Send File**.
- The launcher shortcut **Send a file with Taildrop**.

### Receiving

Arrivals land in the **Inbox** with **Open**, **Save** and **Delete**, and a notification says who sent what.

To skip the Save step, choose a folder: Settings → Sharing & access → **Taildrop Storage Folder**. Received files are then moved there as they arrive. On a TV the folder is `Download/Taildrop` unless you choose another.

The system Files app shows each account's inbox under *TailSocks Taildrop*.

## Taildrive

Shares folders of this device with your tailnet, over WebDAV, and opens the folders others share.

### Sharing a folder

1. Files → TailDrive. **Enable Taildrive** is on by default, with the Download folder shared as `Downloads`.
2. **Add Share** (+): **System Folder Picker** or **Enter Path Manually**. A **Share Name** takes letters, digits, `_`, spaces and brackets; other devices see it in lower case.
3. **Share Full Storage** shares all of it at once.

Who may read and who may write is set by the tailnet policy, not in the app.

### Other devices' shares

**Browse other devices' shares** (the devices icon on top) lists tailnets, devices, their shares and folders. Tap a file and it opens in whatever on the device opens that kind of file — a video or a song in a player, a PDF, a photo or a document in its own app. When nothing takes it, the app says so and can show the file's address.

- With the Taildrive proxy on, films and music go to the player by its `http://127.0.0.1:…` link, and the player seeks in them without downloading them first. **Open files through a link** sends every file that way.
- Other apps see the same folders in the system Files app, under *Taildrive*. They get a file as a stream and copy it at full speed, with TailSocks in the background too. **Seeking in other apps** (Settings → Sharing & access) gives them a file a player can seek in instead (Android 8 and newer), slower to copy; on a TV it is on by default.

### Downloading

Tap ↓ on a file, or select files and folders with a long press and tap **Download**. A folder downloads with everything inside; the app says how much before it starts.

Downloads form a queue under the list: one line and one bar for the whole, and — unfolded — a row for each download with its own ×, then **Clear finished** and **Cancel all**. Up to three files download at once; **Parallel downloads** (Settings → Sharing & access) sets one to four. Where they go is asked the first time, or set under **Taildrive downloads**.

### The Taildrive proxy

**Enable TailDrive Proxy** serves the tailnet's shares — the folders **Browse** shows — at `http://127.0.0.1:33445` (**WebDAV URL (Tap to Copy)**), for a file manager or a player that speaks WebDAV. A file manager with a WebDAV client of its own copies fastest this way.

**Allow from the local network** opens it to your Wi-Fi. **Require Authentication** is forced on with it, and a password is made if there is none. The **WebDAV address** is then shown with a QR code.

### Nothing lists?

The tailnet policy decides. Under the switches the app says what the server allows this device:

- *The server does not let this device share folders.* — missing `drive:share`.
- *The server does not let this device open other devices' folders.* — missing `drive:access`.
- An empty **Browse** list with both allowed means no grant gives you a share yet.

A policy that shares between your own devices — the one the app's template **Taildrive between a person's own devices** writes into an empty policy:

```jsonc
{
	"grants": [
		{
			"src": ["autogroup:member"],
			"dst": ["autogroup:self"],
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

	"nodeAttrs": [
		{
			"target": ["autogroup:member"],
			"attr":   ["drive:share", "drive:access"],
		},
	],
}
```

With an admin API profile for a Tailscale tailnet, **Allow in the policy** opens the visual policy editor on that template. `"access": "ro"` makes the shares read-only. Tailscale's own guide: [Taildrive](https://tailscale.com/kb/1369/taildrive).

On Android 10 and older Taildrive asks for the Storage permission (**Grant Permission**).
