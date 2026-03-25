package lifecycle

import "github.com/park285/Iris/frida/daemon/internal/fridaapi"

type Runner struct {
	runtime    fridaapi.Runtime
	observer   Observer
	currentPID int
	attached   bool
}

type Observer interface {
	Attached(pid int)
	Detached()
}

func NewRunner(runtime fridaapi.Runtime, observer Observer) *Runner {
	return &Runner{runtime: runtime, observer: observer}
}

func (r *Runner) Reconcile(pid int, bundle string) error {
	if !r.attached {
		if err := r.runtime.Attach(pid, bundle); err != nil {
			return err
		}
		r.currentPID = pid
		r.attached = true
		if r.observer != nil {
			r.observer.Attached(pid)
		}
		return nil
	}

	if pid == r.currentPID {
		return nil
	}

	if err := r.runtime.UnloadAndDetach(); err != nil {
		return err
	}
	r.attached = false
	r.currentPID = 0
	if r.observer != nil {
		r.observer.Detached()
	}
	if err := r.runtime.Attach(pid, bundle); err != nil {
		return err
	}
	r.currentPID = pid
	r.attached = true
	if r.observer != nil {
		r.observer.Attached(pid)
	}
	return nil
}

func (r *Runner) Shutdown() error {
	if !r.attached {
		return nil
	}
	if err := r.runtime.UnloadAndDetach(); err != nil {
		return err
	}
	r.attached = false
	r.currentPID = 0
	if r.observer != nil {
		r.observer.Detached()
	}
	return nil
}
