//go:build android

package appctr

// TunDNSAddr is the fake-IP DNS server address to configure in VpnService.Builder.
// Android apps send DNS queries to this address; our DNS interceptor answers them.
const TunDNSAddr = "100.100.100.100"

// TunDNSPort is the UDP port where the TUN DNS interceptor listens inside the Go process.
// The Go process socket is VPN-protected via VpnService.protect(), so it reaches
// real DNS infrastructure without going through the tunnel.
// VpnService.Builder should addDnsServer(TunDNSAddr) and addRoute(TunDNSAddr, 32).
// The hev-socks5-tunnel library handles the mapdns redirect internally.
const TunDNSPort = 1153
