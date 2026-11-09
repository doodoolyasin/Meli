// awgsocks: AmneziaWG client with a userspace network stack and a local SOCKS5 listener.
// Usage: awgsocks -c wg.conf -l 127.0.0.1:10880
package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

var amneziaKeys = map[string]bool{"jc": true, "jmin": true, "jmax": true, "s1": true, "s2": true, "s3": true, "s4": true,
	"h1": true, "h2": true, "h3": true, "h4": true, "i1": true, "i2": true, "i3": true, "i4": true, "i5": true}

func b64hex(s string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("invalid key")
	}
	return hex.EncodeToString(b), nil
}

func main() {
	cfgPath := flag.String("c", "", "config")
	listen := flag.String("l", "127.0.0.1:10880", "socks listen")
	flag.Parse()
	f, err := os.Open(*cfgPath)
	if err != nil {
		fatal(err)
	}
	var iface, peer strings.Builder
	var addrs []netip.Addr
	var dns []netip.Addr
	mtu := 1280
	section := ""
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if i := strings.Index(line, "#"); i == 0 {
			continue
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			continue
		}
		kv := strings.SplitN(line, "=", 2)
		if len(kv) != 2 {
			continue
		}
		k, v := strings.ToLower(strings.TrimSpace(kv[0])), strings.TrimSpace(kv[1])
		switch section {
		case "interface":
			switch {
			case k == "privatekey":
				h, e := b64hex(v)
				if e != nil {
					fatal(fmt.Errorf("PrivateKey: %v", e))
				}
				fmt.Fprintf(&iface, "private_key=%s\n", h)
			case k == "address":
				for _, a := range strings.Split(v, ",") {
					a = strings.TrimSpace(a)
					if p, e := netip.ParsePrefix(a); e == nil {
						addrs = append(addrs, p.Addr())
					}
				}
			case k == "dns":
				for _, a := range strings.Split(v, ",") {
					if ip, e := netip.ParseAddr(strings.TrimSpace(a)); e == nil {
						dns = append(dns, ip)
					}
				}
			case k == "mtu":
				if n, e := strconv.Atoi(v); e == nil {
					mtu = n
				}
			case amneziaKeys[k]:
				fmt.Fprintf(&iface, "%s=%s\n", k, v)
			}
		case "peer":
			switch k {
			case "publickey":
				h, e := b64hex(v)
				if e != nil {
					fatal(fmt.Errorf("PublicKey: %v", e))
				}
				fmt.Fprintf(&peer, "public_key=%s\n", h)
			case "presharedkey":
				h, e := b64hex(v)
				if e != nil {
					fatal(fmt.Errorf("PresharedKey: %v", e))
				}
				fmt.Fprintf(&peer, "preshared_key=%s\n", h)
			case "endpoint":
				host, port, e := net.SplitHostPort(v)
				if e != nil {
					fatal(fmt.Errorf("Endpoint: %v", e))
				}
				if net.ParseIP(host) == nil {
					ips, e := net.LookupHost(host)
					if e != nil || len(ips) == 0 {
						fatal(fmt.Errorf("Endpoint DNS: %v", e))
					}
					host = ips[0]
				}
				fmt.Fprintf(&peer, "endpoint=%s\n", net.JoinHostPort(host, port))
			case "persistentkeepalive":
				fmt.Fprintf(&peer, "persistent_keepalive_interval=%s\n", v)
			case "allowedips":
				for _, a := range strings.Split(v, ",") {
					fmt.Fprintf(&peer, "allowed_ip=%s\n", strings.TrimSpace(a))
				}
			}
		}
	}
	if len(addrs) == 0 {
		fatal(fmt.Errorf("no Interface Address"))
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	tdev, tnet, err := netstack.CreateNetTUN(addrs, dns, mtu)
	if err != nil {
		fatal(err)
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "awg: "))
	if err := dev.IpcSet(iface.String() + peer.String()); err != nil {
		fatal(err)
	}
	if err := dev.Up(); err != nil {
		fatal(err)
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		fatal(err)
	}
	fmt.Println("READY")
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go handle(c, tnet)
	}
}

func fatal(e error) { fmt.Fprintln(os.Stderr, "FATAL:", e); os.Exit(1) }

func handle(c net.Conn, tnet *netstack.Net) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	h := make([]byte, 2)
	if _, err := io.ReadFull(c, h); err != nil || h[0] != 5 {
		return
	}
	io.ReadFull(c, make([]byte, h[1]))
	c.Write([]byte{5, 0})
	r := make([]byte, 4)
	if _, err := io.ReadFull(c, r); err != nil || r[1] != 1 {
		c.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	var host string
	switch r[3] {
	case 1:
		b := make([]byte, 4)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		io.ReadFull(c, b)
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		io.ReadFull(c, l)
		b := make([]byte, l[0])
		io.ReadFull(c, b)
		host = string(b)
	default:
		return
	}
	pb := make([]byte, 2)
	io.ReadFull(c, pb)
	port := binary.BigEndian.Uint16(pb)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if net.ParseIP(host) == nil {
		ips, err := tnet.LookupHost(host)
		if err != nil || len(ips) == 0 {
			c.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
			return
		}
		host = ips[0]
	}
	ap, err := netip.ParseAddrPort(net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	rc, err := tnet.DialContextTCPAddrPort(ctx, ap)
	if err != nil {
		c.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer rc.Close()
	c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	c.SetDeadline(time.Time{})
	go io.Copy(rc, c)
	io.Copy(c, rc)
}
