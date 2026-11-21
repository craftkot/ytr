#!/usr/bin/env python3
"""Unclean JVM shutdown during both APPLY and RESTORE on a disposable CI level.

Eight separate Fabric JVM processes share the isolated world:
  4-7. Persist a partial APPLY across two Anvil regions, hard-halt before ACK #33,
       cold-restart, restore, then cold-restart and verify the original cells.
  8-11. Commit a second move, persist 10 partial RESTORE writes, hard-halt before
        RESTORE ACK #10, cold-restart, reconcile the missing ACK, restore the rest,
        then cold-restart and verify the terminal WAL and original world.

This intentionally runs only inside GitHub Actions; never on a user world.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

import integration_relocation_smoke as base

ROOT, RUN = base.ROOT, base.RUN
CRASH_WORLD = RUN / "crash-world"
MARKER = CRASH_WORLD / "operator_reality" / "ci-smoke.allow"
OWNER = "18762a20-b35d-48af-9807-0c995ea0d853"
JOURNAL = "0dc225ce-087f-4bf1-b24a-940b130230fe"
WAL = CRASH_WORLD / "operator_reality" / "executions" / OWNER / f"{JOURNAL}.json"


def start(phase: int) -> tuple[subprocess.Popen[bytes], Path]:
    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = (
        env.get("JAVA_TOOL_OPTIONS", "") + " -Doperator.reality.ciSmoke=true"
    ).strip()
    log_path = ROOT / f"unclean-crash-phase-{phase}.log"
    log = log_path.open("wb")
    try:
        proc = subprocess.Popen(
            ["gradle", "runServer", "--no-daemon"],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    finally:
        log.close()
    return proc, log_path


def ready(proc: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 210
    last_error = "server was not ready"
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("Server process exited before RCON: " + str(proc.returncode))
        try:
            if "PASSIVE OBSERVATION" in base.rcon("operator status"):
                return
            last_error = "Unexpected status response"
        except Exception as exc:
            last_error = repr(exc)
        time.sleep(1)
    raise RuntimeError("Timed out waiting for RCON: " + last_error)


def cleanup(proc: subprocess.Popen[bytes], log_path: Path) -> None:
    if proc.poll() is None:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait(timeout=15)
    if log_path.exists():
        print(f"---- {log_path.name} last 100 lines ----", file=sys.stderr)
        print("\n".join(log_path.read_text(errors="replace").splitlines()[-100:]),
              file=sys.stderr)


def run_normal(phase: int) -> None:
    proc, path = start(phase)
    try:
        ready(proc)
        # An offline operation must be discovered by the server-wide WAL
        # scanner BEFORE the manual rollback command is executed.
        monitor = base.rcon("operator sandbox monitor")
        print(f"WAL MONITOR PHASE {phase}: {monitor}", flush=True)
        if phase in (6, 10, 14):
            expected = "APPLYING" if phase in (6, 14) else "ROLLING_BACK"
            if (expected not in monitor or "NEW SANDBOX WRITES BLOCKED" not in monitor):
                raise RuntimeError("Unfinished WAL not detected on cold startup: " + monitor)
        if phase in (7, 11, 15):
            if "0 unfinished" not in monitor or "NEW SANDBOX WRITES BLOCKED" in monitor:
                raise RuntimeError("Rolled-back WAL was not released: " + monitor)
        result = base.rcon(f"operator ci-smoke {phase}")
        print(f"UNCLEAN TEST PHASE {phase}: {result}", flush=True)
        if f"OPERATOR_CI_PHASE{phase}_OK:" not in result:
            raise RuntimeError("Integration stage failed: " + result)
        base.send_stop()
        proc.wait(timeout=120)
        if proc.returncode != 0:
            raise RuntimeError(f"Graceful phase {phase} stopped with code {proc.returncode}")
    except BaseException:
        cleanup(proc, path)
        raise


def run_forced_crash() -> None:
    proc, path = start(5)
    try:
        ready(proc)
        try:
            response = base.rcon("operator ci-smoke 5")
            raise RuntimeError("Expected RCON socket loss on hard crash, got " + response)
        except (ConnectionError, BrokenPipeError, OSError, TimeoutError):
            pass  # The actual proof is the process exit, marker, and persisted WAL.

        proc.wait(timeout=120)
        if proc.returncode == 0:
            raise RuntimeError("Expected Gradle failure after deliberate JVM halt")
        log_text = path.read_text(errors="replace")
        if "OPERATOR_CI_UNCLEAN_HALT_AFTER_WRITE_33" not in log_text:
            raise RuntimeError("Missing hard-crash marker in server process log")
        if "OPERATOR_CI_PARTIAL_CHUNKS_FLUSHED_33" not in log_text:
            raise RuntimeError("Both dirty Minecraft chunks were not force-flushed before crash")
        if not WAL.is_file():
            raise RuntimeError("Crash occurred before the WAL was durable")
        ledger = json.loads(WAL.read_text())
        if (ledger.get("phase") != "APPLYING"
                or ledger.get("pendingOperation") != "APPLY"
                or ledger.get("appliedWrites") != 32
                or ledger.get("totalWrites") != 52):
            raise RuntimeError("Unexpected post-crash persisted WAL (expected 32 ACKs + pending #33): "
                               + repr({k: ledger.get(k) for k in
                                       ("phase", "pendingOperation", "appliedWrites",
                                        "totalWrites")}))
        print("OPERATOR_CI_UNCLEAN_CRASH_CONFIRMED: server halted after "
              "33 block writes force-flushed across source/target chunks; crash before WAL ACK #33; 32 durable ACKs", flush=True)
    except BaseException:
        cleanup(proc, path)
        raise


def run_forced_rollback_crash() -> None:
    proc, path = start(9)
    try:
        ready(proc)
        try:
            response = base.rcon("operator ci-smoke 9")
            raise RuntimeError("Expected RCON socket loss during rollback crash, got " + response)
        except (ConnectionError, BrokenPipeError, OSError, TimeoutError):
            pass

        proc.wait(timeout=120)
        if proc.returncode == 0:
            raise RuntimeError("Expected Gradle failure after deliberate rollback JVM halt")
        log_text = path.read_text(errors="replace")
        if "OPERATOR_CI_UNCLEAN_HALT_DURING_RESTORE_AFTER_WRITE_10" not in log_text:
            raise RuntimeError("Missing rollback hard-crash marker in server process log")
        if "OPERATOR_CI_ROLLBACK_PARTIAL_CHUNKS_FLUSHED_10" not in log_text:
            raise RuntimeError("Partially restored Minecraft chunks were not flushed before crash")
        rollback_wal = (
            CRASH_WORLD / "operator_reality" / "executions" / OWNER
            / "d3f9c06b-18d5-4b7c-98e1-9d91d06a4f73.json"
        )
        if not rollback_wal.is_file():
            raise RuntimeError("Rollback WAL missing after forced JVM halt")
        ledger = json.loads(rollback_wal.read_text())
        if (ledger.get("phase") != "ROLLING_BACK"
                or ledger.get("pendingOperation") != "RESTORE"
                or ledger.get("restoredWrites") != 9
                or ledger.get("appliedWrites") != 52
                or ledger.get("totalWrites") != 52):
            raise RuntimeError("Unexpected rollback WAL after crash: "
                               + repr({k: ledger.get(k) for k in
                                       ("phase", "pendingOperation", "restoredWrites",
                                        "appliedWrites", "totalWrites")}))
        print("OPERATOR_CI_ROLLBACK_CRASH_CONFIRMED: 10 restored block writes flushed "
              "before RESTORE ACK #10; WAL contains 9 durable restore ACKs", flush=True)
    except BaseException:
        cleanup(proc, path)
        raise



def run_deferred_forced_crash() -> None:
    """Crash after a write in each of two real chunks and before WAL ACK #3."""
    proc, path = start(13)
    try:
        ready(proc)
        try:
            response = base.rcon("operator ci-smoke 13")
            raise RuntimeError("Expected crash/socket loss, got " + response)
        except (ConnectionError, BrokenPipeError, OSError, TimeoutError):
            pass
        proc.wait(timeout=120)
        if proc.returncode == 0:
            raise RuntimeError("Expected nonzero JVM exit after cross-chunk hard halt")
        output = path.read_text(errors="replace")
        for marker in ("OPERATOR_DEFERRED_CI_TWO_CHUNKS_FLUSHED",
                       "OPERATOR_DEFERRED_CI_HALT_BEFORE_ACK3"):
            if marker not in output:
                raise RuntimeError("Deferred crash marker missing: " + marker)
        deferred = (CRASH_WORLD / "operator_reality" / "executions"
                    / "643ed413-ed77-4530-8939-936c9b97c1b9"
                    / "3c2f8310-2708-453e-9633-6bd138d5c80b.json")
        if not deferred.is_file():
            raise RuntimeError("Cross-chunk WAL was not durable before hard halt")
        ledger = json.loads(deferred.read_text())
        if (ledger.get("phase") != "APPLYING"
                or ledger.get("pendingOperation") != "APPLY"
                or ledger.get("appliedWrites") != 2
                or ledger.get("totalWrites") != 4):
            raise RuntimeError("Unexpected cross-chunk crash WAL: " + repr(ledger))
        print("OPERATOR_DEFERRED_REAL_JVM_CRASH_CONFIRMED: "
              "two chunks flushed, WAL has two ACKs and pending third write",
              flush=True)
    except BaseException:
        cleanup(proc, path)
        raise


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Refusing hard-crash integration test outside GitHub Actions")
    if not (RUN / "eula.txt").is_file() or not (RUN / "server.properties").is_file():
        raise RuntimeError("Dedicated server smoke must run before unclean crash integration")
    if CRASH_WORLD.exists():
        raise RuntimeError("Refusing to reuse a preexisting crash-world directory")

    properties = RUN / "server.properties"
    before = properties.read_text()
    lines = [line for line in before.splitlines() if not line.startswith("level-name=")]
    properties.write_text("\n".join(lines) + "\nlevel-name=crash-world\n")
    MARKER.parent.mkdir(parents=True, exist_ok=False)
    MARKER.write_text("isolated-ci-only\n")
    try:
        run_normal(4)
        run_forced_crash()
        run_normal(6)
        run_normal(7)
        run_normal(8)
        run_forced_rollback_crash()
        run_normal(10)
        run_normal(11)
        run_normal(12)
        run_deferred_forced_crash()
        run_normal(14)
        run_normal(15)
    finally:
        MARKER.unlink(missing_ok=True)
        properties.write_text(before)

    print("OPERATOR_DEFERRED_CROSS_CHUNK_UNCLEAN_RECOVERY_SUCCESS", flush=True)
    print("OPERATOR_UNCLEAN_CRASH_RECOVERY_SUCCESS", flush=True)


if __name__ == "__main__":
    main()
