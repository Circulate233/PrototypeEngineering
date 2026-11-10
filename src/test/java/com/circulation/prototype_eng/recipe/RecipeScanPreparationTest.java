package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanPreparation;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanProbes;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeScanPreparationTest {
    private static final ComponentType<RecipeProcess, Counter> COUNTER = new ComponentType<>(id("counter"), _ -> new Counter(),
        List.of(new ComponentSystem<>() {
            @Override
            public ResourceLocation id() {
                return RecipeScanPreparationTest.id("counter_system");
            }

            @Override
            public void onPreTick(RecipeProcess owner, Counter component) {
                component.value++;
                component.actualPreCalls++;
            }
        }));
    private static final KeyType KEY_TYPE = new KeyType("scan_preparation");
    private static final Key MATERIAL = new Key(KEY_TYPE, "material");
    private static final Runnable NO_OP = () -> {
    };

    private static RecipeScanPreparation layer(String name, int amount, ProcessResult result, List<String> events,
                                               RuntimeException cleanupFailure) {
        return process -> {
            var counter = counter(process);
            events.add("prepare:" + name);
            counter.value += amount;
            return new Projection(counter, result, List.of(), () -> {
                events.add("rollback:" + name);
                counter.value -= amount;
                if (cleanupFailure != null) {
                    throw cleanupFailure;
                }
            });
        };
    }

    private static RecipeProcess candidate() {
        var requirement = new RecipeRequirementType<Boolean>(id("initialization_type"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Boolean> definition) {
                var counter = counter(process);
                var result = counter.value == 1 ? ProcessResult.SUCCESS : new ProcessResult.Blocked("component has not initialized");
                return new StartInitialization(counter, result);
            }
        }).requirement(id("initialization"), true);
        var process = new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 3, List.of(requirement)), 17);
        process.components().create(COUNTER, process);
        return process;
    }

    private static Counter counter(RecipeProcess process) {
        return Objects.requireNonNull(process.components().get(COUNTER));
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("scan_preparation_test", name);
    }

    @Test
    void reversibleProjectionMakesStartObservableWithoutDuplicatingActualFirstTickInitialization() {
        var process = candidate();
        var counter = counter(process);
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.probeStart(process));
        assertEquals(0, counter.value);
        assertEquals(0, counter.actualPreCalls);
        var events = new ObjectArrayList<String>();
        assertSame(ProcessResult.SUCCESS, RecipeScanProbes.check(process,
            List.of(layer("projection", 1, ProcessResult.SUCCESS, events, null)), projected -> {
                assertEquals(1, counter(projected).value);
                return RecipeStageExecutor.probeStart(projected);
            }));
        assertEquals(List.of("prepare:projection", "rollback:projection"), events);
        assertEquals(0, counter.value);
        assertEquals(0, counter.actualPreCalls);
        assertEquals(0, counter.startCommits);
        assertEquals(0, counter.startCompletions);
        assertEquals(0, counter.projectionCommits);
        assertEquals(0, counter.projectionCompletions);
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        process.requirePersistenceBoundary();
        var host = new MachineWorkSlots(process.owner(), List.of());
        process.owner().components().put(MachineWorkSlots.TYPE, host);
        assertTrue(host.start(process));
        process.owner().onSchedule();
        assertEquals(1, counter.value);
        assertEquals(1, counter.actualPreCalls);
        assertEquals(1, counter.startCommits);
        assertEquals(1, counter.startCompletions);
        assertEquals(1, process.state().successfulTicks());
        assertSame(host, process.host());
    }

    @Test
    void allCheckResultsUndoPreparationLayersInReverseOrder() {
        for (var result : List.of(ProcessResult.SUCCESS, new ProcessResult.Blocked("waiting"), new ProcessResult.Failure("invalid"))) {
            var process = candidate();
            var events = new ObjectArrayList<String>();
            var initialState = process.state();
            assertSame(result, RecipeScanProbes.check(process, List.of(
                layer("first", 2, ProcessResult.SUCCESS, events, null),
                layer("second", 3, ProcessResult.SUCCESS, events, null)), projected -> {
                events.add("check");
                assertEquals(5, counter(projected).value);
                return result;
            }));
            assertEquals(List.of("prepare:first", "prepare:second", "check", "rollback:second", "rollback:first"), events);
            assertEquals(0, counter(process).value);
            assertEquals(initialState, process.state());
            assertNull(process.host());
            process.requirePersistenceBoundary();
            assertEquals(0, counter(process).projectionCommits);
            assertEquals(0, counter(process).projectionCompletions);
        }
    }

    @Test
    void rejectedPreparationStopsLaterLayersAndTheCheckButStillUndoesItsOwnAcquisition() {
        for (var result : List.of(new ProcessResult.Blocked("preparation waiting"), new ProcessResult.Failure("preparation invalid"))) {
            var process = candidate();
            var events = new ObjectArrayList<String>();
            assertSame(result, RecipeScanProbes.check(process, List.of(
                layer("first", 2, ProcessResult.SUCCESS, events, null),
                layer("rejecting", 3, result, events, null),
                layer("after", 7, ProcessResult.SUCCESS, events, null)), _ -> {
                throw new AssertionError("A rejected preparation must skip the check");
            }));
            assertEquals(List.of("prepare:first", "prepare:rejecting", "rollback:rejecting", "rollback:first"), events);
            assertEquals(0, counter(process).value);
            assertEquals(0, counter(process).startCommits);
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void preparationAndCheckFaultsAggregateEveryRollbackFaultAndRestoreTheOtherLayers() {
        for (boolean throwInPreparation : List.of(true, false)) {
            var process = candidate();
            var events = new ObjectArrayList<String>();
            var primary = new IllegalStateException("acquisition or check failed");
            var firstCleanup = new IllegalStateException("first cleanup failed");
            var secondCleanup = new IllegalStateException("second cleanup failed");
            var preparations = new ObjectArrayList<RecipeScanPreparation>();
            preparations.add(layer("first", 2, ProcessResult.SUCCESS, events, firstCleanup));
            preparations.add(layer("second", 3, ProcessResult.SUCCESS, events, secondCleanup));
            if (throwInPreparation) {
                preparations.add(_ -> {
                    events.add("prepare:throwing");
                    throw primary;
                });
            }
            assertSame(primary, assertThrows(IllegalStateException.class, () -> RecipeScanProbes.check(process, preparations, _ -> {
                events.add("check");
                throw primary;
            })));
            assertEquals(List.of(secondCleanup, firstCleanup), List.of(primary.getSuppressed()));
            assertEquals(List.of("prepare:first", "prepare:second", throwInPreparation ? "prepare:throwing" : "check",
                "rollback:second", "rollback:first"), events);
            assertEquals(0, counter(process).value);
            assertEquals(0, counter(process).projectionCommits);
            assertEquals(0, counter(process).projectionCompletions);
            assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
            var previousEvents = List.copyOf(events);
            assertThrows(IllegalStateException.class, () -> RecipeScanProbes.check(process, preparations, _ -> ProcessResult.SUCCESS));
            assertEquals(previousEvents, events);
        }
    }

    @Test
    void rollbackFailureOnAnOtherwiseSuccessfulCheckStillCleansEarlierLayersAndPoisonsTheCandidate() {
        var process = candidate();
        var events = new ObjectArrayList<String>();
        var first = new IllegalStateException("first cleanup failed");
        var second = new IllegalStateException("second cleanup failed");
        assertSame(second, assertThrows(IllegalStateException.class, () -> RecipeScanProbes.check(process, List.of(
            layer("first", 2, ProcessResult.SUCCESS, events, first),
            layer("second", 3, ProcessResult.SUCCESS, events, second)), _ -> ProcessResult.SUCCESS)));
        assertEquals(List.of(first), List.of(second.getSuppressed()));
        assertEquals(List.of("prepare:first", "prepare:second", "rollback:second", "rollback:first"), events);
        assertEquals(0, counter(process).value);
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test
    void brokenPreparationWithRealReservationsReleasesAllTokensBeforeItsContractFaultEscapes() {
        var process = candidate();
        var events = new ObjectArrayList<String>();
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(KEY_TYPE, 100);
        var input = new SlottedResourceBuffer(1, capacities, NO_OP);
        var energy = new EnergyBuffer(100, NO_OP);
        input.insert(MATERIAL, 10, Actionable.MODULATE);
        energy.receiveEnergy(20, Actionable.MODULATE);
        var cleanupFailure = new IllegalStateException("broken preparation cleanup failed after undo");
        RecipeScanPreparation broken = owner -> {
            events.add("prepare:broken");
            var material = input.reserveExtract(input.snapshot(), MATERIAL, 4);
            var power = energy.reserveExtractEnergy(energy.energySnapshot(), 3);
            assertEquals(ReservationResult.Status.ACCEPTED, material.status());
            assertEquals(ReservationResult.Status.ACCEPTED, power.status());
            counter(owner).value += 7;
            return new Projection(counter(owner), ProcessResult.SUCCESS,
                List.of(Objects.requireNonNull(material.reservation()), Objects.requireNonNull(power.reservation())), () -> {
                events.add("rollback:broken");
                counter(owner).value -= 7;
                throw cleanupFailure;
            });
        };
        boolean[] checked = {false};
        var failure = assertThrows(IllegalArgumentException.class, () -> RecipeScanProbes.check(process,
            List.of(layer("first", 2, ProcessResult.SUCCESS, events, null), broken), _ -> {
                checked[0] = true;
                return ProcessResult.SUCCESS;
            }));
        assertEquals(List.of(cleanupFailure), List.of(failure.getSuppressed()));
        assertFalse(checked[0]);
        assertEquals(List.of("prepare:first", "prepare:broken", "rollback:broken", "rollback:first"), events);
        assertEquals(0, counter(process).value);
        assertEquals(10, input.amount(MATERIAL));
        assertEquals(10, input.availableAmount(MATERIAL));
        assertEquals(20, energy.storedEnergy());
        assertEquals(20, energy.energySnapshot().availableEnergy());
        assertEquals(0, counter(process).projectionCommits);
        assertEquals(0, counter(process).projectionCompletions);
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test
    void preparationRejectsHostedOrRunningCandidatesBeforeCallingAnyExtension() {
        var process = candidate();
        var events = new ObjectArrayList<String>();
        var preparation = layer("projection", 1, ProcessResult.SUCCESS, events, null);
        var host = new MachineWorkSlots(process.owner(), List.of());
        assertTrue(host.start(process));
        assertThrows(IllegalArgumentException.class, () -> RecipeScanProbes.check(process, List.of(preparation), _ -> ProcessResult.SUCCESS));
        assertTrue(events.isEmpty());
        assertTrue(host.detach(process));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertThrows(IllegalArgumentException.class, () -> RecipeScanProbes.check(process, List.of(preparation), _ -> ProcessResult.SUCCESS));
        assertTrue(events.isEmpty());
        assertEquals(1, counter(process).value);
        assertEquals(1, counter(process).actualPreCalls);
        assertEquals(1, counter(process).startCommits);
    }

    private record StartInitialization(Counter counter, ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
            counter.startCommits++;
        }

        @Override
        public void complete() {
            counter.startCompletions++;
        }

        @Override
        public void rollback() {
        }
    }

    private record Projection(Counter counter, ProcessResult result, List<ResourceReservation> reservations,
                              Runnable cleanup) implements RequirementTransaction {
        @Override
        public void commit() {
            counter.projectionCommits++;
        }

        @Override
        public void complete() {
            counter.projectionCompletions++;
        }

        @Override
        public void rollback() {
            cleanup.run();
        }
    }

    private static final class Counter {
        private int value;
        private int actualPreCalls;
        private int startCommits;
        private int startCompletions;
        private int projectionCommits;
        private int projectionCompletions;
    }
}
