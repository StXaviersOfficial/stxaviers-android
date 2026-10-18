#!/data/data/com.termux/files/usr/bin/bash
# start-bridge.sh — xd-bridge SUPERVISOR (one command, runs forever)
#
# What it does (2026-10-18, replaces the old 2-terminal setup):
#   1. kills any old bridge/tunnel copies
#   2. refreshes server.py from GitHub
#   3. starts the bridge server (localhost:25570, XD_KEY auth)
#   4. starts cloudflared and REGISTERS every new trycloudflare URL with the
#      worker (/internal/device-tunnel) — the agent auto-discovers it, so
#      tunnel URLs never need to be pasted in chat again
#   5. self-heals: if the tunnel dies it restarts (new URL, re-registered);
#      if the server dies it restarts too
#
# Run:  cd ~/xd && bash start-bridge.sh
# (keep Termux alive; the wake lock session from the original setup helps)
set -u
cd ~/xd

WORKER="https://stxaviers-auth.quackeditzofficial.workers.dev"
KEY="$(cat .key 2>/dev/null | tr -d '[:space:]')"
if [ -z "$KEY" ]; then
  echo "ERROR: no ~/xd/.key — write your XD key there first:"
  echo "  echo -n 'XD-xxxx...' > ~/xd/.key"
  exit 1
fi

pkill -f 'python server.py' 2>/dev/null
pkill -f 'cloudflared tunnel' 2>/dev/null
sleep 1

curl -sLo server.py.new https://raw.githubusercontent.com/StXaviersOfficial/stxaviers-android/main/termux/server.py \
  && [ -s server.py.new ] && mv server.py.new server.py

echo "[supervisor] starting bridge server + tunnel (key ${KEY:0:6}...)"
(
  while true; do
    # server health (re)start
    if ! curl -s -m 3 http://127.0.0.1:25570/health >/dev/null 2>&1; then
      pkill -f 'python server.py' 2>/dev/null
      sleep 1
      nohup env XD_KEY="$KEY" python server.py >>bridge.log 2>&1 &
      sleep 2
    fi
    # tunnel: register every URL cloudflared prints; loop restarts it on death
    cloudflared tunnel --url http://127.0.0.1:25570 2>&1 \
      | grep --line-buffered -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' \
      | while read -r U; do
          [ -n "$U" ] && curl -s -m 10 -X POST \
            -H "X-Device-Key: $KEY" -H "Content-Type: application/json" \
            -d "{\"url\":\"$U\"}" \
            "$WORKER/internal/device-tunnel" >/dev/null 2>&1
        done
    sleep 3
  done
) &
echo "[supervisor] running in background. Tunnel URL auto-registers with the worker."
echo "[supervisor] logs: ~/xd/bridge.log | stop: pkill -f start-bridge; pkill -f 'python server.py'; pkill -f 'cloudflared tunnel'"
