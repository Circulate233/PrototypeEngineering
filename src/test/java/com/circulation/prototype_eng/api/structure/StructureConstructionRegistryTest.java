package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstructionRegistryTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void formationCheckReceivesExactStateAndBothPresentAndMissingTiles() {
        var anchor = Blocks.STONE.getDefaultState();
        var tile = new TileEntity() {
        };
        var registry = new StructureConstructionRegistry();
        registry.register(anchor, new ResourceLocation("test", "external"), () -> "test.external", _ -> BlockOrientation.NORTH_UP,
            (state, foundTile) -> {
                assertSame(anchor, state);
                if (foundTile == null) return false;
                assertSame(tile, foundTile);
                return true;
            });
        var entry = registry.find(anchor);
        assertNotNull(entry);
        assertFalse(entry.isFormed(anchor, null, null, BlockPos.ORIGIN));
        assertTrue(entry.isFormed(anchor, tile, null, BlockPos.ORIGIN));
    }

    @Test
    void rotationStatesFindTheirRegistrationAndAnOverlapDoesNotLeavePartialBindings() {
        var registry = new StructureConstructionRegistry();
        var rotatingId = new ResourceLocation("test", "rotating");
        var fixedId = new ResourceLocation("test", "fixed");
        var rotation = StructureAnchorRotation.fourWay(BlockStairs.FACING);
        var anchor = stairs(EnumFacing.NORTH, BlockStairs.EnumHalf.BOTTOM);
        var fixed = stairs(EnumFacing.WEST, BlockStairs.EnumHalf.TOP);
        var conflicting = stairs(EnumFacing.NORTH, BlockStairs.EnumHalf.TOP);
        registry.register(anchor, rotatingId, () -> "test.rotating", rotation);
        registry.register(fixed, fixedId, () -> "test.fixed");
        assertThrows(IllegalArgumentException.class, () -> registry.register(conflicting, fixedId, () -> "test.overlap", rotation));
        assertNull(registry.find(conflicting));

        registry.freeze(new StructureCatalog(List.of(definition(rotatingId, BlockOrientation.NORTH_UP), definition(fixedId, BlockOrientation.EAST_UP))));
        var entry = registry.find(anchor);
        assertNotNull(entry);
        assertSame(entry, registry.find(stairs(EnumFacing.SOUTH, BlockStairs.EnumHalf.BOTTOM)));
        assertSame(entry, registry.find(stairs(EnumFacing.WEST, BlockStairs.EnumHalf.BOTTOM)));
        assertSame(entry, registry.find(stairs(EnumFacing.EAST, BlockStairs.EnumHalf.BOTTOM)));
        assertSame(BlockOrientation.SOUTH_UP, entry.orientation(stairs(EnumFacing.SOUTH, BlockStairs.EnumHalf.BOTTOM), null));
        assertEquals(Set.of(anchor, fixed), registry.bindings().keySet());

        var fixedEntry = registry.find(fixed);
        assertNotNull(fixedEntry);
        assertTrue(fixedEntry.fixed());
        assertNull(fixedEntry.rotation());
        assertSame(BlockOrientation.EAST_UP, fixedEntry.orientation(fixed, null));
    }

    private static IBlockState stairs(EnumFacing facing, BlockStairs.EnumHalf half) {
        return Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, facing).withProperty(BlockStairs.HALF, half);
    }

    private static StructureDefinition definition(ResourceLocation id, BlockOrientation orientation) {
        var pattern = new StructurePattern(BlockPos2ObjectMap.emptyMap());
        return new StructureDefinition(StructureDefinitions.template(id, orientation, BlockPos.ORIGIN, false, pattern, List.of(), List.of()));
    }
}
