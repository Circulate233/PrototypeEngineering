package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex.Footprint;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceRecipeIndexTest {
    private static final KeyType FIRST_TYPE = new KeyType("resource_index_first");
    private static final KeyType SECOND_TYPE = new KeyType("resource_index_second");
    private static final Key A = new Key(FIRST_TYPE, "a");
    private static final Key MISSING = new Key(FIRST_TYPE, "missing");
    private static final Key C = new Key(SECOND_TYPE, "a");
    private static final ResourceLocation GROUP = id("group");
    private static final ResourceLocation OTHER_GROUP = id("other_group");
    private static final Runnable NO_OP = () -> {
    };

    private static RecipeRequirement<ResourceRequirement> inputs(GenericStack... stacks) {
        return RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(List.of(stacks), List.of()));
    }

    private static MachineRecipe recipe(String name, RecipeRequirement<?>... requirements) {
        return new MachineRecipe(id(name), 2, List.of(requirements), Set.of(GROUP));
    }

    private static RecipeCatalog catalog(MachineRecipe... recipes) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "resource.index.pool", List.of(recipes))));
    }

    private static SlottedResourceBuffer buffer(long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST_TYPE, capacity);
        capacities.put(SECOND_TYPE, capacity);
        return new SlottedResourceBuffer(3, capacities, NO_OP);
    }

    private static void install(MachineInstance machine, ResourceHatch... hatches) {
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatches), List.of(), List.of()));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("resource_index_test", path);
    }

    private static void finish(ResourceRecipeIndex.QuantityLookup cursor) {
        for (int step = 0; step < 1000; step++) {
            var budget = new RecipeWorkBudget(1);
            if (cursor.advance(budget)) return;
            assertTrue(budget.remaining() >= 0);
            assertTrue(cursor.current(), "A stable owner must progress rather than restart");
        }
        throw new AssertionError("One-unit lookup did not finish");
    }

    private static void validate(ResourceRecipeIndex.QuantityLookup cursor, boolean expected) {
        var validation = cursor.beginValidation();
        for (int step = 0; step < 1000; step++) {
            if (validation.advance(new RecipeWorkBudget(1))) {
                assertEquals(expected, validation.current());
                return;
            }
        }
        throw new AssertionError("One-unit validation did not finish");
    }

    @Test
    void postingsMatchAllDeclaredKeysAcrossTypesAndPreserveUnknownRecipesWithinCatalogScopeAndOrder() {
        var first = buffer(100);
        var second = buffer(100);
        first.insert(C, 1, Actionable.MODULATE);
        first.insert(A, 3, Actionable.MODULATE);
        second.insert(C, 1, Actionable.MODULATE);
        second.insert(A, 2, Actionable.MODULATE);
        var machine = new MachineInstance();
        install(machine, new Ports(first, null, true), new Ports(new ReadOnlyView(first, Set.of(A, C)), null, true),
            new Ports(second, null, true));
        var both = recipe("z_both", inputs(new GenericStack(A, 3), new GenericStack(C, 2)));
        var energy = recipe("energy", RecipeRequirements.ENERGY.requirement(id("energy_io"), new EnergyRequirement(0, 1, 0, 0, false)));
        var tooMuchA = recipe("too_much_a", inputs(new GenericStack(A, 6)));
        var universal = recipe("universal");
        var dynamic = recipe("dynamic", RecipeRequirements.RESOURCES.requirement(id("dynamic_io"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null, List.of(),
            List.of(new DynamicInputGroup(new NamedResourceMatcher(id("matcher"), (_, _) -> true), MISSING, 1, List.of(MISSING), 10)))));
        var selected = recipe("selected", RecipeRequirements.SELECTIVE.requirement(id("choice"),
            new SelectiveRequirement(List.of(inputs(new GenericStack(MISSING, 1))))));
        var noStartInput = recipe("output_only", RecipeRequirements.RESOURCES.requirement(id("output_io"),
            new ResourceRequirement(List.of(), List.of(new GenericStack(A, 1)))));
        var mutableInput = inputs(new GenericStack(MISSING, 99));
        var externallyModified = recipe("externally_modified", mutableInput);
        var customType = new RecipeRequirementType<ResourceRequirement>(RecipeRequirements.RESOURCES.id(), new RecipeRequirementSystem<>() {
        });
        var unknownType = recipe("unknown_type", customType.requirement(id("unknown_io"), new ResourceRequirement(List.of(new GenericStack(MISSING, 99)), List.of())));
        var tooMuchC = recipe("too_much_c", inputs(new GenericStack(C, 3)));
        var otherGroup = new MachineRecipe(id("a_other_group"), 2, List.of(inputs(new GenericStack(A, 1))), Set.of(OTHER_GROUP));
        var catalog = catalog(both, energy, tooMuchA, universal, dynamic, selected, noStartInput, externallyModified, unknownType, tooMuchC, otherGroup);
        var mutableStacks = new ObjectArrayList<>(List.of(new GenericStack(new Key(FIRST_TYPE, "a"), 1), new GenericStack(A, 2), new GenericStack(C, 2)));
        var bothFootprint = new Footprint(both, mutableStacks);
        mutableStacks.clear();
        var footprints = new ObjectArrayList<>(List.of(new Footprint(otherGroup, List.of(new GenericStack(A, 1))),
            new Footprint(tooMuchC, List.of(new GenericStack(C, 3))), new Footprint(noStartInput, List.of()),
            new Footprint(tooMuchA, List.of(new GenericStack(A, 6))), bothFootprint));
        var index = new ResourceRecipeIndex(catalog, footprints);
        footprints.clear();
        var expected = List.of(both, energy, universal, dynamic, selected, noStartInput, externallyModified, unknownType);
        var candidates = catalog.candidates(machine, Set.of(GROUP), List.of(index));
        assertEquals(expected, candidates);
        var reverseScope = new ObjectArrayList<>(catalog.candidates(machine, Set.of(GROUP), List.of()));
        Collections.reverse(reverseScope);
        assertEquals(expected, index.lookup(machine, Set.of(GROUP), reverseScope));
        var reverseGroups = new ObjectLinkedOpenHashSet<>(List.of(OTHER_GROUP, GROUP));
        assertEquals(List.of(both, energy, universal, dynamic, selected, noStartInput, externallyModified, unknownType, otherGroup),
            catalog.candidates(machine, reverseGroups, List.of(index)));
        var fixed = ResourceRecipeIndex.forFixedInputs(catalog, definition -> definition != externallyModified);
        assertEquals(expected, catalog.candidates(machine, Set.of(GROUP), List.of(fixed)));
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(new RecipeProcess(machine, unknownType, 17)));
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(new RecipeProcess(machine, both, 17)));
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.probeStart(new RecipeProcess(machine, tooMuchA, 17)));
        // A definition input is not proof: the undeclared recipe's actual overlay changes the key.
        var modified = new RecipeProcess(machine, externallyModified, 17);
        RecipeOverlays.getOrCreate(modified).put(mutableInput, "external", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(List.of(new GenericStack(A, 1)), null, null, null, null)).complete();
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(modified));
        assertEquals(3, first.amount(A));
        assertEquals(1, first.amount(C));
        assertEquals(2, second.amount(A));
        assertEquals(1, second.amount(C));
        assertEquals(3, first.availableAmount(A));
        assertEquals(2, second.availableAmount(A));
    }

    @Test
    void aliasesDeduplicatePerContextAndKeyWhileReservationsEmptyInputsAndInvalidHatchesRemainAuthoritative() {
        var storage = buffer(100);
        storage.insert(A, 5, Actionable.MODULATE);
        storage.insert(C, 2, Actionable.MODULATE);
        var machine = new MachineInstance();
        install(machine, new Ports(new ReadOnlyView(storage, Set.of(C)), null, true),
            new Ports(new ReadOnlyView(storage, Set.of(A)), null, true), new Ports(new ReadOnlyView(storage, Set.of(A, C)), null, true));
        var exact = recipe("exact", inputs(new GenericStack(A, 5)));
        var both = recipe("both", inputs(new GenericStack(A, 5), new GenericStack(C, 2)));
        var duplicateWouldMatch = recipe("duplicate_would_match", inputs(new GenericStack(A, 6)));
        var universal = recipe("universal");
        var catalog = catalog(exact, both, duplicateWouldMatch, universal);
        var index = new ResourceRecipeIndex(catalog, List.of(new Footprint(exact, List.of(new GenericStack(A, 5))),
            new Footprint(both, List.of(new GenericStack(A, 5), new GenericStack(C, 2))),
            new Footprint(duplicateWouldMatch, List.of(new GenericStack(A, 6)))));
        assertEquals(List.of(exact, both, universal), catalog.candidates(machine, Set.of(GROUP), List.of(index)));
        var held = storage.reserveExtract(storage.snapshot(), A, 5);
        assertEquals(ReservationResult.Status.ACCEPTED, held.status());
        assertEquals(List.of(universal), catalog.candidates(machine, Set.of(GROUP), List.of(index)));
        assertEquals(5, storage.amount(A));
        ResourceTransactions.rollback(List.of(Objects.requireNonNull(held.reservation())));
        assertEquals(List.of(exact, both, universal), catalog.candidates(machine, Set.of(GROUP), List.of(index)));
        install(machine, new Ports(null, storage, true), new Ports(storage, null, false));
        assertEquals(List.of(universal), catalog.candidates(machine, Set.of(GROUP), List.of(index)));
        machine.components().remove(MachineHatches.TYPE);
        assertEquals(List.of(universal), catalog.candidates(machine, Set.of(GROUP), List.of(index)));
        assertEquals(List.of(universal), catalog.candidates(machine, Set.of(), List.of(index)));
        assertTrue(catalog.candidates(machine, Set.of(OTHER_GROUP), List.of(index)).isEmpty());
        var onlyIndexed = catalog(exact);
        var hardEmpty = new ResourceRecipeIndex(onlyIndexed, List.of(new Footprint(exact, List.of(new GenericStack(A, 5)))));
        assertEquals(List.of(), onlyIndexed.candidates(machine, Set.of(GROUP), List.of(hardEmpty)));
    }

    @Test
    void globalScopeIndexesUngroupedInputsAndRetainsUngroupedAndOtherGroupFallbacks() {
        var storage = buffer(100);
        storage.insert(A, 2, Actionable.MODULATE);
        var machine = new MachineInstance();
        install(machine, new Ports(storage, null, true));
        var matching = new MachineRecipe(id("ungrouped_matching"), 2, List.of(inputs(new GenericStack(A, 2))));
        var missing = new MachineRecipe(id("ungrouped_missing"), 2, List.of(inputs(new GenericStack(MISSING, 1))));
        var ungrouped = new MachineRecipe(id("ungrouped_fallback"), 2, List.of());
        var other = new MachineRecipe(id("other_fallback"), 2, List.of(), Set.of(OTHER_GROUP));
        var catalog = catalog(matching, missing, ungrouped, other);
        var index = ResourceRecipeIndex.forFixedInputs(catalog, _ -> true);
        assertEquals(List.of(matching, ungrouped, other), catalog.candidates(machine, Set.of(), List.of(index)));
        assertEquals(List.of(other), catalog.candidates(machine, Set.of(OTHER_GROUP), List.of(index)));
        assertTrue(catalog.candidates(machine, Set.of(GROUP), List.of(index)).isEmpty());
        machine.components().remove(MachineHatches.TYPE);
        assertEquals(List.of(ungrouped, other), catalog.candidates(machine, Set.of(), List.of(index)));
    }

    @Test
    void totalAvailabilitySaturatesAndFrozenDeclarationsRejectCounterfeitRepeatedAndMalformedFootprints() {
        var first = buffer(Long.MAX_VALUE);
        var second = buffer(Long.MAX_VALUE);
        first.insert(A, Long.MAX_VALUE - 5, Actionable.MODULATE);
        second.insert(A, 10, Actionable.MODULATE);
        var machine = new MachineInstance();
        install(machine, new Ports(first, null, true), new Ports(second, null, true));
        var recipe = recipe("maximum", inputs(new GenericStack(A, Long.MAX_VALUE)));
        var aggregate = recipe("aggregate", inputs(new GenericStack(A, Long.MAX_VALUE)), RecipeRequirements.RESOURCES.requirement(
            id("extra_io"), new ResourceRequirement(List.of(new GenericStack(A, 1)), List.of())));
        var catalog = catalog(recipe, aggregate);
        var footprint = new Footprint(recipe, List.of(new GenericStack(A, Long.MAX_VALUE)));
        var index = new ResourceRecipeIndex(catalog, List.of(footprint,
            new Footprint(aggregate, List.of(new GenericStack(A, Long.MAX_VALUE), new GenericStack(A, 1)))));
        assertEquals(List.of(recipe, aggregate), catalog.candidates(machine, Set.of(GROUP), List.of(index)));
        var fixed = ResourceRecipeIndex.forFixedInputs(catalog, _ -> true);
        assertEquals(List.of(recipe, aggregate), catalog.candidates(machine, Set.of(GROUP), List.of(fixed)));
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(new RecipeProcess(machine, aggregate, 17)));
        assertEquals(Long.MAX_VALUE - 5, first.availableAmount(A));
        assertEquals(10, second.availableAmount(A));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRecipeIndex(catalog, List.of(footprint, footprint)));
        var counterfeit = recipe("maximum", inputs(new GenericStack(A, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRecipeIndex(catalog,
            List.of(new Footprint(counterfeit, List.of(new GenericStack(A, 1))))));
        assertThrows(IllegalArgumentException.class, () -> index.lookup(machine, Set.of(GROUP), List.of(counterfeit)));
        assertThrows(IllegalArgumentException.class, () -> new Footprint(recipe, List.of(new GenericStack(A, 0))));
    }

    @Test
    void freshSameContextAdaptersFinishBoundedLookupAndValidateExactCapturedQuantities() {
        var storage = buffer(100);
        storage.insert(A, 5, Actionable.MODULATE);
        var ports = new FreshPorts(storage);
        var machine = new MachineInstance();
        install(machine, ports);
        var exact = recipe("fresh_exact", inputs(new GenericStack(A, 5)));
        var missing = recipe("fresh_missing", inputs(new GenericStack(A, 6)));
        var catalog = catalog(exact, missing);
        var index = ResourceRecipeIndex.forFixedInputs(catalog, _ -> true);
        var cursor = index.beginLookup(machine, Set.of(GROUP), catalog.recipes());
        finish(cursor);
        assertEquals(List.of(exact), cursor.result());
        assertTrue(ports.views.size() > 1);
        assertEquals(1, ports.views.stream().mapToInt(view -> view.snapshots).sum());
        validate(cursor, true);
        assertEquals(1, ports.views.stream().mapToInt(view -> view.snapshots).sum());
        assertEquals(5, storage.availableAmount(A));
    }

    @Test
    void changedQuantityContextIdentityAndAdapterValidityInvalidateOriginalCaptureWithoutResampling() {
        for (Consumer<FreshPorts> change : List.<Consumer<FreshPorts>>of(
            ports -> ports.source.extract(A, 1, Actionable.MODULATE),
            ports -> {
                var replacement = buffer(100);
                replacement.insert(A, 5, Actionable.MODULATE);
                ports.source = replacement;
            },
            ports -> ports.identity = new Object(),
            ports -> ports.inputValid = false,
            ports -> ports.views.getFirst().valid = false)) {
            var storage = buffer(100);
            storage.insert(A, 5, Actionable.MODULATE);
            var ports = new FreshPorts(storage);
            var machine = new MachineInstance();
            install(machine, ports);
            var exact = recipe("captured_exact", inputs(new GenericStack(A, 5)));
            var catalog = catalog(exact);
            var index = ResourceRecipeIndex.forFixedInputs(catalog, _ -> true);
            var cursor = index.beginLookup(machine, Set.of(GROUP), catalog.recipes());
            for (int step = 0; ports.views.stream().noneMatch(view -> view.snapshots > 0) && step < 100; step++) {
                assertFalse(cursor.advance(new RecipeWorkBudget(1)));
            }
            assertEquals(1, ports.views.stream().mapToInt(view -> view.snapshots).sum());
            change.accept(ports);
            validate(cursor, false);
            assertFalse(cursor.current());
            assertThrows(IllegalStateException.class, cursor::result);
            assertEquals(1, ports.views.stream().mapToInt(view -> view.snapshots).sum());
            var fresh = index.beginLookup(machine, Set.of(GROUP), catalog.recipes());
            finish(fresh);
            boolean eligible = ports.inputValid && ports.source.availableAmount(A) >= 5;
            assertEquals(eligible ? List.of(exact) : List.of(), fresh.result());
        }
    }

    @Test
    void skippedNullInvalidHatchAndInvalidAdapterCannotPublishAfterBecomingUsableBetweenSlices() {
        for (int scenario = 0; scenario < 6; scenario++) {
            int skipped = scenario % 3;
            boolean completedBeforeChange = scenario >= 3;
            var storage = buffer(100);
            storage.insert(A, 5, Actionable.MODULATE);
            var ports = new FreshPorts(storage);
            ports.present = skipped != 0;
            ports.valid = skipped != 1;
            ports.inputValid = skipped != 2;
            var machine = new MachineInstance();
            install(machine, ports);
            var exact = recipe("became_usable", inputs(new GenericStack(A, 5)));
            var catalog = catalog(exact);
            var index = ResourceRecipeIndex.forFixedInputs(catalog, _ -> true);
            var cursor = index.beginLookup(machine, Set.of(GROUP), catalog.recipes());
            for (int step = 0; ports.validReads == 0 && step < 100; step++) {
                assertFalse(cursor.advance(new RecipeWorkBudget(1)));
            }
            assertTrue(ports.validReads > 0);
            if (completedBeforeChange) {
                finish(cursor);
                assertEquals(List.of(), cursor.result());
            }
            ports.present = true;
            ports.valid = true;
            ports.inputValid = true;
            if (completedBeforeChange) {
                validate(cursor, false);
            } else {
                for (int step = 0; cursor.current() && step < 100; step++) {
                    assertFalse(cursor.advance(new RecipeWorkBudget(1)));
                }
            }
            assertFalse(cursor.current());
            assertThrows(IllegalStateException.class, cursor::result);
            var fresh = index.beginLookup(machine, Set.of(GROUP), catalog.recipes());
            finish(fresh);
            assertEquals(List.of(exact), fresh.result());
            validate(fresh, true);
        }
    }

    private static final class FreshPorts implements ResourceHatch {
        private final ObjectArrayList<ReadOnlyView> views = new ObjectArrayList<>();
        private ResourceStorage source;
        private Object identity = new Object();
        private boolean valid = true;
        private boolean present = true;
        private boolean inputValid = true;
        private int validReads;

        private FreshPorts(ResourceStorage source) {
            this.source = source;
        }

        @Override
        public @Nullable ResourceStorage input() {
            if (!present) return null;
            var view = new ReadOnlyView(source, Set.of(A));
            view.valid = inputValid;
            views.add(view);
            return view;
        }

        @Override
        public @Nullable ResourceStorage output() {
            return null;
        }

        @Override
        public Object inputStorageIdentity() {
            return identity;
        }

        @Override
        public boolean isValid() {
            validReads++;
            return valid;
        }
    }

    private record Ports(@Nullable ResourceStorage input, @Nullable ResourceStorage output,
                         boolean valid) implements ResourceHatch {
        @Override
        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Different public views of one real buffer; any IO call would violate the pure index contract.
     */
    private static final class ReadOnlyView implements ResourceStorage {
        private final ResourceStorage delegate;
        private final Set<AEKey> visible;
        private boolean valid = true;
        private int snapshots;

        private ReadOnlyView(ResourceStorage delegate, Set<AEKey> visible) {
            this.delegate = delegate;
            this.visible = visible;
        }

        @Override
        public boolean isValid() {
            return valid && delegate.isValid();
        }

        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public StorageSnapshot snapshot() {
            snapshots++;
            var snapshot = delegate.snapshot();
            var entries = new AEKey2LongMap.LinkdOpenHashMap();
            snapshot.available().object2LongEntrySet().forEach(entry -> {
                if (visible.contains(entry.getKey())) {
                    entries.put(entry.getKey(), entry.getLongValue());
                }
            });
            return new StorageSnapshot(snapshot.context(), snapshot.revision(), entries);
        }

        @Override
        public long amount(AEKey key) {
            throw new AssertionError("Index must use snapshots");
        }

        @Override
        public long availableAmount(AEKey key) {
            throw new AssertionError("Index must use snapshots");
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode) {
            throw new AssertionError("Index must not perform IO");
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            throw new AssertionError("Index must not perform IO");
        }

        @Override
        public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            throw new AssertionError("Index must not reserve");
        }

        @Override
        public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            throw new AssertionError("Index must not reserve");
        }
    }
}
