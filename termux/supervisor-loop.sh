#!/data/data/com.termux/files/usr/bin/bash
# supervisor-loop.sh — spawned (nohup + disowned) by start-bridge.sh.
# Keeps the bridge server alive, keeps cloudflared alive, and keeps the
# WORKER's tunnel registry fresh (re-registers every 300s).
set -u
cd ~/xd

WORKER="https://stxaviers-auth.quackeditzofficial.workers.dev"
KEY="$(cat .key 2>/dev/null | tr -d '[:space:]')"
if [ -z "$KEY" ]; then
  echo "[supervisor] no .key — exiting"
  exit 1
fi

reg() {  # register a tunnel URL with the worker registry
  local u="$1"
  [ -n "$u" ] || return 0
  curl -s -m 10 -X POST \
    -H "X-Device-Key: $KEY" -H "Content-Type: application/json" \
    -d "{\"url\":\"$u\"}" \
    "$WORKER/internal/device-tunnel" >/dev/null 2>&1 \
    && touch tunnel.reg
}

echo "[supervisor] loop started $(date)"

while true; do
  # 1. bridge server health (re)start
  if ! curl -s -m 3 http://127.0.0.1:25570/health >/dev/null 2>&1; then
    pkill -f 'python server.py' 2>/dev/null
    sleep 1
    nohup env XD_KEY="$KEY" python server.py >>bridge.log 2>&1 &
    sleep 2
  fi

  # 2. S25: periodic re-registration of the CURRENT url (age = liveness)
  if [ -s tunnel.url ]; then
    NOW=$(date +%s)
    LASTREG=$(stat -c %Y tunnel.reg 2>/dev/null || echo 0)
    if [ $((NOW - LASTREG)) -ge 300 ]; then
      reg "$(cat tunnel.url)"
    fi
  fi

  # 3. tunnel: register every URL cloudflared prints; loop restarts it on death
  cloudflared tunnel --url http://127.0.0.1:25570 2>&1 \
    | grep --line-buffered -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' \
    | while read -r U; do
        [ -n "$U" ] && { echo "$U" > tunnel.url; reg "$U"; }
      done
  sleep 3
done
