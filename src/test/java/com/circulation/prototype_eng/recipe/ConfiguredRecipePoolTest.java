package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.RecipeConfiguration;
import com.circulation.prototype_eng.api.recipe.RecipeInventoryMatch;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredRecipePoolTest {
    private static final KeyType TYPE = new KeyType("common_pool");
    private static final Key INPUT = new Key(TYPE, "input");
    private static final Key AUXILIARY = new Key(TYPE, "auxiliary");
    private static final Key OUTPUT = new Key(TYPE, "output");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("common_pool_test", path);
    }

    private static SimpleRecipePool.Entry entry(String name) {
        return SimpleRecipePool.Entry.create(id(name), 1, new GenericStack(INPUT, 2),
            new GenericStack(OUTPUT, 1), Set.of(id("original_group")));
    }

    @Test
    void commonRequirementsApplyToAppendedDefinitionsAndKeepSingleInputDiscoveryAcrossFreeze() {
        var original = entry("first");
        var second = entry("second");
        var common = RecipeRequirements.RESOURCES.requirement(id("common"),
            new ResourceRequirement(List.of(new GenericStack(AUXILIARY, 3)), List.of()));
        var source = new SimpleRecipePool(id("simple"), "simple.title", List.of(original));
        var pool = source.withConfiguration(RecipeConfiguration.builder().addRequirement(common)
            .recipeGroups(List.of(id("configured_group"))).durationTicks(2).maxConcurrentThreads(3).build())
                         .withExistingJeiPage("external:common");
        var first = pool.recipes().getFirst();
        var appended = pool.prepareRecipe(second.recipe());
        assertSame(appended, pool.prepareRecipe(appended));
        var preparedAgain = pool.prepareRecipe(RecipeBuilder.from(appended).durationTicks(8).build());
        assertEquals(2, preparedAgain.durationTicks());
        assertEquals(appended.orderedRequirements(), preparedAgain.orderedRequirements());
        var registry = new RecipeRegistry();
        registry.register(pool);
        registry.registerRecipe(pool.id(), appended);
        registry.declareRevision(first, "common_inputs");
        registry.declareRevision(appended, "common_inputs");
        registry.freeze();
        var frozen = Objects.requireNonNull(registry.get(pool.id()));
        assertSame(first, frozen.recipes().getFirst());
        assertSame(appended, frozen.recipes().getLast());
        assertEquals("external:common", frozen.existingJeiPageId());
        assertNotSame(original.recipe(), first);
        assertTrue(first.orderedRequirements().contains(common));
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(INPUT, 8);
        var matches = frozen.match(inventory, 20);
        assertEquals(List.of(first, appended), matches.stream().map(RecipeInventoryMatch::recipe).toList());
        assertEquals(List.of(4, 4), matches.stream().map(RecipeInventoryMatch::maximumParallelism).toList());
        var cursor = frozen.beginMatch(inventory, 20, Set.of(id("configured_group")));
        while (!cursor.advance(new RecipeWorkBudget(1))) assertThrows(IllegalStateException.class, cursor::result);
        assertEquals(List.of(first, appended), cursor.result());
        assertEquals(4, cursor.inputParallelismLimit(first));
        assertTrue(cursor.current());
        var excluded = frozen.beginMatch(inventory, 20, Set.of(id("original_group")));
        assertTrue(excluded.advance(new RecipeWorkBudget(100)));
        assertTrue(Objects.requireNonNull(excluded.result()).isEmpty());
        assertEquals(1, original.recipe().orderedRequirements().size());
    }

    @Test
    void generalAndCompositePoolsUseOrdinaryTransactionalAuxiliaryMaterials() {
        var first = entry("general_first").recipe();
        var second = entry("general_second").recipe();
        var general = new DefaultRecipePool(id("general"), "general.title", List.of(first));
        var composite = new CompositeRecipePool(id("composite"), "composite.title", List.of(general,
            new DefaultRecipePool(id("other"), "other.title", List.of(second))));
        for (var base : List.of(general, composite)) {
            var pool = base.withAuxiliaryInputs(List.of(new GenericStack(AUXILIARY, 1)));
            var frozen = pool.freeze(pool.recipes(), List.of());
            var capacity = new Reference2LongArrayMap<AEKeyType>();
            capacity.put(TYPE, 100);
            var input = new BufferedResourceHatch(HatchMode.INPUT, 2, capacity, () -> {
            });
            var output = new BufferedResourceHatch(HatchMode.OUTPUT, 2, capacity, () -> {
            });
            var stock = Objects.requireNonNull(input.input());
            var products = Objects.requireNonNull(output.output());
            stock.insert(INPUT, 2, Actionable.MODULATE);
            var machine = new MachineInstance();
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(), List.of()));
            var process = new RecipeProcess(machine, frozen.recipes().getFirst(), 7);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            assertEquals(2, stock.availableAmount(INPUT));
            stock.insert(AUXILIARY, 1, Actionable.MODULATE);
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(0, stock.availableAmount(INPUT));
            assertEquals(0, stock.availableAmount(AUXILIARY));
            assertEquals(1, products.availableAmount(OUTPUT));
        }
    }

    @Test
    void configurationAndMetadataDoNotReadLazySourcesBeforeTheFirstRecipeAccess() {
        int[] reads = {0};
        var recipe = entry("lazy").recipe();
        RecipePool lazy = new RecipePool() {
            @Override
            public ResourceLocation id() {
                return ConfiguredRecipePoolTest.id("lazy_pool");
            }

            @Override
            public String translationKey() {
                return "lazy.title";
            }

            @Override
            public List<MachineRecipe> recipes() {
                reads[0]++;
                return List.of(recipe);
            }

            @Override
            public List<MachineType> machines() {
                return List.of();
            }

            @Override
            public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
                return new DefaultRecipePool(id(), translationKey(), recipes).freeze(recipes, machines);
            }
        };
        var configured = lazy.withAuxiliaryInputs(List.of(new GenericStack(AUXILIARY, 1)))
                             .withEvent(id("observe"), RecipeStage.END, (_, _) -> RequirementTransaction.SUCCESS)
                             .withExistingJeiPage("external:lazy");
        assertEquals(0, reads[0]);
        assertEquals(lazy.id(), configured.id());
        assertEquals("lazy.title", configured.translationKey());
        configured.machines();
        assertEquals(0, reads[0]);
        var definitions = configured.recipes();
        assertEquals(1, reads[0]);
        assertSame(definitions, configured.recipes());
        assertEquals(1, reads[0]);
    }

    @Test
    void replacingAuxiliaryInputsAfterOtherConfigurationKeepsOneSharedDeclarationAndCanClearIt() {
        for (var source : List.of(new DefaultRecipePool(id("default_replace"), "replace.title", List.of(entry("default_recipe").recipe())),
            new SimpleRecipePool(id("simple_replace"), "replace.title", List.of(entry("simple_recipe"))))) {
            var configured = source.withAuxiliaryInputs(List.of(new GenericStack(AUXILIARY, 1)))
                                   .withEvent(id("replace_event"), RecipeStage.END, (_, _) -> RequirementTransaction.SUCCESS)
                                   .withAuxiliaryInputs(List.of(new GenericStack(AUXILIARY, 5)));
            var definitions = configured.recipes().getFirst().orderedRequirements();
            var inputs = definitions.stream().map(RecipeRequirement::payload).filter(ResourceRequirement.class::isInstance)
                                    .map(ResourceRequirement.class::cast).flatMap(resource -> resource.inputs().stream()).toList();
            assertEquals(List.of(new GenericStack(INPUT, 2), new GenericStack(AUXILIARY, 5)), inputs);
            var cleared = configured.withAuxiliaryInputs(List.of());
            var remaining = cleared.recipes().getFirst().orderedRequirements().stream()
                                   .map(RecipeRequirement::payload).filter(ResourceRequirement.class::isInstance).map(ResourceRequirement.class::cast)
                                   .flatMap(resource -> resource.inputs().stream()).toList();
            assertEquals(List.of(new GenericStack(INPUT, 2)), remaining);
        }
    }

    @Test
    void recipeAndPoolEventsParticipateInStageTransactionsWithoutRunningDuringAdmissionProbes() {
        var calls = new ObjectArrayList<String>();
        int[] balance = {0};
        var recipe = new RecipeBuilder(id("events"), 1)
            .event(id("start"), RecipeStage.START, (_, stage) -> {
                calls.add("acquire:" + stage);
                return transaction(balance, calls);
            }).build();
        var pool = new DefaultRecipePool(id("events_pool"), "events.title", List.of(recipe))
            .withEvent(id("end"), RecipeStage.END, (_, stage) -> {
                calls.add("acquire:" + stage);
                return transaction(balance, calls);
            });
        var process = new RecipeProcess(new MachineInstance(), pool.recipes().getFirst(), 9);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertTrue(calls.isEmpty());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(2, balance[0]);
        assertEquals(List.of("acquire:START", "commit", "complete", "acquire:END", "commit", "complete"), calls);

        var later = new RecipeRequirementType<>(new ResourceLocation("zz_common_test", "failure"), new RecipeRequirementSystem<String>() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass")
            public RequirementTransaction start(RecipeProcess owner, RecipeRequirement<String> requirement) {
                return new RequirementTransaction() {
                    @Override
                    public ProcessResult result() {
                        return new ProcessResult.Blocked("unavailable");
                    }

                    @Override
                    public void commit() {
                        throw new AssertionError("Blocked stage committed");
                    }

                    @Override
                    public void rollback() {
                        calls.add("blocked_rollback");
                    }
                };
            }
        });
        var blocked = RecipeBuilder.from(recipe).addRequirement(later.requirement(id("block"), "blocked")).build();
        calls.clear();
        var attempt = new RecipeProcess(new MachineInstance(), blocked, 10);
        assertInstanceOf(ProcessResult.Blocked.class, attempt.tick());
        assertEquals(2, balance[0]);
        assertEquals(List.of("acquire:START", "blocked_rollback", "rollback"), calls);
    }

    private static RequirementTransaction transaction(int[] balance, List<String> calls) {
        return new RequirementTransaction() {
            private boolean committed;

            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
                balance[0]++;
                committed = true;
                calls.add("commit");
            }

            @Override
            public void complete() {
                calls.add("complete");
            }

            @Override
            public void rollback() {
                if (committed) balance[0]--;
                calls.add("rollback");
            }
        };
    }

    @Test
    void opaqueCommonStartBehaviorCanSupplyTheOriginalInputWithoutBeingRemovedByAnOldInputIndex() {
        var capacity = new Reference2LongArrayMap<AEKeyType>();
        capacity.put(TYPE, 100);
        var input = new BufferedResourceHatch(HatchMode.INPUT, 2, capacity, () -> {
        });
        var output = new BufferedResourceHatch(HatchMode.OUTPUT, 2, capacity, () -> {
        });
        var stock = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        var original = new SimpleRecipePool(id("supply_pool"), "supply.title", List.of(entry("supplied")), _ -> true);
        var pool = original.withEvent(id("supply"), RecipeStage.START, (_, _) -> {
            assertEquals(2, stock.insert(INPUT, 2, Actionable.MODULATE));
            return new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                }

                @Override
                public void rollback() {
                    assertEquals(2, stock.extract(INPUT, 2, Actionable.MODULATE));
                }
            };
        });
        var frozen = pool.freeze(pool.recipes(), List.of());
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(input, output), List.of(), List.of()));
        assertEquals(0, stock.availableAmount(INPUT));
        assertSame(frozen.recipes().getFirst(), frozen.match(new AEKey2LongMap.OpenHashMap()));
        var cursor = frozen.beginMatch(new AEKey2LongMap.OpenHashMap(), 7, Set.of());
        assertTrue(cursor.advance(new RecipeWorkBudget(1)));
        assertEquals(frozen.recipes(), cursor.result());
        assertEquals(7, cursor.inputParallelismLimit(frozen.recipes().getFirst()));
        assertNull(frozen.beginCandidates(machine, Set.of()));
        assertEquals(frozen.recipes(), new RecipeCatalog(List.of(frozen)).candidates(machine, Set.of(), List.of()));
        assertSame(ProcessResult.SUCCESS, new RecipeProcess(machine, frozen.recipes().getFirst(), 3).tick());
        assertEquals(0, stock.availableAmount(INPUT));
        assertEquals(1, products.availableAmount(OUTPUT));
    }

    @Test
    void duplicateAndConflictingCommonRequirementIdentitiesAreRejectedBeforeSourceAccess() {
        var requirement = RecipeRequirements.RESOURCES.requirement(id("duplicate"), new ResourceRequirement(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> RecipeConfiguration.builder().requirements(List.of(requirement, requirement)).build());
        var conflicting = RecipeRequirements.RESOURCES.requirement(id("conflict/auxiliary_inputs"),
            new ResourceRequirement(List.of(), List.of()));
        var pool = new DefaultRecipePool(id("conflict"), "conflict.title", List.of());
        var configuration = RecipeConfiguration.builder().addRequirement(conflicting)
            .auxiliaryInputs(List.of(new GenericStack(AUXILIARY, 1))).build();
        assertThrows(IllegalArgumentException.class, () -> pool.withConfiguration(configuration));
    }
}
