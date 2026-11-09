package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.RecipeController;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCandidateIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.ResourceRecipeIndex;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePoolStrategiesTest {
    private static final KeyType TYPE = new KeyType("pool_strategy");
    private static final KeyType OTHER = new KeyType("pool_strategy_other");
    private static final Key INPUT = new Key(TYPE, "input");
    private static final Key OUTPUT = new Key(TYPE, "output");
    private static final ResourceLocation GROUP = id("group");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("pool_strategy_test", path);
    }

    private static MachineRecipe recipe(String name) {
        return new MachineRecipe(id(name), 1, List.of(), Set.of(GROUP));
    }

    private static SimpleRecipePool.Entry entry(String name, Key key, long amount) {
        return SimpleRecipePool.Entry.create(id(name), 2, new GenericStack(key, amount), new GenericStack(OUTPUT, 3), Set.of(GROUP));
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, Long.MAX_VALUE);
        capacities.put(OTHER, Long.MAX_VALUE);
        return new SlottedResourceBuffer(3, capacities, () -> {
        });
    }

    private static void install(MachineInstance machine, ResourceStorage... inputs) {
        var ports = new ObjectArrayList<ResourceHatch>();
        for (var input : inputs) ports.add(new Port(input));
        machine.components().put(MachineHatches.TYPE, new MachineHatches(ports, List.of(), List.of()));
    }

    private record Port(ResourceStorage input) implements ResourceHatch {
        @Override
        public @Nullable ResourceStorage output() {
            return null;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }

    private record Completed(List<MachineRecipe> recipes, long work) {
    }

    private static Completed complete(RecipeCatalog.CandidatePass pass, int slice) {
        long work = 0;
        for (int step = 0; step < 20000; step++) {
            var budget = new RecipeWorkBudget(slice);
            boolean done = pass.advance(budget);
            work += slice - budget.remaining();
            if (done) return new Completed(pass.result(), work);
            assertTrue(pass.current());
            assertThrows(IllegalStateException.class, pass::result);
        }
        throw new AssertionError("Pool discovery did not finish");
    }

    @Test
    void registryPreservesIndependentStrategyAndPoolUnionPriorityBeforeExternalIntersection() {
        var first = recipe("first");
        var shared = recipe("shared");
        var last = recipe("last");
        var calls = new int[1];
        var strategy = new StrategyPool(id("custom"), "pool.title", List.of(first, shared), List.of(), _ -> {
            calls[0]++;
            return List.of(shared, first, shared);
        });
        var registry = new RecipeRegistry();
        registry.register(strategy);
        registry.register(new DefaultRecipePool(id("default"), "pool.title", List.of(shared, last)));
        registry.freeze();
        var frozen = Objects.requireNonNull(assertInstanceOf(StrategyPool.class, registry.get(strategy.id())));
        assertEquals(0, calls[0]);
        var expected = registry.catalog().recipes();
        RecipeCandidateIndex filter = (_, _, scope) -> {
            assertEquals(expected, scope);
            return List.of(shared, last);
        };
        var result = complete(registry.catalog().beginCandidates(new MachineInstance(), Set.of(GROUP), List.of(filter)), 1).recipes;
        assertEquals(1, calls[0]);
        assertEquals(List.of(shared, last), result);
        assertSame(shared, frozen.recipes().getLast());
    }

    @Test
    void noOpinionFallsBackOnlyToItsPoolWhileEmptyAndForeignResultsStayExplicit() {
        var first = recipe("first");
        var second = recipe("second");
        var empty = new StrategyPool(id("empty"), "pool.title", List.of(first), List.of(), _ -> List.of());
        var noOpinion = new StrategyPool(id("no_opinion"), "pool.title", List.of(second), List.of(), _ -> null);
        var catalog = new RecipeCatalog(List.of(empty, noOpinion));
        assertEquals(List.of(second), catalog.candidates(new MachineInstance(), Set.of(GROUP), List.of()));
        var foreign = new StrategyPool(id("foreign"), "pool.title", List.of(first), List.of(), _ -> List.of(second));
        var invalid = new RecipeCatalog(List.of(foreign, new DefaultRecipePool(id("other"), "pool.title", List.of(second))));
        assertThrows(IllegalArgumentException.class, () -> invalid.candidates(new MachineInstance(), Set.of(), List.of()));
    }

    @Test
    void malformedFreezeDoesNotPublishAnyCatalogOrEraseTheValidStrategyForRetry() {
        var first = recipe("first");
        var custom = new RecipePool() {
            @Override
            public ResourceLocation id() {
                return RecipePoolStrategiesTest.id("bad");
            }

            @Override
            public String translationKey() {
                return "pool.title";
            }

            @Override
            public List<MachineRecipe> recipes() {
                return List.of(first);
            }

            @Override
            public List<MachineType> machines() {
                return List.of();
            }

            @Override
            public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
                return new DefaultRecipePool(id(), translationKey(), List.of());
            }
        };
        var registry = new RecipeRegistry();
        registry.register(custom);
        assertThrows(IllegalArgumentException.class, registry::freeze);
        assertThrows(IllegalStateException.class, registry::catalog);
    }

    @Test
    void actualQuantityCaptureAndPostingsReduceDiscoveryWorkAgainstTheExistingFixedInputIndex() {
        var entries = new ObjectArrayList<SimpleRecipePool.Entry>();
        entries.add(entry("too_much", INPUT, 12));
        entries.add(entry("first_match", INPUT, 7));
        entries.add(entry("second_match", INPUT, 5));
        for (int i = 0; i < 1000; i++) entries.add(entry("absent_" + i, new Key(TYPE, "absent_" + i), 2));
        var storage = buffer();
        storage.insert(INPUT, 8, Actionable.MODULATE);
        for (RecipeController machine : List.of(new MultithreadController(), new CrossRecipeController())) {
            install(machine, storage);
            // The production controller installs its actual named standard scanner policy; no manual stable predicate.
            var simple = new SimpleRecipePool(id("simple"), "pool.title", entries);
            var optimized = new RecipeCatalog(List.of(simple));
            MachineWorkSlots host = machine.createRecipeHost(List.of(new WorkSlotDefinition("main")), Set.of(GROUP));
            machine.createRecipeScanner(host, optimized, () -> 7);
            var general = new RecipeCatalog(List.of(new DefaultRecipePool(id("general"), "pool.title", simple.recipes())));
            var index = ResourceRecipeIndex.forFixedInputs(general, _ -> true);
            var fast = complete(optimized.beginCandidates(machine, Set.of(GROUP), List.of()), 1);
            var baseline = complete(general.beginCandidates(machine, Set.of(GROUP), List.of(index)), 2);
            assertEquals(List.of(entries.get(1).recipe(), entries.get(2).recipe()), fast.recipes);
            assertEquals(baseline.recipes, fast.recipes);
            assertTrue(fast.work * 20 < baseline.work, "Pool postings must avoid full-catalog scope/index visits");
            assertEquals(8, storage.availableAmount(INPUT));
            assertEquals(1003, optimized.recipes().size());
        }
    }

    @Test
    void aliasesDoNotDoubleCountAndIndependentViewsRetainExactKeyTypesAndLongAmounts() {
        var first = buffer();
        var second = buffer();
        first.insert(INPUT, Long.MAX_VALUE - 2, Actionable.MODULATE);
        second.insert(INPUT, 2, Actionable.MODULATE);
        var other = new Key(OTHER, "input");
        second.insert(other, 1, Actionable.MODULATE);
        var machine = new MachineInstance();
        install(machine, first, first, second);
        var entries = List.of(entry("long", INPUT, Long.MAX_VALUE), entry("other_type", other, 1));
        var simple = new SimpleRecipePool(id("long_pool"), "pool.title", entries, owner -> owner == machine);
        assertEquals(List.of(entries.getFirst().recipe(), entries.getLast().recipe()),
            new RecipeCatalog(List.of(simple)).candidates(machine, Set.of(), List.of()));
        second.extract(INPUT, 1, Actionable.MODULATE);
        assertEquals(List.of(entries.getLast().recipe()), new RecipeCatalog(List.of(simple)).candidates(machine, Set.of(), List.of()));
    }

    @Test
    void poolLiveViewsAreRecheckedAndRestartRecapturesNewCandidatesWithoutChangingSelectedPools() {
        var storage = buffer();
        storage.insert(INPUT, 5, Actionable.MODULATE);
        var missing = new Key(TYPE, "missing");
        var machine = new MachineInstance();
        install(machine, storage);
        var first = entry("first", INPUT, 5);
        var second = entry("second", missing, 3);
        var simple = new SimpleRecipePool(id("live"), "pool.title", List.of(first, second), owner -> owner == machine);
        var catalog = new RecipeCatalog(List.of(simple));
        var pass = catalog.beginCandidates(machine, Set.of(), List.of());
        assertEquals(List.of(first.recipe()), complete(pass, 1).recipes);
        storage.insert(missing, 3, Actionable.MODULATE);
        pass.restartLiveIndexes();
        assertEquals(List.of(first.recipe(), second.recipe()), complete(pass, 1).recipes);
        RecipeCandidateIndex mutation = (_, _, scope) -> {
            storage.extract(INPUT, 1, Actionable.MODULATE);
            return scope;
        };
        var stale = catalog.beginCandidates(machine, Set.of(), List.of(mutation));
        assertFalse(stale.advance(new RecipeWorkBudget(10000)));
        assertFalse(stale.current());
        assertThrows(IllegalStateException.class, stale::result);
    }

    @Test
    void unknownInputSemanticsUsePoolLocalFallbackAndSimpleSchemaRejectsGeneralRequirements() {
        var machine = new MachineInstance();
        install(machine, buffer());
        var first = entry("missing", INPUT, 9);
        var safeDefault = new SimpleRecipePool(id("unknown"), "pool.title", List.of(first));
        assertEquals(List.of(first.recipe()), new RecipeCatalog(List.of(safeDefault)).candidates(machine, Set.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> SimpleRecipePool.Entry.of(recipe("general")));
        var registry = new RecipeRegistry();
        registry.register(safeDefault);
        registry.registerRecipe(safeDefault.id(), recipe("general"));
        assertThrows(IllegalArgumentException.class, registry::freeze);
        assertThrows(IllegalStateException.class, registry::catalog);
    }

    @Test
    void skippedInvalidAndNullBindingsBecomingUsableDuringYieldInvalidateTheWholeOpinion() {
        for (boolean initiallyInvalid : List.of(true, false)) {
            var storage = buffer();
            storage.insert(INPUT, 7, Actionable.MODULATE);
            var port = new MutablePort(initiallyInvalid ? storage : null, !initiallyInvalid);
            var machine = new MachineInstance();
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(port), List.of(), List.of()));
            var pool = new SimpleRecipePool(id("skipped"), "pool.title", List.of(entry("input", INPUT, 7)), owner -> owner == machine);
            var cursor = Objects.requireNonNull(pool.beginCandidates(machine, Set.of()));
            assertFalse(cursor.advance(new RecipeWorkBudget(2))); // hatch list and skipped binding, before its proof
            port.valid = true;
            port.input = storage;
            assertFalse(cursor.advance(new RecipeWorkBudget(100)));
            assertFalse(cursor.current());
            assertThrows(IllegalStateException.class, cursor::result);
            assertEquals(List.of(pool.recipes().getFirst()), new RecipeCatalog(List.of(pool)).candidates(machine, Set.of(), List.of()));
        }
    }

    @Test
    void freshTemporaryAdaptersSharePhysicalIdentityAndMakeBoundedProgressWithoutDoubleCounting() {
        var storage = buffer();
        storage.insert(INPUT, 7, Actionable.MODULATE);
        ResourceHatch port = new ResourceHatch() {
            @Override
            public ResourceStorage input() {
                return new FreshView(storage);
            }

            @Override
            public @Nullable ResourceStorage output() {
                return null;
            }

            @Override
            public boolean isValid() {
                return true;
            }
        };
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(port, port), List.of(), List.of()));
        var matching = entry("matching", INPUT, 7);
        var tooMuch = entry("too_much", INPUT, 8);
        var pool = new SimpleRecipePool(id("adapters"), "pool.title", List.of(matching, tooMuch), owner -> owner == machine);
        var catalog = new RecipeCatalog(List.of(pool));
        assertEquals(List.of(matching.recipe()), complete(catalog.beginCandidates(machine, Set.of(), List.of()), 1).recipes);
        assertEquals(List.of(matching.recipe()), catalog.candidates(machine, Set.of(), List.of()));
    }

    @Test
    void defaultControllerFallsBackForAnActualCustomFactoryThatMakesTheMissingInputOptionalAndAdmitsIt() {
        for (RecipeController machine : List.of(new MultithreadController(), new CrossRecipeController())) {
            var input = buffer();
            var output = buffer();
            ResourceHatch port = new ResourceHatch() {
                @Override
                public ResourceStorage input() {
                    return input;
                }

                @Override
                public ResourceStorage output() {
                    return output;
                }

                @Override
                public boolean isValid() {
                    return true;
                }
            };
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(port), List.of(), List.of()));
            var requirement = RecipeRequirements.RESOURCES.requirement(id("optional_io"), new ResourceRequirement(
                List.of(new GenericStack(INPUT, 9)), List.of(new GenericStack(OUTPUT, 3))));
            var definition = new MachineRecipe(id("optional"), 2, List.of(requirement));
            var pool = new SimpleRecipePool(id("optional_pool"), "pool.title", List.of(SimpleRecipePool.Entry.of(definition)));
            var catalog = new RecipeCatalog(List.of(pool));
            MachineWorkSlots host = machine.createRecipeHost(List.of(new WorkSlotDefinition("main")), Set.of());
            machine.createRecipeScanner(host, catalog, () -> 7);
            RecipeProcessFactory factory = (owner, recipe, seed, tuning) -> {
                var process = new RecipeProcess(owner, recipe, seed, tuning);
                RecipeOverlays.getOrCreate(process).put(requirement, "make_optional", RecipeOverlayTypes.RESOURCE_IO,
                    new ResourceRequirementPatch(List.of(), null, null, null, null)).complete();
                return process;
            };
            var custom = machine instanceof CrossRecipeController
                ? RecipeScanner.crossRecipe(host, catalog, factory, List.of(), List.of(), _ -> false, () -> 17, List.of())
                : RecipeScanner.multithreaded(host, catalog, factory, List.of(), List.of(), _ -> false, () -> 17, List.of());
            assertEquals(List.of(definition), catalog.candidates(machine, Set.of(), List.of()));
            assertEquals(RecipeScanner.Status.ADMITTED, custom.scan(new ScanLimits(1, 10)).status());
            assertEquals(0, input.availableAmount(INPUT));
        }
    }

    private static final class MutablePort implements ResourceHatch {
        @Nullable ResourceStorage input;
        boolean valid;

        MutablePort(@Nullable ResourceStorage input, boolean valid) {
            this.input = input;
            this.valid = valid;
        }

        @Override
        public @Nullable ResourceStorage input() {
            return input;
        }

        @Override
        public @Nullable ResourceStorage output() {
            return null;
        }

        @Override
        public boolean isValid() {
            return valid;
        }
    }

    private record FreshView(ResourceStorage delegate) implements ResourceStorage {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public StorageSnapshot snapshot() {
            return delegate.snapshot();
        }

        @Override
        public long amount(AEKey key) {
            throw new AssertionError("Quantity lookup uses snapshots");
        }

        @Override
        public long availableAmount(AEKey key) {
            throw new AssertionError("Quantity lookup uses snapshots");
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode) {
            throw new AssertionError("Lookup must not mutate");
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            throw new AssertionError("Lookup must not mutate");
        }

        @Override
        public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            throw new AssertionError("Lookup must not reserve");
        }

        @Override
        public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            throw new AssertionError("Lookup must not reserve");
        }
    }

    private record StrategyPool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes,
                                List<MachineType> machines,
                                Function<MachineInstance, @Nullable Collection<MachineRecipe>> lookup) implements RecipePool {
        @Override
        public StrategyPool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            return new StrategyPool(id, translationKey, recipes, machines, lookup);
        }

        @Override
        @SuppressWarnings("ReturnOfInnerClass") // Explicit incremental custom-pool fixture.
        public RecipeCandidateIndex.Cursor beginCandidates(MachineInstance machine, Set<ResourceLocation> groups) {
            return new RecipeCandidateIndex.Cursor() {
                private boolean done;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    if (done) return true;
                    if (!budget.take()) return false;
                    done = true;
                    return true;
                }

                @Override
                public @Nullable Collection<MachineRecipe> result() {
                    if (!done) throw new IllegalStateException("Incomplete custom pool");
                    return lookup.apply(machine);
                }
            };
        }
    }
}
