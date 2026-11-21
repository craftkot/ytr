package ru.ehtid.operator.reality;

import java.util.Optional;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** CI-only diagnostic world pass. Draws resident buffers, not rebuilt vertices.
 * Both an X-ray control and an ordinary depth-tested, read-only pass are
 * provided for comparing occlusion in the same camera view. No terrain replacement. */
final class TerrainDiagnosticDraw {
    private static final RenderPipeline XRAY=pipeline(false);
    private static final RenderPipeline DEPTH=pipeline(true);

    private static RenderPipeline pipeline(boolean depth) {
        return RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("operator_reality",
                        depth?"pipeline/terrain_depth":"pipeline/terrain_diagnostic"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0,DefaultVertexFormat.POSITION_COLOR)
                .withDepthStencilState(depth
                        ? Optional.of(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(),false))
                        : Optional.empty())
                .withCull(false).build());
    }
    private static final Vector4f ORIGINAL=new Vector4f(1f,0f,1f,1f);
    private static final Vector4f ALTERNATE=new Vector4f(0f,1f,1f,1f);
    private static final Vector3f OFFSET=new Vector3f();
    private static final Matrix4f TEXTURE=new Matrix4f();

    private TerrainDiagnosticDraw() {}
    static void draw(RenderPass pass,GpuTerrainMeshPair pair,boolean alternate,
                     int x,int z,Vec3 eye,boolean depth) {
        RenderSystem.assertOnRenderThread();
        var transform=new Matrix4f(RenderSystem.getModelViewMatrixCopy());
        transform.translate((float)(x-eye.x),(float)-eye.y,(float)(z-eye.z));
        var uniform=RenderSystem.getDynamicUniforms().writeTransform(
                transform,alternate?ALTERNATE:ORIGINAL,OFFSET,TEXTURE);
        pass.pushDebugGroup(()->"Operator resident terrain diagnostic");
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(depth?DEPTH:XRAY));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",uniform);
            pass.setVertexBuffer(0,pair.buffer(alternate).slice());
            // Draw each independently addressable section directly from
            // its resident vertex range; no per-section copies or uploads.
            for(var range:pair.ranges(alternate).values())
                pass.draw(range.vertexCount(),1,range.firstVertex(),0);
        } finally { pass.popDebugGroup(); }
    }
}
