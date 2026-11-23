#!/usr/bin/env python3
"""Real Minecraft three-boot stone voxel WAL crash/recovery integration.

Runs ONLY in a disposable GitHub Actions dedicated-server world:
  1. Three stone->air changes journaled, two saved, JVM forcibly halted.
  2. Cold restart loads the actual Anvil mixed state and repairs it via WAL.
  3. Another cold restart verifies pristine blocks and terminal RESOLVED WAL.

The fixture command is registered only with its separate JVM property and
will refuse any world directory other than run/voxel-crash-world.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import time

import integration_relocation_smoke as base

ROOT,RUN=base.ROOT,base.RUN
WORLD=RUN/"voxel-crash-world"
MARKER=WORLD/"operator_reality"/"ci-voxel-crash.allow"
WAL=WORLD/"operator_reality"/"voxel-wal"/"19239f45-e562-456b-a90c-77031c987fd1.vwal"


def journal_phase() -> int:
    if not WAL.is_file():
        raise RuntimeError("No persisted voxel WAL file")
    raw=WAL.read_bytes()
    if len(raw)>16384 or len(raw)<8200:
        raise RuntimeError("Voxel WAL size is invalid")
    data,digest=raw[:-32],raw[-32:]
    if hashlib.sha256(data).digest()!=digest:
        raise RuntimeError("Voxel WAL checksum invalid")
    magic,version=struct.unpack_from(">II",data,0)
    if (magic,version)!=(0x45564854,1):
        raise RuntimeError("Voxel WAL header invalid")
    offset=24
    length=struct.unpack_from(">H",data,offset)[0]
    offset+=2
    dimension=data[offset:offset+length].decode("utf-8")
    offset+=length
    x,y,z,revision=struct.unpack_from(">iiiq",data,offset)
    offset+=20
    phase=data[offset]
    offset+=1
    before=data[offset:offset+4096]
    after=data[offset+4096:offset+8192]
    expected={(8*16+8)*16+x for x in (7,8,9)}
    changes={i for i,(a,b) in enumerate(zip(before,after)) if a!=b}
    if ((x,y,z,revision)!=(0,12,0,1) or dimension!="minecraft:overworld"
            or changes!=expected or len(data)!=offset+8192
            or any(before[i]!=1 or after[i]!=0 for i in changes)):
        raise RuntimeError("Voxel WAL identity or three-block transition mismatch")
    return phase


def start(phase: int) -> tuple[subprocess.Popen[bytes], Path]:
    env=os.environ.copy()
    env["JAVA_TOOL_OPTIONS"]=(
        env.get("JAVA_TOOL_OPTIONS","")+" -Doperator.reality.ciVoxelCrash=true"
    ).strip()
    log=ROOT/f"voxel-crash-phase-{phase}.log"
    with log.open("wb") as output:
        proc=subprocess.Popen(
            ["gradle","runServer","--no-daemon"],
            cwd=ROOT,env=env,stdout=output,stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    return proc,log


def ready(proc: subprocess.Popen[bytes]) -> None:
    end=time.monotonic()+210
    last=""
    while time.monotonic()<end:
        if proc.poll() is not None:
            raise RuntimeError(f"Server exited before RCON readiness: {proc.returncode}")
        try:
            if "PASSIVE OBSERVATION" in base.rcon("operator status"):
                return
            last="Unexpected status"
        except Exception as e:
            last=repr(e)
        time.sleep(1)
    raise RuntimeError("Voxel smoke RCON startup timed out: "+last)


def cleanup(proc: subprocess.Popen[bytes],log: Path) -> None:
    if proc.poll() is None:
        try:
            os.killpg(proc.pid,signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL)
            proc.wait(timeout=15)
    if log.is_file():
        print(f"--- {log.name} tail ---",file=sys.stderr)
        print("\n".join(log.read_text(errors="replace").splitlines()[-100:]),file=sys.stderr)


def crash_boot() -> None:
    proc,log=start(1)
    try:
        ready(proc)
        try:
            response=base.rcon("operator ci-voxel-crash 1")
            raise RuntimeError("Server did not HALT on first partial voxel write: "+response)
        except (ConnectionError,BrokenPipeError,OSError,TimeoutError):
            pass
        proc.wait(timeout=120)
        if proc.returncode==0:
            raise RuntimeError("Forced JVM halt unexpectedly exited successfully")
        output=log.read_text(errors="replace")
        if ("OPERATOR_VOXEL_CI_FORCED_HALT_AFTER_2" not in output
                or "OPERATOR_VOXEL_CI_PARTIAL_BLOCKS_FLUSHED_2" not in output):
            raise RuntimeError("Server did not confirm exactly two flushed writes before crash")
        if journal_phase()!=0:
            raise RuntimeError("Crash did not leave a durable PREPARED intent")
        print("OPERATOR_VOXEL_CI_HARD_CRASH_CONFIRMED",flush=True)
    except BaseException:
        cleanup(proc,log)
        raise


def normal_boot(phase: int) -> None:
    proc,log=start(phase)
    try:
        ready(proc)
        status=base.rcon(f"operator ci-voxel-crash {phase}")
        expected=f"OPERATOR_VOXEL_CI_PHASE{phase}_OK:"
        if expected not in status:
            raise RuntimeError("Cold voxel recovery failed: "+status)
        if journal_phase()!=2:
            raise RuntimeError("On-disk WAL was not durably RESOLVED")
        print(status,flush=True)
        base.send_stop()
        proc.wait(timeout=120)
        if proc.returncode!=0:
            raise RuntimeError(f"Cold JVM shutdown code {proc.returncode}")
    except BaseException:
        cleanup(proc,log)
        raise


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS")!="true":
        raise RuntimeError("Hard JVM crash test is prohibited outside GitHub Actions")
    if not (RUN/"server.properties").is_file() or not (RUN/"eula.txt").is_file():
        raise RuntimeError("Smoke requires the prior disposable dedicated server test")
    if WORLD.exists():
        raise RuntimeError("Refusing to touch an existing voxel crash world")
    properties=RUN/"server.properties"
    previous=properties.read_text()
    modified=[l for l in previous.splitlines() if not l.startswith("level-name=")]
    properties.write_text("\n".join(modified)+"\nlevel-name=voxel-crash-world\n")
    MARKER.parent.mkdir(parents=True,exist_ok=False)
    MARKER.write_text("explicit isolated CI-only hard-crash opt-in\n")
    try:
        crash_boot()
        normal_boot(2)
        normal_boot(3)
    finally:
        MARKER.unlink(missing_ok=True)
        properties.write_text(previous)
    print("OPERATOR_VOXEL_COLD_RESTART_RECOVERY_SUCCESS",flush=True)


if __name__=="__main__":
    main()
