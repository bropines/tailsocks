package appctr

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
)

// Tailscale Services (svc:name) and Prefs.AdvertiseServices.
//
// The daemon tells the tailnet a service is active on this node only while its
// name is in AdvertiseServices; a service that merely has rules in the serve
// config is reported with its ports and inactive, which is what `tailscale
// serve drain` leaves behind. Upstream's CLI advertises a service when its
// first rule is created and stops when it is cleared. A write from the Serve
// screen does the same here, and leaves the services it did not create or
// remove as they were, so a drained one stays drained.

// servicesAdoptedMark is the file in a profile's state directory that says its
// configured services have been advertised once (adoptConfiguredServices).
const servicesAdoptedMark = "services-advertised"

// serveServiceNames returns the svc: names a serve config JSON has rules for.
func serveServiceNames(configJSON []byte) []string {
	var cfg struct {
		Services map[string]json.RawMessage
	}
	if err := json.Unmarshal(configJSON, &cfg); err != nil {
		return nil
	}
	var names []string
	for name := range cfg.Services {
		if strings.HasPrefix(name, "svc:") {
			names = append(names, name)
		}
	}
	slices.Sort(names)
	return names
}

// advertiseAfter returns what AdvertiseServices should hold once the serve
// config's services go from before to after: the created ones join, the
// removed ones leave, everything else — a drained service, a name advertised
// without rules — is kept. changed is false when the list stays as it is.
func advertiseAfter(advertised, before, after []string) (list []string, changed bool) {
	for _, name := range advertised {
		if slices.Contains(before, name) && !slices.Contains(after, name) {
			changed = true
			continue
		}
		list = append(list, name)
	}
	for _, name := range after {
		if !slices.Contains(before, name) && !slices.Contains(list, name) {
			list = append(list, name)
			changed = true
		}
	}
	return list, changed
}

func advertisedServices() ([]string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/prefs", nil)
	if err != nil {
		return nil, err
	}
	var prefs struct {
		AdvertiseServices []string
	}
	if err := json.Unmarshal(data, &prefs); err != nil {
		return nil, err
	}
	return prefs.AdvertiseServices, nil
}

func setAdvertisedServices(list []string) error {
	if list == nil {
		list = []string{}
	}
	payload, _ := json.Marshal(map[string]any{
		"AdvertiseServices":    list,
		"AdvertiseServicesSet": true,
	})
	slog.Info("LocalAPI: [PATCH] /localapi/v0/prefs (AdvertiseServices)", "services", list)
	_, err := doLocalRequest("PATCH", "/localapi/v0/prefs", strings.NewReader(string(payload)))
	return err
}

// currentServeServices reads the services the daemon's serve config has now.
func currentServeServices() ([]string, error) {
	data, err := doLocalRequest("GET", "/localapi/v0/serve-config", nil)
	if err != nil {
		return nil, err
	}
	return serveServiceNames(data), nil
}

// syncAdvertisedServices brings AdvertiseServices in line with a serve config
// write that took the services from before to after.
func syncAdvertisedServices(before, after []string) error {
	advertised, err := advertisedServices()
	if err != nil {
		return err
	}
	list, changed := advertiseAfter(advertised, before, after)
	if !changed {
		return nil
	}
	return setAdvertisedServices(list)
}

// adoptConfiguredServices advertises, once per profile, every service the
// serve config has rules for. Up to 5.8.0 the daemon was patched to call each
// configured service active whatever AdvertiseServices said, so a profile may
// hold services that answer today and are not in the list; without this they
// would go quiet on the update. Once, because afterwards a service missing
// from the list is one the user drained.
func adoptConfiguredServices() {
	stateMu.Lock()
	stateDir := PC.State()
	stateMu.Unlock()
	if stateDir == "" {
		return
	}
	mark := filepath.Join(stateDir, servicesAdoptedMark)
	if _, err := os.Stat(mark); err == nil {
		return
	}
	if err := adoptServices(); err != nil {
		// Not marked: the next start tries again.
		slog.Warn("Services: could not advertise the configured services", "err", err)
		return
	}
	if err := os.WriteFile(mark, nil, 0o600); err != nil {
		slog.Warn("Services: could not record that the services were advertised", "err", err)
	}
}

func adoptServices() error {
	configured, err := currentServeServices()
	if err != nil {
		return fmt.Errorf("serve config: %w", err)
	}
	if len(configured) == 0 {
		return nil
	}
	advertised, err := advertisedServices()
	if err != nil {
		return fmt.Errorf("prefs: %w", err)
	}
	list, changed := advertiseAfter(advertised, nil, configured)
	if !changed {
		return nil
	}
	slog.Info("Services: advertising the services configured before 5.8.1", "services", configured)
	return setAdvertisedServices(list)
}
