package appctr

import (
	"log/slog"
	"net"
	"strings"
	_ "time/tzdata"

	_ "golang.org/x/mobile/bind"
	"golang.org/x/net/dns/dnsmessage"
)

// --- JNI Exported Functions (Static methods in Appctr class) ---

func NativeDnsQuery(domain, qtype string) string {
	slog.Info("LocalAPI: DNS Query", "domain", domain, "type", qtype)
	stateMu.Lock()
	opt := lastOptions
	stateMu.Unlock()

	if !IsRunning() {
		return "Error: Tailscaled is not running."
	}

	var msg dnsmessage.Message
	msg.Header.ID = 0x1234
	msg.Header.RecursionDesired = true

	t := dnsmessage.TypeA
	if strings.ToUpper(qtype) == "AAAA" {
		t = dnsmessage.TypeAAAA
	}

	name, err := dnsmessage.NewName(domain + ".")
	if err != nil {
		return "Invalid domain"
	}

	msg.Questions = []dnsmessage.Question{{Name: name, Type: t, Class: dnsmessage.ClassINET}}
	query, _ := msg.Pack()

	fallbacks := []string{"8.8.8.8:53", "1.1.1.1:53"}
	doh := ""
	if opt != nil {
		if opt.DnsFallbacks != "" {
			fallbacks = strings.Split(opt.DnsFallbacks, ",")
		}
		doh = opt.DohFallback
	}

	resp := processDNSQuery(query, fallbacks, doh)
	if resp == nil {
		return "No response"
	}

	var respMsg dnsmessage.Message
	if err := respMsg.Unpack(resp); err != nil {
		return "Error unpacking: " + err.Error()
	}

	if len(respMsg.Answers) == 0 {
		return "No answers (RCODE: " + respMsg.Header.RCode.String() + ")"
	}

	var results []string
	for _, ans := range respMsg.Answers {
		switch b := ans.Body.(type) {
		case *dnsmessage.AResource:
			results = append(results, net.IP(b.A[:]).String())
		case *dnsmessage.AAAAResource:
			results = append(results, net.IP(b.AAAA[:]).String())
		case *dnsmessage.CNAMEResource:
			results = append(results, b.CNAME.String())
		default:
			results = append(results, "Unknown record type")
		}
	}
	return strings.Join(results, "\n")
}

func FlushDNS() {
	splitDNSCache.Range(func(key, value interface{}) bool {
		splitDNSCache.Delete(key)
		return true
	})
	resetNodeCache()
	setMagicDNSSuffix("")
	dnsCacheFlush()
	slog.Info("DNS caches and metadata reset")
}

func ResetDNSMetadata() {
	FlushDNS()
}
