package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstructionRegistryTest {
    @Test
    void formationCheckReceivesExactStateAndBothPresentAndMissingTiles() {
        var anchor = (IBlockState) Proxy.newProxyInstance(IBlockState.class.getClassLoader(), new Class<?>[]{IBlockState.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var tile = new TileEntity() { };
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
}
