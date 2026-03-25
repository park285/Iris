//go:build !frida_core

package agentbuild

func NewBundler(_ string) Bundler {
	return CLIBundler{}
}
