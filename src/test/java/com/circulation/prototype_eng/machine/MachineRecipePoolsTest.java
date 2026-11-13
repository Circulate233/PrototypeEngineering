package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.localization.RecipeText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineRecipePoolsTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("machine_pool_test", name);
    }

    private static MachineRecipe recipe(String name) {
        return new MachineRecipe(id(name), 2, List.of());
    }

    private static MachineType.Builder type(String name) {
        return MachineType.builder(id(name), RecipeText.Cancelled, id("shape"));
    }

    private static RecipePool pool(String name, MachineRecipe... recipes) {
        return new DefaultRecipePool(id(name), "pool.test.title", ObjectArrayList.of(recipes));
    }

    private static MachineWorkSlots slots(MachineInstance machine) {
        return Objects.requireNonNull(machine.components().get(MachineWorkSlots.TYPE));
    }

    private static RecipeScanner scanner(MachineInstance machine, RecipeCatalog catalog) {
        return new RecipeScanner(slots(machine), catalog, () -> 123);
    }

    private static MachineAssembly assembly(MachineType type, MachineRecipe... recipes) {
        var codecs = new ObjectArrayList<RecipeProcessNbt>();
        for (var recipe : recipes) codecs.add(RecipeProcessNbt.builder(recipe, "pool-test-1").build());
        return MachineAssembly.builder(type, "pool-test-1").instanceFactory(DynamicMachine::new)
                              .workSlots(new MachineWorkSlotsNbt("pool-test-1", codecs)).build();
    }

    private static DynamicMachine formed(MachineAssembly assembly) {
        var machine = assertInstanceOf(DynamicMachine.class, assembly.create(new Host(assembly.type()), new MachineLifecycleRegistry()));
        Objects.requireNonNull(machine.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
        return machine;
    }

    @Test
    void declarationAndReverseBindingsPublishTogetherAfterLaterAddonPoolRegistration() {
        var first = type("first").recipePool(id("late")).build();
        var second = type("second").recipePool(id("late")).recipePool(id("unused")).build();
        var machines = new MachineTypeRegistry();
        machines.registerAll(List.of(first, second));
        machines.freeze();
        var recipes = new RecipeRegistry();
        recipes.register(pool("late", recipe("shared")));
        recipes.registerPool(id("unused"), "pool.test.empty");
        recipes.freeze(machines.types());
        var late = Objects.requireNonNull(recipes.get(id("late")));
        var unused = Objects.requireNonNull(recipes.get(id("unused")));
        assertEquals(List.of(first, second), late.machines());
        assertEquals(List.of(second), unused.machines());
        assertEquals(List.of(late, unused), recipes.catalog().poolsFor(second));
    }

    @Test
    void unknownReferenceRejectsWholePublicationAndCanBeFixedBeforeRetry() {
        var valid = type("valid").recipePool(id("known")).build();
        var unknown = type("unknown").recipePool(id("missing")).build();
        var registry = new RecipeRegistry();
        registry.register(pool("known", recipe("one")));
        assertThrows(IllegalArgumentException.class, () -> registry.freeze(List.of(valid, unknown)));
        assertThrows(IllegalStateException.class, registry::catalog);
        assertThrows(IllegalStateException.class, () -> registry.get(id("known")));
        registry.registerPool(id("missing"), "pool.test.later");
        registry.freeze(List.of(valid, unknown));
        assertEquals(List.of(valid), Objects.requireNonNull(registry.get(id("known"))).machines());
        assertEquals(List.of(unknown), Objects.requireNonNull(registry.get(id("missing"))).machines());
    }

    @Test
    void registeredGroupsCannotSelectUnboundPoolAndZeroDeclarationHasNoAutomaticCandidates() {
        var recipe = new MachineRecipe(id("grouped"), 2, List.of(), Set.of(id("group")));
        var catalog = new RecipeCatalog(List.of(pool("unbound", recipe)));
        var declared = type("empty").component(MachineWorkSlots.TYPE).recipeGroup(id("group")).build();
        var machine = formed(assembly(declared, recipe));
        assertEquals(RecipeScanner.Status.EXHAUSTED, scanner(machine, catalog).scan(new ScanLimits(20, 20)).status());
        assertTrue(slots(machine).activeProcesses().isEmpty());
        var standalone = new MachineInstance();
        var standaloneSlots = new MachineWorkSlots(standalone, List.of());
        assertEquals(RecipeScanner.Status.ADMITTED, new RecipeScanner(standaloneSlots, catalog, () -> 1).scan(new ScanLimits(20, 20)).status());
    }

    @Test
    void actualInstanceOverrideChoosesOutsideStaticDeclarationAndEmptySelectionStopsNextScan() {
        var a = recipe("a_recipe");
        var b = recipe("b_recipe");
        var catalog = new RecipeCatalog(List.of(pool("a", a), pool("b", b)));
        var type = type("dynamic").component(MachineWorkSlots.TYPE).recipePool(id("a")).build();
        var machine = formed(assembly(type, a, b));
        machine.selection = List.of(catalog.pools().get(1));
        var scanner = scanner(machine, catalog);
        var result = scanner.scan(new ScanLimits(20, 20));
        assertEquals(RecipeScanner.Status.ADMITTED, result.status());
        assertSame(b, Objects.requireNonNull(result.process()).recipe());
        slots(machine).detach(Objects.requireNonNull(result.process()));
        machine.selection = List.of();
        assertEquals(RecipeScanner.Status.EXHAUSTED, scanner.scan(new ScanLimits(20, 20)).status());
        assertEquals(2, machine.samples);
    }

    @Test
    void explicitSlotGroupsRemainSecondaryFiltersInsideSelectedPools() {
        var rejected = new MachineRecipe(id("reject"), 2, List.of(), Set.of(id("other")));
        var accepted = new MachineRecipe(id("accept"), 2, List.of(), Set.of(id("slot_group")));
        var catalog = new RecipeCatalog(List.of(pool("selected", rejected, accepted)));
        var type = type("slot").component(MachineWorkSlots.TYPE).recipePool(id("selected"))
                               .recipeGroup(id("unrelated_default")).workSlot(new WorkSlotDefinition("work", Set.of(id("slot_group")))).build();
        var machine = formed(assembly(type, rejected, accepted));
        assertSame(accepted, Objects.requireNonNull(scanner(machine, catalog).scan(new ScanLimits(20, 20)).process()).recipe());
    }

    @Test
    void duplicatePoolsAndCrossPoolCanonicalRecipeKeepOriginalCatalogPriority() {
        var shared = recipe("shared");
        var later = recipe("later");
        var first = pool("first", shared);
        var second = pool("second", later, shared);
        var catalog = new RecipeCatalog(List.of(first, second));
        var type = type("priority").component(MachineWorkSlots.TYPE).build();
        var machine = formed(assembly(type, shared, later));
        machine.selection = List.of(second, second, first);
        assertEquals(List.of(shared, later), catalog.candidates(machine, Set.of(), List.of()));
        assertSame(shared, Objects.requireNonNull(scanner(machine, catalog).scan(new ScanLimits(20, 20)).process()).recipe());
    }

    @Test
    void counterfeitPoolIsRejectedByActualScanningBeforeAnyAdmission() {
        var recipe = recipe("canonical");
        var canonical = pool("pool", recipe);
        var catalog = new RecipeCatalog(List.of(canonical));
        var type = type("counterfeit").component(MachineWorkSlots.TYPE).build();
        var machine = formed(assembly(type, recipe));
        machine.selection = List.of(pool("pool", recipe));
        assertThrows(IllegalArgumentException.class, () -> scanner(machine, catalog).scan(new ScanLimits(20, 20)));
        assertTrue(slots(machine).activeProcesses().isEmpty());
    }

    @Test
    void changedPoolsApplyAfterFrozenYieldedPassFinishesThenEmptySelectionDisablesNextPass() {
        var guard = new RecipeRequirementType<Boolean>(id("guard"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> requirement) {
                return new Outcome(process.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1) == 1
                    ? ProcessResult.SUCCESS : RecipeText.Cancelled.blocked());
            }
        }).requirement(id("guard_requirement"), true);
        var old = new MachineRecipe(id("old"), 2, List.of(guard));
        var next = recipe("next");
        var catalog = new RecipeCatalog(List.of(pool("old", old), pool("next", next)));
        var type = type("yield").component(MachineWorkSlots.TYPE).attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 8).build();
        // No persistence of this custom requirement is exercised.
        var machine = formed(MachineAssembly.builder(type, "yield-1").instanceFactory(DynamicMachine::new)
                                            .workSlots(new MachineWorkSlotsNbt("yield-1", List.of())).build());
        machine.selection = List.of(catalog.pools().getFirst());
        var scanner = scanner(machine, catalog);
        var first = scanner.beginScan(new ScanLimits(1, 1));
        assertEquals(RecipeScanner.Status.YIELDED, first.advance(new RecipeWorkBudget(100_000), () -> true).status());
        first.releasePureYield();
        machine.selection = List.of(catalog.pools().get(1));
        RecipeProcess oldAdmitted = null;
        for (int tick = 0; tick < 8 && oldAdmitted == null; tick++) {
            var attempt = scanner.beginScan(new ScanLimits(1, 1));
            var result = attempt.advance(new RecipeWorkBudget(100_000), () -> true);
            if (result.status() == RecipeScanner.Status.ADMITTED) oldAdmitted = result.process();
            else attempt.releasePureYield();
        }
        assertSame(old, Objects.requireNonNull(oldAdmitted).recipe());
        assertEquals(1, machine.samples);
        slots(machine).detach(oldAdmitted);
        var second = scanner.beginScan(new ScanLimits(1, 1));
        var admitted = second.advance(new RecipeWorkBudget(100_000), () -> true);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        assertSame(next, Objects.requireNonNull(admitted.process()).recipe());
        slots(machine).detach(Objects.requireNonNull(admitted.process()));
        machine.selection = List.of();
        assertEquals(RecipeScanner.Status.EXHAUSTED, scanner.scan(new ScanLimits(20, 20)).status());
    }

    @Test
    void boundedLogicalPassProgressesWithHugeFreshEquivalentImmutableListsAcrossTicks() {
        var target = recipe("target");
        var pools = new ObjectArrayList<RecipePool>();
        for (int i = 0; i < 200; i++) pools.add(pool("pool_" + i));
        pools.add(pool("target_pool", target));
        var catalog = new RecipeCatalog(pools);
        var type = type("large_selection").component(MachineWorkSlots.TYPE).build();
        var machine = formed(assembly(type, target));
        machine.selection = catalog.pools();
        machine.copySelection = true;
        var scanner = scanner(machine, catalog);
        RecipeProcess admitted = null;
        for (int tick = 0; tick < 100 && admitted == null; tick++) {
            var attempt = scanner.beginScan(new ScanLimits(10, 10));
            var result = attempt.advance(new RecipeWorkBudget(128), () -> true);
            if (result.status() == RecipeScanner.Status.ADMITTED) admitted = result.process();
            else {
                assertEquals(RecipeScanner.Status.YIELDED, result.status());
                assertFalse(attempt.hasObligations());
                attempt.releasePureYield();
            }
        }
        assertSame(target, Objects.requireNonNull(admitted).recipe());
        assertEquals(1, machine.samples);
        slots(machine).detach(admitted);
        assertSame(target, Objects.requireNonNull(scanner.scan(new ScanLimits(20, 20)).process()).recipe());
        assertEquals(2, machine.samples);
    }

    @Test
    void poolAwareAdmissionRejectsNewExcludedWorkButExistingWorkAndRestoreKeepTheirOriginalRecipe() {
        var recipe = recipe("running");
        var catalog = new RecipeCatalog(List.of(pool("pool", recipe)));
        var type = type("restore").component(MachineWorkSlots.TYPE).recipePool(id("pool")).build();
        var assembly = assembly(type, recipe);
        var machine = formed(assembly);
        var result = scanner(machine, catalog).scan(new ScanLimits(20, 20));
        var process = Objects.requireNonNull(result.process());
        slots(machine).tickProcesses();
        machine.selection = List.of();
        assertSame(process, slots(machine).activeProcesses().getFirst());
        var saved = assembly.encode(machine);
        var restored = assertInstanceOf(DynamicMachine.class, assembly.restore(new Host(type), saved, new MachineLifecycleRegistry()));
        assertNotSame(machine, restored);
        restored.selection = List.of();
        Objects.requireNonNull(restored.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
        var restoredProcess = slots(restored).activeProcesses().getFirst();
        assertSame(recipe, restoredProcess.recipe());
        slots(restored).tickProcesses();
        assertTrue(restoredProcess.state().progress() > process.state().progress());
        slots(machine).detach(process);
        var selection = catalog.select(machine);
        assertFalse(slots(machine).startIn("main", new RecipeProcess(machine, recipe, 99), selection));
        var direct = new RecipeProcess(machine, recipe, 100);
        assertTrue(slots(machine).startIn("main", direct));
        assertSame(direct, slots(machine).activeProcesses().getFirst());
    }

    @Test
    void restoredFactorySubclassActuallyControlsAutomaticLookup() {
        var recipe = recipe("factory");
        var catalog = new RecipeCatalog(List.of(pool("pool", recipe)));
        var type = type("factory").component(MachineWorkSlots.TYPE).build();
        var assembly = assembly(type, recipe);
        var machine = formed(assembly);
        var saved = assembly.encode(machine);
        var restored = assertInstanceOf(DynamicMachine.class, assembly.restore(new Host(type), saved, new MachineLifecycleRegistry()));
        restored.selection = catalog.pools();
        Objects.requireNonNull(restored.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
        assertSame(recipe, Objects.requireNonNull(scanner(restored, catalog).scan(new ScanLimits(20, 20)).process()).recipe());
        assertEquals(1, restored.samples);
    }

    @Test
    void explicitSelectionResetRequiresOldAttemptSettlementThenSamplesNewPools() {
        var first = recipe("reset_first");
        var second = recipe("reset_second");
        var catalog = new RecipeCatalog(List.of(pool("first", first), pool("second", second)));
        var type = type("reset").component(MachineWorkSlots.TYPE).build();
        var machine = formed(assembly(type, first, second));
        machine.selection = List.of(catalog.pools().getFirst());
        var scanner = scanner(machine, catalog);
        var attempt = scanner.beginScan(new ScanLimits(20, 20));
        assertEquals(RecipeScanner.Status.YIELDED, attempt.advance(new RecipeWorkBudget(1), () -> true).status());
        assertThrows(IllegalStateException.class, scanner::reset);
        attempt.cancel();
        assertEquals(RecipeScanner.Status.PAUSED, attempt.advance(new RecipeWorkBudget(100), () -> false).status());
        machine.selection = List.of(catalog.pools().get(1));
        scanner.reset();
        assertSame(second, Objects.requireNonNull(scanner.scan(new ScanLimits(20, 20)).process()).recipe());
    }

    @Test
    void instanceFactoryRejectsForeignDeclarationAndReusedAlreadyAttachedMachine() {
        var type = type("factory_reject").build();
        var foreign = type("foreign").build();
        var wrong = MachineAssembly.builder(type, "factory-1").instanceFactory(_ -> new MachineInstance(foreign)).build();
        assertThrows(IllegalArgumentException.class, () -> wrong.create(new Host(type), new MachineLifecycleRegistry()));
        var reused = new MachineInstance(type);
        var assembly = MachineAssembly.builder(type, "factory-1").instanceFactory(_ -> reused).build();
        assertSame(reused, assembly.create(new Host(type), new MachineLifecycleRegistry()));
        assertThrows(IllegalArgumentException.class, () -> assembly.create(new Host(type), new MachineLifecycleRegistry()));
    }

    private static final class DynamicMachine extends MachineInstance {
        @Nullable
        private List<RecipePool> selection;
        private int samples;
        private boolean copySelection;

        private DynamicMachine(MachineType type) {
            super(type);
        }

        @Override
        public List<RecipePool> getRecipePools(RecipeCatalog catalog) {
            samples++;
            var selected = selection == null ? super.getRecipePools(catalog) : selection;
            return copySelection ? List.copyOf(new ObjectArrayList<>(selected)) : selected;
        }
    }

    private record Host(MachineType type) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type.id(), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("Pool tests do not use a Minecraft world");
        }

        @Override
        public void requireServerThread() {
        }
    }

    private record Outcome(ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }
}
