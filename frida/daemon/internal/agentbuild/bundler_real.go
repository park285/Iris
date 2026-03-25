//go:build frida_core

package agentbuild

import (
	"os"
	"strings"

	frida "github.com/frida/frida-go/frida"
)

type fridaCompilerBundler struct{}

func (fridaCompilerBundler) Build(projectRoot, entryPoint string) (string, error) {
	if strings.HasSuffix(entryPoint, ".js") {
		output, err := os.ReadFile(entryPoint)
		if err != nil {
			return "", err
		}
		return string(output), nil
	}

	opts := frida.NewCompilerOptions()
	opts.SetProjectRoot(projectRoot)
	compiler := frida.NewCompiler()
	defer compiler.Clean()
	return compiler.Build(entryPoint, opts)
}

func NewBundler(_ string) Bundler {
	return fridaCompilerBundler{}
}
