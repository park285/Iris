//go:build !frida_core

package fridaapi

import "errors"

type unavailableRuntime struct{}

func NewRuntime(_, _ string, _ MessageHandler) Runtime {
	return unavailableRuntime{}
}

func (unavailableRuntime) Attach(_ int, _ string) error {
	return errors.New("frida runtime backend is not wired in this build")
}

func (unavailableRuntime) UnloadAndDetach() error {
	return nil
}
