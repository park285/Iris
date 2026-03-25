//go:build frida_core

package agentbuild

import frida "github.com/frida/frida-go/frida"

type fridaCompilerBundler struct{}

func (fridaCompilerBundler) Build(projectRoot, entryPoint string) (string, error) {
	opts := frida.NewCompilerOptions()
	opts.SetProjectRoot(projectRoot)
	compiler := frida.NewCompiler()
	defer compiler.Clean()
	return compiler.Build(entryPoint, opts)
}

func NewBundler(_ string) Bundler {
	return fridaCompilerBundler{}
}
