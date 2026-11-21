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
    private static final RenderPipeline XRAY=pipeline(false,false);
    private static final RenderPipeline DEPTH=pipeline(true,false);
    private static final RenderPipeline REPLACEMENT=pipeline(true,true);

    private static RenderPipeline pipeline(boolean depth,boolean write) {
        return RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("operator_reality",
                        write?"pipeline/terrain_replacement":depth?"pipeline/terrain_depth":"pipeline/terrain_diagnostic"))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withVertexBinding(0,DefaultVertexFormat.POSITION_COLOR)
                .withDepthStencilState(depth
                        ? Optional.of(new DepthStencilState(DepthStencilState.DEFAULT.depthTest(),write))
                        : Optional.empty())
                .withCull(false).build());
    }
    private static final Vector4f ORIGINAL=new Vector4f(1f,0f,1f,1f);
    private static final Vector4f ALTERNATE=new Vector4f(0f,1f,1f,1f);
    private static final Vector3f OFFSET=new Vector3f();
    private static final Matrix4f TEXTURE=new Matrix4f();

    private TerrainDiagnosticDraw() {}
    static boolean replacementReady() {
        return RenderSystem.getCompiledPipeline(REPLACEMENT)!=null;
    }
    static void draw(RenderPass pass,GpuTerrainMeshPair pair,boolean alternate,
                     int x,int z,Vec3 eye,boolean depth) {
        drawRanges(pass,pair,alternate,x,z,eye,depth?DEPTH:XRAY,null);
    }

    static void drawReplacement(RenderPass pass,GpuTerrainMeshPair pair,boolean alternate,
                     int x,int z,Vec3 eye,SectionTerrainMesh.Key key) {
        drawRanges(pass,pair,alternate,x,z,eye,REPLACEMENT,key);
    }

    private static void drawRanges(RenderPass pass,GpuTerrainMeshPair pair,boolean alternate,
                     int x,int z,Vec3 eye,RenderPipeline pipeline,SectionTerrainMesh.Key key) {
        RenderSystem.assertOnRenderThread();
        var transform=new Matrix4f(RenderSystem.getModelViewMatrixCopy());
        transform.translate((float)(x-eye.x),(float)-eye.y,(float)(z-eye.z));
        var uniform=RenderSystem.getDynamicUniforms().writeTransform(
                transform,alternate?ALTERNATE:ORIGINAL,OFFSET,TEXTURE);
        pass.pushDebugGroup(()->"Operator resident terrain diagnostic");
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",uniform);
            pass.setVertexBuffer(0,pair.buffer(alternate).slice());
            // Draw each independently addressable section directly from
            // its resident vertex range; no per-section copies or uploads.
            for(var entry:pair.ranges(alternate).entrySet()) {
                if(key!=null && !key.equals(entry.getKey())) continue;
                var range=entry.getValue();
                pass.draw(range.vertexCount(),1,range.firstVertex(),0);
            }
        } finally { pass.popDebugGroup(); }
    }
}
