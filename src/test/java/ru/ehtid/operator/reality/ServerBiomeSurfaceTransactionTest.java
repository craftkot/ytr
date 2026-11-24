package ru.ehtid.operator.reality;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerBiomeSurfaceTransactionTest {
    @Test void leafDistanceIsDerivedButAllOtherVanillaBlockStatePropertiesMustMatch() {
        var source=Blocks.SPRUCE_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT,true)
                .setValue(LeavesBlock.DISTANCE,1);
        var gameUpdatedDistance=source.setValue(LeavesBlock.DISTANCE,7);
        assertNotEquals(source,gameUpdatedDistance);
        assertTrue(ServerBiomeSurfaceTransaction.matchesGuard(source,source));
        assertTrue(ServerBiomeSurfaceTransaction.matchesGuard(
                gameUpdatedDistance,source));

        // A different leaf type, persistent state, or waterlogged state
        // still vetoes the biome transaction and must not be overwritten.
        assertFalse(ServerBiomeSurfaceTransaction.matchesGuard(
                Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),
                source));
        assertFalse(ServerBiomeSurfaceTransaction.matchesGuard(
                gameUpdatedDistance.setValue(LeavesBlock.PERSISTENT,false),
                source));
        assertFalse(ServerBiomeSurfaceTransaction.matchesGuard(
                Blocks.STONE.defaultBlockState(),source));
        assertFalse(ServerBiomeSurfaceTransaction.matchesGuard(
                Blocks.AIR.defaultBlockState(),source));
    }

    @Test void nonLeafChangesStillRequireExactProperties() {
        var original=Blocks.OAK_LOG.defaultBlockState();
        var xAxis=original.setValue(
                net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                net.minecraft.core.Direction.Axis.X);
        assertFalse(ServerBiomeSurfaceTransaction.matchesGuard(xAxis,original));
        assertTrue(ServerBiomeSurfaceTransaction.matchesGuard(original,original));
        assertFalse(ServerBiomeSurfaceTransaction.matchesGuard(
                Blocks.PODZOL.defaultBlockState(),
                Blocks.GRASS_BLOCK.defaultBlockState()));
    }
}
