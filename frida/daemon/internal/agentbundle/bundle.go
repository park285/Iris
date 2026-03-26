package agentbundle

import (
	"embed"
	"fmt"
	"io/fs"
	"sort"
	"strings"
)

//go:embed bundles/*.js
var bundles embed.FS

// StaticSource는 embedded JS bundle을 제공한다.
type StaticSource struct {
	name    string
	content string
}

func (s *StaticSource) Bundle() (string, error) { return s.content, nil }
func (s *StaticSource) String() string          { return "embedded:" + s.name }

// Source는 embedded bundle에서 name에 해당하는 StaticSource를 반환한다.
func Source(name string) (*StaticSource, error) {
	data, err := bundles.ReadFile("bundles/" + name + ".js")
	if err != nil {
		return nil, fmt.Errorf("agent %q not found (available: %v): %w",
			name, Available(), err)
	}
	return &StaticSource{name: name, content: string(data)}, nil
}

// Available는 embedded bundle 이름 목록을 반환한다.
func Available() []string {
	entries, err := fs.ReadDir(bundles, "bundles")
	if err != nil {
		return nil
	}
	var names []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".js") {
			names = append(names, strings.TrimSuffix(e.Name(), ".js"))
		}
	}
	sort.Strings(names)
	return names
}
