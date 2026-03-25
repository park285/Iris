package agentbuild

import (
	"fmt"
	"os/exec"
	"path/filepath"
)

type CLIBundler struct {
	NPMExecutable string
}

func (b CLIBundler) Build(projectRoot, entryPoint string) (string, error) {
	npmExecutable := b.NPMExecutable
	if npmExecutable == "" {
		npmExecutable = "npm"
	}

	relativeEntryPoint, err := filepath.Rel(projectRoot, entryPoint)
	if err != nil {
		return "", err
	}

	cmd := exec.Command(npmExecutable, "exec", "frida-compile", "--", relativeEntryPoint, "-o", "-")
	cmd.Dir = projectRoot
	output, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("frida-compile failed: %w: %s", err, string(output))
	}

	return string(output), nil
}
