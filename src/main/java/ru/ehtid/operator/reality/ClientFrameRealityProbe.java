package ru.ehtid.operator.reality;

import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.ehtid.operator.OperatorMod;

/**
 * Real-client acceptance probe. Uses the current vanilla camera/frustum
 * before level extraction, rather than player yaw sampled by a server tick.
 * The selected payload is a prebuilt immutable four-cell strip description,
 * NOT a compiled GPU mesh or an applied/client-authoritative collision update.
 */
public final class ClientFrameRealityProbe {
    private static final boolean ENABLED =
            Boolean.getBoolean("operator.reality.ciClientSmoke")
                    && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final String REGION = "ci-tunnel-strip";
    private static final FrameRealitySelector.Bounds BOUNDS =
            new FrameRealitySelector.Bounds(0, 202, 0, 2, 203, 2);
    private static final AABB GUARDED_BOUNDS = new AABB(0,202,0,2,203,2).inflate(0.25D);
    private static final FrameRealitySelector<List<String>> SELECTOR =
            new FrameRealitySelector<>();
    private static ClientLevel previousLevel;
    private static Vec3 previousPosition;
    private static float previousYaw;
    private static boolean armed;
    private static boolean done;

    private ClientFrameRealityProbe() { }

    public static void onFrame(Camera camera) {
        if (!ENABLED) return;
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.getSingleplayerServer() == null) return;
        if (client.level != previousLevel) {
            SELECTOR.clear();
            previousLevel = client.level;
            armed = false;
            done = false;
            previousPosition = null;
        }
        if (done || client.level == null || camera == null) return;
        if (camera.isPanoramicMode() || camera.getCapturedFrustum() != null
                || camera.getCullFrustum() == null || camera.entity() == null) {
            SELECTOR.sample(null);
            previousPosition = null;
            return;
        }
        Vec3 eye = camera.position();
        float yaw = camera.yRot();
        if (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)
                || !Float.isFinite(yaw)) {
            SELECTOR.sample(null);
            previousPosition = null;
            return;
        }

        // Arm only at the first real fixture viewpoint, with the strip
        // within the vanilla frustum. The test does NOT select while invisible.
        if (!armed) {
            if (Math.abs(eye.x - .5D) > .3D || Math.abs(eye.z - 4.5D) > .3D
                    || eye.y < 200D || eye.y > 204D
                    || Math.abs(Math.abs(yaw) - 180F) > 8F
                    || !camera.getCullFrustum().isVisible(GUARDED_BOUNDS)) return;
            SELECTOR.watch(REGION, BOUNDS);
            SELECTOR.prepare(REGION, 1L, List.of("air", "air", "stone", "stone"));
            armed = true;
            OperatorMod.LOGGER.info("OPERATOR_FRAME_REALITY_ARMED preparedRevision=1");
        }
        long start = System.nanoTime();
        // An expanded AABB counts as visible conservatively, even when its
        // center is outside. Nothing in the live world is mutated here.
        var selected = SELECTOR.sample(bounds ->
                camera.getCullFrustum().isVisible(GUARDED_BOUNDS)
                || GUARDED_BOUNDS.inflate(1D).contains(eye));
        long elapsed = System.nanoTime() - start;
        for (var event : selected) {
            double rotation = Math.abs(Math.IEEEremainder(yaw - previousYaw, 360D));
            double translation = previousPosition == null
                    ? Double.POSITIVE_INFINITY : eye.distanceTo(previousPosition);
            if (event.exitFrame() - event.previousVisibleFrame() == 1
                    && rotation >= 150D && translation <= .25D
                    && event.revision() == 1L && event.variant().size() == 4) {
                OperatorMod.LOGGER.info("OPERATOR_FRAME_REALITY_EXIT_SUCCESS "
                        + "previousFrame={} exitFrame={} yawDelta={} positionDelta={} "
                        + "selectionNanos={} variantRevision={}",
                        event.previousVisibleFrame(), event.exitFrame(), rotation,
                        translation, elapsed, event.revision());
                done = true;
            } else {
                OperatorMod.LOGGER.error("OPERATOR_FRAME_REALITY_EXIT_INVALID "
                        + "rotation={} translation={}", rotation, translation);
                done = true;
            }
        }
        previousYaw = yaw;
        previousPosition = eye;
    }
}
