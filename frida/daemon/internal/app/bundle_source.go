package app

import (
	"fmt"
	"os"
)

// BundleSource는 JS agent bundle을 제공한다.
type BundleSource interface {
	Bundle() (string, error)
	String() string
}

// FileBundleSource는 파일시스템에서 bundle을 읽는다.
type FileBundleSource struct {
	path string
}

func NewFileBundleSource(path string) *FileBundleSource {
	return &FileBundleSource{path: path}
}

func (f *FileBundleSource) Bundle() (string, error) {
	data, err := os.ReadFile(f.path)
	if err != nil {
		return "", fmt.Errorf("read override bundle %s: %w", f.path, err)
	}
	return string(data), nil
}

func (f *FileBundleSource) String() string {
	return "file:" + f.path
}
