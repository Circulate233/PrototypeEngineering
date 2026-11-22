package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeInventoryMatch;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRecipePoolAuxiliaryTest {
    private static final KeyType MATERIAL = new KeyType("simple_aux_material");
    private static final KeyType FLUID = new KeyType("simple_aux_fluid");
    private static final Key FIRST = new Key(MATERIAL, "first");
    private static final Key SECOND = new Key(MATERIAL, "second");
    private static final Key WATER = new Key(FLUID, "water");
    private static final Key PRODUCT = new Key(MATERIAL, "product");
    private static final ResourceLocation GROUP = id("group");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("simple_auxiliary_test", path);
    }

    private static SimpleRecipePool.Entry entry(String path, Key input, long amount) {
        return SimpleRecipePool.Entry.create(id(path), 1, new GenericStack(input, amount),
            new GenericStack(PRODUCT, 2), Set.of(GROUP));
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(MATERIAL, Long.MAX_VALUE);
        capacities.put(FLUID, Long.MAX_VALUE);
        return new SlottedResourceBuffer(4, capacities, () -> {
        });
    }

    private static MachineInstance machine(SlottedResourceBuffer input, SlottedResourceBuffer output) {
        var machine = new MachineInstance();
        install(machine, input, output);
        return machine;
    }

    private static void install(MachineInstance machine, SlottedResourceBuffer input, SlottedResourceBuffer output) {
        machine.components().put(MachineHatches.TYPE,
            new MachineHatches(List.of(new Port(input, output)), List.of(), List.of()));
    }

    @Test
    void commonInputsApplyToEveryRecipeWhileInventoryAndLiveDiscoveryUseOnlyThePrimaryInput() {
        var first = entry("first_recipe", FIRST, 3);
        var second = entry("second_recipe", SECOND, 5);
        var original = new SimpleRecipePool(id("pool"), "pool.title", List.of(first, second), _ -> true);
        var pool = original.withAuxiliaryInputs(List.of(new GenericStack(WATER, 1)));
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(FIRST, 12);
        inventory.put(SECOND, 10);

        var matches = pool.match(inventory, 100);
        assertEquals(pool.recipes(), matches.stream().map(RecipeInventoryMatch::recipe).toList());
        assertEquals(List.of(4, 2), matches.stream().map(RecipeInventoryMatch::maximumParallelism).toList());
        assertSame(pool.recipes().getFirst(), pool.match(inventory));
        var cursor = pool.beginMatch(inventory, 100, Set.of(GROUP));
        while (!cursor.advance(new RecipeWorkBudget(1))) {
            assertTrue(cursor.current());
        }
        assertEquals(pool.recipes(), cursor.result());

        var stock = buffer();
        stock.insert(FIRST, inventory.getLong(FIRST), Actionable.MODULATE);
        stock.insert(SECOND, inventory.getLong(SECOND), Actionable.MODULATE);
        var machine = machine(stock, buffer());
        assertEquals(pool.recipes(), new RecipeCatalog(List.of(pool)).candidates(machine, Set.of(GROUP), List.of()));
        for (int index = 0; index < pool.recipes().size(); index++) {
            var declared = (ResourceRequirement) pool.recipes().get(index).orderedRequirements().getFirst().payload();
            assertEquals(new GenericStack(WATER, 1), declared.inputs().getLast());
            var process = new RecipeProcess(machine, pool.recipes().get(index), index);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        }
        assertEquals(inventory.getLong(FIRST), stock.availableAmount(FIRST));
        assertEquals(inventory.getLong(SECOND), stock.availableAmount(SECOND));
        var onlyAuxiliary = new AEKey2LongMap.OpenHashMap();
        onlyAuxiliary.put(WATER, Long.MAX_VALUE);
        assertTrue(pool.match(onlyAuxiliary, 100).isEmpty());
        assertSame(first.recipe(), original.recipes().getFirst());
        assertEquals(1, ((ResourceRequirement) first.recipe().orderedRequirements().getFirst().payload()).inputs().size());
    }

    @Test
    void missingAuxiliaryMaterialNeverPaysTheMainInputAndRetryPaysBothByParallelismOnce() {
        var base = entry("payment", FIRST, 4);
        var pool = new SimpleRecipePool(id("payment_pool"), "pool.title", List.of(base))
            .withAuxiliaryInputs(List.of(new GenericStack(WATER, 3)));
        var input = buffer();
        var auxiliary = buffer();
        var output = buffer();
        input.insert(FIRST, 20, Actionable.MODULATE);
        auxiliary.insert(WATER, 5, Actionable.MODULATE);
        var machine = machine(input, output);
        machine.components().put(MachineHatches.TYPE,
            new MachineHatches(List.of(new Port(input, output), new Port(auxiliary, null)), List.of(), List.of()));
        var process = new RecipeProcess(machine, pool.recipes().getFirst(), 1);
        process.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);

        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(20, input.availableAmount(FIRST));
        assertEquals(5, auxiliary.availableAmount(WATER));
        assertEquals(0, output.availableAmount(PRODUCT));

        auxiliary.insert(WATER, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(12, input.availableAmount(FIRST));
        assertEquals(0, auxiliary.availableAmount(WATER));
        assertEquals(4, output.availableAmount(PRODUCT));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(12, input.availableAmount(FIRST));
        assertEquals(4, output.availableAmount(PRODUCT));
    }

    @Test
    void equalMainAndAuxiliaryKeysShareOneRequiredBalanceWithoutDoubleSpending() {
        var base = entry("same_key", FIRST, 4);
        var pool = new SimpleRecipePool(id("same_key_pool"), "pool.title", List.of(base))
            .withAuxiliaryInputs(List.of(new GenericStack(FIRST, 2), new GenericStack(FIRST, 1)));
        var input = buffer();
        var output = buffer();
        input.insert(FIRST, 6, Actionable.MODULATE);
        var process = new RecipeProcess(machine(input, output), pool.recipes().getFirst(), 1);

        assertSame(pool.recipes().getFirst(), pool.match(input.snapshot().available()));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(6, input.availableAmount(FIRST));
        assertEquals(0, output.availableAmount(PRODUCT));
        input.insert(FIRST, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.availableAmount(FIRST));
        assertEquals(2, output.availableAmount(PRODUCT));
    }

    @Test
    void configuredSourceAllocationAlsoControlsTheAuxiliaryInput() {
        var source = entry("allocation", FIRST, 1).recipe();
        var originalRequirement = source.orderedRequirements().getFirst();
        var resources = ((ResourceRequirement) originalRequirement.payload()).withInputAllocation(InputAllocation.SINGLE_SOURCE);
        var recipe = new MachineRecipe(source.id(), source.durationTicks(),
            List.of(RecipeRequirements.RESOURCES.requirement(originalRequirement.id(), resources)), source.recipeGroups());
        var pool = new SimpleRecipePool(id("allocation_pool"), "pool.title", List.of(SimpleRecipePool.Entry.of(recipe)))
            .withAuxiliaryInputs(List.of(new GenericStack(WATER, 2)));
        var input = buffer();
        var firstAuxiliary = buffer();
        var secondAuxiliary = buffer();
        var output = buffer();
        input.insert(FIRST, 1, Actionable.MODULATE);
        firstAuxiliary.insert(WATER, 1, Actionable.MODULATE);
        secondAuxiliary.insert(WATER, 1, Actionable.MODULATE);
        var machine = machine(input, output);
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Port(input, output),
            new Port(firstAuxiliary, null), new Port(secondAuxiliary, null)), List.of(), List.of()));
        var process = new RecipeProcess(machine, pool.recipes().getFirst(), 1);

        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(1, input.availableAmount(FIRST));
        assertEquals(1, firstAuxiliary.availableAmount(WATER));
        assertEquals(1, secondAuxiliary.availableAmount(WATER));
        firstAuxiliary.insert(WATER, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.availableAmount(FIRST));
        assertEquals(0, firstAuxiliary.availableAmount(WATER));
        assertEquals(1, secondAuxiliary.availableAmount(WATER));
    }

    @Test
    void freezeAndAdditionalRegistrationRetainCompleteDefinitionsMetadataAndExactObjects() {
        var source = entry("metadata", FIRST, 2).recipe();
        var recipe = new MachineRecipe(source.id(), source.durationTicks(), source.orderedRequirements(), Set.of(GROUP), 3);
        var original = new SimpleRecipePool(id("metadata_pool"), "pool.title", List.of(SimpleRecipePool.Entry.of(recipe)));
        var pool = original.withAuxiliaryInputs(List.of(new GenericStack(WATER, 1))).withExistingJeiPage("external:category");
        var canonical = pool.recipes().getFirst();
        var added = pool.prepareRecipe(entry("added", SECOND, 3).recipe());
        var registry = new RecipeRegistry();
        registry.register(pool);
        registry.registerRecipe(pool.id(), added);
        registry.declareRevision(canonical, "with_water");
        registry.declareRevision(added, "with_water");
        registry.freeze();
        var frozen = Objects.requireNonNull(assertInstanceOf(SimpleRecipePool.class, registry.get(pool.id())));

        assertNotSame(recipe, canonical);
        assertSame(canonical, frozen.recipes().getFirst());
        assertSame(added, frozen.recipes().getLast());
        assertEquals(pool.auxiliaryInputs(), frozen.auxiliaryInputs());
        assertEquals("external:category", frozen.existingJeiPageId());
        assertEquals(recipe.durationTicks(), canonical.durationTicks());
        assertEquals(recipe.recipeGroups(), canonical.recipeGroups());
        assertEquals(recipe.maxConcurrentThreads(), canonical.maxConcurrentThreads());
        assertSame(recipe.parallelismSelection(), canonical.parallelismSelection());
        assertEquals("with_water", registry.snapshot().revision(canonical));
        for (var complete : frozen.recipes()) {
            var resources = (ResourceRequirement) complete.orderedRequirements().getFirst().payload();
            assertEquals(new GenericStack(WATER, 1), resources.inputs().getLast());
        }
        var stock = new AEKey2LongMap.OpenHashMap();
        stock.put(SECOND, 3);
        assertSame(added, frozen.match(stock));
    }

    @Test
    void realAdmissionReducesParallelismForAuxiliaryShortageAfterPrimaryInputDiscovery() {
        var pool = new SimpleRecipePool(id("admission_pool"), "pool.title", List.of(entry("admission", FIRST, 4)))
            .withAuxiliaryInputs(List.of(new GenericStack(WATER, 2)));
        var input = buffer();
        var output = buffer();
        input.insert(FIRST, 20, Actionable.MODULATE);
        input.insert(WATER, 2, Actionable.MODULATE);
        var machine = new MultithreadController();
        install(machine, input, output);
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 5);
        var host = machine.createRecipeHost(List.of(new WorkSlotDefinition("main")), Set.of());
        var scanner = machine.createRecipeScanner(host, new RecipeCatalog(List.of(pool)), () -> 1);

        var selected = scanner.scan(new ScanLimits(1, 10));
        assertEquals(RecipeScanner.Status.ADMITTED, selected.status());
        var process = Objects.requireNonNull(selected.process());
        assertEquals(1, RecipeParallelism.effective(process));
        assertEquals(20, input.availableAmount(FIRST));
        assertEquals(2, input.availableAmount(WATER));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(16, input.availableAmount(FIRST));
        assertEquals(0, input.availableAmount(WATER));
        assertEquals(2, output.availableAmount(PRODUCT));
    }

    @Test
    void replacingOrClearingAuxiliaryInputsRebuildsOnlyTheReturnedDeclaration() {
        var original = new SimpleRecipePool(id("replacement_pool"), "pool.title", List.of(entry("replacement", FIRST, 2)));
        var old = original.withAuxiliaryInputs(List.of(new GenericStack(WATER, 1)));
        var changed = old.withAuxiliaryInputs(List.of(new GenericStack(SECOND, 3)));
        var cleared = old.withAuxiliaryInputs(List.of());
        var input = buffer();
        var output = buffer();
        input.insert(FIRST, 6, Actionable.MODULATE);
        input.insert(SECOND, 3, Actionable.MODULATE);
        var machine = machine(input, output);

        assertInstanceOf(ProcessResult.Blocked.class, new RecipeProcess(machine, old.recipes().getFirst(), 1).tick());
        assertSame(ProcessResult.SUCCESS, new RecipeProcess(machine, changed.recipes().getFirst(), 2).tick());
        assertEquals(0, input.availableAmount(SECOND));
        assertSame(ProcessResult.SUCCESS, new RecipeProcess(machine, cleared.recipes().getFirst(), 3).tick());
        assertEquals(2, input.availableAmount(FIRST));
        assertEquals(4, output.availableAmount(PRODUCT));
        assertEquals(List.of(new GenericStack(WATER, 1)), old.auxiliaryInputs());
        assertTrue(original.auxiliaryInputs().isEmpty());
    }

    @Test
    void combinedMainAndAuxiliaryQuantityOverflowFailsBeforeAnyReservationOrPayment() {
        var pool = new SimpleRecipePool(id("overflow_pool"), "pool.title", List.of(entry("overflow", FIRST, Long.MAX_VALUE)))
            .withAuxiliaryInputs(List.of(new GenericStack(FIRST, 1)));
        var input = buffer();
        var output = buffer();
        input.insert(FIRST, Long.MAX_VALUE, Actionable.MODULATE);
        var process = new RecipeProcess(machine(input, output), pool.recipes().getFirst(), 1);

        assertThrows(ArithmeticException.class, process::tick);
        assertEquals(Long.MAX_VALUE, input.availableAmount(FIRST));
        assertEquals(0, output.availableAmount(PRODUCT));
    }

    @Test
    void registrationAutomaticallyAppliesSharedInputsToLaterRecipesWithoutRepeatedDeclarations() {
        var pool = new SimpleRecipePool(id("appended_pool"), "pool.title", List.of())
            .withAuxiliaryInputs(List.of(new GenericStack(WATER, 1)));
        var registry = new RecipeRegistry();
        registry.register(pool);
        var first = entry("appended_first", FIRST, 2).recipe();
        var second = entry("appended_second", SECOND, 3).recipe();
        registry.registerRecipe(pool.id(), first);
        registry.registerRecipe(pool.id(), second);
        registry.freeze();
        var frozen = Objects.requireNonNull(registry.get(pool.id()));
        var input = buffer();
        var output = buffer();
        input.insert(FIRST, 2, Actionable.MODULATE);
        input.insert(SECOND, 3, Actionable.MODULATE);
        var machine = machine(input, output);
        for (var recipe : frozen.recipes()) {
            var process = new RecipeProcess(machine, recipe, 1);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            input.insert(WATER, 1, Actionable.MODULATE);
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(0, input.availableAmount(WATER));
        }
        assertEquals(0, input.availableAmount(FIRST));
        assertEquals(0, input.availableAmount(SECOND));
        assertEquals(4, output.availableAmount(PRODUCT));
    }

    @Test
    void replacementRetainsSharedInputsAndCanonicalIdentityWithoutChangingThePreviousSnapshot() {
        var base = entry("replace", FIRST, 2);
        var pool = new SimpleRecipePool(id("replacement_pool"), "pool.title", List.of(base))
            .withAuxiliaryInputs(List.of(new GenericStack(WATER, 1)));
        var current = pool.recipes().getFirst();
        var registry = new RecipeRegistry();
        registry.register(pool);
        registry.declareRevision(current, "before");
        registry.freeze();
        var before = registry.snapshot();
        var raw = entry("replace", SECOND, 3).recipe();
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(before, List.of(new RecipeRegistry.Definition(raw, "after"))));
        var next = pool.prepareRecipe(raw);
        assertSame(next, pool.prepareRecipe(next));
        var prepared = registry.prepareReplacement(before, List.of(new RecipeRegistry.Definition(next, "after")));
        registry.install(prepared);
        var frozen = Objects.requireNonNull(assertInstanceOf(SimpleRecipePool.class, registry.get(pool.id())));
        assertSame(next, frozen.recipes().getFirst());
        assertSame(current, before.orderedPools().getFirst().recipes().getFirst());
        assertEquals(pool.auxiliaryInputs(), frozen.auxiliaryInputs());
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(SECOND, 3);
        assertSame(next, frozen.match(inventory));
        var input = buffer();
        input.insert(SECOND, 3, Actionable.MODULATE);
        var process = new RecipeProcess(machine(input, buffer()), next, 1);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        input.insert(WATER, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.availableAmount(WATER));
    }

    private record Port(SlottedResourceBuffer input, @Nullable SlottedResourceBuffer output)
        implements ResourceHatch {
        @Override
        public boolean isValid() {
            return true;
        }
    }
}
