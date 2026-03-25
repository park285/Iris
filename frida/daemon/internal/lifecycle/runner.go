package lifecycle

import "github.com/park285/Iris/frida/daemon/internal/fridaapi"

type Runner struct {
	runtime    fridaapi.Runtime
	currentPID int
	attached   bool
}

func NewRunner(runtime fridaapi.Runtime) *Runner {
	return &Runner{runtime: runtime}
}

func (r *Runner) Reconcile(pid int, bundle string) error {
	if !r.attached {
		if err := r.runtime.Attach(pid, bundle); err != nil {
			return err
		}
		r.currentPID = pid
		r.attached = true
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
	if err := r.runtime.Attach(pid, bundle); err != nil {
		return err
	}
	r.currentPID = pid
	r.attached = true
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
	return nil
}
