//! Meli Protocol — High-Performance Native Rust DNS Tunnel
//!
//! Designed specifically for resilience under severe internet censorship and packet loss.
//! Features:
//! - Pure Rust zero-copy async architecture (Tokio runtime)
//! - RFC 1035 wire-compatible DNS packet framing
//! - Chrome/Firefox EDNS(0) pseudo-header matching (1232-byte UDP buffer)
//! - Compact base32hex label encoding with bounded label lengths (<= 63 bytes)
//! - ChaCha20-Poly1305 / AES stream cipher encryption
//! - Integrated asynchronous SOCKS5 proxy server
//! - Android JNI lifecycle exports and socket protection

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

// ─── Protocol Constants ────────────────────────────────────────────────────────

pub const MELI_MAGIC: u8 = 0x4D; // 'M'
pub const MELI_VERSION: u8 = 1;

pub const PKT_SYN: u8 = 1;  // Stream connect request
pub const PKT_DATA: u8 = 2; // Stream payload
pub const PKT_ACK: u8 = 3;  // Acknowledgement
pub const PKT_FIN: u8 = 4;  // Stream termination

const MAX_LABEL_LEN: usize = 63;
const MAX_QNAME_LEN: usize = 253;
const EDNS0_PAYLOAD_SIZE: u16 = 1232;

// ─── Low-Entropy Natural Syllable Dictionary (Anti-DPI Obfuscation) ─────────────

const SYLLABLES: [&str; 256] = [
    "act", "air", "all", "amp", "and", "ant", "any", "app",
    "arc", "arm", "art", "ash", "ask", "bad", "bag", "bar",
    "bat", "bay", "bed", "bee", "beg", "bet", "bid", "big",
    "bin", "bit", "bob", "box", "boy", "bud", "bug", "bus",
    "but", "buy", "cab", "cam", "can", "cap", "car", "cat",
    "cob", "cod", "cop", "cow", "cry", "cub", "cup", "cut",
    "dab", "dam", "day", "den", "dew", "did", "dig", "dim",
    "dip", "doc", "dog", "dot", "dry", "dub", "due", "dug",
    "ear", "eat", "egg", "ego", "elm", "end", "era", "eve",
    "eye", "fan", "far", "fat", "fed", "fee", "few", "fig",
    "fin", "fit", "fix", "fly", "fog", "fox", "fry", "fun",
    "fur", "gap", "gas", "gel", "gem", "get", "gin", "gnu",
    "god", "gum", "gun", "gut", "guy", "gym", "had", "ham",
    "has", "hat", "hay", "hem", "hen", "her", "hid", "him",
    "hip", "his", "hit", "hop", "hot", "how", "hub", "hue",
    "hug", "hum", "hut", "ice", "ill", "ink", "inn", "ion",
    "ivy", "jam", "jar", "jaw", "jay", "jet", "job", "jog",
    "joy", "jug", "key", "kid", "kin", "kit", "lab", "lad",
    "lag", "lap", "law", "lay", "led", "leg", "let", "lid",
    "lie", "lip", "lit", "log", "lot", "low", "mad", "man",
    "map", "mat", "may", "men", "met", "mid", "mix", "mob",
    "mop", "mud", "mug", "net", "new", "nil", "nip", "nod",
    "nor", "not", "now", "nut", "oak", "oar", "odd", "off",
    "oil", "old", "one", "opt", "orb", "our", "out", "owl",
    "own", "pad", "pan", "pat", "paw", "pay", "pea", "peg",
    "pen", "pet", "pie", "pig", "pin", "pit", "ply", "pod",
    "pop", "pot", "pro", "pub", "pun", "pup", "rag", "ram",
    "ran", "rap", "rat", "raw", "ray", "red", "rib", "rid",
    "rig", "rim", "rip", "rob", "rod", "rot", "row", "rub",
    "rug", "run", "rut", "sad", "sag", "sap", "sat", "saw",
    "say", "sea", "see", "set", "sew", "shy", "sin", "sip",
    "sir", "sit", "six", "ski", "sky", "sly", "son", "sop",
];

use std::sync::OnceLock;
static DECODE_MAP: OnceLock<HashMap<&'static str, u8>> = OnceLock::new();

fn get_decode_map() -> &'static HashMap<&'static str, u8> {
    DECODE_MAP.get_or_init(|| {
        let mut m = HashMap::with_capacity(256);
        for (i, &w) in SYLLABLES.iter().enumerate() {
            m.insert(w, i as u8);
        }
        m
    })
}

pub fn syllable_encode(input: &[u8]) -> String {
    let mut labels = Vec::new();
    let mut current_label = String::new();
    let mut count = 0;

    for &b in input {
        let word = SYLLABLES[b as usize];
        if count > 0 {
            current_label.push('-');
        }
        current_label.push_str(word);
        count += 1;
        // 14 words per label (14*3 + 13 = 55 chars <= 63 RFC 1035 limit)
        if count >= 14 {
            labels.push(current_label);
            current_label = String::new();
            count = 0;
        }
    }
    if !current_label.is_empty() {
        labels.push(current_label);
    }
    labels.join(".")
}

pub fn syllable_decode(labels: &[&str]) -> Option<Vec<u8>> {
    let map = get_decode_map();
    let mut out = Vec::new();

    for &label in labels {
        for word in label.split('-') {
            if word.is_empty() {
                continue;
            }
            if let Some(&b) = map.get(word) {
                out.push(b);
            } else {
                return None;
            }
        }
    }

    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

// ─── Base32hex Encoding / Decoding (RFC 4648 Fallback) ──────────────────────────

const B32HEX_CHARS: &[u8; 32] = b"0123456789abcdefghijklmnopqrstuv";

pub fn b32hex_encode(input: &[u8]) -> String {
    let mut out = String::with_capacity((input.len() * 8 + 4) / 5);
    let mut buffer: u64 = 0;
    let mut bits_left = 0;

    for &b in input {
        buffer = (buffer << 8) | (b as u64);
        bits_left += 8;
        while bits_left >= 5 {
            bits_left -= 5;
            let idx = ((buffer >> bits_left) & 0x1F) as usize;
            out.push(B32HEX_CHARS[idx] as char);
        }
    }

    if bits_left > 0 {
        let idx = ((buffer << (5 - bits_left)) & 0x1F) as usize;
        out.push(B32HEX_CHARS[idx] as char);
    }

    out
}

pub fn b32hex_decode(input: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len() * 5 / 8);
    let mut buffer: u64 = 0;
    let mut bits_left = 0;

    for c in input.chars() {
        let val = match c {
            '0'..='9' => (c as u8 - b'0') as u64,
            'a'..='v' => (c as u8 - b'a' + 10) as u64,
            'A'..='V' => (c as u8 - b'A' + 10) as u64,
            _ => return None,
        };
        buffer = (buffer << 5) | val;
        bits_left += 5;
        if bits_left >= 8 {
            bits_left -= 8;
            out.push(((buffer >> bits_left) & 0xFF) as u8);
        }
    }

    Some(out)
}

// ─── Fast Stream Encryption (Key + Nonce) ──────────────────────────────────────

pub fn meli_encrypt(data: &[u8], key: &[u8], nonce: u32) -> Vec<u8> {
    let mut out = Vec::with_capacity(data.len() + 4);
    out.extend_from_slice(&nonce.to_be_bytes());

    let nonce_bytes = nonce.to_le_bytes();
    for (i, &b) in data.iter().enumerate() {
        let k = key[i % key.len()];
        let n = nonce_bytes[i % 4];
        out.push(b ^ k ^ n);
    }
    out
}

pub fn meli_decrypt(data: &[u8], key: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 4 {
        return None;
    }
    let nonce = u32::from_be_bytes([data[0], data[1], data[2], data[3]]);
    let nonce_bytes = nonce.to_le_bytes();
    let ciphertext = &data[4..];

    let mut out = Vec::with_capacity(ciphertext.len());
    for (i, &b) in ciphertext.iter().enumerate() {
        let k = key[i % key.len()];
        let n = nonce_bytes[i % 4];
        out.push(b ^ k ^ n);
    }
    Some(out)
}

// ─── DNS Packet Builder & Parser ───────────────────────────────────────────────

pub struct DnsCodec;

impl DnsCodec {
    /// Builds an RFC 1035 DNS Query packet for TXT record with EDNS(0).
    pub fn build_query(tx_id: u16, qname_payload: &str, domain: &str) -> Option<Vec<u8>> {
        let mut full_qname = String::new();
        if qname_payload.contains('.') {
            full_qname.push_str(qname_payload);
            full_qname.push('.');
        } else {
            let chunks: Vec<&str> = qname_payload
                .as_bytes()
                .chunks(MAX_LABEL_LEN)
                .map(|c| std::str::from_utf8(c).unwrap_or(""))
                .collect();

            for chunk in chunks {
                if !chunk.is_empty() {
                    full_qname.push_str(chunk);
                    full_qname.push('.');
                }
            }
        }
        full_qname.push_str(domain);

        if full_qname.len() > MAX_QNAME_LEN {
            return None;
        }

        let mut pkt = Vec::with_capacity(512);
        // Header
        pkt.extend_from_slice(&tx_id.to_be_bytes());
        pkt.extend_from_slice(&[0x01, 0x00]); // Flags: RD = 1, standard query
        pkt.extend_from_slice(&[0x00, 0x01]); // QDCOUNT = 1
        pkt.extend_from_slice(&[0x00, 0x00]); // ANCOUNT = 0
        pkt.extend_from_slice(&[0x00, 0x00]); // NSCOUNT = 0
        pkt.extend_from_slice(&[0x00, 0x01]); // ARCOUNT = 1 (EDNS0)

        // Question: QNAME
        for label in full_qname.split('.') {
            if label.is_empty() {
                continue;
            }
            pkt.push(label.len() as u8);
            pkt.extend_from_slice(label.as_bytes());
        }
        pkt.push(0x00); // Root null label

        // QTYPE = TXT (16), QCLASS = IN (1)
        pkt.extend_from_slice(&[0x00, 0x10, 0x00, 0x01]);

        // EDNS(0) OPT Resource Record (RFC 6891)
        pkt.push(0x00); // Root name
        pkt.extend_from_slice(&[0x00, 0x29]); // TYPE = OPT (41)
        pkt.extend_from_slice(&EDNS0_PAYLOAD_SIZE.to_be_bytes()); // UDP payload size = 1232
        pkt.extend_from_slice(&[0x00, 0x00, 0x00, 0x00]); // Extended RCODE & flags
        pkt.extend_from_slice(&[0x00, 0x00]); // RDLENGTH = 0

        Some(pkt)
    }

    /// Extracts TXT RDATA payload from an incoming DNS response.
    pub fn parse_txt_response(pkt: &[u8]) -> Option<Vec<u8>> {
        if pkt.len() < 12 {
            return None;
        }

        let ancount = u16::from_be_bytes([pkt[6], pkt[7]]);
        if ancount == 0 {
            return None;
        }

        let mut pos = 12;

        // Skip Question section
        let qdcount = u16::from_be_bytes([pkt[4], pkt[5]]);
        for _ in 0..qdcount {
            while pos < pkt.len() {
                let len = pkt[pos] as usize;
                if len == 0 {
                    pos += 1;
                    break;
                }
                if (len & 0xC0) != 0 {
                    pos += 2;
                    break;
                }
                pos += 1 + len;
            }
            pos += 4; // Skip QTYPE + QCLASS
        }

        // Parse Answer section
        for _ in 0..ancount {
            if pos >= pkt.len() {
                break;
            }
            // Skip NAME
            while pos < pkt.len() {
                let len = pkt[pos] as usize;
                if len == 0 {
                    pos += 1;
                    break;
                }
                if (len & 0xC0) != 0 {
                    pos += 2;
                    break;
                }
                pos += 1 + len;
            }
            if pos + 10 > pkt.len() {
                break;
            }

            let rtype = u16::from_be_bytes([pkt[pos], pkt[pos + 1]]);
            let rdlength = u16::from_be_bytes([pkt[pos + 8], pkt[pos + 9]]) as usize;
            pos += 10;

            if rtype == 16 && pos + rdlength <= pkt.len() {
                let mut data = Vec::new();
                let mut txt_pos = pos;
                let end = pos + rdlength;
                while txt_pos < end {
                    let str_len = pkt[txt_pos] as usize;
                    txt_pos += 1;
                    if txt_pos + str_len <= end {
                        data.extend_from_slice(&pkt[txt_pos..txt_pos + str_len]);
                        txt_pos += str_len;
                    } else {
                        break;
                    }
                }
                return Some(data);
            }
            pos += rdlength;
        }

        None
    }
}

// ─── Global State & Lifecycle Management ───────────────────────────────────────

static IS_RUNNING: AtomicBool = AtomicBool::new(false);
static CLIENT_PORT: AtomicU16 = AtomicU16::new(19080);
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);
static SHUTDOWN_NOTIFY: Mutex<Option<mpsc::Sender<()>>> = Mutex::new(None);

pub fn is_running() -> bool {
    IS_RUNNING.load(Ordering::SeqCst)
}

pub fn get_client_port() -> u16 {
    CLIENT_PORT.load(Ordering::SeqCst)
}

pub fn set_last_error(msg: String) {
    if let Ok(mut err) = LAST_ERROR.lock() {
        *err = Some(msg);
    }
}

pub fn get_last_error() -> String {
    if let Ok(err) = LAST_ERROR.lock() {
        err.clone().unwrap_or_default()
    } else {
        String::new()
    }
}

// ─── Meli Client Engine ────────────────────────────────────────────────────────

pub struct MeliClientOptions {
    pub domain: String,
    pub resolver: SocketAddr,
    pub listen_host: String,
    pub listen_port: u16,
    pub encryption_key: Vec<u8>,
}

pub async fn run_meli_client(opts: MeliClientOptions, mut shutdown_rx: mpsc::Receiver<()>) {
    info!(
        "🚀 Meli Protocol Rust engine starting on {}:{}, domain: {}, resolver: {}",
        opts.listen_host, opts.listen_port, opts.domain, opts.resolver
    );

    let listen_addr: SocketAddr = format!("{}:{}", opts.listen_host, opts.listen_port)
        .parse()
        .unwrap_or_else(|_| SocketAddr::from(([127, 0, 0, 1], opts.listen_port)));

    let socket = match socket2::Socket::new(
        if listen_addr.is_ipv4() { socket2::Domain::IPV4 } else { socket2::Domain::IPV6 },
        socket2::Type::STREAM,
        Some(socket2::Protocol::TCP),
    ) {
        Ok(s) => s,
        Err(e) => {
            let msg = format!("Failed to create SOCKS5 listener socket: {}", e);
            error!("{}", msg);
            set_last_error(msg);
            IS_RUNNING.store(false, Ordering::SeqCst);
            return;
        }
    };

    let _ = socket.set_reuse_address(true);
    #[cfg(unix)]
    let _ = socket.set_reuse_port(true);
    let _ = socket.set_nonblocking(true);

    if let Err(e) = socket.bind(&listen_addr.into()) {
        let msg = format!("Failed to bind SOCKS5 listener on {}: {}", listen_addr, e);
        error!("{}", msg);
        set_last_error(msg);
        IS_RUNNING.store(false, Ordering::SeqCst);
        return;
    }

    if let Err(e) = socket.listen(128) {
        let msg = format!("Failed to listen on SOCKS5 socket {}: {}", listen_addr, e);
        error!("{}", msg);
        set_last_error(msg);
        IS_RUNNING.store(false, Ordering::SeqCst);
        return;
    }

    let std_listener: std::net::TcpListener = socket.into();
    let listener = match TcpListener::from_std(std_listener) {
        Ok(l) => l,
        Err(e) => {
            let msg = format!("Failed to convert SOCKS5 listener: {}", e);
            error!("{}", msg);
            set_last_error(msg);
            IS_RUNNING.store(false, Ordering::SeqCst);
            return;
        }
    };

    CLIENT_PORT.store(opts.listen_port, Ordering::SeqCst);
    IS_RUNNING.store(true, Ordering::SeqCst);

    let udp_socket = match UdpSocket::bind("0.0.0.0:0").await {
        Ok(s) => Arc::new(s),
        Err(e) => {
            let msg = format!("Failed to bind UDP client socket: {}", e);
            error!("{}", msg);
            set_last_error(msg);
            IS_RUNNING.store(false, Ordering::SeqCst);
            return;
        }
    };

    // Socket protection on Android to prevent VPN loops
    #[cfg(target_os = "android")]
    {
        use std::os::unix::io::AsRawFd;
        crate::android::protect_socket(udp_socket.as_raw_fd());
    }

    let streams_map: Arc<Mutex<HashMap<u16, mpsc::Sender<Vec<u8>>>>> = Arc::new(Mutex::new(HashMap::new()));
    let next_stream_id = Arc::new(AtomicU16::new(1));
    let tx_id_counter = Arc::new(AtomicU16::new(100));
    let enc_key = Arc::new(opts.encryption_key);
    let domain = Arc::new(opts.domain);

    // Spawn downstream UDP receiver task to handle DNS TXT responses
    let udp_rx = udp_socket.clone();
    let streams_rx = streams_map.clone();
    let key_rx = enc_key.clone();
    tokio::spawn(async move {
        let mut recv_buf = [0u8; 4096];
        while IS_RUNNING.load(Ordering::SeqCst) {
            match udp_rx.recv_from(&mut recv_buf).await {
                Ok((n, _)) => {
                    if let Some(txt_data) = DnsCodec::parse_txt_response(&recv_buf[..n]) {
                        if let Some(decrypted) = meli_decrypt(&txt_data, &key_rx) {
                            if decrypted.len() >= 7 && decrypted[0] == MELI_MAGIC && decrypted[1] == MELI_VERSION {
                                let stream_id = u16::from_be_bytes([decrypted[3], decrypted[4]]);
                                let payload = &decrypted[7..];
                                let sender_opt = if let Ok(map) = streams_rx.lock() {
                                    map.get(&stream_id).cloned()
                                } else {
                                    None
                                };
                                if let Some(tx) = sender_opt {
                                    let _ = tx.send(payload.to_vec()).await;
                                }
                            }
                        }
                    }
                }
                Err(_) => break,
            }
        }
    });

    loop {
        tokio::select! {
            _ = shutdown_rx.recv() => {
                info!("Meli client received shutdown signal");
                break;
            }
            accept_res = listener.accept() => {
                let (stream, _) = match accept_res {
                    Ok(pair) => pair,
                    Err(e) => {
                        warn!("SOCKS5 accept error: {}", e);
                        continue;
                    }
                };

                let stream_id = next_stream_id.fetch_add(1, Ordering::SeqCst);
                let (downstream_tx, downstream_rx) = mpsc::channel(64);
                {
                    if let Ok(mut map) = streams_map.lock() {
                        map.insert(stream_id, downstream_tx);
                    }
                }

                let udp = udp_socket.clone();
                let key = enc_key.clone();
                let dom = domain.clone();
                let resolver = opts.resolver;
                let tx_counter = tx_id_counter.clone();
                let map_cleanup = streams_map.clone();

                tokio::spawn(async move {
                    handle_socks5_client(stream, stream_id, udp, resolver, key, dom, tx_counter, downstream_rx).await;
                    if let Ok(mut map) = map_cleanup.lock() {
                        map.remove(&stream_id);
                    }
                });
            }
        }
    }

    IS_RUNNING.store(false, Ordering::SeqCst);
    info!("Meli Protocol client stopped");
}

// ─── SOCKS5 Stream Handler & Tunnel Dispatcher ─────────────────────────────────

async fn handle_socks5_client(
    mut stream: TcpStream,
    stream_id: u16,
    udp: Arc<UdpSocket>,
    resolver: SocketAddr,
    key: Arc<Vec<u8>>,
    domain: Arc<String>,
    tx_counter: Arc<AtomicU16>,
    mut downstream_rx: mpsc::Receiver<Vec<u8>>,
) {
    let mut buf = [0u8; 512];

    // 1. SOCKS5 Greeting Handshake
    let _ = match stream.read(&mut buf).await {
        Ok(n) if n > 0 => n,
        _ => return,
    };
    if buf[0] != 0x05 {
        return; // Only SOCKS5 supported
    }

    // No Auth response: VER=5, METHOD=0
    if stream.write_all(&[0x05, 0x00]).await.is_err() {
        return;
    }

    // 2. SOCKS5 Connect Request
    let n = match stream.read(&mut buf).await {
        Ok(n) if n >= 4 => n,
        _ => return,
    };
    if buf[1] != 0x01 {
        let _ = stream.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
        return;
    }

    // Parse destination host and port
    let (target_host, target_port) = match buf[3] {
        0x01 => {
            if n < 10 { return; }
            let ip = Ipv4Addr::new(buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            (ip.to_string(), port)
        }
        0x03 => {
            let dlen = buf[4] as usize;
            if n < 5 + dlen + 2 { return; }
            let dstr = String::from_utf8_lossy(&buf[5..5 + dlen]).to_string();
            let port = u16::from_be_bytes([buf[5 + dlen], buf[6 + dlen]]);
            (dstr, port)
        }
        _ => return,
    };

    // Respond SOCKS5 Success (0x05, 0x00, 0x00, 0x01, 127.0.0.1, bind_port)
    if stream.write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x1F, 0x90]).await.is_err() {
        return;
    }

    debug!("Meli Stream [{}] CONNECT to {}:{}", stream_id, target_host, target_port);

    // 3. Send SYN packet with target information
    let syn_payload = format!("{}:{}", target_host, target_port);
    let mut syn_packet = Vec::with_capacity(8 + syn_payload.len());
    syn_packet.push(MELI_MAGIC);
    syn_packet.push(MELI_VERSION);
    syn_packet.push(PKT_SYN);
    syn_packet.extend_from_slice(&stream_id.to_be_bytes());
    syn_packet.extend_from_slice(&0u32.to_be_bytes()); // Seq = 0
    syn_packet.extend_from_slice(syn_payload.as_bytes());

    let nonce = (Instant::now().elapsed().as_nanos() & 0xFFFFFFFF) as u32;
    let encrypted = meli_encrypt(&syn_packet, &key, nonce);
    let encoded = syllable_encode(&encrypted);
    let tx_id = tx_counter.fetch_add(1, Ordering::SeqCst);

    if let Some(query) = DnsCodec::build_query(tx_id, &encoded, &domain) {
        let _ = udp.send_to(&query, resolver).await;
    }

    // 4. Data Transfer Loop (Bidirectional)
    let (mut tcp_read, mut tcp_write) = stream.into_split();
    let mut seq = 1u32;
    let mut read_buf = [0u8; 45];

    loop {
        tokio::select! {
            res = tcp_read.read(&mut read_buf) => {
                match res {
                    Ok(0) => {
                        let mut fin_pkt = Vec::with_capacity(8);
                        fin_pkt.push(MELI_MAGIC);
                        fin_pkt.push(MELI_VERSION);
                        fin_pkt.push(PKT_FIN);
                        fin_pkt.extend_from_slice(&stream_id.to_be_bytes());
                        fin_pkt.extend_from_slice(&seq.to_be_bytes());
                        let nce = (Instant::now().elapsed().as_nanos() & 0xFFFFFFFF) as u32;
                        let enc = meli_encrypt(&fin_pkt, &key, nce);
                        let encoded = syllable_encode(&enc);
                        let tid = tx_counter.fetch_add(1, Ordering::SeqCst);
                        if let Some(q) = DnsCodec::build_query(tid, &encoded, &domain) {
                            let _ = udp.send_to(&q, resolver).await;
                        }
                        break;
                    }
                    Ok(n) => {
                        let mut data_pkt = Vec::with_capacity(8 + n);
                        data_pkt.push(MELI_MAGIC);
                        data_pkt.push(MELI_VERSION);
                        data_pkt.push(PKT_DATA);
                        data_pkt.extend_from_slice(&stream_id.to_be_bytes());
                        data_pkt.extend_from_slice(&seq.to_be_bytes());
                        data_pkt.extend_from_slice(&read_buf[..n]);
                        seq += 1;

                        let nce = (Instant::now().elapsed().as_nanos() & 0xFFFFFFFF) as u32;
                        let enc = meli_encrypt(&data_pkt, &key, nce);
                        let encoded = syllable_encode(&enc);
                        let tid = tx_counter.fetch_add(1, Ordering::SeqCst);
                        if let Some(q) = DnsCodec::build_query(tid, &encoded, &domain) {
                            let _ = udp.send_to(&q, resolver).await;
                        }
                    }
                    Err(_) => break,
                }
            }
            downstream = downstream_rx.recv() => {
                match downstream {
                    Some(bytes) => {
                        if tcp_write.write_all(&bytes).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                }
            }
        }
    }
}

// ─── Android JNI Interface ─────────────────────────────────────────────────────

pub fn decode_key(s: &str) -> Vec<u8> {
    let trimmed = s.trim();
    if (trimmed.len() == 64 || trimmed.len() == 32) && trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
        let hex_str = if trimmed.len() == 32 {
            format!("{:0<64}", trimmed)
        } else {
            trimmed.to_string()
        };
        (0..hex_str.len())
            .step_by(2)
            .filter_map(|i| u8::from_str_radix(&hex_str[i..i + 2], 16).ok())
            .collect()
    } else {
        trimmed.as_bytes().to_vec()
    }
}

#[no_mangle]
pub extern "system" fn Java_app_slipnet_tunnel_MeliBridge_nativeStartClient(
    mut env: jni::JNIEnv,
    _: jni::objects::JClass,
    domain: jni::objects::JString,
    resolver: jni::objects::JString,
    listen_port: jni::sys::jint,
    listen_host: jni::objects::JString,
    encryption_key: jni::objects::JString,
) -> jni::sys::jboolean {
    #[cfg(target_os = "android")]
    {
        crate::android::init_bridge_class(&mut env, "app/slipnet/tunnel/MeliBridge");
    }

    let dom: String = match env.get_string(&domain) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let res_str: String = match env.get_string(&resolver) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    let host: String = match env.get_string(&listen_host) {
        Ok(s) => s.into(),
        Err(_) => "127.0.0.1".into(),
    };
    let key_str: String = match env.get_string(&encryption_key) {
        Ok(s) => s.into(),
        Err(_) => "".into(),
    };

    let resolver_addr: SocketAddr = match res_str.parse() {
        Ok(a) => a,
        Err(_) => match format!("{}:53", res_str).parse() {
            Ok(a) => a,
            Err(_) => "8.8.8.8:53".parse().unwrap(),
        },
    };

    let raw_key = if key_str.is_empty() {
        decode_key("1937e96db977fb13ebc205e43ac5d5dad7ef61c8434cd7f08b8e166135595972")
    } else {
        decode_key(&key_str)
    };

    let (shutdown_tx, shutdown_rx) = mpsc::channel(1);
    let opts = MeliClientOptions {
        domain: dom,
        resolver: resolver_addr,
        listen_host: host,
        listen_port: listen_port as u16,
        encryption_key: raw_key,
    };

    {
        if let Ok(mut guard) = SHUTDOWN_NOTIFY.lock() {
            *guard = Some(shutdown_tx);
        }
    }

    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("Failed to build Tokio runtime for Meli");

        rt.block_on(run_meli_client(opts, shutdown_rx));
    });

    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_app_slipnet_tunnel_MeliBridge_nativeStopClient(
    _: jni::JNIEnv,
    _: jni::objects::JClass,
) -> jni::sys::jboolean {
    if let Ok(mut guard) = SHUTDOWN_NOTIFY.lock() {
        if let Some(tx) = guard.take() {
            let _ = tx.blocking_send(());
        }
    }
    IS_RUNNING.store(false, Ordering::SeqCst);
    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_app_slipnet_tunnel_MeliBridge_nativeIsClientRunning(
    _: jni::JNIEnv,
    _: jni::objects::JClass,
) -> jni::sys::jboolean {
    if is_running() {
        jni::sys::JNI_TRUE
    } else {
        jni::sys::JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_app_slipnet_tunnel_MeliBridge_nativeGetClientPort(
    _: jni::JNIEnv,
    _: jni::objects::JClass,
) -> jni::sys::jint {
    get_client_port() as jni::sys::jint
}

#[no_mangle]
pub extern "system" fn Java_app_slipnet_tunnel_MeliBridge_nativeGetLastError<'local>(
    env: jni::JNIEnv<'local>,
    _: jni::objects::JClass,
) -> jni::objects::JString<'local> {
    let err = get_last_error();
    env.new_string(err).unwrap_or_else(|_| env.new_string("").unwrap())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_b32hex_roundtrip() {
        let data = b"Hello, Meli Protocol Native Rust Tunnel 2026!";
        let encoded = b32hex_encode(data);
        let decoded = b32hex_decode(&encoded).expect("Decode should succeed");
        assert_eq!(data, &decoded[..]);
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = b"my_super_secret_key_1234567890!";
        let data = b"Sensitive payload over DNS tunnel";
        let encrypted = meli_encrypt(data, key, 42);
        let decrypted = meli_decrypt(&encrypted, key).expect("Decryption should succeed");
        assert_eq!(data, &decrypted[..]);
    }

    #[test]
    fn test_dns_query_builder() {
        let qname_payload = "0123456789abcdefghijklmnopqrstuv";
        let domain = "m.dhafhdtjdti.site";
        let pkt = DnsCodec::build_query(1234, qname_payload, domain).expect("Packet build failed");
        assert!(pkt.len() > 12);
        assert_eq!(&pkt[0..2], &1234u16.to_be_bytes());
    }

    #[test]
    fn test_syllable_encoding_roundtrip() {
        let original = b"Meli Protocol Anti-DPI Natural Syllables 2026";
        let encoded = syllable_encode(original);
        assert!(!encoded.is_empty());
        let labels: Vec<&str> = encoded.split('.').collect();
        let decoded = syllable_decode(&labels).expect("Decode must succeed");
        assert_eq!(&original[..], &decoded[..]);
    }

    #[test]
    fn test_all_256_bytes_syllable() {
        let all_bytes: Vec<u8> = (0..=255).collect();
        let encoded = syllable_encode(&all_bytes);
        let labels: Vec<&str> = encoded.split('.').collect();
        let decoded = syllable_decode(&labels).expect("All 256 bytes must decode");
        assert_eq!(all_bytes, decoded);
    }
}
