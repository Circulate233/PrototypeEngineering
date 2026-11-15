package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.localization.RecipeText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class MachineTypeRegistryTest {
    private static MachineType type(String machine, String structure) {
        return MachineType.builder(id(machine), RecipeText.Cancelled, id(structure)).build();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }

    @Test
    void rejectedBatchesPreservePendingDeclarationsAndAcceptLaterCorrectionInOrder() {
        var registry = new MachineTypeRegistry();
        var first = type("first", "shape");
        var second = type("second", "shape");
        var third = type("third", "shape");
        registry.register(first);
        assertThrows(IllegalArgumentException.class, () -> registry.registerAll(List.of(second, type("first", "shape"))));
        assertThrows(IllegalArgumentException.class, () -> registry.registerAll(List.of(second, second)));
        registry.registerAll(List.of(second, third));
        registry.freeze();

        assertEquals(List.of(first, second, third), registry.types());
        assertSame(first, registry.get(first.id()));
        assertThrows(IllegalStateException.class, () -> registry.register(type("late", "shape")));
        assertThrows(IllegalStateException.class, registry::freeze);
    }

    @Test
    void missingOrInvalidStructuresPublishNothingAndCanBeCorrectedWithoutReplacingTypes() {
        var registry = new MachineTypeRegistry();
        var first = type("first", "first_shape");
        var second = type("second", "second_shape");
        var firstShape = new Shape(id("first_shape"));
        var secondShape = new Shape(id("second_shape"));
        registry.registerAll(List.of(first, second));
        registry.freeze();

        assertThrows(IllegalStateException.class, () -> registry.structure(first));
        assertThrows(IllegalArgumentException.class, () -> registry.resolveStructures(Map.of(firstShape.id(), firstShape)));
        assertThrows(IllegalStateException.class, () -> registry.structure(first));
        assertThrows(IllegalArgumentException.class, () -> registry.resolveStructures(Map.of(firstShape.id(), firstShape, secondShape.id(), firstShape)));
        assertThrows(IllegalStateException.class, () -> registry.structure(second));
        registry.resolveStructures(Map.of(firstShape.id(), firstShape, secondShape.id(), secondShape));

        assertSame(first, registry.get(first.id()));
        assertSame(second, registry.get(second.id()));
        assertSame(firstShape, registry.structure(first));
        assertSame(secondShape, registry.structure(second));
        assertThrows(IllegalStateException.class, () -> registry.resolveStructures(Map.of()));
    }

    @Test
    void equalIdsCannotImpersonateTheCanonicalMachineDefinition() {
        var canonical = type("machine", "shape");
        var impersonator = type("machine", "shape");
        var registry = new MachineTypeRegistry();
        registry.register(canonical);
        registry.freeze();
        var shape = new Shape(id("shape"));
        registry.resolveStructures(Map.of(shape.id(), shape));

        assertSame(shape, registry.structure(canonical));
        assertThrows(IllegalArgumentException.class, () -> registry.structure(impersonator));
        assertThrows(IllegalArgumentException.class, () -> registry.structure(type("absent", "shape")));
    }

    @Test
    void definitionValidationAndBothRegistryStagesNeverExecuteAComponentFactory() {
        var first = new ComponentType<MachineInstance, Object>(id("first_component"), _ -> fail("Definition cannot create components"), List.of());
        var second = new ComponentType<MachineInstance, Object>(id("second_component"), _ -> fail("Definition cannot create components"), List.of());
        var builder = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).component(first);
        var canonical = builder.build();
        builder.component(second);
        var registry = new MachineTypeRegistry();
        registry.register(canonical);
        registry.freeze();
        var shape = new Shape(id("shape"));
        registry.resolveStructures(Map.of(shape.id(), shape));

        // Reusing the builder cannot modify the declaration already published by either stage.
        assertEquals(List.of(first), canonical.componentTypes());
        assertSame(shape, registry.structure(canonical));
    }

    private record Shape(ResourceLocation shapeId, Runnable onReadId) implements MachineStructure {
        private Shape(ResourceLocation id) {
            this(id, () -> {
            });
        }

        @Override
        public ResourceLocation id() {
            onReadId.run();
            return shapeId;
        }

        @Override
        public BlockOrientation orientation() {
            return fail("Registration must not inspect orientation");
        }

        @Override
        public boolean hideWorldBlocks() {
            return fail("Registration must not inspect rendering");
        }

        @Override
        public MachineStructure transform(StructureTransform rotation) {
            return fail("Registration must not transform a structure");
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) {
            fail("Registration must not read the world");
        }

        @Override
        public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) {
            return fail("Registration must not match a structure");
        }
    }
}
