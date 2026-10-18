# XD Bridge — Termux setup (the owner's command list)

This replaces the old XD Rig completely. Real Termux does the terminal work,
the tiny **XD Assist** app does the screen control, and `server.py` (this
folder) is the bridge between them. Everything below is typed on the phone,
in **Termux**.

## One-time setup (do this once)

```bash
pkg update -y && pkg upgrade -y
pkg install -y python cloudflared
termux-setup-storage
```

- `termux-setup-storage` shows one Android permission popup — **Allow** it.
  (Only needed if you want file access to the shared storage from the bridge.)
- Install Termux from **F-Droid** if possible (the Play Store build is old).

Then copy the bridge onto the phone:

```bash
mkdir -p ~/xd && cd ~/xd
curl -LO https://raw.githubusercontent.com/StXaviersOfficial/stxaviers-android/main/termux/server.py
```

Install the **XD Assist** app too (from the xd-delivery repo, `xdassist-1.0.0.apk`).

## Every time you want the phone controllable (2 terminals)

**Terminal 1 — start the bridge:**

```bash
cd ~/xd
export XD_KEY="pick-any-long-passphrase"
python server.py
```

**Terminal 2 — start the tunnel (new Termux session, swipe from the left edge
→ New Session):**

```bash
cloudflared tunnel --url http://127.0.0.1:25570
```

Terminal 2 prints a link like `https://something-words-here.trycloudflare.com`
near the top. **That link + your XD_KEY passphrase are the two things you paste
in chat** so the developer can drive the phone (and they're what you type into
the XD Assist app).

## Connect the XD Assist app (once)

1. Open **XD Assist**
2. Bridge URL → the `https://….trycloudflare.com` link from terminal 2
3. Secure key → the same `XD_KEY` passphrase
4. **SAVE & TEST BRIDGE** → should say `Bridge: ONLINE`
5. **ENABLE SCREEN CONTROL** → Android Accessibility settings open → find
   **XD Assist screen control** → switch it **ON**

From that moment the bridge can tap / swipe / type / back / home / read the
screen, run shell commands, and move files — but ONLY while your two Termux
terminals are running, and only through your own key.

## Keep it alive while the screen is off

Termux may freeze background processes when Android dozes. Before leaving the
app:

```bash
termux-wake-lock
```

(acquire once per session; `termux-wake-unlock` turns it off.)

## Stop everything when done

Ctrl+C in both terminals (or close the sessions), then:

```bash
pkill -f server.py; pkill cloudflared
```

and switch **XD Assist screen control OFF** in Accessibility settings.

## What the developer can do through the bridge (for reference)

- `POST /exec {"cmd":"..."}` — run any Termux command (bash), root too if the
  phone has `su`
- `GET /fs/list?path=home/…` / `shared/…` — browse files
- `GET /fs/read?path=…` / `POST /fs/write` — read/write files
- `POST /rig/queue {"type":"tap|swipe|text|back|home|dump|key","x":..,"y":..}`
  — a command the XD Assist app performs on the screen; `GET /rig/wait?id=…`
  waits for its result

Every request must carry header `X-Backend-Key: <your XD_KEY>` — without it the
bridge answers `403 bad key`. If you start `server.py` **without** setting
XD_KEY it warns loudly and accepts anyone — never do that.

## Troubleshooting

- **Bridge: UNREACHABLE in the app** → terminal 1 died, or the tunnel link
  changed (quick tunnels pick a new link every start — re-paste the fresh one
  into the app).
- **`/rig/wait` says "timeout waiting for the assist app"** → the XD Assist
  accessibility service is OFF (or the phone killed it) — re-enable it from
  the app's button.
- **`cloudflared: command not found` after install** → restart Termux once.
