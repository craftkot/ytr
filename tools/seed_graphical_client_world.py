#!/usr/bin/env python3
"""Make one disposable real Minecraft 26.3 world for the graphical client smoke.
Starts Fabric dedicated server once, saves the vanilla world, then stops it.
Never modifies personal game files or production worlds.
"""
from __future__ import annotations
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
import integration_relocation_smoke as base

RUN = base.RUN
ROOT = base.ROOT
OUT = ROOT / "client-seed-server.log"


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Only allowed in disposable GitHub runner")
    if (RUN / "world").exists():
        raise RuntimeError("World already exists; refusing destructive seed")
    RUN.mkdir(exist_ok=True)
    (RUN / "eula.txt").write_text("eula=true\n")
    (RUN / "server.properties").write_text(
        "enable-rcon=true\nrcon.port=25575\n"
        "rcon.password=operator-ci-smoke-password\n"
        "online-mode=false\nserver-port=25565\n"
    )
    with OUT.open("wb") as log:
        proc = subprocess.Popen(
            ["gradle", "runServer", "--no-daemon"],
            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError(f"Server exited before ready: {proc.returncode}")
                try:
                    if "Operator 0.23" in base.rcon("operator status"):
                        break
                except Exception:
                    pass
                time.sleep(1)
            else:
                raise RuntimeError("Dedicated Fabric seed server never reached RCON ready")
            base.send_stop()
            proc.wait(timeout=120)
            if proc.returncode != 0:
                raise RuntimeError("Seed server failed to stop cleanly: " + str(proc.returncode))
            if not (RUN / "world" / "level.dat").is_file():
                raise RuntimeError("No persisted Minecraft level.dat after seed")
            print("OPERATOR_CLIENT_SEED_WORLD_SUCCESS", flush=True)
        except BaseException:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
            print("\n".join(OUT.read_text(errors="replace").splitlines()[-100:]),
                  file=sys.stderr)
            raise


if __name__ == "__main__":
    main()
