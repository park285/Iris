//go:build frida_core

//nolint:depguard // Raw frida-go bindings are intentionally isolated in this adapter.
package fridaapi

import (
	"fmt"

	frida "github.com/frida/frida-go/frida"
)

type realRuntime struct {
	deviceID string
	handler  MessageHandler
	manager  *frida.DeviceManager
	session  *frida.Session
	script   *frida.Script
}

func NewRuntime(deviceID, _ string, handler MessageHandler) Runtime {
	return &realRuntime{deviceID: deviceID, handler: handler}
}

func (r *realRuntime) ensureManager() *frida.DeviceManager {
	if r.manager == nil {
		r.manager = frida.NewDeviceManager()
	}
	return r.manager
}

func (r *realRuntime) Attach(pid int, bundle string) error {
	device, err := frida.DeviceByID(r.deviceID)
	if err != nil {
		return fmt.Errorf("get device by id: %w", err)
	}

	session, err := device.Attach(pid, nil)
	if err != nil {
		return fmt.Errorf("attach pid %d: %w", pid, err)
	}

	script, err := session.CreateScript(bundle)
	if err != nil {
		_ = session.Detach()
		return fmt.Errorf("create script: %w", err)
	}

	script.On("message", func(message string, _ []byte) {
		if r.handler != nil {
			r.handler(message)
			return
		}
		fmt.Println(message)
	})

	if err := script.Load(); err != nil {
		script.Clean()
		_ = session.Detach()
		return fmt.Errorf("load script: %w", err)
	}

	r.session = session
	r.script = script
	return nil
}

func (r *realRuntime) UnloadAndDetach() error {
	if r.script != nil {
		if err := r.script.Unload(); err != nil && !r.script.IsDestroyed() {
			return err
		}
		r.script.Clean()
		r.script = nil
	}

	if r.session != nil {
		if !r.session.IsDetached() {
			if err := r.session.Detach(); err != nil {
				return err
			}
		}
		r.session.Clean()
		r.session = nil
	}

	if r.manager != nil {
		if err := r.manager.Close(); err != nil {
			return err
		}
		r.manager.Clean()
		r.manager = nil
	}

	return nil
}

func (*realRuntime) Available() bool {
	return true
}
