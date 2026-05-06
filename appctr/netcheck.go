package appctr

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"tailscale.com/net/netcheck"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
)

// GetNetcheckFromAPI выполняет нативный сетевой тест, используя DERP map от демона.
func GetNetcheckFromAPI() string {
	if !IsRunning() {
		return `{"Error": "Tailscaled is not running."}`
	}

	slog.Info("LocalAPI: [GET] /localapi/v0/derpmap (for netcheck)")
	// 1. Получаем DERP map из демона
	data, err := doLocalRequest("GET", "/localapi/v0/derpmap", nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Failed to get DERP map: %v"}`, err)
	}

	var dm tailcfg.DERPMap
	if err := json.Unmarshal(data, &dm); err != nil {
		return fmt.Sprintf(`{"Error": "Failed to parse DERP map: %v"}`, err)
	}

	// 2. Инициализируем монитор сети (статичный, без шины событий)
	nm := netmon.NewStatic()
	defer nm.Close()

	// 3. Запускаем нативный netcheck
	c := &netcheck.Client{
		NetMon: nm,
		Logf: func(format string, args ...any) {
			slog.Info(fmt.Sprintf("netcheck: "+format, args...))
		},
	}

	report, err := c.GetReport(context.Background(), &dm, nil)
	if err != nil {
		return fmt.Sprintf(`{"Error": "Netcheck failed: %v"}`, err)
	}

	if report == nil {
		return `{"Error": "Netcheck returned nil report"}`
	}

	slog.Info("LocalAPI: Netcheck completed")
	// 4. Возвращаем JSON отчета
	res, err := json.Marshal(report)
	if err != nil {
		return fmt.Sprintf(`{"Error": "JSON marshal failed: %v"}`, err)
	}
	return string(res)
}
