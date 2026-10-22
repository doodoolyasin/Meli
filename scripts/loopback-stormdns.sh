#!/usr/bin/env bash
# loopback-stormdns.sh — vendored upstream engine "stormdns" loopback harness (loop0 matrix)
#
# Stack (all 127.0.0.1):
#   curl --socks5-hostname 127.0.0.1:18080
#     -> meli-core client SOCKS5
#     -> resolver 127.0.0.1:5399 (mock forwarder busyout via loopback_common.sh)
#     -> meli-core server UDP :5300
#     -> server SOCKS5 dial 127.0.0.1:19080 (python HTTP echo backend)
# Acceptance: 5 consecutive POST bodies survive BYTE-EXACT (cmp).
set -u
set -o pipefail

NAME="stormdns"
CLIENT_BIN="${LB_CLIENT_BIN:-/tmp/lb-stormdns-client}"
SERVER_BIN="${LB_SERVER_BIN:-/tmp/lb-stormdns-server}"
DOMAIN="w.loop0.test"
DNS_PORT=5301
RES_PORT=5399
SOCKS_PORT=18081
HTTP_PORT=19081
ITERS=5
MAX_TIME=60

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LB_WORK="$(mktemp -d /tmp/loop0-${NAME}.XXXXXX)"
export LB_WORK

source "$ROOT/scripts/loopback_common.sh"

echo "=== loopback $NAME (workdir $LB_WORK) ==="

cat > "$LB_WORK/encrypt_key.txt" <<'EOF'
0123456789abcdef0123456789abcdef
EOF

cat > "$LB_WORK/server.toml" <<EOF
PROTOCOL_TYPE = "SOCKS5"
UDP_HOST = "127.0.0.1"
UDP_PORT = $DNS_PORT
DOMAIN = ["$DOMAIN"]
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef"
UDP_READERS = 2
DNS_REQUEST_WORKERS = 2
DEFERRED_SESSION_WORKERS = 1
DNS_UPSTREAM_SERVERS = ["1.1.1.1:53"]  # unused: curl targets are 127.0.0.1 IPs
DNS_UPSTREAM_TIMEOUT = 4.0
SOCKS_CONNECT_TIMEOUT = 60.0
ALLOW_PRIVATE_TARGETS = true
SESSION_TIMEOUT_SECONDS = 300.0
LOG_LEVEL = "INFO"
CONFIG_VERSION = "10"
EOF

cat > "$LB_WORK/client.toml" <<EOF
PROTOCOL_TYPE = "SOCKS5"
DOMAINS = ["$DOMAIN"]
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = $SOCKS_PORT
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef"
RESOLVER_BALANCING_STRATEGY = 1
UPLOAD_PACKET_DUPLICATION_COUNT = 1
DOWNLOAD_PACKET_DUPLICATION_COUNT = 1
SETUP_PACKET_DUPLICATION_COUNT = 1
RECHECK_INACTIVE_SERVERS_ENABLED = false
AUTO_REMOVE_LOW_MTU_SERVERS = false
LOCAL_DNS_ENABLED = false
MTU_TEST_RETRIES = 2
MTU_TEST_TIMEOUT = 2.0
MTU_TEST_PARALLELISM = 8
LOG_LEVEL = "INFO"
CONFIG_VERSION = "1"
EOF

printf '127.0.0.1:%s\n' "$RES_PORT" > "$LB_WORK/client_resolvers.txt"

lb_write_helpers "$LB_WORK" "$NAME"

cleanup() { lb_cleanup_all; }
trap cleanup EXIT

lb_lo_alias_add || { echo "stormdns: lo alias unavailable"; exit 77; }
lb_start_http_echo "$HTTP_PORT" "stormdns-echo" "$LB_ALIAS_IP" "stormdns-echo"
lb_start_mock_resolver "$RES_PORT" "$DNS_PORT"
lb_spawn "$LB_WORK" "$LB_WORK/server.log" "$SERVER_BIN" -config server.toml
lb_spawn "$LB_WORK" "$LB_WORK/client.log" "$CLIENT_BIN" -config client.toml -resolvers client_resolvers.txt

lb_wait_listening tcp "$HTTP_PORT" 20 || { echo "$NAME: http backend not listening"; tail -20 "$LB_WORK/http-echo.log"; exit 1; }
lb_wait_listening udp "$DNS_PORT" 20 || { echo "$NAME: tunnel server not listening"; tail -20 "$LB_WORK/server.log"; exit 1; }
lb_wait_listening tcp "$SOCKS_PORT" 90 || { echo "$NAME: socks not listening"; tail -40 "$LB_WORK/client.log"; exit 1; }

sleep 5  # MTU probes + handshake settle

if lb_loop "$NAME" "127.0.0.1:$SOCKS_PORT" "http://$LB_ALIAS_IP:$HTTP_PORT/loop0" "$ITERS" "$MAX_TIME" "$LB_WORK" \
   && lb_all_running; then
  echo "$NAME: ACCEPTED ($ITERS/$ITERS byte-exact on loopback)"
  RC=0
else
  echo "$NAME: FAIL"
  RC=1
fi

exit $RC
