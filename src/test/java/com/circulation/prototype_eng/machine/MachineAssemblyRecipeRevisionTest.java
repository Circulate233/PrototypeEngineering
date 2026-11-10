package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineAssemblyRecipeRevisionTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("assembly_recipe_revision", name);
    }

    private static MachineRecipe recipe(int duration) {
        return new MachineRecipe(id("recipe"), duration, List.of(), Set.of(id("group")));
    }

    private static MachineType type() {
        return MachineType.builder(id("machine"), RecipeText.Cancelled, id("structure"))
                          .component(MachineWorkSlots.TYPE).attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2)
                          .workSlot(new WorkSlotDefinition("old", Set.of(id("group"))))
                          .workSlot(new WorkSlotDefinition("new", Set.of(id("group")))).build();
    }

    private static MachineWorkSlots slots(MachineInstance machine) {
        return Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE));
    }

    private static NBTTagCompound savedSlots(NBTTagCompound assemblyTag) {
        var components = assemblyTag.getTagList("Components", 10);
        for (int i = 0; i < components.tagCount(); i++) {
            var entry = components.getCompoundTagAt(i);
            if (entry.getString("Type").equals(MachineWorkSlots.TYPE.id().toString()))
                return entry.getCompoundTag("Data");
        }
        throw new AssertionError("Missing saved work slots");
    }

    @Test
    void previouslyCreatedAssemblySavesAndRestoresBothExactRevisionsWithoutChangingLayout() {
        var old = recipe(5);
        var next = recipe(12);
        var oldCodec = RecipeProcessNbt.builder(old, "old-v1").build();
        var nextCodec = RecipeProcessNbt.builder(next, "new-v2").build();
        var resolver = new RecipeProcessCodecs(List.of(oldCodec));
        var workSlotsCodec = new MachineWorkSlotsNbt("layout-v1", resolver);
        var assembly = MachineAssembly.builder(type(), "machine-v1").workSlots(workSlotsCodec).build();
        var original = assembly.create(new Host(), new MachineLifecycleRegistry());
        Objects.requireNonNull(original.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
        var oldProcess = new RecipeProcess(original, old, 1);
        assertTrue(slots(original).startIn("old", oldProcess));
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("pool"), "pool.title", List.of(old)));
        registry.bindProcessCodecs(resolver);
        registry.freeze();
        var prepared = registry.prepareReplacement(registry.snapshot(), List.of(new RecipeRegistry.Definition(next, "new-v2")), List.of(nextCodec));
        registry.install(prepared);
        var newProcess = new RecipeProcess(original, next, 2);
        assertTrue(slots(original).startIn("new", newProcess));
        assertEquals(2, original.recipeThreadCount(old.id()));
        var saved = assembly.encode(original);
        var workSlots = savedSlots(saved);
        assertEquals("layout-v1", workSlots.getString("Revision"));
        assertEquals("old-v1", workSlots.getTagList("Slots", 10).getCompoundTagAt(0).getCompoundTag("Process").getString("Revision"));
        assertEquals("new-v2", workSlots.getTagList("Slots", 10).getCompoundTagAt(1).getCompoundTag("Process").getString("Revision"));
        var restored = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
        var restoredOld = Objects.requireNonNull(slots(restored).requireSlot("old").process());
        var restoredNew = Objects.requireNonNull(slots(restored).requireSlot("new").process());
        assertSame(old, restoredOld.recipe());
        assertSame(next, restoredNew.recipe());
        assertNotSame(oldProcess, restoredOld);
        assertNotSame(newProcess, restoredNew);
        assertEquals(oldProcess.state(), restoredOld.state());
        assertEquals(newProcess.state(), restoredNew.state());
        assertEquals(saved, assembly.encode(restored));
        assertSame(resolver, workSlotsCodec.processCodecs());
    }

    @Test
    void missingHistoricalRevisionKeepsRawControllerSaveAndDoesNotPartiallyReplaceLiveSlots() {
        var old = recipe(5);
        var next = recipe(12);
        var oldCodec = RecipeProcessNbt.builder(old, "old-v1").build();
        var nextCodec = RecipeProcessNbt.builder(next, "new-v2").build();
        var declaration = type();
        var completeCodec = new MachineWorkSlotsNbt("layout-v1", List.of(oldCodec, nextCodec));
        var complete = MachineAssembly.builder(declaration, "machine-v1").workSlots(completeCodec).build();
        var original = complete.create(new Host(), new MachineLifecycleRegistry());
        Objects.requireNonNull(original.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
        // Decode a supported first entry before encountering the missing historical revision.
        assertTrue(slots(original).startIn("old", new RecipeProcess(original, next, 1)));
        assertTrue(slots(original).startIn("new", new RecipeProcess(original, old, 2)));
        var saved = complete.encode(original);
        var incompleteCodec = new MachineWorkSlotsNbt("layout-v1", List.of(nextCodec));
        var before = slots(original).snapshot();
        assertThrows(IllegalArgumentException.class, () -> Objects.requireNonNull(original.lifecycle()).withPersistence(() -> {
            incompleteCodec.restore(slots(original), savedSlots(saved));
            return null;
        }));
        assertEquals(before, slots(original).snapshot());
        var incomplete = MachineAssembly.builder(declaration, "machine-v1").workSlots(incompleteCodec).build();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1)) {
            var controller = new MachineController(incomplete, new Host(), scheduler, new MachineLifecycleRegistry());
            assertThrows(IllegalArgumentException.class, () -> controller.load(saved));
            assertNull(controller.machine());
            assertEquals(saved, controller.save());
            Objects.requireNonNull(controller.save()).setString("Revision", "caller-mutation");
            assertEquals(saved, controller.save());
        }
    }

    @Test
    void assemblyRejectsWrongOrNonPersistentFollowingBeforeLoadWhileKeepingFixedScanners() {
        var recipe = recipe(5);
        var codec = RecipeProcessNbt.builder(recipe, "v1").build();
        var resolver = new RecipeProcessCodecs(List.of(codec));
        var otherResolver = new RecipeProcessCodecs(List.of(codec));
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("pool"), "pool.title", List.of(recipe)));
        registry.bindProcessCodecs(resolver);
        registry.freeze();
        var following = RecipeScanning.component(id("following"), registry, new ScanLimits(1, 1));
        var followingType = MachineType.builder(id("machine"), RecipeText.Cancelled, id("structure"))
                                       .component(MachineWorkSlots.TYPE).component(following).build();
        var wrongAssembly = MachineAssembly.builder(followingType, "machine-v1")
                                           .workSlots(new MachineWorkSlotsNbt("layout-v1", otherResolver)).recreated(following).build();
        var loads = new int[1];
        var listeners = new MachineLifecycleRegistry();
        try (var _ = listeners.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                loads[0]++;
            }
        })) {
            assertThrows(IllegalArgumentException.class, () -> wrongAssembly.create(new Host(), listeners));
            assertEquals(0, loads[0]);
            var correct = MachineAssembly.builder(followingType, "machine-v1")
                                         .workSlots(new MachineWorkSlotsNbt("layout-v1", resolver)).recreated(following).build();
            correct.create(new Host(), listeners);
            assertEquals(1, loads[0]);
            var independent = RecipeScanning.component(id("independent"), host ->
                new RecipeScanner(host, registry.catalog(), () -> 1).follow(registry, null), new ScanLimits(1, 1), _ -> true);
            var independentType = MachineType.builder(id("machine"), RecipeText.Cancelled, id("structure"))
                                             .component(MachineWorkSlots.TYPE).component(independent).build();
            var incompatible = MachineAssembly.builder(independentType, "machine-v1")
                                              .workSlots(new MachineWorkSlotsNbt("layout-v1", resolver)).recreated(independent).build();
            assertThrows(IllegalArgumentException.class, () -> incompatible.create(new Host(), listeners));
            assertEquals(1, loads[0]);
            var fixed = RecipeScanning.component(id("fixed"), registry::catalog, new ScanLimits(1, 1));
            var fixedType = MachineType.builder(id("machine"), RecipeText.Cancelled, id("structure"))
                                       .component(MachineWorkSlots.TYPE).component(fixed).build();
            var compatible = MachineAssembly.builder(fixedType, "machine-v1")
                                            .workSlots(new MachineWorkSlotsNbt("layout-v1", List.of(codec))).recreated(fixed).build();
            compatible.create(new Host(), listeners);
            assertEquals(2, loads[0]);
        }
    }

    private static final class Host implements MachineLifecycleHost {
        private final Thread thread = Thread.currentThread();

        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("Pure assembly test must not access World");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Foreign thread");
        }
    }
}
