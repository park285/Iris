package agentbuild

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
)

type Request struct {
	ProjectRoot string
	EntryPoint  string
	DumpPath    string
}

type Bundler interface {
	Build(projectRoot, entryPoint string) (string, error)
}

func Compile(req Request, bundler Bundler) (string, error) {
	projectRoot := filepath.Clean(req.ProjectRoot)
	entryPoint := filepath.Clean(req.EntryPoint)
	rel, err := filepath.Rel(projectRoot, entryPoint)
	if err != nil {
		return "", err
	}
	if strings.HasPrefix(rel, "..") || rel == "." {
		return "", errors.New("entry point must stay within project root")
	}

	bundle, err := bundler.Build(projectRoot, entryPoint)
	if err != nil {
		return "", err
	}

	if req.DumpPath != "" {
		if err := os.MkdirAll(filepath.Dir(req.DumpPath), 0o755); err != nil {
			return "", err
		}
		if err := os.WriteFile(req.DumpPath, []byte(bundle), 0o644); err != nil {
			return "", err
		}
	}

	return bundle, nil
}
