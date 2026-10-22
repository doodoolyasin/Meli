package main

import (
	"encoding/binary"
	"encoding/json"
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"time"
)

// Result is one line of the machine-readable output.
type Result struct {
	Check  string `json:"check"`
	Target string `json:"target"`
	Open   bool   `json:"open"`
	Detail string `json:"detail"`
	TookMs int64  `json:"took_ms"`
}

func emit(r Result) { b, _ := json.Marshal(r); fmt.Println(string(b)) }

// ---- dns-delegation: NS(name) resolves to relayIP ----
func checkDelegation(name, relayIP string, timeout time.Duration) Result {
	start := time.Now()
	c := &net.Resolver{PreferGo: true,
		Dial: func(ctx context.Context, network string, addr string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, "1.1.1.1:53")
		}}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	ns, err := c.LookupNS(ctx, name)
	if err != nil {
		ips, aerr := net.LookupIP(name)
		if aerr != nil || len(ips) == 0 {
			return Result{"dns-delegation", name + " -> " + relayIP, false,
				"lookup failed: " + err.Error(), ms(start)}
		}
		return Result{"dns-delegation", name + " -> " + relayIP, hasIP(ips, relayIP),
			fmt.Sprintf("A records %v (no NS record)", ips), ms(start)}
	}
	hosts := make([]string, 0, len(ns))
	for _, h := range ns {
		hosts = append(hosts, h.Host)
	}
	ok := false
	ips := []net.IP{}
	if len(hosts) > 0 {
		ips, _ = net.LookupIP(hosts[0])
		ok = hasIP(ips, relayIP)
	}
	return Result{"dns-delegation", name + " -> " + relayIP, ok,
		fmt.Sprintf("NS=%v NS-IPs=%v", hosts, ips), ms(start)}
}

func hasIP(ips []net.IP, want string) bool {
	for _, ip := range ips {
		if ip.String() == want {
			return true
		}
	}
	return false
}

// ---- udp53: server:53 answers a DNS query with QR=1 ----
func checkUDP(host, port string, timeout time.Duration) Result {
	start := time.Now()
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(host, port))
	if err != nil {
		return Result{"udp53", host + ":" + port, false, "resolve: " + err.Error(), ms(start)}
	}
	conn, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return Result{"udp53", host + ":" + port, false, "dial: " + err.Error(), ms(start)}
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	if _, err := conn.Write(buildQuery("masterdns-hc.invalid")); err != nil {
		return Result{"udp53", host + ":" + port, false, "write: " + err.Error(), ms(start)}
	}
	buf := make([]byte, 512)
	n, err := conn.Read(buf)
	if err != nil {
		return Result{"udp53", host + ":" + port, false, "no reply (blocked/filtered): " + err.Error(), ms(start)}
	}
	ok := n >= 12 && (buf[2]&0x80) != 0
	return Result{"udp53", host + ":" + port, ok, fmt.Sprintf("reply %d bytes", n), ms(start)}
}

// ---- tcp ----
func checkTCP(hostPort string, timeout time.Duration) Result {
	start := time.Now()
	conn, err := net.DialTimeout("tcp", hostPort, timeout)
	if err != nil {
		return Result{"tcp", hostPort, false, "connect: " + err.Error(), ms(start)}
	}
	conn.Close()
	return Result{"tcp", hostPort, true, "connected", ms(start)}
}

// ---- icmp echo (needs CAP_NET_RAW: sudo or setcap cap_net_raw=+ep) ----
func checkICMP(host string, timeout time.Duration) Result {
	start := time.Now()
	ip, err := net.ResolveIPAddr("ip4", host)
	if err != nil {
		return Result{"icmp", host, false, "resolve: " + err.Error(), ms(start)}
	}
	conn, err := net.ListenPacket("ip4:icmp", "0.0.0.0")
	if err != nil {
		return Result{"icmp", host, false, "raw socket (need CAP_NET_RAW/sudo): " + err.Error(), ms(start)}
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	pkt := icmpEcho(0x1d34, 7, []byte("masterdns-hc"))
	if _, err := conn.WriteTo(pkt, &net.IPAddr{IP: ip.IP}); err != nil {
		return Result{"icmp", host, false, "send: " + err.Error(), ms(start)}
	}
	buf := make([]byte, 1500)
	for {
		n, _, rerr := conn.ReadFrom(buf)
		if rerr != nil {
			return Result{"icmp", host, false, "timeout (no echo reply): " + rerr.Error(), ms(start)}
		}
		// Echo reply: type 0, same id, seq, len >= full ICMP header (8 bytes).
		if n >= 8 && buf[0] == 0 && n-8 == len(pkt)-8 {
			return Result{"icmp", host, true, "echo reply", ms(start)}
		}
	}
}

// ---- ntp: UDP/123 answers an NTPv4 client packet with mode=4 ----
func checkNTP(host string, timeout time.Duration) Result {
	start := time.Now()
	addr, err := net.ResolveUDPAddr("udp", host+":123")
	if err != nil {
		return Result{"ntp", host + ":123", false, "resolve: " + err.Error(), ms(start)}
	}
	conn, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		return Result{"ntp", host + ":123", false, "dial: " + err.Error(), ms(start)}
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	pkt := make([]byte, 48)
	pkt[0] = 0x23
	if _, err := conn.Write(pkt); err != nil {
		return Result{"ntp", host + ":123", false, "write: " + err.Error(), ms(start)}
	}
	buf := make([]byte, 512)
	n, err := conn.Read(buf)
	if err != nil {
		return Result{"ntp", host + ":123", false, "no reply: " + err.Error(), ms(start)}
	}
	ok := n >= 48 && (buf[0]&0x07) == 0x04
	return Result{"ntp", host + ":123", ok, fmt.Sprintf("reply %d bytes", n), ms(start)}
}

func ms(s time.Time) int64 { return time.Since(s).Milliseconds() }

func buildQuery(name string) []byte {
	b := make([]byte, 0, 64)
	h := make([]byte, 12)
	binary.BigEndian.PutUint16(h[0:2], 0x5a5a)
	h[2] = 0x01
	h[3] = 0x00
	binary.BigEndian.PutUint16(h[4:6], 1)
	b = append(b, h...)
	for _, lbl := range splitLabels(name) {
		b = append(b, byte(len(lbl)))
		b = append(b, lbl...)
	}
	b = append(b, 0, 0x00, 0x01, 0x00, 0x01)
	return b
}

func splitLabels(n string) []string {
	out := []string{}
	cur := ""
	for i := 0; i < len(n); i++ {
		if n[i] == '.' {
			out = append(out, cur)
			cur = ""
		} else {
			cur += string(n[i])
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

func icmpEcho(id, seq uint16, payload []byte) []byte {
	b := make([]byte, 8+len(payload))
	b[0], b[1] = 8, 0
	binary.BigEndian.PutUint16(b[4:6], id)
	binary.BigEndian.PutUint16(b[6:8], seq)
	copy(b[8:], payload)
	sum := checksum(b)
	b[2] = byte(sum >> 8)
	b[3] = byte(sum & 0xFF)
	return b
}

func checksum(bs []byte) uint16 {
	var s uint32
	n := len(bs)
	for i := 0; i+1 < n; i += 2 {
		s += uint32(bs[i])<<8 + uint32(bs[i+1])
	}
	if n%2 == 1 {
		s += uint32(bs[n-1]) << 8
	}
	for s>>16 > 0 {
		s = (s >> 16) + (s & 0xFFFF)
	}
	return ^uint16(s)
}

func main() {
	domainF := flag.String("domain", "", "tunnel subdomain for dns-delegation")
	relayF := flag.String("relay-ip", "", "expected relay server IP for dns-delegation")
	udpF := flag.String("udp", "", "resolver host:port to probe udp53 (e.g. 5.202.10.202:53)")
	tcpF := flag.String("tcp", "", "host:port to probe tcp")
	icmpF := flag.String("icmp", "", "host IP to probe icmp")
	ntpF := flag.String("ntp", "", "host IP to probe ntp (udp/123)")
	toF := flag.Int("timeout-ms", 4000, "per-probe timeout")
	flag.Parse()
	to := time.Duration(*toF) * time.Millisecond
	any := false
	if *relayF != "" && *domainF != "" {
		any = true
		res := checkDelegation(*domainF, *relayF, to)
		emit(res)
		if !res.Open {
			os.Exit(1)
		}
	}
	if *udpF != "" {
		any = true
		host, port, perr := net.SplitHostPort(*udpF)
		if perr != nil {
			host, port = *udpF, "53"
		}
		res := checkUDP(host, port, to)
		emit(res)
		if !res.Open {
			os.Exit(1)
		}
	}
	if *tcpF != "" {
		any = true
		res := checkTCP(*tcpF, to)
		emit(res)
		if !res.Open {
			os.Exit(1)
		}
	}
	if *icmpF != "" {
		any = true
		res := checkICMP(*icmpF, to)
		emit(res)
		if !res.Open {
			os.Exit(1)
		}
	}
	if *ntpF != "" {
		any = true
		res := checkNTP(*ntpF, to)
		emit(res)
		if !res.Open {
			os.Exit(1)
		}
	}
	if !any {
		flag.Usage()
		os.Exit(1)
	}
}
