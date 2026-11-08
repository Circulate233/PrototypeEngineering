package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticRecipeProcessingTest {
    private static final ResourceLocation GROUP = id("group");
    private static final ScanLimits LIMITS = new ScanLimits(1, 1);

    @Test
    void declaredScannerAdmitsBeforeProcessingAndRestoredWorkDoesNotReplayStart() {
        var tracking = new Tracking();
        var recipe = tracking.recipe("recipe");
        var registry = registry(recipe);
        var scanner = RecipeScanning.component(id("scanner"), registry::catalog, LIMITS);
        var assembly = assembly(scanner, recipe);
        registry.freeze(List.of(assembly.type()));
        try (var scheduler = scheduler()) {
            var original = controller(assembly, scheduler);
            original.load(null);
            scheduler.tick();
            scheduler.tick();
            assertEquals(0, tracking.acquisitions);
            assertTrue(slots(original).activeProcesses().isEmpty());
            form(original);
            scheduler.tick();
            var process = slots(original).activeProcesses().getFirst();
            assertSame(recipe, process.recipe());
            assertEquals(1, process.state().successfulTicks());
            assertEquals(1, tracking.starts);
            assertEquals(1, tracking.ticks);
            scheduler.tick();
            assertEquals(2, process.state().successfulTicks());
            var originalScanner = runtime(original).components().get(scanner);
            original.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            var saved = Objects.requireNonNull(original.save());
            var replacement = controller(assembly, scheduler);
            replacement.load(saved);
            var restored = slots(replacement).activeProcesses().getFirst();
            assertNotSame(process, restored);
            assertNotSame(originalScanner, runtime(replacement).components().get(scanner));
            assertEquals(process.state(), restored.state());
            scheduler.tick();
            assertEquals(2, tracking.ticks);
            assertEquals(2, restored.state().successfulTicks());
            form(replacement);
            scheduler.tick();
            scheduler.tick();
            assertTrue(slots(replacement).activeProcesses().isEmpty());
            assertEquals(1, tracking.starts);
            assertEquals(4, tracking.ticks);
            assertEquals(1, tracking.ends);
            assertEquals(2, process.state().successfulTicks());
            replacement.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void declaringOnlyWorkSlotsDoesNotImplicitlyScanRecipes() {
        var tracking = new Tracking();
        var recipe = tracking.recipe("manual");
        var type = declaration().component(MachineWorkSlots.TYPE).build();
        var assembly = MachineAssembly.builder(type, "v1").workSlots(codec(recipe)).build();
        try (var scheduler = scheduler()) {
            var controller = controller(assembly, scheduler);
            controller.load(null);
            form(controller);
            scheduler.tick();
            scheduler.tick();
            assertTrue(slots(controller).activeProcesses().isEmpty());
            assertEquals(0, tracking.acquisitions);
            var manual = new RecipeProcess(runtime(controller), recipe, 31);
            assertTrue(slots(controller).start(manual));
            scheduler.tick();
            assertEquals(1, tracking.starts);
            assertEquals(1, manual.state().successfulTicks());
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void extensionFactoryAndReadinessControlAdmissionWithoutPausingExistingWork() {
        var tracking = new Tracking();
        var recipe = tracking.recipe("extension");
        var registry = registry(recipe);
        registry.freeze();
        boolean[] ready = {false};
        int[] factories = {0};
        int[] lookups = {0};
        RecipeCandidateIndex index = (_, _, scope) -> { lookups[0]++; return scope; };
        var scanner = RecipeScanning.component(id("custom_scanner"), host -> {
            factories[0]++;
            return new RecipeScanner(host, registry.catalog(), (owner, candidate, seed, tuning) -> {
                var process = new RecipeProcess(owner, candidate, seed, tuning);
                process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 2);
                return process;
            }, List.of(), List.of(index), _ -> false, () -> 47);
        }, LIMITS, _ -> ready[0]);
        try (var scheduler = scheduler()) {
            var controller = controller(assembly(scanner, recipe), scheduler);
            controller.load(null);
            form(controller);
            scheduler.tick();
            assertEquals(1, factories[0]);
            assertEquals(0, lookups[0]);
            assertEquals(0, tracking.acquisitions);
            ready[0] = true;
            scheduler.tick();
            var process = slots(controller).activeProcesses().getFirst();
            assertEquals(2, process.state().progress());
            assertEquals(1, tracking.starts);
            ready[0] = false;
            scheduler.tick();
            assertEquals(1, lookups[0]);
            assertEquals(2, tracking.ticks);
            assertEquals(1, tracking.ends);
            assertTrue(slots(controller).activeProcesses().isEmpty());
            ready[0] = true;
            scheduler.tick();
            assertEquals(2, tracking.starts);
            controller.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void assemblyRejectsAbsentOrLaterWorkSlotsAndAFactoryReturningAnotherHost() {
        var recipe = new Tracking().recipe("contract");
        var registry = registry(recipe);
        registry.freeze();
        var scanner = RecipeScanning.component(id("scanner"), registry::catalog, LIMITS);
        var missing = MachineAssembly.builder(declaration().component(scanner).build(), "v1").recreated(scanner).build();
        var reversed = MachineAssembly.builder(declaration().component(scanner).component(MachineWorkSlots.TYPE).build(), "v1")
            .workSlots(codec(recipe)).recreated(scanner).build();
        var foreign = RecipeScanning.component(id("foreign_scanner"), _ -> new RecipeScanner(
            new MachineWorkSlots(new MachineInstance(), List.of()), registry.catalog(), () -> 59), LIMITS, _ -> true);
        for (var invalid : List.of(missing, reversed, assembly(foreign, recipe))) {
            try (var scheduler = scheduler()) {
                var controller = controller(invalid, scheduler);
                assertThrows(IllegalArgumentException.class, () -> controller.load(null));
                assertEquals(0, scheduler.registeredCount());
                assertNull(controller.machine());
            }
        }
    }

    @Test
    void recreatedScannerRestartsItsSearchWhileTheWorkSlotCodecRetainsMachineState() {
        var firstTracking = new Tracking();
        firstTracking.accept = false;
        var first = firstTracking.recipe("first");
        var second = new Tracking().recipe("second");
        var registry = registry(first, second);
        registry.freeze();
        var scanner = RecipeScanning.component(id("cursor_scanner"), registry::catalog, LIMITS);
        var assembly = assembly(scanner, first, second);
        try (var scheduler = scheduler()) {
            var original = controller(assembly, scheduler);
            original.load(null);
            form(original);
            scheduler.tick();
            assertTrue(slots(original).activeProcesses().isEmpty());
            assertEquals(1, firstTracking.acquisitions);
            original.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            var replacement = controller(assembly, scheduler);
            replacement.load(Objects.requireNonNull(original.save()));
            firstTracking.accept = true;
            form(replacement);
            scheduler.tick();
            assertSame(first, slots(replacement).activeProcesses().getFirst().recipe());
            assertEquals(1, firstTracking.starts);
            replacement.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    @Test
    void invalidCanonicalCatalogCannotPublishAnyFrozenPoolsOrCloseRegistration() {
        var registry = new RecipeRegistry();
        var first = new MachineRecipe(id("conflict"), 4, List.of(), Set.of(GROUP));
        var second = new MachineRecipe(first.id(), 8, List.of(), Set.of(GROUP));
        registry.register(new RecipePool(id("first_pool"), "first", List.of(first)));
        registry.register(new RecipePool(id("second_pool"), "second", List.of(second)));
        assertThrows(IllegalArgumentException.class, registry::freeze);
        assertThrows(IllegalStateException.class, registry::catalog);
        assertThrows(IllegalStateException.class, registry::pools);
        assertThrows(IllegalStateException.class, () -> registry.get(id("first_pool")));
        registry.registerPool(id("still_open"), "open");
    }

    @Test
    void catalogSupplierIsResolvedAtAssemblyAfterRegistrationFreezes() {
        var tracking = new Tracking();
        var recipe = tracking.recipe("late_catalog");
        var registry = registry(recipe);
        var scanner = RecipeScanning.component(id("late_scanner"), registry::catalog, LIMITS);
        var assembly = assembly(scanner, recipe);
        try (var scheduler = scheduler()) {
            var early = controller(assembly, scheduler);
            assertThrows(IllegalStateException.class, () -> early.load(null));
        }
        registry.freeze();
        try (var scheduler = scheduler()) {
            var ready = controller(assembly, scheduler);
            ready.load(null);
            form(ready);
            scheduler.tick();
            assertEquals(1, tracking.starts);
            assertEquals(1, tracking.ticks);
            ready.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    private static RecipeRegistry registry(MachineRecipe... recipes) {
        var registry = new RecipeRegistry();
        registry.register(new RecipePool(id("pool"), "pool", List.of(recipes)));
        return registry;
    }

    private static MachineType.Builder declaration() {
        return MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape"))
            .recipePool(id("pool")).recipeGroup(GROUP).executionMode(ExecutionMode.MAIN_THREAD);
    }

    private static MachineAssembly assembly(ComponentType<MachineInstance, RecipeScanner> scanner, MachineRecipe... recipes) {
        return MachineAssembly.builder(declaration().component(MachineWorkSlots.TYPE).component(scanner).build(), "v1")
            .workSlots(codec(recipes)).recreated(scanner).build();
    }

    private static MachineWorkSlotsNbt codec(MachineRecipe... recipes) {
        return new MachineWorkSlotsNbt("v1", Arrays.stream(recipes)
            .map(recipe -> RecipeProcessNbt.builder(recipe, "v1").build()).toList());
    }

    private static MachineController controller(MachineAssembly assembly, TaskScheduler scheduler) {
        return new MachineController(assembly, new Host(), scheduler, new MachineLifecycleRegistry());
    }

    private static MachineInstance runtime(MachineController controller) { return Objects.requireNonNull(controller.machine()); }
    private static MachineWorkSlots slots(MachineController controller) { return Objects.requireNonNull(runtime(controller).components().get(MachineWorkSlots.TYPE)); }
    private static void form(MachineController controller) { Objects.requireNonNull(runtime(controller).lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of())); }
    private static TaskScheduler scheduler() { return new TaskScheduler(Thread.currentThread(), 1, 1); }
    private static ResourceLocation id(String path) { return new ResourceLocation("automatic_recipe_test", path); }

    private static final class Tracking implements RecipeRequirementSystem<String> {
        private int acquisitions;
        private int starts;
        private int ticks;
        private int ends;
        private boolean accept = true;

        private MachineRecipe recipe(String name) {
            var type = new RecipeRequirementType<>(id(name + "_requirement_type"), this);
            return new MachineRecipe(id(name), 4, List.of(type.requirement(id(name + "_requirement"), name)), Set.of(GROUP));
        }

        @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
            acquisitions++;
            return transaction(0, accept ? ProcessResult.SUCCESS : RecipeText.StorageChanged.blocked());
        }
        @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) { return transaction(1, ProcessResult.SUCCESS); }
        @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) { return transaction(2, ProcessResult.SUCCESS); }

        private RequirementTransaction transaction(int stage, ProcessResult result) {
            return new RequirementTransaction() {
                private boolean committed;
                @Override public ProcessResult result() { return result; }
                @Override public void commit() { committed = true; add(1); }
                @Override public void rollback() { if (committed) { add(-1); committed = false; } }
                private void add(int delta) {
                    switch (stage) {
                        case 0 -> starts += delta;
                        case 1 -> ticks += delta;
                        case 2 -> ends += delta;
                        default -> throw new AssertionError("Unexpected fixture stage");
                    }
                }
            };
        }
    }

    private static final class Host implements MachineLifecycleHost {
        private final Thread owner = Thread.currentThread();
        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new AssertionError("Pure controller tests cannot access a Minecraft World"); }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != owner) { throw new IllegalStateException("Controller requires the fixture owner thread"); }
        }
    }
}
