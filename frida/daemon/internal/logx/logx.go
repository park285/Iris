package logx

import "log"

func Printf(format string, args ...any) {
	log.Printf(format, args...)
}
