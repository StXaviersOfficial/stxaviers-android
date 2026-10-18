#!/usr/bin/env python3
# xd-bridge server — Termux remote-access bridge for the owner's phone.
# Pure Python STDLIB (no pip). Runs in Termux; cloudflared exposes it.
#
# Auth: every request must carry header  X-Backend-Key: <XD_KEY>  (except /health).
#
# Endpoints:
#   GET  /health                     → {"ok":true,...} (no key needed)
#   GET  /                           → endpoint list (key needed)
#   POST /exec        {"cmd":"ls -la","timeout":30}  → {"code":0,"out":"...","err":"..."}
#   GET  /fs/list?path=/relative     → {"entries":[{name,size,dir,mtime}...]}
#   GET  /fs/read?path=/relative     → raw file bytes (binary safe)
#   POST /fs/write    {"path":"...","content_b64":"..."}  → {"ok":true,"size":N}
#   POST /fs/mkdir    {"path":"..."} → {"ok":true}
#   POST /fs/delete   {"path":"..."} → {"ok":true}
#   POST /rig/queue   {"id":"..","type":"tap|swipe|text|back|home|dump|key","x":..,"y":..}  → queued for the XD Assist app
#   GET  /rig/next-cmd               → one queued command (the app polls this)
#   POST /rig/result  {"id":"..","ok":true,"data":{...}} (the app posts results)
#   GET  /rig/wait?id=..&timeout=25  → blocks until the result arrives
#
# File paths: "home/..." → $HOME/... ; "shared/..." → $HOME/storage/shared/...
# (termux-setup-storage must have been accepted once — command 1 does this.)
# "root" reads: if the phone has su (root), /exec can run "su -c '...'"
# and file paths starting with "abs/..." are used literally.
import base64
import json
import os
import queue
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

XD_KEY = os.environ.get("XD_KEY", "").strip()
PORT = int(os.environ.get("XD_PORT", "25570"))
HOME = os.path.expanduser("~")
SHARED = os.path.join(HOME, "storage", "shared")

RIG_QUEUE = queue.Queue()
RIG_RESULTS = {}          # id -> result dict
RIG_LOCK = threading.Lock()


def resolve(path):
    """Map bridge paths to real filesystem paths safely."""
    if not path:
        raise ValueError("empty path")
    if path == "home":
        return HOME
    if path.startswith("home/"):
        rest = path[len("home/"):]
        return os.path.normpath(os.path.join(HOME, rest))
    if path == "shared":
        return SHARED if os.path.isdir(SHARED) else HOME
    if path.startswith("shared/"):
        rest = path[len("shared/"):]
        return os.path.normpath(os.path.join(SHARED, rest))
    if path.startswith("abs/"):
        return os.path.normpath(path[len("abs/"):])
    raise ValueError("path must start with home/ shared/ or abs/")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ── plumbing ──────────────────────────────────────────────────────
    def log_message(self, fmt, *args):
        print("[%s] %s" % (time.strftime("%H:%M:%S"), fmt % args), flush=True)

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {"_raw": base64.b64encode(raw).decode()}

    def authorized(self):
        if not XD_KEY:
            return True   # no key configured (not recommended)
        return self.headers.get("X-Backend-Key", "").strip() == XD_KEY

    def query(self, name, default=None):
        import urllib.parse
        q = urllib.parse.urlparse(self.path).query
        for pair in q.split("&"):
            if "=" in pair:
                k, v = pair.split("=", 1)
                if k == name:
                    return urllib.parse.unquote(v)
        return default

    def do_GET(self):
        try:
            path = self.path.split("?")[0]
            if path == "/health":
                return self.send_json({"ok": True, "service": "xd-bridge",
                                       "whoami": os.environ.get("USER", ""),
                                       "cwd": os.getcwd()})
            if not self.authorized():
                return self.send_json({"ok": False, "error": "bad key"}, 403)
            if path == "/":
                return self.send_json({"ok": True, "service": "xd-bridge",
                                       "endpoints": ["/health", "/exec", "/fs/list",
                                                     "/fs/read", "/fs/write", "/fs/mkdir",
                                                     "/fs/delete", "/rig/queue",
                                                     "/rig/next-cmd", "/rig/result",
                                                     "/rig/wait"]})
            if path == "/fs/list":
                real = resolve(self.query("path", "shared"))
                entries = []
                for name in sorted(os.listdir(real)):
                    full = os.path.join(real, name)
                    try:
                        st = os.stat(full)
                        entries.append({"name": name, "dir": os.path.isdir(full),
                                        "size": st.st_size, "mtime": int(st.st_mtime)})
                    except Exception:
                        entries.append({"name": name, "dir": None, "size": 0, "mtime": 0})
                return self.send_json({"ok": True, "path": self.query("path"),
                                       "real": real, "entries": entries})
            if path == "/fs/read":
                real = resolve(self.query("path"))
                with open(real, "rb") as f:
                    data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if path == "/rig/next-cmd":
                try:
                    cmd = RIG_QUEUE.get(timeout=float(self.query("timeout", "0")))
                except queue.Empty:
                    return self.send_json({"wait": True})
                return self.send_json(cmd)
            if path == "/rig/wait":
                cid = self.query("id", "")
                deadline = time.time() + float(self.query("timeout", "25"))
                while time.time() < deadline:
                    with RIG_LOCK:
                        if cid in RIG_RESULTS:
                            return self.send_json(RIG_RESULTS.pop(cid))
                    time.sleep(0.15)
                return self.send_json({"ok": False, "error": "timeout waiting for the assist app "
                                      "(is it connected? is the accessibility service ON?)"})
            return self.send_json({"ok": False, "error": "unknown endpoint " + path}, 404)
        except Exception as e:
            return self.send_json({"ok": False, "error": str(e)}, 500)

    def do_POST(self):
        try:
            if not self.authorized():
                return self.send_json({"ok": False, "error": "bad key"}, 403)
            path = self.path.split("?")[0]
            body = self.body_json()
            if path == "/exec":
                cmd = body.get("cmd", "")
                timeout = int(body.get("timeout", 60))
                if not cmd:
                    return self.send_json({"ok": False, "error": "no cmd"}, 400)
                p = subprocess.run(["bash", "-lc", cmd], capture_output=True,
                                   text=True, timeout=timeout)
                return self.send_json({"ok": p.returncode == 0, "code": p.returncode,
                                       "out": p.stdout, "err": p.stderr})
            if path == "/fs/write":
                real = resolve(body.get("path", ""))
                data = base64.b64decode(body.get("content_b64", ""))
                os.makedirs(os.path.dirname(real), exist_ok=True)
                with open(real, "wb") as f:
                    f.write(data)
                return self.send_json({"ok": True, "size": len(data), "real": real})
            if path == "/fs/mkdir":
                real = resolve(body.get("path", ""))
                os.makedirs(real, exist_ok=True)
                return self.send_json({"ok": True, "real": real})
            if path == "/fs/delete":
                real = resolve(body.get("path", ""))
                if os.path.isdir(real):
                    shutil.rmtree(real)
                else:
                    os.remove(real)
                return self.send_json({"ok": True})
            if path == "/rig/queue":
                cid = body.get("id") or ("c%d" % int(time.time() * 1000))
                cmd = dict(body)
                cmd["id"] = cid
                with RIG_LOCK:
                    RIG_RESULTS.pop(cid, None)
                RIG_QUEUE.put(cmd)
                return self.send_json({"ok": True, "id": cid, "queued": True})
            if path == "/rig/result":
                cid = body.get("id", "")
                with RIG_LOCK:
                    RIG_RESULTS[cid] = body
                return self.send_json({"ok": True})
            return self.send_json({"ok": False, "error": "unknown endpoint " + path}, 404)
        except subprocess.TimeoutExpired:
            return self.send_json({"ok": False, "error": "command timed out"}, 504)
        except Exception as e:
            return self.send_json({"ok": False, "error": str(e)}, 500)


def main():
    if not XD_KEY:
        print("!! XD_KEY not set — the bridge will be OPEN to anyone on the tunnel!", flush=True)
    print("xd-bridge starting on 127.0.0.1:%d" % PORT, flush=True)
    print("HOME=%s SHARED=%s" % (HOME, SHARED), flush=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.serve_forever()


if __name__ == "__main__":
    main()
