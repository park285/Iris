package lifecycle

import (
	"fmt"
	"hash/fnv"

	"github.com/park285/Iris/frida/daemon/internal/fridaapi"
)

// bundleDigest는 bundle 문자열(JS 소스 전체)의 FNV-1a hash.
// 대형 문자열 두 벌 저장 대신 uint64 비교로 변경 감지.
func bundleDigest(bundle string) uint64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(bundle))
	return h.Sum64()
}

type Runner struct {
	runtime       fridaapi.Runtime
	observer      Observer
	currentPID    int
	currentDigest uint64
	attached      bool
}

type Observer interface {
	Attached(pid int)
	Detached()
}

func NewRunner(runtime fridaapi.Runtime, observer Observer) *Runner {
	return &Runner{runtime: runtime, observer: observer}
}

func (r *Runner) Reconcile(pid int, bundle string) error {
	digest := bundleDigest(bundle)

	if !r.attached {
		if err := r.runtime.Attach(pid, bundle); err != nil {
			return fmt.Errorf("attach pid %d: %w", pid, err)
		}

		r.currentPID = pid
		r.currentDigest = digest
		r.attached = true
		if r.observer != nil {
			r.observer.Attached(pid)
		}

		return nil
	}

	if pid == r.currentPID && digest == r.currentDigest {
		return nil
	}

	if err := r.runtime.UnloadAndDetach(); err != nil {
		return fmt.Errorf("detach pid %d: %w", r.currentPID, err)
	}

	r.attached = false
	r.currentPID = 0
	r.currentDigest = 0
	if r.observer != nil {
		r.observer.Detached()
	}

	if err := r.runtime.Attach(pid, bundle); err != nil {
		return fmt.Errorf("reattach pid %d: %w", pid, err)
	}

	r.currentPID = pid
	r.currentDigest = digest
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
		return fmt.Errorf("shutdown detach pid %d: %w", r.currentPID, err)
	}

	r.attached = false
	r.currentPID = 0
	r.currentDigest = 0
	if r.observer != nil {
		r.observer.Detached()
	}

	return nil
}
