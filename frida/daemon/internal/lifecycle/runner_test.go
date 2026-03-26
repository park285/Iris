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

func (f *fakeRuntime) Attach(_ int, _ string) error {
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

func (*fakeRuntime) Available() bool {
	return true
}

type fakeObserver struct {
	events []string
}

func (f *fakeObserver) Attached(_ int) {
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

func TestRunnerReloadsWhenBundleChangesForSamePID(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle-v1"); err != nil {
		t.Fatalf("first Reconcile returned error: %v", err)
	}

	if err := r.Reconcile(1234, "bundle-v2"); err != nil {
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

func TestRunnerSkipsReconcileForSamePIDAndBundle(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle-v1"); err != nil {
		t.Fatalf("first Reconcile returned error: %v", err)
	}

	if err := r.Reconcile(1234, "bundle-v1"); err != nil {
		t.Fatalf("second Reconcile returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}

	if !reflect.DeepEqual(observer.events, []string{"attached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached"})
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
