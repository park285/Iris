package lifecycle

import (
	"reflect"
	"testing"
)

type fakeRuntime struct {
	events        []string
	attachCalls   int
	attachErrorOn int
}

func (f *fakeRuntime) Attach(pid int, bundle string) error {
	f.attachCalls++
	if f.attachErrorOn > 0 && f.attachCalls == f.attachErrorOn {
		return assertiveError("attach failed")
	}
	f.events = append(f.events, "attach", "create-script", "load")
	return nil
}

func (f *fakeRuntime) UnloadAndDetach() error {
	f.events = append(f.events, "unload", "detach")
	return nil
}

type fakeObserver struct {
	events []string
}

func (f *fakeObserver) Attached(pid int) {
	f.events = append(f.events, "attached")
}

func (f *fakeObserver) Detached() {
	f.events = append(f.events, "detached")
}

func TestRunnerAttachesOnFirstPID(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle"); err != nil {
		t.Fatalf("Reconcile returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}
	if !reflect.DeepEqual(observer.events, []string{"attached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached"})
	}
}

func TestRunnerReattachesWhenPIDChanges(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle-a"); err != nil {
		t.Fatalf("first Reconcile returned error: %v", err)
	}
	if err := r.Reconcile(5678, "bundle-b"); err != nil {
		t.Fatalf("second Reconcile returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load", "unload", "detach", "attach", "create-script", "load"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}
	if !reflect.DeepEqual(observer.events, []string{"attached", "detached", "attached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached", "detached", "attached"})
	}
}

func TestRunnerShutdownUsesUnloadThenDetach(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle"); err != nil {
		t.Fatalf("Reconcile returned error: %v", err)
	}
	if err := r.Shutdown(); err != nil {
		t.Fatalf("Shutdown returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load", "unload", "detach"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}
	if !reflect.DeepEqual(observer.events, []string{"attached", "detached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached", "detached"})
	}
}

func TestRunnerClearsStateBeforeRetryingFailedReattach(t *testing.T) {
	rt := &fakeRuntime{attachErrorOn: 2}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle-a"); err != nil {
		t.Fatalf("first Reconcile returned error: %v", err)
	}
	if err := r.Reconcile(5678, "bundle-b"); err == nil {
		t.Fatal("expected second Reconcile to fail")
	}
	if err := r.Reconcile(5678, "bundle-b"); err != nil {
		t.Fatalf("third Reconcile returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load", "unload", "detach", "attach", "create-script", "load"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}
	if !reflect.DeepEqual(observer.events, []string{"attached", "detached", "attached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached", "detached", "attached"})
	}
}

type assertiveError string

func (e assertiveError) Error() string { return string(e) }
