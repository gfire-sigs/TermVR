//go:build !linux

package mux

import (
	"context"
	"errors"
	"net"
	"time"
)

func dialDirectAddr(ctx context.Context, ip net.IP, port uint16, _ int, _ time.Duration) (net.Conn, error) {
	return dialDirectTCPPlain(ctx, ip, port)
}

// StartTransparentProxyServer requires Linux SO_ORIGINAL_DST and returns an unsupported error elsewhere.
func StartTransparentProxyServer(_ context.Context, _ ProxyConfig) (*ProxyServer, error) {
	return nil, errors.New("transparent SO_ORIGINAL_DST proxy requires Linux")
}

// InstallTransparentRedirect requires Linux netfilter IPT_SO_SET_REPLACE.
func InstallTransparentRedirect(_ uint16, _ []string, _ int) error {
	return errors.New("iptables IPT_SO_SET_REPLACE requires Linux")
}
