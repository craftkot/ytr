#!/usr/bin/env python3
"""
Three independent Fabric JVM boots against the same disposable CI world:
1. Create a 26-block stone shell, execute live SandboxTransactionEngine.apply()
2. Verify chunks/WAL survived restart; execute live rollback()
3. Verify both original regions and rolled-back WAL survived a second restart.

Explicitly opt-in: the JVM property AND a world-local marker are required.
Never point this script at an actual player's world directory.
"""
from __future__ import annotations

import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / "run"
MARKER = RUN / "world" / "operator_reality" / "ci-smoke.allow"
HOST, PORT = "127.0.0.1", 25575
PASSWORD = "operator-ci-smoke-password"


def exact(sock: socket.socket, count: int) -> bytes:
    result = b""
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise ConnectionError("RCON socket closed")
        result += chunk
    return result


def packet(sock: socket.socket) -> tuple[int, int, str]:
    length = struct.unpack("<i", exact(sock, 4))[0]
    if length < 10 or length > 100000:
        raise RuntimeError(f"Invalid RCON length {length}")
    data = exact(sock, length)
    request_id, packet_type = struct.unpack("<ii", data[:8])
    return request_id, packet_type, data[8:-2].decode("utf-8", errors="replace")


def send(sock: socket.socket, request_id: int, typ: int, text: str) -> None:
    body = struct.pack("<ii", request_id, typ) + text.encode("utf-8") + b"\0\0"
    sock.sendall(struct.pack("<i", len(body)) + body)


def rcon(command: str) -> str:
    with socket.create_connection((HOST, PORT), timeout=4) as sock:
        sock.settimeout(8)
        send(sock, 1, 3, PASSWORD)
        auth, _, _ = packet(sock)
        if auth != 1:
            raise RuntimeError("RCON login rejected")
        send(sock, 2, 2, command)
        response, _, body = packet(sock)
        if response != 2:
            raise RuntimeError(f"RCON response to {command!r} was not confirmed")
        return body


def run_phase(number: int) -> None:
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = (
        (env.get("JAVA_TOOL_OPTIONS", "") + " -Doperator.reality.ciSmoke=true").strip()
    )
    log_path = ROOT / f"integration-phase-{number}.log"
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            ["gradle", "runServer", "--no-daemon"],
            cwd=ROOT,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 210
            last_error = ""
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(
                        f"Fabric process exited before RCON on phase {number}: {process.returncode}"
                    )
                try:
                    status = rcon("operator status")
                    if "PASSIVE OBSERVATION" in status:
                        break
                    last_error = "unexpected operator status: " + status
                except Exception as exc:
                    last_error = repr(exc)
                time.sleep(1)
            else:
                raise RuntimeError(f"Server did not become ready: {last_error}")

            result = rcon(f"operator ci-smoke {number}")
            print(f"PHASE {number}: {result}", flush=True)
            if f"OPERATOR_CI_PHASE{number}_OK:" not in result:
                raise RuntimeError(f"Actual block integration phase {number} failed: {result}")

            stop = rcon("stop")
            print(f"Stopping server after phase {number}: {stop}", flush=True)
            process.wait(timeout=120)
            if process.returncode != 0:
                raise RuntimeError(f"Server/Gradle failed on shutdown: {process.returncode}")
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
            if log_path.is_file():
                print(f"--- {log_path.name} last 120 lines ---", file=sys.stderr)
                lines = log_path.read_text(errors="replace").splitlines()
                print("\n".join(lines[-120:]), file=sys.stderr)
            raise


def main() -> None:
    if not (RUN / "eula.txt").is_file():
        raise RuntimeError("This script requires the prior isolated server smoke step")
    if not (RUN / "server.properties").is_file():
        raise RuntimeError("Missing CI server.properties")

    # Only the known disposable GitHub-run world; never touch another world.
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Refusing to operate outside GitHub Actions")
    MARKER.parent.mkdir(parents=True, exist_ok=True)
    MARKER.write_text("isolated-ci-only\n")
    try:
        for phase in (1, 2, 3):
            run_phase(phase)
    finally:
        MARKER.unlink(missing_ok=True)

    print("OPERATOR_LIVE_RELOCATION_INTEGRATION_SUCCESS", flush=True)


if __name__ == "__main__":
    main()
