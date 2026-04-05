package systray

type MenuItem struct {
	ClickedCh chan struct{}
}

func (m *MenuItem) Check() {}
func (m *MenuItem) Uncheck() {}
func (m *MenuItem) Disable() {}
func (m *MenuItem) Enable() {}
func (m *MenuItem) Hide() {}
func (m *MenuItem) Show() {}
func (m *MenuItem) SetTitle(s string) {}
func (m *MenuItem) SetTooltip(s string) {}
func (m *MenuItem) SetIcon(b []byte) {}
func (m *MenuItem) AddSubMenuItem(title, tooltip string) *MenuItem { return &MenuItem{ClickedCh: make(chan struct{})} }
func (m *MenuItem) AddSubMenuItemCheckbox(title, tooltip string, checked bool) *MenuItem { return &MenuItem{ClickedCh: make(chan struct{})} }
func (m *MenuItem) AddSeparator() {}

func AddMenuItem(title, tooltip string) *MenuItem { return &MenuItem{ClickedCh: make(chan struct{})} }
func AddMenuItemCheckbox(title, tooltip string, checked bool) *MenuItem { return &MenuItem{ClickedCh: make(chan struct{})} }
func AddSeparator() {}
func Run(onReady func(), onExit func()) {}
func Quit() {}
func SetIcon(b []byte) {}
func SetTitle(s string) {}
func SetTooltip(s string) {}
func SetTemplateIcon(a []byte, b []byte) {}
func ResetMenu() {}