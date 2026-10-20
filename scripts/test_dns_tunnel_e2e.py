#!/usr/bin/env python3
"""
Automated End-to-End Test for Meli / MasterDnsVPN Primary DNS Tunnel Path.
Verifies:
  1. Backend TCP echo target server.
  2. meli-server UDP listener, domain matching, XOR encryption, session state machine.
  3. meli-client MTU probes, session handshake, local TCP proxy listener.
  4. Full round-trip bidirectional data transmission across DNS tunnel framing.
"""

import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

stop_event = threading.Event()

def handle_echo_client(conn, addr):
    try:
        conn.settimeout(15.0)
        while not stop_event.is_set():
            data = conn.recv(4096)
            if not data:
                break
            conn.sendall(data)
    except Exception:
        pass
    finally:
        conn.close()

def echo_server_thread(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port))
    s.listen(10)
    s.settimeout(0.5)
    while not stop_event.is_set():
        try:
            conn, addr = s.accept()
            t = threading.Thread(target=handle_echo_client, args=(conn, addr), daemon=True)
            t.start()
        except socket.timeout:
            continue
        except Exception:
            break
    s.close()

def main():
    test_dir = tempfile.mkdtemp(prefix="meli_e2e_")
    echo_port = 19888
    dns_port = 19553
    client_proxy_port = 19999
    key = "0123456789abcdef0123456789abcdef"

    try:
        # Pre-seed encryption key
        key_file = os.path.join(test_dir, "encrypt_key.txt")
        with open(key_file, "w") as f:
            f.write(f"{key}\n")

        # Server config
        server_cfg_file = os.path.join(test_dir, "server.toml")
        with open(server_cfg_file, "w") as f:
            f.write(f"""
PROTOCOL_TYPE = "TCP"
UDP_HOST = "127.0.0.1"
UDP_PORT = {dns_port}
DOMAIN = ["v.e2e.test"]
FORWARD_IP = "127.0.0.1"
FORWARD_PORT = {echo_port}
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "{key}"
UDP_READERS = 1
DNS_REQUEST_WORKERS = 2
DEFERRED_SESSION_WORKERS = 1
""")

        # Client config
        client_cfg_file = os.path.join(test_dir, "client.toml")
        with open(client_cfg_file, "w") as f:
            f.write(f"""
PROTOCOL_TYPE = "TCP"
DOMAINS = ["v.e2e.test"]
LISTEN_IP = "127.0.0.1"
LISTEN_PORT = {client_proxy_port}
DATA_ENCRYPTION_METHOD = 1
ENCRYPTION_KEY = "{key}"
AUTO_REMOVE_LOW_MTU_SERVERS = false
MTU_TEST_RETRIES = 1
MTU_TEST_TIMEOUT = 1.0
RESOLVER_BALANCING_STRATEGY = 1
RX_TX_WORKERS = 1
""")

        resolvers_file = os.path.join(test_dir, "resolvers.txt")
        with open(resolvers_file, "w") as f:
            f.write(f"127.0.0.1:{dns_port}\n")

        # Build binaries if missing
        server_bin = "/tmp/meli-server"
        client_bin = "/tmp/meli-client"
        if not os.path.exists(server_bin) or not os.path.exists(client_bin):
            print("[E2E] Compiling binaries...")
            subprocess.check_call(["go", "build", "-o", server_bin, "./cmd/server"])
            subprocess.check_call(["go", "build", "-o", client_bin, "./cmd/client"])

        # Start Echo Server
        t = threading.Thread(target=echo_server_thread, args=(echo_port,), daemon=True)
        t.start()

        # Start Server
        server_proc = subprocess.Popen(
            [server_bin, "-config", server_cfg_file],
            cwd=test_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True
        )
        time.sleep(1.0)
        if server_proc.poll() is not None:
            print("[E2E] Server failed to start:", server_proc.stdout.read())
            sys.exit(1)

        # Start Client
        client_proc = subprocess.Popen(
            [client_bin, "-config", client_cfg_file, "-resolvers", resolvers_file],
            cwd=test_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True
        )

        client_ready = False
        sock = None
        for _ in range(40):
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(15.0)
                sock.connect(("127.0.0.1", client_proxy_port))
                client_ready = True
                break
            except Exception:
                time.sleep(0.5)

        if not client_ready or sock is None:
            print("[E2E] Client proxy listener never became ready")
            stop_event.set()
            client_proc.terminate()
            server_proc.terminate()
            sys.exit(1)

        payload = b"PING_END_TO_END_DNS_TUNNEL_PAYLOAD_1234567890"
        sock.sendall(payload)

        received = b""
        start_time = time.time()
        while len(received) < len(payload) and (time.time() - start_time) < 15.0:
            sock.settimeout(3.0)
            chunk = sock.recv(1024)
            if not chunk:
                break
            received += chunk
        sock.close()

        stop_event.set()
        time.sleep(0.5)

        client_proc.terminate()
        server_proc.terminate()
        client_proc.wait(timeout=3)
        server_proc.wait(timeout=3)

        if received == payload:
            print(">>> SUCCESS: END-TO-END DNS TUNNEL PATH VERIFIED! <<<")
            sys.exit(0)
        else:
            print(f">>> FAILED: Expected {payload}, got {received} <<<")
            sys.exit(1)

    finally:
        shutil.rmtree(test_dir, ignore_errors=True)

if __name__ == "__main__":
    main()
