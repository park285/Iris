package adb

import (
	"context"
	"testing"
)

func TestParsePID(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    int
		wantErr bool
	}{
		{name: "simple", input: "1234\n", want: 1234},
		{name: "trimmed", input: " 98765 ", want: 98765},
		{name: "empty", input: "", wantErr: true},
		{name: "invalid", input: "abc", wantErr: true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := ParsePID(tc.input)
			if tc.wantErr {
				if err == nil {
					t.Fatal("expected error")
				}
				return
			}
			if err != nil {
				t.Fatalf("ParsePID returned error: %v", err)
			}
			if got != tc.want {
				t.Fatalf("ParsePID = %d, want %d", got, tc.want)
			}
		})
	}
}

type fakeCommandRunner struct {
	args   []string
	stdout string
	err    error
}

func (f *fakeCommandRunner) Run(_ context.Context, name string, args ...string) (string, error) {
	f.args = append([]string{name}, args...)
	return f.stdout, f.err
}

func TestLookupPIDUsesADBPidof(t *testing.T) {
	runner := &fakeCommandRunner{stdout: "1234\n"}

	pid, err := LookupPID(context.Background(), runner, "emulator-5554")
	if err != nil {
		t.Fatalf("LookupPID returned error: %v", err)
	}

	if pid != 1234 {
		t.Fatalf("LookupPID = %d, want %d", pid, 1234)
	}
	wantArgs := []string{"adb", "-s", "emulator-5554", "shell", "pidof com.kakao.talk"}
	if len(runner.args) != len(wantArgs) {
		t.Fatalf("args = %v, want %v", runner.args, wantArgs)
	}
	for i := range wantArgs {
		if runner.args[i] != wantArgs[i] {
			t.Fatalf("args = %v, want %v", runner.args, wantArgs)
		}
	}
}
