package ru.ehtid.operator.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ehtid.operator.reality.ClientFrameRealityProbe;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void operator$sampleCameraFrame(DeltaTracker deltaTracker, Camera camera,
            float worldPartialTicks, CallbackInfo ci) {
        ClientFrameRealityProbe.onFrame(camera);
        ru.ehtid.operator.reality.ClientTerrainMeshProbe.onFrame(camera);
        ru.ehtid.operator.reality.ClientDynamicTerrainProbe.onFrame(camera);
        ru.ehtid.operator.reality.ClientVoxelSectionProbe.onFrame(camera);
        ru.ehtid.operator.reality.ClientOffscreenBiomeProbe.onFrame(camera);
    }
}
