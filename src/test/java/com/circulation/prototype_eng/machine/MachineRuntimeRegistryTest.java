package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.localization.RecipeText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class MachineRuntimeRegistryTest {
    @Test
    void incompleteOrInvalidRuntimePublishesNeitherBindingAndAcceptsACompleteRetry() {
        var first = type("first", "first_shape");
        var second = type("second", "second_shape");
        var firstShape = new Shape(first.structureId());
        var secondShape = new Shape(second.structureId());
        var structures = Map.of(firstShape.id(), firstShape, secondShape.id(), secondShape);
        var firstAssembly = assembly(first);
        var secondAssembly = assembly(second);
        var registry = frozen(first, second);

        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(Map.of(firstShape.id(), firstShape), List.of(firstAssembly, secondAssembly)));
        assertUnpublished(registry, first, second);
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(Map.of(firstShape.id(), secondShape, secondShape.id(), secondShape), List.of(firstAssembly, secondAssembly)));
        assertUnpublished(registry, first, second);
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(structures, List.of(firstAssembly)));
        assertUnpublished(registry, first, second);
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(structures, List.of(firstAssembly, firstAssembly, secondAssembly)));
        assertUnpublished(registry, first, second);
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(structures, List.of(firstAssembly, assembly(type("second", "second_shape")))));
        assertUnpublished(registry, first, second);
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(structures, List.of(firstAssembly, secondAssembly, assembly(type("unknown", "first_shape")))));
        assertUnpublished(registry, first, second);
        assertThrows(NullPointerException.class, () -> registry.resolveRuntime(structures, Arrays.asList(firstAssembly, null, secondAssembly)));
        assertUnpublished(registry, first, second);

        registry.resolveRuntime(structures, List.of(secondAssembly, firstAssembly));
        assertSame(firstShape, registry.structure(first));
        assertSame(secondShape, registry.structure(second));
        assertSame(firstAssembly, registry.assembly(first));
        assertSame(secondAssembly, registry.assembly(second));
        assertThrows(IllegalArgumentException.class, () -> registry.assembly(type("first", "first_shape")));
        assertThrows(IllegalStateException.class, () -> registry.resolveRuntime(structures, List.of(firstAssembly, secondAssembly)));
        assertThrows(IllegalStateException.class, () -> registry.resolveStructures(structures));
    }

    @Test
    void assemblyValidationCannotObserveStructuresEarlyOrReenterPublicationAndNeverCallsFactories() {
        var component = new ComponentType<MachineInstance, Object>(id("component"), _ -> fail("Runtime registration cannot create components"), List.of());
        var type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).component(component).build();
        var assembly = MachineAssembly.builder(type, "v1").recreated(component).build();
        var shape = new Shape(type.structureId());
        var structures = Map.of(shape.id(), shape);
        var registry = frozen(type);
        var definitions = new AbstractCollection<MachineAssembly>() {
            @Override public @NotNull Iterator<MachineAssembly> iterator() {
                assertUnpublished(registry, type);
                assertThrows(IllegalStateException.class, () -> registry.resolveStructures(structures));
                return List.of(assembly).iterator();
            }
            @Override public int size() { return 1; }
        };

        registry.resolveRuntime(structures, definitions);
        assertSame(shape, registry.structure(type));
        assertSame(assembly, registry.assembly(type));
    }

    @Test
    void structureCallbacksCannotReenterRuntimePublicationAndFailureRemainsRetryable() {
        var type = type("machine", "shape");
        var registry = frozen(type);
        var assembly = assembly(type);
        var reentrant = new Shape(type.structureId(), () -> registry.resolveRuntime(Map.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> registry.resolveRuntime(Map.of(type.structureId(), reentrant), List.of(assembly)));
        assertUnpublished(registry, type);

        var shape = new Shape(type.structureId());
        registry.resolveRuntime(Map.of(shape.id(), shape), List.of(assembly));
        assertSame(shape, registry.structure(type));
        assertSame(assembly, registry.assembly(type));
    }

    @Test
    void runtimeRequiresFrozenDeclarationsAndCannotUpgradeLegacyStructurePublication() {
        var registry = new MachineTypeRegistry();
        var type = type("machine", "shape");
        var shape = new Shape(type.structureId());
        var assembly = assembly(type);
        registry.register(type);
        assertThrows(IllegalStateException.class, () -> registry.resolveRuntime(Map.of(shape.id(), shape), List.of(assembly)));
        assertThrows(IllegalStateException.class, () -> registry.assembly(type));
        registry.freeze();
        registry.resolveStructures(Map.of(shape.id(), shape));

        assertSame(shape, registry.structure(type));
        assertThrows(IllegalStateException.class, () -> registry.assembly(type));
        assertThrows(IllegalStateException.class, () -> registry.resolveRuntime(Map.of(shape.id(), shape), List.of(assembly)));
        assertSame(shape, registry.structure(type));
    }

    @Test
    void eventRejectsDuplicateAndForeignDefinitionsAndKeepsStableSnapshotsWithoutPublishing() {
        var first = type("first", "shape");
        var second = type("second", "other_shape");
        var registry = frozen(first, second);
        var event = new MachineRuntimeRegistrationEvent(registry);
        var firstShape = new Shape(first.structureId());
        var secondShape = new Shape(second.structureId());
        var firstAssembly = assembly(first);
        var secondAssembly = assembly(second);
        event.structure(firstShape);
        event.assembly(firstAssembly);
        var oldStructures = event.structures();
        var oldAssemblies = event.assemblies();

        assertThrows(IllegalArgumentException.class, () -> event.structure(new Shape(firstShape.id())));
        assertThrows(IllegalArgumentException.class, () -> event.assembly(assembly(first)));
        assertThrows(IllegalArgumentException.class, () -> event.assembly(assembly(type("second", "other_shape"))));
        assertThrows(UnsupportedOperationException.class, () -> oldStructures.put(secondShape.id(), secondShape));
        assertThrows(UnsupportedOperationException.class, () -> oldAssemblies.add(secondAssembly));
        event.structure(secondShape);
        event.assembly(secondAssembly);
        assertEquals(Map.of(firstShape.id(), firstShape), oldStructures);
        assertEquals(List.of(firstAssembly), oldAssemblies);
        assertUnpublished(registry, first, second);

        registry.resolveRuntime(event.structures(), event.assemblies());
        assertSame(firstShape, registry.structure(first));
        assertSame(secondShape, registry.structure(second));
        assertSame(firstAssembly, registry.assembly(first));
        assertSame(secondAssembly, registry.assembly(second));
    }

    private static MachineTypeRegistry frozen(MachineType... types) {
        var registry = new MachineTypeRegistry();
        registry.registerAll(List.of(types));
        registry.freeze();
        return registry;
    }

    private static void assertUnpublished(MachineTypeRegistry registry, MachineType... types) {
        for (var type : types) {
            assertThrows(IllegalStateException.class, () -> registry.structure(type));
            assertThrows(IllegalStateException.class, () -> registry.assembly(type));
        }
    }

    private static MachineAssembly assembly(MachineType type) { return MachineAssembly.builder(type, "v1").build(); }
    private static MachineType type(String machine, String structure) { return MachineType.builder(id(machine), RecipeText.Cancelled, id(structure)).build(); }
    private static ResourceLocation id(String path) { return new ResourceLocation("test", path); }

    private record Shape(ResourceLocation shapeId, Runnable onReadId) implements MachineStructure {
        private Shape(ResourceLocation id) { this(id, () -> {}); }
        @Override public ResourceLocation id() { onReadId.run(); return shapeId; }
        @Override public BlockOrientation orientation() { return fail("Registration must not inspect orientation"); }
        @Override public boolean hideWorldBlocks() { return fail("Registration must not inspect rendering"); }
        @Override public MachineStructure transform(StructureTransform rotation) { return fail("Registration must not transform a structure"); }
        @Override public void collectReads(StructureReadPlan.Builder plan, BlockPos anchor, BlockPos controller) { fail("Registration must not read the world"); }
        @Override public StructureMatch match(StructureSnapshot snapshot, BlockPos anchor, BlockPos controller) { return fail("Registration must not match a structure"); }
    }
}
