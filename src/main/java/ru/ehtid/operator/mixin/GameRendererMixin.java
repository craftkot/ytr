package ru.ehtid.operator.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ehtid.operator.reality.ClientTerrainMeshProbe;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void operator$closeTerrainGpuBuffers(CallbackInfo ci) {
        ClientTerrainMeshProbe.closeGpu();
        ru.ehtid.operator.reality.ClientDynamicTerrainProbe.closeGpu();
        ru.ehtid.operator.reality.ClientVoxelSectionProbe.closeGpu();
    }
}
