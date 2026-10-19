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

Then set up the bridge folder (one time):

```bash
mkdir -p ~/xd && cd ~/xd
echo -n 'XD-your-key-here' > .key      # the XD key from the agent
curl -LO https://raw.githubusercontent.com/StXaviersOfficial/stxaviers-android/main/termux/start-bridge.sh
```

Install the **XD Assist** app too (xd-delivery repo, `xdassist-1.0.1.apk`):
its Bridge URL field pre-fills the eternal worker URL — you only paste the
key once. Then enable its accessibility service.

## Starting the bridge (ONE command — the self-healing supervisor)

```bash
cd ~/xd && bash start-bridge.sh
```

That's it. The supervisor (2026-10-19 redesign):

- pulls the latest `server.py` + `supervisor-loop.sh` from GitHub itself,
- starts the bridge server (localhost:25570, key-authenticated),
- starts cloudflared and **registers every new tunnel URL with the worker**
  automatically — tunnel URLs are never pasted anywhere again,
- **survives closing the Termux session** (nohup + disown — the old version
  died with the session, which is what killed the tunnel overnight),
- **re-registers the current tunnel every 5 minutes** — the worker always
  knows the phone is alive (registry age = liveness),
- self-heals: tunnel dies → restarted with a fresh URL; server dies →
  restarted. The XD Assist app reconnects on its own within seconds.

Stop everything:

```bash
pkill -f supervisor-loop.sh; pkill -f 'python server.py'; pkill -f 'cloudflared tunnel'
```

Logs: `~/xd/supervisor.log` (supervisor) and `~/xd/bridge.log` (server).
Tip: keep Termux alive with a wake lock (`termux-wake-lock`) so Android
doesn't freeze it in the background.

## What the agent can do through the bridge

- `/exec` — run shell commands on the phone
- `/fs/*` — list/read/write files (scoped to Termux home + shared storage)
- `/rig/*` — the screen-control channel the XD Assist app consumes
  (tap / swipe / type / dump screen / open app / navigation)

Everything requires the `X-Backend-Key` header (your XD key), so only
key-holders can drive the phone. The bridge **refuses to start** without
the key set.
