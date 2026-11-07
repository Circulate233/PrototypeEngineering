package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ResearchMaterialCycleTest {
    private static final KeyType TYPE = new KeyType("research_material_cycle");
    private static final Key RAW = new Key(TYPE, "raw");
    private static final Key INTERMEDIATE = new Key(TYPE, "intermediate");
    private static final Key RESULT = new Key(TYPE, "result");
    private static final ResourceLocation SCORE = id("score");

    @Test void sourceCompletionMakesStartProductsAvailableToTickAndTickProductsAvailableToEnd() {
        var storage = storage();
        var unrelated = storage();
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, hatches(unrelated));
        int[] providerCalls = {0};
        var provider = new ComponentType<MachineInstance, MachineRecipeRequirements>(id("ordinary_provider"),
            _ -> _ -> { providerCalls[0]++; throw new AssertionError("Ordinary provider must not run for research"); }, List.of());
        machine.components().create(provider, machine);
        var phases = new ObjectArrayList<RecipeStage>();
        var cycle = RecipeProcess.materialCycle(machine, id("products"), List.of(
            RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(List.of(),
                List.of(new GenericStack(RAW, 5)), List.of(new GenericStack(INTERMEDIATE, 5)),
                List.of(new GenericStack(RESULT, 1)), false)),
            observer((_, phase) -> {
                phases.add(phase);
                var token = switch (phase) {
                    case START -> Objects.requireNonNull(storage.reserveInsert(storage.snapshot(), RAW, 5).reservation());
                    case TICK -> null;
                    case END -> Objects.requireNonNull(storage.reserveExtract(storage.snapshot(), INTERMEDIATE, 5).reservation());
                };
                return token == null ? RequirementTransaction.SUCCESS : reserved(token);
            })), hatches(storage), 7);
        var session = Objects.requireNonNull(cycle.beginTick());
        var start = Objects.requireNonNull(session.nextStage());
        settle(start, List.of());
        assertEquals(5, storage.availableAmount(RAW));
        machine.components().replaceValue(MachineHatches.TYPE, hatches(unrelated));
        var tick = Objects.requireNonNull(session.nextStage());
        settle(tick, List.of());
        assertEquals(0, storage.availableAmount(RAW));
        assertEquals(5, storage.availableAmount(INTERMEDIATE));
        var end = Objects.requireNonNull(session.nextStage());
        settle(end, List.of());
        assertNull(session.nextStage());
        session.finish();
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
        assertEquals(1, storage.availableAmount(RESULT));
        assertEquals(0, storage.availableAmount(INTERMEDIATE));
        assertEquals(0, unrelated.amount(RESULT));
        assertEquals(0, providerCalls[0]);
        assertSame(machine, cycle.owner());
        cycle.requirePersistenceBoundary();
    }

    @Test void zeroFractionalAndFastSpeedsStillProduceExactlyOneSuccessfulTickWithTheirScaledTimeline() {
        for (double speed : new double[]{0, 0.5, 2}) {
            var machine = new MachineInstance();
            machine.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, speed);
            var phases = new ObjectArrayList<RecipeStage>();
            var cycle = RecipeProcess.materialCycle(machine, id("speed"), List.of(observer((process, phase) -> {
                phases.add(phase);
                if (phase == RecipeStage.TICK) { assertEquals(new RecipeProcess.Tick(0, speed, 0, 1), process.tickContext()); }
                return RequirementTransaction.SUCCESS;
            })), emptyHatches(), 4);
            assertSame(ProcessResult.SUCCESS, cycle.tick());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, cycle.state().lifecycle());
            assertEquals(1, cycle.state().successfulTicks());
            assertEquals(speed, cycle.state().progress());
        }
    }

    @Test void machineParallelismDoesNotMultiplyOneMaterialPaymentButMaterialSelectorModifiersStillApply() {
        var storage = storage();
        storage.insert(RAW, 20, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 4);
        machine.attributes().addModifier(StandardMachineAttributes.PROCESS_PARALLELISM,
            new NumericAttributeModifier("machine_parallel", 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
        var selection = RecipeRequirements.SELECTIVE.requirement(id("parallel_selection"), new SelectiveRequirement(
            List.of(observer((process, phase) -> {
                if (phase == RecipeStage.START) { assertEquals(1, RecipeParallelism.effective(process)); }
                else { assertEquals(2, RecipeParallelism.effective(process)); }
                return RequirementTransaction.SUCCESS;
            })), List.of(new SelectiveModifierDescriptor(id("material_parallel"), context ->
                context.putProcessAttributeModifier(StandardMachineAttributes.PROCESS_PARALLELISM,
                    new NumericAttributeModifier("material_parallel", 1, AttributeModifier.Operation.MULTIPLY_TOTAL))))));
        var cycle = RecipeProcess.materialCycle(machine, id("parallel"), List.of(selection,
            RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(List.of(new GenericStack(RAW, 2)),
                List.of(new GenericStack(RAW, 2)), List.of(), List.of(new GenericStack(RESULT, 3)), false))), hatches(storage), 2);
        assertSame(ProcessResult.SUCCESS, cycle.tick());
        assertEquals(14, storage.availableAmount(RAW));
        assertEquals(6, storage.availableAmount(RESULT));
        assertEquals(8, machine.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM));
        assertEquals(1, RecipeParallelism.effective(cycle));
    }

    @Test void blockedEndRetainsSelectorEffectsAndRetriesOnlyEndWithoutRepeatingPaidStartOrTick() {
        var energy = energy(20);
        var phases = new ObjectArrayList<RecipeStage>();
        boolean[] blocked = {true};
        int[] modifiers = {0};
        var candidate = observer((process, phase) -> {
            phases.add(phase);
            if (phase != RecipeStage.START) { assertEquals(5, process.attributes().value(SCORE)); }
            return phase == RecipeStage.END && blocked[0] ? blocked() : RequirementTransaction.SUCCESS;
        });
        var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(candidate),
            List.of(new SelectiveModifierDescriptor(id("bonus"), context -> {
                modifiers[0]++;
                context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 4, AttributeModifier.Operation.ADDITION));
            }))));
        var cycle = RecipeProcess.materialCycle(new MachineInstance(), id("blocked_end"), List.of(selection,
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 0, false))),
            energyHatches(energy), 1);
        assertInstanceOf(ProcessResult.Blocked.class, cycle.tick());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, cycle.state().lifecycle());
        assertEquals(1, cycle.state().successfulTicks());
        assertEquals(15, energy.storedEnergy());
        assertEquals(5, cycle.attributes().value(SCORE));
        blocked[0] = false;
        assertSame(ProcessResult.SUCCESS, cycle.tick());
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END, RecipeStage.END), phases);
        assertEquals(1, modifiers[0]);
        assertEquals(15, energy.storedEnergy());
        assertEquals(1, cycle.attributes().value(SCORE));
        cycle.requirePersistenceBoundary();
    }

    @Test void endParticipantCommitFailureRefundsOnlyItsCurrentPhaseAndUndoesTheParticipant() {
        var energy = energy(10);
        var cycle = energyCycle(energy);
        var session = Objects.requireNonNull(cycle.beginTick());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        assertEquals(5, energy.storedEnergy());
        var end = Objects.requireNonNull(session.nextStage());
        end.acquire();
        var fault = new IllegalStateException("grant commit");
        int[] granted = {0};
        var participant = new ReversibleTransaction() {
            @Override public void commit() { granted[0] = 1; assertEquals(12, energy.storedEnergy()); throw fault; }
            @Override public void rollback() { granted[0] = 0; }
        };
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(end), List.of(participant)));
        apply(batch);
        assertSame(fault, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertTrue(batch.rollback());
        resolve(batch);
        assertTrue(end.settleBatch(fault));
        session.finish();
        assertEquals(0, granted[0]);
        assertEquals(5, energy.storedEnergy());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, cycle.state().lifecycle());
        assertEquals(1, cycle.state().successfulTicks());
        assertThrows(IllegalStateException.class, cycle::tick);
    }

    @Test void busyTransferLeavesExternalParticipantAndCurrentStageWithTheirCaller() {
        var energy = energy(10);
        var cycle = energyCycle(energy);
        var session = Objects.requireNonNull(cycle.beginTick());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        var end = Objects.requireNonNull(session.nextStage());
        end.acquire();
        int[] commits = {0};
        int[] rollbacks = {0};
        var participant = new ReversibleTransaction() {
            @Override public void commit() { commits[0]++; }
            @Override public void rollback() { rollbacks[0]++; }
        };
        var fence = Objects.requireNonNull(StorageFence.tryAcquire(List.of(energy.context())));
        try {
            assertNull(RecipeProcess.Stage.tryAcquireBatch(List.of(end), List.of(participant)));
            assertEquals(0, commits[0]);
            assertEquals(0, rollbacks[0]);
            assertTrue(end.transferable());
        } finally { fence.release(); }
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(end), List.of(participant)));
        apply(batch);
        assertTrue(batch.commit(() -> true));
        resolve(batch);
        assertTrue(end.settleBatch(null));
        session.finish();
        assertEquals(1, commits[0]);
        assertEquals(0, rollbacks[0]);
        assertEquals(12, energy.storedEnergy());
    }

    @Test void normalEndParticipantRejectionRetriesEndWithoutConsumingEarlierPhaseEnergyAgain() {
        var energy = energy(10);
        var cycle = energyCycle(energy);
        var session = Objects.requireNonNull(cycle.beginTick());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        var end = Objects.requireNonNull(session.nextStage());
        end.acquire();
        int[] canceled = {0};
        var rejected = new ReversibleTransaction() {
            @Override public boolean prepareCommit() { return false; }
            @Override public void commit() { fail("Rejected grant must not commit"); }
            @Override public void rollback() { canceled[0]++; }
        };
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(end), List.of(rejected)));
        apply(batch);
        assertFalse(batch.commit(() -> true));
        assertTrue(batch.rollback());
        resolve(batch);
        assertTrue(end.settleBatch(null));
        session.finish();
        assertEquals(1, canceled[0]);
        assertEquals(5, energy.storedEnergy());
        assertInstanceOf(ProcessResult.Blocked.class, cycle.state().result());
        var retry = Objects.requireNonNull(cycle.beginTick());
        var retriedEnd = Objects.requireNonNull(retry.nextStage());
        assertEquals(RecipeStage.END, retriedEnd.phase());
        settle(retriedEnd, List.of());
        assertNull(retry.nextStage());
        retry.finish();
        assertEquals(12, energy.storedEnergy());
        assertEquals(1, cycle.state().successfulTicks());
        cycle.requirePersistenceBoundary();
    }

    @Test void unknownEndCompletionPinsTheExactBatchAndDoesNotReplayAnyMaterialPhase() {
        var phases = new ObjectArrayList<RecipeStage>();
        var cycle = RecipeProcess.materialCycle(new MachineInstance(), id("unknown"), List.of(observer((_, phase) -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        })), emptyHatches(), 1);
        var session = Objects.requireNonNull(cycle.beginTick());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        settle(Objects.requireNonNull(session.nextStage()), List.of());
        var end = Objects.requireNonNull(session.nextStage());
        end.acquire();
        boolean[] confirmed = {false};
        int[] completions = {0};
        var fault = new IllegalStateException("unknown completion");
        var participant = new ReversibleTransaction() {
            @Override public void commit() { }
            @Override public void complete() { completions[0]++; throw fault; }
            @Override public void rollback() { fail("The decided END completion must not roll back"); }
            @Override public Resolution resolution() { return confirmed[0] ? Resolution.COMPLETED : Resolution.UNKNOWN; }
        };
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(end), List.of(participant)));
        assertSame(fault, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertFalse(batch.resolveBusiness());
        assertFalse(end.settleBatch(fault));
        assertSame(end, Objects.requireNonNull(cycle.pendingTick()).currentStage());
        assertThrows(IllegalStateException.class, cycle::beginTick);
        assertThrows(IllegalStateException.class, session::finish);
        confirmed[0] = true;
        assertTrue(batch.resolveBusiness());
        batch.finish();
        assertTrue(end.settleBatch(fault));
        session.finish();
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
        assertEquals(1, completions[0]);
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, cycle.state().lifecycle());
        cycle.requireQuiescent();
    }

    @Test void restoredZeroProgressCompletedTickContinuesWithEndAndRejectsImpossibleCycleStates() {
        var phases = new ObjectArrayList<RecipeStage>();
        var definition = new MachineRecipe(id("restored"), 1, List.of(observer((_, phase) -> {
            phases.add(phase);
            return RequirementTransaction.SUCCESS;
        })));
        var cycle = RecipeProcess.restoredMaterialCycle(new MachineInstance(), definition, 1, ProbabilityTuning.defaultTuning(),
            new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 0, 1, ProcessResult.SUCCESS), emptyHatches());
        assertSame(ProcessResult.SUCCESS, cycle.tick());
        assertEquals(List.of(RecipeStage.END), phases);
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, cycle.state().lifecycle());
        assertThrows(IllegalArgumentException.class, () -> RecipeProcess.restoredMaterialCycle(new MachineInstance(), definition,
            1, ProbabilityTuning.defaultTuning(), new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 1, 0, ProcessResult.SUCCESS), emptyHatches()));
        assertThrows(IllegalArgumentException.class, () -> RecipeProcess.restoredMaterialCycle(new MachineInstance(), definition,
            1, ProbabilityTuning.defaultTuning(), new RecipeProcess.State(RecipeProcess.Lifecycle.RUNNING, 2, 2, ProcessResult.SUCCESS), emptyHatches()));
        assertThrows(IllegalArgumentException.class, () -> RecipeProcess.restoredMaterialCycle(new MachineInstance(), definition,
            1, ProbabilityTuning.defaultTuning(), new RecipeProcess.State(RecipeProcess.Lifecycle.COMPLETED, 0, 0, ProcessResult.SUCCESS), emptyHatches()));
    }

    @Test void ordinaryProcessesContinueToConsumeTheCurrentMachineHatches() {
        var original = storage();
        var replacement = storage();
        original.insert(RAW, 5, Actionable.MODULATE);
        replacement.insert(RAW, 5, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, hatches(original));
        var process = new RecipeProcess(machine, new MachineRecipe(id("ordinary"), 0, List.of(
            RecipeRequirements.RESOURCES.requirement(id("input"), new ResourceRequirement(List.of(new GenericStack(RAW, 5)), List.of())))), 1);
        machine.components().replaceValue(MachineHatches.TYPE, hatches(replacement));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(5, original.availableAmount(RAW));
        assertEquals(0, replacement.availableAmount(RAW));
    }

    private static RecipeProcess energyCycle(EnergyBuffer energy) {
        return RecipeProcess.materialCycle(new MachineInstance(), id("energy_cycle"), List.of(
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 7, false))), energyHatches(energy), 1);
    }

    private static void settle(RecipeProcess.Stage stage, List<? extends ReversibleTransaction> participants) {
        stage.acquire();
        assertSame(ProcessResult.SUCCESS, stage.result());
        var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage), participants));
        apply(batch);
        assertTrue(batch.commit(() -> true));
        resolve(batch);
        assertTrue(stage.settleBatch(null));
    }

    private static void apply(ResourceBatch batch) { for (var source : batch.sources()) { assertTrue(source.apply()); } }

    private static void resolve(ResourceBatch batch) {
        for (var source : batch.sources()) { assertTrue(source.resolve()); }
        batch.finish();
    }

    private static RequirementTransaction reserved(ResourceReservation reservation) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public List<ResourceReservation> reservations() { return List.of(reservation); }
            @Override public void commit() { }
            @Override public void rollback() { }
        };
    }

    private static RequirementTransaction blocked() {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return new ProcessResult.Blocked("wait"); }
            @Override public void commit() { fail("Blocked END must not commit"); }
            @Override public void rollback() { }
        };
    }

    private static RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> observer(
            BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var type = new RecipeRequirementType<>(id("observer_type"),
            new RecipeRequirementSystem<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>>() {
                @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> requirement) {
                    return requirement.payload().apply(process, RecipeStage.START);
                }
                @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> requirement) {
                    return requirement.payload().apply(process, RecipeStage.TICK);
                }
                @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> requirement) {
                    return requirement.payload().apply(process, RecipeStage.END);
                }
            });
        return type.requirement(id("observer"), action);
    }

    private static SlottedResourceBuffer storage() { return new SlottedResourceBuffer(4, Map.of(TYPE, 100L), () -> { }); }

    private static EnergyBuffer energy(long initial) {
        var buffer = new EnergyBuffer(100, () -> { });
        buffer.receiveEnergy(initial, Actionable.MODULATE);
        return buffer;
    }

    private static MachineHatches hatches(SlottedResourceBuffer storage) {
        return new MachineHatches(List.of(new ResourceHatch() {
            @Override public ResourceStorage input() { return storage; }
            @Override public ResourceStorage output() { return storage; }
            @Override public boolean isValid() { return storage.isValid(); }
        }), List.of(), List.of());
    }

    private static MachineHatches emptyHatches() { return new MachineHatches(List.of(), List.of(), List.of()); }
    private static MachineHatches energyHatches(EnergyBuffer energy) { return new MachineHatches(List.of(), List.of(energy), List.of(energy)); }
    private static ResourceLocation id(String path) { return new ResourceLocation("prototype_eng_test", path); }
}
