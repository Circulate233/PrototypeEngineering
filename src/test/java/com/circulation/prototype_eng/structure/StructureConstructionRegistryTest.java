package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureConstructionRegistry;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureConstructionRegistryTest {
    private static final ResourceLocation ID = new ResourceLocation("builder_test", "external");

    private static IBlockState state() {
        // Exercise exact registration identity without creating Minecraft blocks or a game registry.
        return (IBlockState) Proxy.newProxyInstance(IBlockState.class.getClassLoader(), new Class<?>[]{IBlockState.class}, (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "external_anchor";
            case "getPropertyKeys" -> List.of();
            default -> throw new AssertionError("Unexpected world/state read: " + method.getName());
        });
    }

    private static StructureCatalog catalog() {
        return StructureCatalog.fromShapes(Map.of(ID, TemplateStructure.create(ID, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(Map.of()), List.of(), List.of())));
    }

    @Test
    void ordinaryStatesCanBindAnExistingStructureWithoutControllerOrTile() {
        var registry = new StructureConstructionRegistry();
        var anchor = state();
        registry.register(anchor, ID, () -> "builder.test.external");
        registry.freeze(catalog());
        var entry = registry.find(anchor);
        assertNotNull(entry);
        assertEquals(ID, entry.structure());
        assertSame(BlockOrientation.NORTH_UP, entry.orientation().apply(anchor));
        assertNull(registry.find(state()));
        assertThrows(IllegalStateException.class, () -> registry.register(state(), ID, () -> "late"));
    }

    @Test
    void orientationCallbackIsRetainedAndDuplicateAnchorsAreRejected() {
        var registry = new StructureConstructionRegistry();
        var anchor = state();
        registry.register(anchor, ID, () -> "external", _ -> BlockOrientation.SOUTH_UP);
        var entry = registry.find(anchor);
        assertNotNull(entry);
        assertSame(BlockOrientation.SOUTH_UP, entry.orientation().apply(anchor));
        assertThrows(IllegalArgumentException.class, () -> registry.register(anchor, ID, () -> "duplicate"));
    }

    @Test
    void missingStructuresFailAtRegistrationFreeze() {
        var registry = new StructureConstructionRegistry();
        registry.register(state(), ID, () -> "external");
        assertThrows(IllegalArgumentException.class, () -> registry.freeze(new StructureCatalog(List.of())));
    }
}
