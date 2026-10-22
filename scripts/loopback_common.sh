#!/usr/bin/env bash
# loopback_common.sh — shared helpers for scripts/loopback-<engine>.sh (loop0 matrix)
#
# Rules inherited from test/e2e style + dns-tunnel-engineering-v2 skill notes:
#   * every child process is spawned once, its exact PID recorded in LB_PIDS,
#     killed by exact PID only. NEVER pkill -f with a seeded pattern.
#   * mock recursive resolver = dnspython-free python UDP forwarder on
#     127.0.0.1:5399 relaying datagrams to the engine server (also loopback).
#   * backend = plain python HTTP echo; PASS is cmp on POST body bytes, not http code.
#   * loopback only: every bind/host literal is 127.0.0.1.

LB_PIDS=()
# LB_WORK is set by the engine script before it sources this file.

lb_spawn() { # lb_spawn <cwd> <logfile> <argv...>
  local dir="$1" log="$2"; shift 2
  ( cd "$dir" && exec env "$@" ) >>"$log" 2>&1 &
  local pid=$!
  LB_PIDS+=("$pid")
  echo "[spawn] pid=$pid $*"
}

lb_cleanup() { # kill by exact PID, two passes
  for pid in "${LB_PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null
  done
  sleep 0.5
  for pid in "${LB_PIDS[@]:-}"; do
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
  done
  wait 2>/dev/null
}

lb_all_running() {
  for pid in "${LB_PIDS[@]:-}"; do
    kill -0 "$pid" 2>/dev/null || { echo "[dead] pid=$pid exited early"; return 1; }
  done
  return 0
}

lb_wait_listening() { # lb_wait_listening <tcp|udp> <port> <max_seconds>
  local proto="$1" port="$2" max="$3" i flag
  case "$proto" in tcp) flag=t ;; udp) flag=u ;; *) return 2 ;; esac
  for i in $(seq 1 "$max"); do
    ss -${flag}ln 2>/dev/null | grep -q "[:.]${port}\b" && return 0
    sleep 1
  done
  return 1
}

# lb_write_helpers <workdir> <tag>
# writes mock_resolver.py + http_echo.py (plain stdlib python) into workdir
lb_write_helpers() { # <workdir> <tag>
  local d="$1" tag="$2"
  cat > "$d/mock_resolver.py" <<'EOF'
import socket, sys, threading
listen_port = int(sys.argv[1]); upstream = ("127.0.0.1", int(sys.argv[2]))
ln = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ln.bind(("127.0.0.1", listen_port)); ln.settimeout(1.0)
print("mock resolver ready", flush=True)

def handle(buf, peer):
    # one ephemeral upstream socket per datagram: the server replies to this
    # transaction's socket, and we DRAIN it (tunnel responses arrive as many
    # fragments) until 300 ms of silence, then forward every fragment back.
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0)); s.settimeout(0.3)
    try:
        s.sendto(buf, upstream)
        while True:
            try:
                resp, _ = s.recvfrom(65535)
            except socket.timeout:
                break
            ln.sendto(resp, peer)
    except OSError:
        pass
    finally:
        s.close()

while True:
    try:
        buf, peer = ln.recvfrom(65535)
    except socket.timeout:
        continue
    threading.Thread(target=handle, args=(buf, peer), daemon=True).start()
EOF
  cat > "$d/http_echo.py" <<EOF
from http.server import HTTPServer, BaseHTTPRequestHandler
import sys
port, tag = int(sys.argv[1]), "$tag"
bind = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n)
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *a):
        print(tag, fmt % a, flush=True)
HTTPServer((bind, port), H).serve_forever()
EOF
}

# lb_start_http_echo <port> [tag] [bind_addr]
lb_start_http_echo() {
  local port="$1" tag="${2:-HTTP-ECHO}" bind="${3:-127.0.0.1}"
  lb_spawn "$LB_WORK" "$LB_WORK/http-echo.log" \
    python3 "$LB_WORK/http_echo.py" "$port" "$tag" "$bind"
}

# lb_start_mock_resolver <listen_port> <upstream_port>
lb_start_mock_resolver() {
  local listen_port="$1" upstream_port="$2"
  lb_spawn "$LB_WORK" "$LB_WORK/mock-resolver.log" \
    python3 "$LB_WORK/mock_resolver.py" "$listen_port" "$upstream_port"
}

# Temp loopback alias (sudo, transient, on `lo`): some vendored engines hard-block
# private/loopback SOCKS5 targets in validateSOCKSTargetHost with no config knob;
# binding the backend on a global-unicast alias of the SAME host keeps all traffic
# on the host (kernel routes it over lo). never touches any non-loopback alt.
LB_ALIAS_IP="${LB_ALIAS_IP:-198.51.100.77}"
LB_ALIAS_ADDED=0
lb_lo_alias_add() {
  ip -4 addr show dev lo | grep -q "$LB_ALIAS_IP " && { echo "[alias] $LB_ALIAS_IP already on lo"; return 0; }
  if sudo -n ip addr add "$LB_ALIAS_IP/32" dev lo 2>/dev/null; then
    LB_ALIAS_ADDED=1
    echo "[alias] added $LB_ALIAS_IP/32 on lo"
    return 0
  fi
  echo "[alias] FAILED to add (sudo -n ip addr add)"
  return 1
}
lb_lo_alias_del() {
  [ "${LB_ALIAS_ADDED:-0}" = 1 ] && sudo -n ip addr del "$LB_ALIAS_IP/32" dev lo 2>/dev/null
  LB_ALIAS_ADDED=0
}

# standard cleanup used by every engine harness: exact-PID kills first, then alias
lb_cleanup_all() {
  lb_cleanup
  lb_lo_alias_del
}

# lb_assert_clean <tag> — after cleanup, prove our ports are free (report + rc)
lb_assert_clean() { # <tag> <port...>
  local tag="$1"; shift
  local bad=0
  for port in "$@"; do
    if ss -tln 2>/dev/null | grep -q "[:.]${port}\b" || ss -uln 2>/dev/null | grep -q "[:.]${port}\b"; then
      echo "[$tag] STRAY LISTENER on port $port"
      ss -tlnp 2>/dev/null | grep "[:.]${port}\b" || true
      ss -ulnp 2>/dev/null | grep "[:.]${port}\b" || true
      bad=1
    fi
  done
  return $bad
}

# lb_loop <engine_tag> <socks_addr> <target_url> <iterations> <max_time> <workdir>
# generates a unique payload per iteration, POSTs it through the proxy and
# requires a byte-exact cmp against the request body every time.
lb_loop() {
  local tag="$1" socks="$2" url="$3" iters="$4" max_time="$5" work="$6"
  local pass=0 i body out rc
  for i in $(seq 1 "$iters"); do
    body="$work/req-$i.bin"; out="$work/resp-$i.bin"
    { printf 'LOOP0|%s|%03d|' "$tag" "$i"; head -c 1400 /dev/urandom; } > "$body"
    curl -sS --socks5-hostname "$socks" --max-time "$max_time" -X POST \
      --data-binary "@$body" -o "$out" "$url" || rc=$?
    if [ "${rc:-0}" -ne 0 ]; then
      echo "iter $i FAIL curl_exit=${rc:-?}"
      return 1
    fi
    if cmp -s "$body" "$out"; then
      echo "iter $i PASS bytes=$(wc -c < "$out") sha=$(sha256sum "$out" | cut -c1-16)"
      pass=$((pass + 1))
    else
      echo "iter $i FAIL cmp mismatch bytes=$(wc -c < "$out") expected=$(wc -c < "$body")"
      return 1
    fi
  done
  echo "$tag: $pass/$iters byte-exact"
  [ "$pass" -eq "$iters" ]
}
