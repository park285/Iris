package fridaapi

type Runtime interface {
	Attach(pid int, bundle string) error
	UnloadAndDetach() error
}
