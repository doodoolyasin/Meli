package hunter

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"
)

// RacerResult holds the first successful DNS query response from a pool of racing resolvers.
type RacerResult struct {
	ResolverIP net.IP
	Response   []byte
	RTT        time.Duration
	Error      error
}

// RaceQuery dispatches a single DNS query concurrently across up to maxResolvers.
// Returns the fastest valid response. Censors throttling one ISP or ASN will be outpaced
// by resolvers on surviving autonomous systems.
func RaceQuery(ctx context.Context, queryPacket []byte, resolvers []net.IP, timeout time.Duration) (*RacerResult, error) {
	if len(resolvers) == 0 {
		return nil, fmt.Errorf("no resolvers provided for racing")
	}

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	resultChan := make(chan *RacerResult, len(resolvers))
	var wg sync.WaitGroup

	for _, ip := range resolvers {
		wg.Add(1)
		go func(target net.IP) {
			defer wg.Done()
			start := time.Now()

			conn, err := net.DialTimeout("udp4", net.JoinHostPort(target.String(), "53"), timeout)
			if err != nil {
				return
			}
			defer conn.Close()

			_ = conn.SetDeadline(time.Now().Add(timeout))
			if _, err := conn.Write(queryPacket); err != nil {
				return
			}

			buf := make([]byte, 2048)
			n, err := conn.Read(buf)
			if err != nil || n < 12 {
				return
			}

			select {
			case resultChan <- &RacerResult{
				ResolverIP: target,
				Response:   buf[:n],
				RTT:        time.Since(start),
			}:
				cancel() // First valid packet cancels remaining slow/hung racers!
			case <-ctx.Done():
			}
		}(ip)
	}

	// Wait in background to clean up goroutines
	go func() {
		wg.Wait()
		close(resultChan)
	}()

	select {
	case res := <-resultChan:
		if res != nil {
			return res, nil
		}
		return nil, fmt.Errorf("all racing resolvers timed out or failed")
	case <-ctx.Done():
		// Check if a result arrived right as or before cancel() was invoked
		select {
		case res := <-resultChan:
			if res != nil {
				return res, nil
			}
		default:
		}
		return nil, fmt.Errorf("race query timed out after %v", timeout)
	}
}
