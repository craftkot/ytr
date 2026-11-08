#!/usr/bin/env python3
"""Graphical Minecraft/Fabric client integration, modeled after BLACK FREQUENCY.

Uses an existing disposable dedicated-server world as a quick-play singleplayer
world. Starts REAL Fabric runClient on Xvfb/llvmpipe, waits for the actual
integrated ServerPlayer, captures three real game-window PNGs, and verifies
two-block WAL mutation + rollback markers emitted from the ordinary tick path.

Not a fake player: the JVM's ClientTunnelSmoke observes a connected ServerPlayer.
Opt-in test JVM flag + GitHub Actions + world marker required.
"""
from __future__ import annotations

import os
import shutil
import signal
import re
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "run"
SOURCE = RUN / "world"
WORLD_NAME = "ci-operator-client-world"
WORLD = RUN / "saves" / WORLD_NAME
MARKER_DIR = WORLD / "operator_reality"
SCREENSHOTS = ROOT / "client-smoke-artifacts"
CLIENT_LOG = RUN / "logs" / "latest.log"
CONSOLE = ROOT / "client-console.log"


def log(s: str) -> None:
    print("OPERATOR_CLIENT_RUNNER " + s, flush=True)


def await_marker(proc: subprocess.Popen[bytes], marker: str,
                 seconds: int = 180) -> str:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"Client exited {proc.returncode} before {marker}")
        data = CLIENT_LOG.read_text(encoding="utf-8", errors="replace") if CLIENT_LOG.is_file() else ""
        if "OPERATOR_CLIENT_TEST FAILED" in data:
            bad = [x for x in data.splitlines() if "OPERATOR_CLIENT_TEST FAILED" in x]
            raise RuntimeError("Test controller reported failure: " + " | ".join(bad[-3:]))
        if marker in data:
            log("Verified integrated-world marker " + marker)
            return data
        time.sleep(1)
    raise RuntimeError("Timed out waiting for " + marker
                       + " | client log tail: "
                       + " / ".join(
                           (CLIENT_LOG.read_text(errors="replace").splitlines()[-10:]
                            if CLIENT_LOG.is_file() else ["no log"])))
def window_id() -> str:
    found = subprocess.run(
        ["xdotool", "search", "--onlyvisible", "--name", "Minecraft"],
        capture_output=True, text=True, check=False
    ).stdout.splitlines()
    if not found:
        # Native Wayland client inside nested Weston appears as Weston's X11
        # window on the outer Xvfb display, not as an X11 Minecraft window.
        found = subprocess.run(
            ["xdotool", "search", "--onlyvisible", "--name", "Weston"],
            capture_output=True, text=True, check=False
        ).stdout.splitlines()
    if not found:
        # Kiosk-shell Weston often has NO X11 window title, but logs the
        # actual X11 output window id. Target the real X11 output rather
        # than sending mouse/keyboard events into an unfocused root.
        weston_log = ROOT / "weston.log"
        if weston_log.is_file():
            match = re.search(r"x11 output .* window id (\d+)",
                              weston_log.read_text(errors="replace"))
            if match:
                found = [match.group(1)]
    if not found:
        raise RuntimeError("Weston compositor X11 output window unavailable")
    return found[-1]


def screenshot(name: str) -> Path:
    window = window_id()
    if window:
        subprocess.run(["xdotool", "windowsize", window, "1280", "720"],
                       check=False, timeout=10)
        subprocess.run(["xdotool", "windowmove", window, "0", "0"],
                       check=False, timeout=10)
    else:
        log("No named X11 window; capturing the actual Weston/Xvfb root surface")
    # Hide in-game HUD for an unobstructed comparison.
    subprocess.run(["xdotool", "key", "--clearmodifiers", "F1"], check=False)
    time.sleep(0.6)
    path = SCREENSHOTS / name
    subprocess.run(["scrot", "-a", "0,0,1280,720", str(path)],
                   check=True, timeout=12)
    subprocess.run(["xdotool", "key", "--clearmodifiers", "F1"], check=False)
    with Image.open(path) as image:
        img = image.convert("RGB")
        channels = ImageStat.Stat(img).stddev
        if img.size != (1280, 720) or max(channels) < 5.0:
            raise RuntimeError(
                f"Screenshot is blank/uniform: {path.name} stddev={channels}"
            )
    if path.stat().st_size < 6000:
        raise RuntimeError(f"Screenshot too small: {path}")
    log(f"PNG from REAL Minecraft window: {path.name} ({path.stat().st_size} bytes)")
    return path


def click_ui(x: int, y: int) -> None:
    # Input reaches the real Minecraft Wayland window through nested Weston's
    # X11 host. Hold the mouse down long enough for SDL3 to register it.
    window = window_id()
    # No X11 window manager is running on Xvfb; windowactivate can fail.
    # Explicitly give Weston the X keyboard focus before real input.
    subprocess.run(["xdotool", "windowfocus", window], check=False, timeout=6)
    subprocess.run(["xdotool", "mousemove", "--sync", str(x), str(y)], check=False)
    time.sleep(0.35)
    subprocess.run(["xdotool", "mousedown", "1"], check=False)
    time.sleep(0.4)
    subprocess.run(["xdotool", "mouseup", "1"], check=False)
    time.sleep(0.2)
    # Enter triggers the highlighted default menu action on first run
    # when SDL's nested Wayland pointer does not pass through XTest clicks.
    subprocess.run(["xdotool", "key", "--clearmodifiers", "Return"], check=False)
    log(f"CLICKED_GUI focusedWestonWindow={window} x={x} y={y}")


def try_open_existing_world(proc: subprocess.Popen[bytes]) -> None:
    # The 26.3 quick-play arg may stop at its first-launch accessibility or
    # resource-loading screen. Use actual graphical input as BLACK FREQUENCY's
    # client harness does, preserving screenshots for diagnosis.
    await_marker(proc, "Created window using SDL video driver: wayland", 115)
    # Quick Play should now enter the seeded world directly, once the
    # documented onboardAccessibility:false option removes the modal.
    # Give software-rendered Minecraft a bounded window to load chunks.
    for _ in range(45):
        text = CLIENT_LOG.read_text(errors="replace") if CLIENT_LOG.is_file() else ""
        if "OPERATOR_CLIENT_TEST SCENE_READY" in text:
            return
        if "OPERATOR_CLIENT_TEST FAILED" in text:
            raise RuntimeError("Minecraft scene controller failed before UI interaction")
        if proc.poll() is not None:
            raise RuntimeError("Minecraft client exited during initial world loading")
        time.sleep(1)
    screenshot("00-client-menu.png")
    # The remaining interactions are a last-resort menu fallback.

    # First-run accessibility introduction (Continue button at bottom).
    click_ui(640, 665)
    time.sleep(4)
    screenshot("00-after-first-run-continue.png")
    if CLIENT_LOG.is_file() and "OPERATOR_CLIENT_TEST SCENE_READY" in CLIENT_LOG.read_text(errors="replace"):
        return

    # Singleplayer button in vanilla Minecraft's central title menu.
    click_ui(640, 353)
    time.sleep(7)
    screenshot("00-after-singleplayer.png")
    if CLIENT_LOG.is_file() and "OPERATOR_CLIENT_TEST SCENE_READY" in CLIENT_LOG.read_text(errors="replace"):
        return

    # One seeded CI world exists. Select its row and choose Play Selected.
    click_ui(640, 174)
    time.sleep(1)
    click_ui(640, 663)
    time.sleep(9)
    screenshot("00-after-world-join-attempt.png")


def acknowledge(name: str) -> None:
    path = MARKER_DIR / name
    if path.exists():
        raise RuntimeError("Stale screenshot acknowledgement " + str(path))
    path.write_text("frame captured from actual Minecraft client\n")


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("Refusing to create screenshots outside isolated GitHub CI")
    if not os.environ.get("DISPLAY"):
        raise RuntimeError("Xvfb DISPLAY is required for a real client screenshot")
    if not SOURCE.is_dir() or not (SOURCE / "level.dat").is_file():
        raise RuntimeError("Missing disposable Fabric server world from earlier CI smoke")
    if WORLD.exists():
        raise RuntimeError("Client test world already exists; refusing to overwrite it")
    (RUN / "saves").mkdir(exist_ok=True)
    shutil.copytree(SOURCE, WORLD)
    MARKER_DIR.mkdir(parents=True, exist_ok=True)
    (MARKER_DIR / "ci-client.allow").write_text("isolated graphical CI world\n")
    SCREENSHOTS.mkdir(exist_ok=True)
    # Screenshot audit of the previous CI run proved that SDL3/Wayland mouse
    # events did not dismiss the first-launch Welcome to Minecraft overlay.
    # The documented vanilla option bypasses the overlay entirely. This is
    # confined to the isolated CI client game directory, never a user's game.
    (RUN / "options.txt").write_text(
        "version:5023\\nonboardAccessibility:false\\nnarrator:0\\n"
    )
    if CLIENT_LOG.exists():
        CLIENT_LOG.unlink()

    env = os.environ.copy()
    env["JAVA_TOOL_OPTIONS"] = (
        env.get("JAVA_TOOL_OPTIONS", "")
        + " -Doperator.reality.ciClientSmoke=true -Djava.awt.headless=false -Xmx4G"
    ).strip()
    env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
    # Minecraft 26.3 SDL3/RenderPearl cannot create the requested GLX visual
    # on Xvfb, despite GL 4.5 being available. Use a nested native-Wayland
    # client, rendered into the Xvfb-visible Weston compositor window.
    env["SDL_VIDEO_DRIVER"] = "wayland"
    env["XDG_SESSION_TYPE"] = "wayland"
    # Gradle's Fabric Loom client run keeps this same run/ gameDirectory.
    command = [
        "gradle", "runClient", "--no-daemon",
        "--args=--quickPlaySingleplayer " + WORLD_NAME,
    ]
    log("Starting actual Fabric Minecraft client with quick-play world")
    with CONSOLE.open("wb") as console:
        proc = subprocess.Popen(
            command, cwd=ROOT, env=env, stdout=console, stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            try_open_existing_world(proc)
            logs = await_marker(proc, "OPERATOR_CLIENT_TEST SCENE_READY", 95)
            if "starting integrated minecraft server" not in logs.lower():
                log("World spawn confirmed by actual ServerPlayer callback")
            time.sleep(8)
            before = screenshot("01-before-fold.png")
            acknowledge("ci-before-captured.allow")

            await_marker(proc, "OPERATOR_CLIENT_TEST AUTONOMOUS_BLOCK_MOVED", 90)
            time.sleep(3)
            after = screenshot("02-after-fold.png")
            acknowledge("ci-after-captured.allow")

            await_marker(proc, "OPERATOR_CLIENT_TEST ROLLBACK_COMPLETE", 90)
            time.sleep(3)
            restored = screenshot("03-restored.png")
            acknowledge("ci-restored-captured.allow")

            await_marker(proc, "OPERATOR_CLIENT_TEST SUCCESS", 35)
            # A simple non-identical frame check is meaningless: the camera
            # can rotate while the block stays visually unchanged. Verify a
            # material change specifically between the stable AFTER and
            # ROLLED-BACK frames, where the camera target is the same.
            with Image.open(after) as after_img, Image.open(restored) as restored_img:
                aa = after_img.convert("RGB")
                bb = restored_img.convert("RGB")
                delta = ImageChops.difference(aa, bb)
                vis = delta.crop((350, 100, 930, 560)).convert("L")
                material_pixels = sum(
                    1 for px in vis.getdata() if px >= 35
                )
                amplified = delta.point(lambda x: min(255, x * 5))
                amplified.save(SCREENSHOTS / "04-after-vs-restored-difference.png")
                log(f"Stable-camera change in mutation ROI: {material_pixels} pixels")
                if material_pixels < 1500:
                    raise RuntimeError(
                        f"Block relocation confirmed server-side, but only "
                        f"{material_pixels} visually changed pixels in expected ROI "
                        f"between AFTER and RESTORED. The result is not visible."
                    )
            with Image.open(before) as a, Image.open(after) as b:
                if ImageChops.difference(a.convert("RGB"), b.convert("RGB")).getbbox() is None:
                    raise RuntimeError("Before and after frames are exactly identical")
            log("In-game AFTER vs RESTORED material block difference verified")
            with Image.open(restored) as image:
                if image.size != (1280, 720):
                    raise RuntimeError("Bad restoration screenshot resolution")
            client_log = CLIENT_LOG.read_text(errors="replace")
            if not any("logged in with entity id" in line.lower()
                       or "joined the game" in line.lower()
                       for line in client_log.splitlines()):
                raise RuntimeError("No actual player-join event recorded")
            # Independently assert the CLIENT'S own chunk states and rotation.
            # The integrated-server's authoritative logs alone cannot prove
            # that the rendered client received the changed blocks.
            # Minecraft normalizes yaw=180 and yaw=-180 interchangeably;
            # both face north. Test equivalence, not textual formatting.
            samples = [
                (match.group(1), match.group(2))
                for match in re.finditer(
                    r"OPERATOR_CLIENT_RENDER_STATE source=(STONE|AIR) "
                    r"target=(STONE|AIR) camera=\(0,200,4\) yaw=(-?180\.0)",
                    client_log,
                )
            ]
            expected_states = [("STONE", "AIR"), ("AIR", "STONE"),
                               ("STONE", "AIR")]
            progress = 0
            for state in samples:
                if state == expected_states[progress]:
                    progress += 1
                    if progress == len(expected_states):
                        break
            if progress != len(expected_states):
                raise RuntimeError(
                    "Real client did not confirm STONE/AIR -> AIR/STONE -> "
                    "STONE/AIR with camera facing north (+/-180 degrees); "
                    f"got {samples!r}"
                )
            log("Actual Minecraft client BlockState and yaw=180 synchronization verified")
            log("OPERATOR_REAL_CLIENT_WORLD_SCREENSHOTS_SUCCESS")
        except BaseException:
            print("--- last 100 console lines ---", file=sys.stderr)
            print("\n".join(CONSOLE.read_text(errors="replace").splitlines()[-100:]),
                  file=sys.stderr)
            if CLIENT_LOG.is_file():
                print("--- last 100 Minecraft lines ---", file=sys.stderr)
                print("\n".join(CLIENT_LOG.read_text(errors="replace").splitlines()[-100:]),
                      file=sys.stderr)
            raise
        finally:
            if proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    proc.wait(timeout=12)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=12)


if __name__ == "__main__":
    main()
