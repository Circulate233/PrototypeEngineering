package com.circulation.prototype_eng.api.research.station;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementPreparation;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.recipe.PreparedRequirement;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.circulation.prototype_eng.api.research.station.StationFixtures.Entry;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.OWNER;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.TYPE;
import static com.circulation.prototype_eng.api.research.station.StationFixtures.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchStationWorkTest {
    private static final TestResources.Key RAW = new TestResources.Key(TYPE, "raw");
    private static final TestResources.Key PRODUCT = new TestResources.Key(TYPE, "product");

    private static ResearchDefinition definition(long demand, RecipeRequirement<?>... requirements) {
        var formula = ResearchResourceFormula.builder();
        if (demand > 0) {
            formula.constant(NetworkResources.COMPUTE, demand);
        }
        var stage = ResearchLevelStage.builder(1, 1).researchTier(2).resourceFormula(formula.build());
        for (var requirement : requirements) {
            stage.materialRequirement(requirement);
        }
        return ResearchDefinition.builder(id("research")).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM).stage(stage.build()).build();
    }

    private static RecipeRequirement<?> io(long start, long tick, long output) {
        return RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(List.of(new GenericStack(RAW, start)), List.of(new GenericStack(RAW, tick)),
            List.of(), List.of(new GenericStack(PRODUCT, output)), false));
    }

    private static RecipeRequirement<?> energy() {
        return RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(3, 2, 0, 1, false));
    }

    private static RecipeRequirement<?> observe(Function<RecipeStage, RequirementTransaction> action) {
        return new RecipeRequirementType<>(id("observer"), new RecipeRequirementSystem<Function<RecipeStage, RequirementTransaction>>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                return requirement.payload().apply(RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                return requirement.payload().apply(RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Function<RecipeStage, RequirementTransaction>> requirement) {
                return requirement.payload().apply(RecipeStage.END);
            }
        }).requirement(id("observation"), action);
    }

    private static ResearchStationWork.Context context(long time) {
        return new ResearchStationWork.Context(time, Set.of(OWNER), () -> true);
    }

    private static PlannedDeadline deadline() {
        return PlannedDeadline.after(Duration.ofSeconds(5));
    }

    private static void put(Entry station, long amount) {
        assertEquals(amount, Objects.requireNonNull(station.warehouse().input()).insert(RAW, amount, Actionable.MODULATE));
    }

    private static long input(Entry station) {
        return Objects.requireNonNull(station.warehouse().input()).amount(RAW);
    }

    private static long output(Entry station) {
        return Objects.requireNonNull(station.warehouse().output()).amount(PRODUCT);
    }

    @Test
    void realStationWorkContinuesMoreThanOneBudgetOfEmptyMaterialRootsUnderItsOriginalCapture() {
        var phases = new ObjectArrayList<RecipeStage>();
        var roots = new ObjectArrayList<RecipeRequirement<?>>();
        for (int index = 0; index < 16_500; index++) {
            roots.add(RecipeRequirements.RESOURCES.requirement(id("empty_" + index), new ResourceRequirement(List.of(), List.of())));
        }
        roots.add(observe(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }));
        var definition = definition(1, roots.toArray(RecipeRequirement<?>[]::new));
        try (var fixture = new StationFixtures(1, 100, 2, definition); var lanes = new Lanes()) {
            fixture.station(2, 0);
            fixture.refresh();
            var state = new State(fixture);
            var result = state.work.advance(Objects.requireNonNull(fixture.selector.capture("team", 2)), context(1), lanes.tasks, deadline());
            assertEquals(ResearchStationWork.Status.COMPLETED, result.status());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
            assertEquals(1, state.player.completedLevel(id("research")));
            assertEquals(0, state.queue.size());
        }
    }

    @Test
    void realStationWorkReauthorizesLargePureEndSlicesWithoutRepeatingMaterialTickOrGrant() {
        var phases = new ObjectArrayList<RecipeStage>();
        var pool = new ObjectArrayList<WeightedSampling.Weighted<GenericStack>>();
        for (int index = 0; index < 1000; index++) {
            pool.add(new WeightedSampling.Weighted<>(new GenericStack(PRODUCT, 1), index + 1));
        }
        var current = new AtomicBoolean(true);
        var firstSlice = new AtomicBoolean(true);
        var builtin = RecipeRequirements.RESOURCES.system();
        var controlled = new RecipeRequirementType<>(id("controlled_random"), new RecipeRequirementSystem<ResourceRequirement>() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass")
            // Revokes a real research capture only after its first accepted pure slice.
            public RequirementPreparation prepare(RecipeProcess process, RecipeRequirement<ResourceRequirement> requirement, RecipeStage stage) {
                var binding = builtin.prepare(process, requirement, stage);
                if (binding == null || !firstSlice.get()) {
                    return binding;
                }
                return new RequirementPreparation() {
                    private RecipePureComputation original;
                    private RecipePureComputation wrapped;

                    @Override
                    public RecipePureComputation computation() {
                        var currentComputation = binding.computation();
                        if (currentComputation == null) {
                            return null;
                        }
                        if (currentComputation != original) {
                            original = currentComputation;
                            wrapped = budget -> {
                                boolean ready = currentComputation.advance(budget);
                                if (!ready && firstSlice.compareAndSet(true, false)) {
                                    current.set(false);
                                }
                                return ready;
                            };
                        }
                        return wrapped;
                    }

                    @Override
                    public boolean ownerWorkMetered() {
                        return binding.ownerWorkMetered();
                    }

                    @Override
                    public boolean captureOnlyPreparation() {
                        return binding.captureOnlyPreparation();
                    }

                    @Override
                    public RequirementTransaction acquire() {
                        var transaction = binding.acquire();
                        if (transaction instanceof PreparedRequirement) {
                            // Keep the real worker gate attached through native owner capture pauses.
                            return new PreparedRequirement(this) {
                                @Override
                                public List<? extends ResourceReservation> reservations() {
                                    return transaction.reservations();
                                }

                                @Override
                                public void complete() {
                                    super.complete();
                                    transaction.complete();
                                }

                                @Override
                                public void rollback() {
                                    super.rollback();
                                    transaction.rollback();
                                }
                            };
                        }
                        return transaction;
                    }

                    @Override
                    public void retainPureFacts() {
                        binding.retainPureFacts();
                    }
                };
            }
        });
        var random = controlled.requirement(id("random"), new ResourceRequirement(List.of(), List.of(), List.of(), List.of(),
            false, new ResourceChance(12_800, null), new RandomOutputPool(pool, 3)));
        var definition = definition(1, io(1, 1, 1), random, observe(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }));
        try (var fixture = new StationFixtures(1, 100, 2, definition); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            var warehouse = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(TYPE, 10_000L), () -> {
            });
            Objects.requireNonNull(warehouse.input()).insert(RAW, 3, Actionable.MODULATE);
            Objects.requireNonNull(station.machine().lifecycle()).form(new MachineHatches(List.of(warehouse), List.of(station.energy()), List.of(station.energy())));
            fixture.refresh();
            var state = new State(fixture);
            long time = 1;
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var result = state.work.advance(capture, new ResearchStationWork.Context(time, Set.of(OWNER), current::get), lanes.tasks, deadline());
            assertEquals(ResearchStationWork.Status.PENDING, result.status());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK), phases);
            assertEquals(0, state.player.completedLevel(id("research")));
            while (result.status() != ResearchStationWork.Status.COMPLETED) {
                assertTrue(time < 100, "New station captures must continue the pure cursor under fresh authority");
                state.work.requestStop(PlannedCancellationToken.Reason.EPOCH_CHANGED);
                state.work.awaitExit();
                state.work.resolve(deadline());
                fixture.nextTick();
                current.set(true);
                time++;
                capture = Objects.requireNonNull(fixture.selector.renew(Objects.requireNonNull(state.work.sources())));
                result = state.work.advance(capture, new ResearchStationWork.Context(time, Set.of(OWNER), current::get), lanes.tasks, deadline());
            }
            assertTrue(time > 1);
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
            assertEquals(1, Objects.requireNonNull(warehouse.input()).amount(RAW));
            assertEquals(385, Objects.requireNonNull(warehouse.output()).amount(PRODUCT));
            assertEquals(1, state.player.completedLevel(id("research")));
            assertEquals(0, state.queue.size());
        }
    }

    @Test
    void differentStationRatesPayTheirOwnPowerOnlyWhenProgressCommits() {
        try (var fixture = new StationFixtures(2, 100, 2, definition(10, io(2, 1, 1))); var lanes = new Lanes()) {
            var first = fixture.station(2, 2, 3);
            var second = fixture.station(3, 5, 4);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(10, first.energy().storedEnergy());
            assertEquals(10, second.energy().storedEnergy());
            put(first, 6);
            assertEquals(ResearchStationWork.Status.ADVANCED, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(7, state.queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
            assertEquals(7, first.energy().storedEnergy());
            assertEquals(6, second.energy().storedEnergy());
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(7, first.energy().storedEnergy());
            fixture.nextTick();
            capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(capture, context(2), lanes.tasks, deadline()).status());
            assertEquals(4, first.energy().storedEnergy());
            assertEquals(2, second.energy().storedEnergy());
            assertEquals(2, output(first) + output(second));
        }
    }

    @Test
    void unpoweredStationDoesNotContributeSpeedOrBlockThePoweredStation() {
        try (var fixture = new StationFixtures(2, 100, 2, definition(10)); var lanes = new Lanes()) {
            var first = fixture.station(2, 2, 3);
            var second = fixture.station(3, 5, 20);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(1, capture.stationIds().size());
            assertEquals(ResearchStationWork.Status.ADVANCED, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(2, state.queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
            assertEquals(7, first.energy().storedEnergy());
            assertEquals(10, second.energy().storedEnergy());
        }
    }

    @Test
    void stationsShareOneMaterialCyclePerSegmentAndLeaveOrdinaryNumericBudgetUntouched() {
        var phases = new ObjectArrayList<RecipeStage>();
        var definition = definition(10, io(3, 3, 2), energy(), observe(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }));
        try (var fixture = new StationFixtures(2, 100, 2, definition); var lanes = new Lanes()) {
            var first = fixture.station(2, 0);
            var second = fixture.station(2, 0);
            put(first, 5);
            put(second, 7);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.ADVANCED, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(6, state.queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
            assertEquals(6, input(first) + input(second));
            assertEquals(2, output(first) + output(second));
            assertEquals(16, first.energy().storedEnergy() + second.energy().storedEnergy());
            assertEquals(100, Objects.requireNonNull(fixture.roleOf(first.machine()).workPermit()).availableResources().getLong(NetworkResources.COMPUTE));
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(3, phases.size());
            fixture.nextTick();
            capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            var result = state.work.advance(capture, context(2), lanes.tasks, deadline());
            assertEquals(ResearchStationWork.Status.COMPLETED, result.status());
            assertEquals(4, Objects.requireNonNull(result.grant()).grantedResources().getLong(NetworkResources.COMPUTE));
            assertEquals(0, input(first) + input(second));
            assertEquals(4, output(first) + output(second));
            assertEquals(12, first.energy().storedEnergy() + second.energy().storedEnergy());
            assertEquals(1, state.player.completedLevel(id("research")));
            assertEquals(0, state.queue.size());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END, RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
        }
    }

    @Test
    void noNumericProgressDoesNotRunMaterialsButAnEmptyNumericDemandStillCompletesItsCycle() {
        int[] calls = {0};
        var observer = observe(_ -> {
            calls[0]++;
            return RequirementTransaction.SUCCESS;
        });
        try (var fixture = new StationFixtures(1, 0, 2, definition(10, io(2, 1, 1), observer)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, 10);
            fixture.refresh();
            var state = new State(fixture);
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(Objects.requireNonNull(fixture.selector.capture("team", 2)), context(1), lanes.tasks, deadline()).status());
            assertEquals(0, calls[0]);
            assertEquals(10, input(station));
            assertNull(state.work.sources());
        }
        try (var fixture = new StationFixtures(1, 0, 2, definition(0, io(2, 1, 1), observer)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, 10);
            fixture.refresh();
            var state = new State(fixture);
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(Objects.requireNonNull(fixture.selector.capture("team", 2)), context(1), lanes.tasks, deadline()).status());
            assertEquals(3, calls[0]);
            assertEquals(7, input(station));
            assertEquals(1, output(station));
        }
    }

    @Test
    void blockedEndRetainsPaidStartAndTickAtZeroSpeedAndRenewsTheSameSources() {
        var phases = new ObjectArrayList<RecipeStage>();
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, io(2, 1, 2), observe(phase -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        }))); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, 10);
            station.machine().attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0);
            Objects.requireNonNull(station.warehouse().output()).insert(PRODUCT, 100, Actionable.MODULATE);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(7, input(station));
            assertEquals(0, state.queue.resourceProgressSnapshot().getLong(NetworkResources.COMPUTE));
            assertEquals(1, Objects.requireNonNull(state.work.materialState()).successfulTicks());
            assertEquals(0, Objects.requireNonNull(state.work.materialState()).progress());
            Objects.requireNonNull(station.warehouse().output()).extract(PRODUCT, 2, Actionable.MODULATE);
            fixture.nextTick();
            capture = Objects.requireNonNull(fixture.selector.renew(Objects.requireNonNull(state.work.sources())));
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(capture, context(2), lanes.tasks, deadline()).status());
            assertEquals(7, input(station));
            assertEquals(100, output(station));
            assertEquals(1, phases.stream().filter(phase -> phase == RecipeStage.START).count());
            assertEquals(1, phases.stream().filter(phase -> phase == RecipeStage.TICK).count());
        }
    }

    @Test
    void busyCompletionWaitsAtEndAndDoesNotBuyAnotherMaterialCycle() {
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, io(2, 1, 2))); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, 10);
            fixture.refresh();
            var state = new State(fixture);
            var busy = state.player.prepareCompletion(id("research"), true, 1, IntLists.emptyList(), 0);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture, context(1), lanes.tasks, deadline()).status());
            assertEquals(7, input(station));
            assertEquals(0, output(station));
            assertFalse(state.queue.busy());
            busy.rollback();
            fixture.nextTick();
            capture = Objects.requireNonNull(fixture.selector.renew(Objects.requireNonNull(state.work.sources())));
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(capture, context(2), lanes.tasks, deadline()).status());
            assertEquals(7, input(station));
            assertEquals(2, output(station));
            assertEquals(1, state.player.completedLevel(id("research")));
        }
    }

    @Test
    void lateAuthorityLossReversesOnlyEndAndItsGrantThenRetriesAgainstTheNewQueueEpoch() {
        boolean[] current = {true};
        boolean[] reject = {true};
        var observer = observe(phase -> phase == RecipeStage.END ? new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
                if (reject[0]) {
                    current[0] = false;
                }
            }

            @Override
            public void rollback() {
            }
        } : RequirementTransaction.SUCCESS);
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, io(2, 1, 2), observer)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, 10);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertEquals(ResearchStationWork.Status.WAITING, state.work.advance(capture,
                new ResearchStationWork.Context(1, Set.of(OWNER), () -> current[0]), lanes.tasks, deadline()).status());
            assertEquals(7, input(station));
            assertEquals(0, output(station));
            assertEquals(0, state.player.completedLevel(id("research")));
            assertFalse(state.queue.busy());
            assertEquals(1, state.queue.size());
            assertTrue(state.queue.resourceProgressSnapshot().isEmpty());
            current[0] = true;
            reject[0] = false;
            fixture.nextTick();
            capture = Objects.requireNonNull(fixture.selector.renew(Objects.requireNonNull(state.work.sources())));
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.advance(capture, context(2), lanes.tasks, deadline()).status());
            assertEquals(7, input(station));
            assertEquals(2, output(station));
        }
    }

    @Test
    void unknownEndCompletionRetainsItsPaidGrantUntilTheOriginalParticipantConfirms() {
        var failure = new IllegalStateException("Unconfirmed external END completion");
        int[] completions = {0};
        boolean[] confirmed = {false};
        var observer = observe(phase -> phase == RecipeStage.END ? new RequirementTransaction() {
            @Override
            public ProcessResult result() {
                return ProcessResult.SUCCESS;
            }

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
            }

            @Override
            public void complete() {
                completions[0]++;
                throw failure;
            }

            @Override
            public Resolution resolution() {
                return confirmed[0] ? Resolution.COMPLETED : Resolution.UNKNOWN;
            }
        } : RequirementTransaction.SUCCESS);
        try (var fixture = new StationFixtures(1, 100, 2, definition(3, io(2, 1, 2), observer)); var lanes = new Lanes()) {
            var station = fixture.station(2, 0);
            put(station, 10);
            fixture.refresh();
            var state = new State(fixture);
            var capture = Objects.requireNonNull(fixture.selector.capture("team", 2));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> state.work.advance(capture, context(1), lanes.tasks, deadline())));
            assertTrue(state.work.pending());
            assertEquals(1, completions[0]);
            assertEquals(1, state.player.completedLevel(id("research")));
            assertEquals(0, state.queue.size());
            assertEquals(ResearchStationWork.Status.PENDING, state.work.resolve(deadline()).status());
            assertEquals(1, completions[0]);
            confirmed[0] = true;
            assertEquals(ResearchStationWork.Status.COMPLETED, state.work.resolve(deadline()).status());
            assertFalse(state.work.pending());
            assertEquals(1, completions[0]);
            assertEquals(7, input(station));
            assertEquals(2, output(station));
            assertTrue(state.work.cancel(deadline()));
            assertEquals(1, state.player.completedLevel(id("research")));
        }
    }

    private static final class State {
        private final TeamResearchQueue queue;
        private final PlayerResearchState player = new PlayerResearchState(OWNER);
        private final ResearchStationWork work;

        private State(StationFixtures fixture) {
            queue = new TeamResearchQueue(fixture.research, fixture.resources, "team");
            queue.enqueue(id("research"), 1, OWNER, 0);
            work = new ResearchStationWork(queue, new TeamOwnedResearch("team", fixture.research), Map.of(OWNER, player));
        }
    }

    private static final class Lanes implements AutoCloseable {
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final ExecutorService first = Executors.newSingleThreadExecutor();
        private final ExecutorService second = Executors.newSingleThreadExecutor();
        private final PlannedTaskExecutor tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{first, second});

        @Override
        public void close() {
            first.close();
            second.close();
            workers.close();
        }
    }
}
