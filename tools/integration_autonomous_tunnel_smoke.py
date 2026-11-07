#!/usr/bin/env python3
"""BLACK FREQUENCY-style actual-world, accelerated-observation integration.
Three *fresh* Fabric JVM boots, one disposable world, real chunk edits and WALs.
The synthetic player only supplies position/rotation; no real client is launched.
"""
from __future__ import annotations
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
import integration_relocation_smoke as base

ROOT, RUN = base.ROOT, base.RUN
WORLD = RUN / "operator-tunnel-ci-world"
MARKER = WORLD / "operator_reality" / "ci-tunnel-fold.allow"
PROPS = RUN / "server.properties"
OWNER = "e8d1d74a-e4b8-4200-9dc6-d6a40bc60f33"


def phase(number: int) -> None:
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = (
        env.get("JAVA_TOOL_OPTIONS", "")
        + " -Doperator.reality.ciTunnelFold=true"
    ).strip()
    log_path = ROOT / f"integration-tunnel-phase-{number}.log"
    with log_path.open("wb") as log:
        proc = subprocess.Popen(
            ["gradle", "runServer", "--no-daemon"],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 210
            last_error = ""
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError(f"Server exited before RCON: {proc.returncode}")
                try:
                    if "PASSIVE OBSERVATION" in base.rcon("operator status"):
                        break
                    last_error = "Unexpected status payload"
                except Exception as exc:
                    last_error = repr(exc)
                time.sleep(1)
            else:
                raise RuntimeError(f"RCON did not become ready: {last_error}")
            command = f"operator ci-tunnel-fold {number}"
            response = base.rcon(command)
            print(f"OPERATOR_TUNNEL_WORLD_TEST phase={number}: {response}", flush=True)
            if f"OPERATOR_TUNNEL_CI_PHASE{number}_OK:" not in response:
                raise RuntimeError("Real-world autonomous fold scenario failed: " + response)
            monitor = base.rcon("operator sandbox monitor")
            print(f"OPERATOR_TUNNEL_WAL phase={number}: {monitor}", flush=True)
            if "0 unfinished" not in monitor or "0 invalid" not in monitor:
                raise RuntimeError("Bad or unfinished WAL after phase " + str(number))
            if number > 1 and "1 committed" not in monitor:
                raise RuntimeError("Expected persisted auto transaction after restart")
            base.send_stop()
            proc.wait(timeout=120)
            if proc.returncode != 0:
                raise RuntimeError(f"Server/Gradle exited {proc.returncode}")
        except BaseException:
            if proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
            print("--- tail " + log_path.name + " ---", file=sys.stderr)
            print("\n".join(log_path.read_text(errors="replace").splitlines()[-120:]),
                  file=sys.stderr)
            raise


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Refusing to touch any non-GitHub world")
    if not PROPS.is_file() or not (RUN / "eula.txt").is_file():
        raise RuntimeError("Requires prior isolated server startup smoke")
    if WORLD.exists():
        raise RuntimeError("CI tunnel fold world already exists: refusing reuse")
    saved_properties = PROPS.read_bytes()
    try:
        PROPS.write_bytes(
            saved_properties.rstrip(b"\r\n")
            + b"\nlevel-name=operator-tunnel-ci-world\n"
        )
        MARKER.parent.mkdir(parents=True, exist_ok=False)
        MARKER.write_text("synthetic-actor; real blocks; isolated CI only\n")
        for step in (1, 2, 3):
            phase(step)
    finally:
        PROPS.write_bytes(saved_properties)
        MARKER.unlink(missing_ok=True)

    print("OPERATOR_AUTONOMOUS_TUNNEL_REAL_WORLD_INTEGRATION_SUCCESS", flush=True)


if __name__ == "__main__":
    main()
