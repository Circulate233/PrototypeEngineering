package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeDefinitionIndex;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.FixedIoScanConstraint;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MachineRecipeRequirementsTest {
    private static final ComponentType<MachineInstance, MachineRecipeRequirements> PROVIDER =
        new ComponentType<>(id("provider"), _ -> _ -> List.of(), List.of());
    private static final ResourceLocation SCORE = id("score");

    private static void assertProbeScope(RecipeProcess process, RequirementPath path, RecipeProcess.Tick tick) {
        assertTrue(process.probing());
        assertEquals(path, process.requirementPath());
        assertSame(tick, process.tickContext());
    }

    private static MachineInstance owner(List<RecipeRequirement<?>> roots) {
        var owner = new MachineInstance();
        owner.components().put(PROVIDER, _ -> roots);
        return owner;
    }

    private static EnergyBuffer energy(long amount) {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.receiveEnergy(amount, Actionable.MODULATE);
        return energy;
    }

    private static RecipeRequirement<String> observer(String name,
                                                      BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action, List<String> events) {
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return action.apply(process, RecipeStage.END);
            }

            @Override
            public void cancel(RecipeProcess process, RecipeRequirement<String> requirement) {
                events.add("cancel:" + name);
            }
        };
        return new RecipeRequirementType<>(id(name), system).requirement(id(name), "value");
    }

    private static ResourceLocation id(String value) {
        return new ResourceLocation("machine_requirement_test", value);
    }

    @Test
    void providedNestedRequirementsExecuteAndAbortThroughTheirActualFrozenPaths() {
        var events = new ObjectArrayList<String>();
        var leaf = observer("leaf", (current, phase) -> {
            assertFalse(current.probing());
            var path = current.requirementPath();
            assertNotNull(path);
            var parent = path.parent();
            assertNotNull(parent);
            assertNotNull(parent.parent());
            return new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    assertEquals(path, current.requirementPath());
                    events.add(phase.name());
                }

                @Override
                public void rollback() {
                }
            };
        }, events);
        var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(leaf)));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(1, selection));
        var recipe = new MachineRecipe(id("recipe"), 3, List.of());
        var owner = owner(List.of(checkpoint));
        var process = new RecipeProcess(owner, recipe, 17);
        assertSame(recipe, process.recipe());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of("START", "TICK", "END"), events);
        assertTrue(process.cancel());
        assertEquals(List.of("START", "TICK", "END"), events, "A completed checkpoint selection has no active child to cancel");
        process.requirePersistenceBoundary();
        events.clear();
        var activeRoot = RecipeRequirements.SELECTIVE.requirement(id("active_root"), new SelectiveRequirement(List.of(selection)));
        var active = new RecipeProcess(owner(List.of(activeRoot)), recipe, 18);
        assertSame(ProcessResult.SUCCESS, active.tick());
        assertEquals(List.of("START", "TICK"), events);
        assertTrue(active.cancel());
        assertEquals(List.of("START", "TICK", "cancel:leaf"), events);
        active.requirePersistenceBoundary();
    }

    @Test
    void codecRestoresProvidedSelectionAndItsExactOverlayTargetsWithoutRerunningStart() {
        var energy = energy(100);
        var leaf = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(0, 1, 0, 0, false));
        int[] modifierCalls = {0};
        var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(leaf),
            List.of(new SelectiveModifierDescriptor(id("modifier"), context -> {
                modifierCalls[0]++;
                context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 3, AttributeModifier.Operation.ADDITION));
                context.putRequirementOverlay(leaf, leaf.path(context.selectionPath()), "rate", RecipeOverlayTypes.ENERGY_RATES,
                    new EnergyRequirementPatch(2L, null, null));
            }))));
        var recipe = new MachineRecipe(id("recipe"), 2, List.of());
        var owner = owner(List.of(selection));
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy), List.of()));
        var original = new RecipeProcess(owner, recipe, 17);
        assertSame(ProcessResult.SUCCESS, original.tick());
        assertEquals(98, energy.savedEnergy());
        assertEquals(4, original.attributes().value(SCORE));
        var codec = RecipeProcessNbt.builder(recipe, "provider-v1").build();
        var saved = codec.encode(original);
        var restored = codec.decode(owner, saved);
        assertEquals(original.state(), restored.state());
        assertEquals(1, modifierCalls[0]);
        assertSame(leaf, new RecipeDefinitionIndex(restored).require(leaf.path(selection.path(null))));
        assertEquals(RecipeOverlays.getOrCreate(original).snapshot(), RecipeOverlays.getOrCreate(restored).snapshot());
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, restored.state().lifecycle());
        assertEquals(96, energy.savedEnergy());
        assertEquals(1, restored.attributes().value(SCORE));
        assertTrue(RecipeOverlays.getOrCreate(restored).snapshot().isEmpty());
        restored.requirePersistenceBoundary();
    }

    @Test
    void savedGraphRejectsDeletedOrChangedProviderRootsAndMissingNestedOwnership() {
        var leaf = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(0, 0, 0, 0, false));
        var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(leaf)));
        var recipe = new MachineRecipe(id("recipe"), 2, List.of());
        var owner = owner(List.of(selection));
        var original = new RecipeProcess(owner, recipe, 17);
        assertSame(ProcessResult.SUCCESS, original.tick());
        var codec = RecipeProcessNbt.builder(recipe, "provider-v1").build();
        var saved = codec.encode(original);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new MachineInstance(), saved));
        var renamed = RecipeRequirements.SELECTIVE.requirement(id("renamed"), new SelectiveRequirement(List.of(leaf)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(owner(List.of(renamed)), saved));
        var missingState = saved.copy();
        missingState.getTagList("Components", 10).removeTag(0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(owner, missingState));
        assertFalse(SelectiveState.getOrCreate(original).snapshot().isEmpty());
        original.requirePersistenceBoundary();
    }

    @Test
    void fixedIoAdmissionIncludesProvidedTickCosts() {
        var requirement = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(0, 10, 0, 0, false));
        var owner = owner(List.of(requirement));
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(), List.of(energy(5)), List.of()));
        var process = new RecipeProcess(owner, new MachineRecipe(id("recipe"), 2, List.of()), 17);
        assertInstanceOf(ProcessResult.Blocked.class, new FixedIoScanConstraint().test(process));
        process.requirePersistenceBoundary();
    }

    @Test
    void captureRejectsConflictingRootsAndFreezesProviderDeclarationLists() {
        var base = observer("same", (_, _) -> RequirementTransaction.SUCCESS, new ObjectArrayList<>());
        var recipe = new MachineRecipe(id("recipe"), 2, List.of(base));
        assertThrows(IllegalArgumentException.class, () -> new RecipeProcess(owner(List.of(base)), recipe, 17));
        var aliasedType = new RecipeRequirementType<>(base.type().id(), new RecipeRequirementSystem<String>() {
        });
        var aliased = aliasedType.requirement(id("other"), "value");
        assertThrows(IllegalArgumentException.class, () -> new RecipeProcess(owner(List.of(aliased)), recipe, 17));
        assertThrows(IllegalArgumentException.class, () -> new RecipeProcess(owner(List.of(base.withPayload("changed"))),
            new MachineRecipe(id("recipe"), 2, List.of()), 17));
        var added = observer("added", (_, _) -> RequirementTransaction.SUCCESS, new ObjectArrayList<>());
        var declarations = new ObjectArrayList<RecipeRequirement<?>>();
        declarations.add(added);
        var owner = owner(declarations);
        var process = new RecipeProcess(owner, recipe, 17);
        declarations.clear();
        assertTrue(process.orderedRequirements().contains(added));
        assertSame(recipe.orderedRequirements(), new RecipeProcess(new MachineInstance(), recipe, 17).orderedRequirements());
    }

    @Test
    void explicitProbeScopeCoversAcquisitionAndCleanupWithoutMistakingOrdinaryNewProcesses() {
        var events = new ObjectArrayList<String>();
        var requirement = observer("probe", (current, phase) -> {
            events.add((current.probing() ? "probe:" : "work:") + phase);
            if (current.probing()) {
                assertThrows(IllegalStateException.class, () -> current.withProbeContext(() -> null));
                assertThrows(IllegalStateException.class, current::cancel);
            }
            return new RequirementTransaction() {
                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    assertFalse(current.probing());
                }

                @Override
                public void rollback() {
                    assertTrue(current.probing());
                    events.add("cleanup:" + phase);
                }
            };
        }, events);
        var process = new RecipeProcess(owner(List.of(requirement)), new MachineRecipe(id("recipe"), 0, List.of()), 17);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStage(process, RecipeStage.TICK));
        assertFalse(process.probing());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of("probe:START", "cleanup:START", "probe:TICK", "cleanup:TICK", "work:START", "work:END"), events);
        process.requirePersistenceBoundary();
    }

    @Test
    void deferredProbeCleanupAndQueriesRestoreTheirOriginalFlagPathAndTick() {
        var process = new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 2, List.of()), 17);
        var tick = new RecipeProcess.Tick(0, 1, 0, 1);
        boolean[] recoverable = {false};
        var requirement = observer("probe", (current, _) -> {
            var path = current.requirementPath();
            assertTrue(current.probing());
            return new RequirementTransaction() {
                private Resolution resolution = Resolution.UNKNOWN;

                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    fail("Probes never commit business");
                }

                @Override
                public void rollback() {
                    assertProbeScope(current, path, tick);
                    if (!recoverable[0]) {
                        throw new IllegalStateException("uncertain probe cleanup");
                    }
                    resolution = Resolution.ROLLED_BACK;
                }

                @Override
                public Resolution resolution() {
                    assertProbeScope(current, path, tick);
                    return resolution == Resolution.ROLLED_BACK ? resolution : recoverable[0] ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
                }
            };
        }, new ObjectArrayList<>());
        var captured = process.withProbeContext(() -> process.withTickContext(tick, () -> requirement.acquire(process, RecipeStage.TICK)));
        assertFalse(process.probing());
        assertThrows(IllegalStateException.class, captured::rollback);
        assertEquals(RequirementTransaction.Resolution.UNKNOWN, captured.resolution());
        assertFalse(process.probing());
        recoverable[0] = true;
        captured.rollback();
        assertTrue(process.quiescent());
        assertFalse(process.probing());
        assertNull(process.tickContext());
    }
}
