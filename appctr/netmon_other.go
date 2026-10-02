//go:build !android

package appctr

// setInProcessDefaultRoute: only Android's netmon takes the default route from
// the app; elsewhere it finds the route itself.
func setInProcessDefaultRoute(name string) {}
