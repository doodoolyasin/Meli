package client

import (
	"net"
	"sync"
)

// SocketProtector is an interface for protecting sockets on Android VpnService.
type SocketProtector interface {
	Protect(fd int) bool
}

var (
	globalProtector   SocketProtector
	globalProtectorMu sync.RWMutex
)

// SetSocketProtector registers the global SocketProtector instance.
func SetSocketProtector(p SocketProtector) {
	globalProtectorMu.Lock()
	globalProtector = p
	globalProtectorMu.Unlock()
}

// ProtectFD protects a file descriptor using the registered protector.
func ProtectFD(fd int) bool {
	globalProtectorMu.RLock()
	p := globalProtector
	globalProtectorMu.RUnlock()
	if p != nil {
		return p.Protect(fd)
	}
	return true
}

// ProtectUDPConn protects a UDP connection's socket from being routed back into VPN.
func ProtectUDPConn(conn *net.UDPConn) bool {
	if conn == nil {
		return true
	}
	sc, err := conn.SyscallConn()
	if err != nil {
		return false
	}
	res := true
	_ = sc.Control(func(fd uintptr) {
		res = ProtectFD(int(fd))
	})
	return res
}
