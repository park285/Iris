#!/usr/bin/env python3
"""
graft-daemon.py — KakaoTalk PID 추적 + Frida hook 자동 attach 데몬

용법:
  python3 graft-daemon.py [--device DEVICE_ID] [--script SCRIPT_PATH]

기본값:
  --device  emulator-5554
  --script  /data/local/tmp/thread-image-graft.js
"""

import argparse
import os
import signal
import subprocess
import threading
import time

KAKAO_PACKAGE = "com.kakao.talk"
POLL_INTERVAL = 30
ATTACH_RETRY_DELAY = 5
LOG_PREFIX = "[graft-daemon]"


def log(msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"{ts} {LOG_PREFIX} {msg}", flush=True)


def get_kakao_pid(device_id):
    """adb를 통해 KakaoTalk PID 조회."""
    try:
        result = subprocess.run(
            ["adb", "-s", device_id, "shell", f"pidof {KAKAO_PACKAGE}"],
            capture_output=True, text=True, timeout=5,
        )
        pid_str = result.stdout.strip()
        if pid_str and pid_str.isdigit():
            return int(pid_str)
    except (subprocess.TimeoutExpired, Exception):
        pass
    return None


def run_frida(device_id, pid, script_path):
    """frida CLI를 실행하고 프로세스 객체를 반환."""
    cmd = ["frida", "-D", device_id, "-p", str(pid), "-l", script_path]
    log(f"attaching: {' '.join(cmd)}")
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return proc


def stream_output(proc):
    """frida 출력을 실시간 로깅. 프로세스 종료 시 반환."""
    try:
        for raw_line in proc.stdout:
            line = raw_line.decode("utf-8", errors="replace").rstrip()
            if line:
                log(f"  {line}")
    except Exception:
        pass
    proc.wait()
    return proc.returncode


def main():
    parser = argparse.ArgumentParser(description="KakaoTalk Frida graft daemon")
    parser.add_argument("--device", default="emulator-5554", help="ADB device ID")
    parser.add_argument(
        "--script",
        default=os.path.join(os.path.dirname(__file__), "thread-image-graft.js"),
        help="Frida JS script path",
    )
    args = parser.parse_args()

    log(f"started (device={args.device}, script={args.script})")

    # SIGTERM/SIGINT 처리
    running = True
    frida_proc = None

    def shutdown(signum, _frame):
        nonlocal running, frida_proc
        log(f"signal {signum} received, shutting down")
        running = False
        if frida_proc and frida_proc.poll() is None:
            frida_proc.terminate()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    last_pid = None

    while running:
        pid = get_kakao_pid(args.device)

        if pid is None:
            if last_pid is not None:
                log(f"KakaoTalk exited (was PID {last_pid}), waiting for restart...")
                last_pid = None
            time.sleep(POLL_INTERVAL)
            continue

        if pid == last_pid:
            # 같은 PID — frida가 아직 살아있는지 확인
            if frida_proc and frida_proc.poll() is not None:
                log(f"frida exited (code={frida_proc.returncode}), re-attaching...")
                last_pid = None  # 재attach 트리거
                time.sleep(ATTACH_RETRY_DELAY)
                continue
            time.sleep(POLL_INTERVAL)
            continue

        # 새 PID 감지
        log(f"KakaoTalk PID: {pid}" + (f" (changed from {last_pid})" if last_pid else ""))

        # 기존 frida 종료
        if frida_proc and frida_proc.poll() is None:
            log("terminating previous frida session")
            frida_proc.terminate()
            frida_proc.wait(timeout=5)

        # 새 프로세스에 약간의 초기화 시간 부여
        time.sleep(ATTACH_RETRY_DELAY)

        # PID가 아직 유효한지 재확인
        check_pid = get_kakao_pid(args.device)
        if check_pid != pid:
            log(f"PID changed during wait ({pid} -> {check_pid}), retrying")
            continue

        frida_proc = run_frida(args.device, pid, args.script)
        last_pid = pid

        t = threading.Thread(target=stream_output, args=(frida_proc,), daemon=True)
        t.start()

    if frida_proc and frida_proc.poll() is None:
        frida_proc.terminate()
        try:
            frida_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            frida_proc.kill()

    log("daemon stopped")


if __name__ == "__main__":
    main()
