package ru.ehtid.operator.reality;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockStateFacePlannerTest {
    private static BlockStateSectionPalette.StateKey material(String name) {
        return BlockStateSectionPalette.StateKey.simple("minecraft:"+name);
    }
    private static BlockStateSectionPalette.StateKey[] cells(
            BlockStateSectionPalette.StateKey value) {
        var cells=new BlockStateSectionPalette.StateKey[4096];
        Arrays.fill(cells,value);
        return cells;
    }
    private static BlockStateSectionPalette.StateKey[][] halo(
            BlockStateSectionPalette.StateKey value) {
        var faces=new BlockStateSectionPalette.StateKey[6][256];
        for(var face:faces)Arrays.fill(face,value);
        return faces;
    }
    @Test void materialIdentitiesAndOrientationSurviveFaceGeneration() {
        var blocks=cells(material("air"));
        var logX=new BlockStateSectionPalette.StateKey(
                "minecraft:oak_log",Map.of("axis","x"));
        var dirt=material("dirt");
        blocks[VoxelSectionSurface.index(6,7,8)]=logX;
        blocks[VoxelSectionSurface.index(7,7,8)]=dirt;
        var snapshot=BlockStateSectionPalette.capture(blocks,halo(material("air")));
        var mesh=BlockStateFacePlanner.plan(snapshot,BlockStateFacePlanner::testFixtureShape);
        assertEquals(2,mesh.opaqueCells());
        assertEquals(4094,mesh.airCells());
        assertEquals(0,mesh.vanillaCells());
        assertEquals(0,mesh.unresolvedNeighbors());
        assertTrue(mesh.entirelyFullCubesOrAir());
        assertEquals(10,mesh.faces().size());
        assertEquals(5,mesh.faces().stream().filter(f->f.state().equals(logX)).count());
        assertEquals(5,mesh.faces().stream().filter(f->f.state().equals(dirt)).count());
        assertTrue(mesh.faces().stream().filter(f->
                f.state().equals(logX)
                && f.quad().face()==VoxelSectionSurface.Face.WEST).count()==1);
        assertThrows(UnsupportedOperationException.class,()->mesh.faces().clear());
    }
    @Test void glassAndSlabsStayVanillaOwnedAndNeverBecomeSolidCubeFaces() {
        var blocks=cells(material("air"));
        blocks[VoxelSectionSurface.index(5,7,7)]=material("stone");
        blocks[VoxelSectionSurface.index(6,7,7)]=material("glass");
        blocks[VoxelSectionSurface.index(7,7,7)]=new BlockStateSectionPalette.StateKey(
                "minecraft:oak_slab",Map.of("type","top","waterlogged","false"));
        var snapshot=BlockStateSectionPalette.capture(blocks,halo(material("air")));
        var plan=BlockStateFacePlanner.plan(snapshot,BlockStateFacePlanner::testFixtureShape);
        assertEquals(1,plan.opaqueCells());
        assertEquals(2,plan.vanillaCells());
        assertEquals(4093,plan.airCells());
        assertEquals(1,plan.unresolvedNeighbors());
        assertEquals(5,plan.faces().size());
        assertFalse(plan.entirelyFullCubesOrAir());
        assertTrue(plan.faces().stream().noneMatch(f->
                f.state().blockId().equals("minecraft:glass")
                || f.state().blockId().equals("minecraft:oak_slab")));
    }
    @Test void twoNeighborSectionsSuppressInternalBoundaryFaces() {
        var blocks=cells(material("air"));
        blocks[VoxelSectionSurface.index(15,7,7)]=material("stone");
        var neighbor=halo(material("air"));
        neighbor[VoxelSectionSurface.Face.EAST.ordinal()][7*16+7]=material("dirt");
        var plan=BlockStateFacePlanner.plan(
                BlockStateSectionPalette.capture(blocks,neighbor),
                BlockStateFacePlanner::testFixtureShape);
        assertEquals(5,plan.faces().size());
        assertEquals(0,plan.faces().stream().filter(
                f->f.quad().face()==VoxelSectionSurface.Face.EAST).count());
        assertTrue(plan.entirelyFullCubesOrAir());
    }
    @Test void unknownNeighborDisablesFullCoverageAndNullShapeFailsClosed() {
        var blocks=cells(material("air"));
        blocks[VoxelSectionSurface.index(0,7,7)]=material("stone");
        var neighbor=halo(material("air"));
        neighbor[VoxelSectionSurface.Face.WEST.ordinal()][7*16+7]=material("sea_lantern");
        var snapshot=BlockStateSectionPalette.capture(blocks,neighbor);
        var mesh=BlockStateFacePlanner.plan(snapshot,BlockStateFacePlanner::testFixtureShape);
        assertEquals(1,mesh.unresolvedNeighbors());
        assertFalse(mesh.entirelyFullCubesOrAir());
        assertEquals(5,mesh.faces().size());
        assertThrows(NullPointerException.class,()->BlockStateFacePlanner.plan(
                snapshot,key->key.blockId().endsWith("sea_lantern")
                        ?null:BlockStateFacePlanner.testFixtureShape(key)));
    }
    @Test void identicalBlockStateCanHavePositionDependentShapeAndMustNotBeMisclassified() {
        var core=cells(material("air"));
        int first=VoxelSectionSurface.index(8,8,8);
        int second=VoxelSectionSurface.index(9,8,8);
        core[first]=material("stone");
        core[second]=material("stone");
        var palette=BlockStateSectionPalette.capture(core,halo(material("air")));
        byte[] shape=new byte[BlockStateSectionPalette.TOTAL];
        shape[first]=(byte)BlockStateFacePlanner.Shape.FULL_OPAQUE.ordinal();
        shape[second]=(byte)BlockStateFacePlanner.Shape.VANILLA_REQUIRED.ordinal();
        var selected=BlockStateFacePlanner.plan(palette,shape);
        assertEquals(1,selected.opaqueCells());
        assertEquals(1,selected.vanillaCells());
        assertEquals(4094,selected.airCells());
        assertEquals(1,selected.unresolvedNeighbors());
        assertEquals(5,selected.faces().size());
        assertFalse(selected.entirelyFullCubesOrAir());
        assertTrue(selected.faces().stream().allMatch(face->
                face.state().equals(material("stone"))));
        var invalid=shape.clone();
        invalid[0]=7;
        assertThrows(IllegalArgumentException.class,
                ()->BlockStateFacePlanner.plan(palette,invalid));
        assertThrows(IllegalArgumentException.class,
                ()->BlockStateFacePlanner.plan(palette,new byte[4096]));
    }

    @Test void runtimeHaloClassificationSuppressesBoundaryFacesWithoutLosingMaterial() {
        var core=cells(material("air"));
        var all=halo(material("air"));
        int position=VoxelSectionSurface.index(0,7,7);
        core[position]=new BlockStateSectionPalette.StateKey("minecraft:oak_log",Map.of("axis","x"));
        all[VoxelSectionSurface.Face.WEST.ordinal()][7*16+7]=material("glass");
        var palette=BlockStateSectionPalette.capture(core,all);
        byte[] masks=new byte[BlockStateSectionPalette.TOTAL];
        masks[position]=(byte)BlockStateFacePlanner.Shape.FULL_OPAQUE.ordinal();
        int west=BlockStateSectionPalette.BLOCKS+7*16+7;
        masks[west]=(byte)BlockStateFacePlanner.Shape.VANILLA_REQUIRED.ordinal();
        var result=BlockStateFacePlanner.plan(palette,masks);
        assertEquals(5,result.faces().size());
        assertEquals(1,result.unresolvedNeighbors());
        assertTrue(result.faces().stream().allMatch(face->
                face.state().properties().get("axis").equals("x")));
    }

}
