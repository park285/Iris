package fridaapi

import (
	"strconv"

	json "github.com/goccy/go-json"
)

type Runtime interface {
	Attach(pid int, bundle string) error
	UnloadAndDetach() error
	Available() bool
}

type MessageHandler func(message string)

type HealthMessage struct {
	Event       string
	State       string
	Ready       bool
	Hooks       map[string]bool
	Counters    map[string]int64
	TimestampMs int64
	Payload     map[string]any
}

func ParseHealthMessage(message string) (HealthMessage, bool) {
	var envelope struct {
		Type    string          `json:"type"`
		Payload json.RawMessage `json:"payload"`
	}
	if err := json.Unmarshal([]byte(message), &envelope); err != nil {
		return HealthMessage{}, false
	}

	if envelope.Type != "send" || len(envelope.Payload) == 0 {
		return HealthMessage{}, false
	}

	var payloadString string

	var payload map[string]any
	if err := json.Unmarshal(envelope.Payload, &payload); err != nil {
		if err := json.Unmarshal(envelope.Payload, &payloadString); err != nil {
			return HealthMessage{}, false
		}

		if err := json.Unmarshal([]byte(payloadString), &payload); err != nil {
			return HealthMessage{}, false
		}
	}

	if rawType, _ := payload["type"].(string); rawType != "graft-health" {
		return HealthMessage{}, false
	}

	return HealthMessage{
		Event:       stringValue(payload["event"]),
		State:       stringValue(payload["state"]),
		Ready:       boolValue(payload["ready"]),
		Hooks:       boolMapValue(payload["hooks"]),
		Counters:    int64MapValue(payload["counters"]),
		TimestampMs: int64Value(payload["timestampMs"]),
		Payload:     payload,
	}, true
}

func stringValue(value any) string {
	text, _ := value.(string)
	return text
}

func boolValue(value any) bool {
	flag, _ := value.(bool)
	return flag
}

func boolMapValue(value any) map[string]bool {
	source, ok := value.(map[string]any)
	if !ok || len(source) == 0 {
		return nil
	}

	result := make(map[string]bool, len(source))
	for key, raw := range source {
		switch typed := raw.(type) {
		case bool:
			result[key] = typed
		case map[string]any:
			if flag, ok := typed["installed"].(bool); ok {
				result[key] = flag
			}
		}
	}

	return result
}

func int64MapValue(value any) map[string]int64 {
	source, ok := value.(map[string]any)
	if !ok || len(source) == 0 {
		return nil
	}

	result := make(map[string]int64, len(source))
	for key, raw := range source {
		appendInt64MapValue(result, key, raw)
	}

	return result
}

func appendInt64MapValue(result map[string]int64, prefix string, value any) {
	if nested, ok := value.(map[string]any); ok {
		for key, raw := range nested {
			appendInt64MapValue(result, prefix+"."+key, raw)
		}

		return
	}

	result[prefix] = int64Value(value)
}

func int64Value(value any) int64 {
	switch typed := value.(type) {
	case int:
		return int64(typed)
	case int64:
		return typed
	case float64:
		return int64(typed)
	case json.Number:
		parsed, _ := typed.Int64()
		return parsed
	case string:
		parsed, _ := strconv.ParseInt(typed, 10, 64)
		return parsed
	default:
		return 0
	}
}
