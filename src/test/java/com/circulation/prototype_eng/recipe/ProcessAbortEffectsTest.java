package com.circulation.prototype_eng.recipe;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessAbortEffectsTest {
    private static final KeyType KEY_TYPE = new KeyType("process_abort");
    private static final Key MATERIAL = new Key(KEY_TYPE, "material");
    private static final ResourceLocation SCORE = id("score");
    private static final ProcessResult.Failure CANCELLED = new ProcessResult.Failure("cancelled");

    private static NumericAttributeModifier addition(String name, double amount) {
        return new NumericAttributeModifier(name, amount, AttributeModifier.Operation.ADDITION);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("process_abort_test", name);
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(KEY_TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, () -> {
        });
    }

    @Test
    void cancellationRemovesOnlyOwnedEffectsAndChoicesWithoutEndOrHistoricalRefund() {
        var fixture = new Fixture();
        fixture.machine.attributes().setBase(SCORE, 2);
        fixture.machine.attributes().addModifier(SCORE, addition("machine", 4));
        var choice = fixture.choice("choice", List.of(fixture.leaf), true);
        var process = fixture.process(choice);
        var childPath = fixture.leaf.path(choice.path(null));
        var layers = RecipeOverlays.getOrCreate(process);
        layers.put(fixture.leaf, childPath, "foreign_before", fixture.addition, 4).complete();
        process.attributes().addModifier(SCORE, addition("foreign", 5));
        assertSame(ProcessResult.SUCCESS, process.tick());
        layers.put(fixture.leaf, childPath, "foreign_after", fixture.addition, 7).complete();
        var state = SelectiveState.getOrCreate(process);
        var owned = state.snapshot().get(choice.path(null));
        var locks = ResourceInputLocks.getOrCreate(process);
        var lock = new ResourceInputLocks.Key(childPath, RecipeStage.START, ResourceInputLocks.Kind.FUZZY, 0);
        assertTrue(locks.putIfAbsent(lock, MATERIAL));
        assertEquals(14, process.attributes().value(SCORE));
        assertEquals(18, layers.resolve(fixture.leaf, childPath).payload());

        assertTrue(process.cancel(CANCELLED));
        assertEquals(RecipeProcess.Lifecycle.CANCELLED, process.state().lifecycle());
        assertEquals(11, process.attributes().value(SCORE));
        assertEquals(13, layers.resolve(fixture.leaf, childPath).payload());
        assertEquals(2, layers.snapshot().size());
        assertEquals(14, fixture.input.availableAmount(MATERIAL));
        assertEquals(0, fixture.output.availableAmount(MATERIAL));
        assertEquals(0, fixture.probe.ends);
        assertEquals(List.of(childPath), fixture.probe.cancellations);
        assertTrue(locks.snapshot().isEmpty());
        assertTrue(state.snapshot().isEmpty());
        var receipt = state.abortReceipts().getFirst();
        assertEquals(choice.path(null), receipt.selectionPath());
        assertEquals(owned.modifiers(), receipt.modifiers());
        assertEquals(owned.overlays(), receipt.overlays());
        assertNull(process.attributes().localSnapshot().get(SCORE).modifiers().get(owned.modifiers().getFirst().id()));
        assertFalse(layers.hasLayer(fixture.leaf, childPath, owned.overlays().getFirst().layerId()));
        process.requirePersistenceBoundary();
        assertFalse(process.cancel(CANCELLED));
        assertEquals(1, fixture.probe.cancellations.size());
        assertEquals(1, state.abortReceipts().size());
    }

    @Test
    void nestedCancellationVisitsOnlyChosenUnfinishedChildrenAndKeepsTheirEffectiveScope() {
        var fixture = new Fixture();
        var rejected = new Probe("rejected", fixture.input, fixture.output);
        rejected.startResult = new ProcessResult.Blocked("unavailable");
        var rejectedLeaf = rejected.type.requirement(id("rejected"), 2);
        var untouched = new Probe("untouched", fixture.input, fixture.output);
        var untouchedLeaf = untouched.type.requirement(id("untouched"), 2);
        var inner = fixture.choice("inner", List.of(rejectedLeaf, fixture.leaf, untouchedLeaf), false);
        var outer = fixture.choice("outer", List.of(inner, untouchedLeaf), false);
        var process = fixture.process(outer);
        var childPath = fixture.leaf.path(inner.path(outer.path(null)));
        assertSame(ProcessResult.SUCCESS, process.tick());
        RecipeOverlays.getOrCreate(process).put(fixture.leaf, childPath, "foreign", fixture.addition, 7).complete();

        assertTrue(process.cancel(CANCELLED));
        assertEquals(List.of(childPath), fixture.probe.cancellations);
        assertEquals(List.of(9), fixture.probe.cancelledPayloads);
        assertEquals(1, rejected.starts);
        assertTrue(rejected.cancellations.isEmpty());
        assertEquals(0, untouched.starts);
        assertTrue(untouched.cancellations.isEmpty());
        assertEquals(0, fixture.probe.ends);
        assertEquals(9, RecipeOverlays.getOrCreate(process).resolve(fixture.leaf, childPath).payload());
    }

    @Test
    void cancellationBeforeStartAndDisabledSelectionDoNotActivateChildren() {
        var fixture = new Fixture();
        var choice = fixture.choice("choice", List.of(fixture.leaf), true);
        var unstarted = fixture.process(choice);
        assertTrue(unstarted.cancel(CANCELLED));
        assertEquals(0, fixture.probe.starts);
        assertTrue(fixture.probe.cancellations.isEmpty());
        assertNull(unstarted.components().get(SelectiveState.TYPE));

        fixture.probe.startResult = new ProcessResult.Blocked("unavailable");
        var disabled = fixture.process(choice);
        assertSame(ProcessResult.SUCCESS, disabled.tick());
        var selection = Objects.requireNonNull(SelectiveState.getOrCreate(disabled).selection(choice.path(null)));
        assertEquals(-1, selection.index());
        assertTrue(disabled.cancel(CANCELLED));
        assertTrue(fixture.probe.cancellations.isEmpty());
        assertTrue(SelectiveState.getOrCreate(disabled).abortReceipts().isEmpty());
        assertEquals(20, fixture.input.availableAmount(MATERIAL));
    }

    @Test
    void completedAndUntriggeredCheckpointChildrenReceiveNoCancellation() {
        var fixture = new Fixture();
        var finished = fixture.choice("finished", List.of(fixture.leaf), true);
        var future = fixture.choice("future", List.of(fixture.leaf), true);
        var now = RecipeRequirements.CHECKPOINT.requirement(id("now"), new CheckpointRequirement(0, finished));
        var later = RecipeRequirements.CHECKPOINT.requirement(id("later"), new CheckpointRequirement(8, future));
        var process = fixture.process(now, later);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var selection = Objects.requireNonNull(SelectiveState.getOrCreate(process).selection(finished.path(now.path(null))));
        assertTrue(selection.completed());
        assertEquals(1, fixture.probe.starts);
        assertEquals(1, fixture.probe.ends);

        assertTrue(process.cancel(CANCELLED));
        assertTrue(fixture.probe.cancellations.isEmpty());
        assertEquals(1, fixture.probe.ends);
        assertEquals(18, fixture.input.availableAmount(MATERIAL));
        assertEquals(7, fixture.output.availableAmount(MATERIAL), "END uses the START-installed amount overlay");
        assertTrue(SelectiveState.getOrCreate(process).snapshot().isEmpty());
        assertTrue(SelectiveState.getOrCreate(process).abortReceipts().isEmpty());
    }

    @Test
    void replacementOwnershipFaultsPreserveForeignEffectsAndStillCleanOtherSelectionsAndLocks() {
        var fixture = new Fixture();
        var first = fixture.choice("first", List.of(fixture.leaf), true);
        var second = fixture.choice("second", List.of(fixture.leaf), true);
        var process = fixture.process(first, second);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var state = SelectiveState.getOrCreate(process);
        var owned = state.snapshot().get(first.path(null));
        var modifier = owned.modifiers().getFirst();
        var overlay = owned.overlays().getFirst();
        assertTrue(process.attributes().removeModifier(modifier.attribute(), modifier.id()));
        var foreign = addition(modifier.id(), 9);
        assertTrue(process.attributes().addModifier(modifier.attribute(), foreign));
        var layers = RecipeOverlays.getOrCreate(process);
        layers.put(fixture.leaf, overlay.path(), overlay.layerId(), fixture.addition, 8).complete();
        var locks = ResourceInputLocks.getOrCreate(process);
        locks.putIfAbsent(new ResourceInputLocks.Key(fixture.leaf.path(second.path(null)), RecipeStage.START,
            ResourceInputLocks.Kind.DYNAMIC, 0), MATERIAL);

        var failure = assertThrows(IllegalStateException.class, () -> process.cancel(CANCELLED));
        assertTrue(failure.getMessage().contains("Owned overlay layer changed"));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("Owned local modifier changed"));
        assertSame(foreign, process.attributes().localSnapshot().get(SCORE).modifiers().get(modifier.id()));
        assertEquals(10, layers.resolve(fixture.leaf, overlay.path()).payload());
        assertEquals(2, layers.resolve(fixture.leaf, fixture.leaf.path(second.path(null))).payload());
        assertEquals(1, layers.snapshot().size());
        assertTrue(locks.snapshot().isEmpty());
        assertTrue(state.snapshot().isEmpty());
        assertEquals(second.path(null), state.abortReceipts().getFirst().selectionPath());
        assertEquals(1, state.abortReceipts().size());
        assertEquals(2, fixture.probe.cancellations.size());
        assertEquals(16, fixture.input.availableAmount(MATERIAL));
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test
    void throwingOverlayRecomputationStillRemovesOtherEffectsAndRecordsPartialCleanup() {
        var fixture = new Fixture();
        var choice = fixture.choice("choice", List.of(fixture.leaf), true);
        var process = fixture.process(choice);
        var path = fixture.leaf.path(choice.path(null));
        var failing = new AtomicBoolean();
        var primary = new IllegalStateException("foreign applier failed");
        var overlay = new RecipeOverlayType<Integer, Integer>(id("throwing_addition"), fixture.probe.type, (base, amount) -> {
            if (failing.get()) {
                throw primary;
            }
            return base + amount;
        });
        var layers = RecipeOverlays.getOrCreate(process);
        layers.put(fixture.leaf, path, "foreign", overlay, 4).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        failing.set(true);

        assertSame(primary, assertThrows(IllegalStateException.class, () -> process.cancel(CANCELLED)));
        assertTrue(process.attributes().localSnapshot().isEmpty());
        assertEquals(2, layers.snapshot().size());
        var receipt = SelectiveState.getOrCreate(process).abortReceipts().getFirst();
        assertEquals(1, receipt.modifiers().size());
        assertTrue(receipt.overlays().isEmpty());
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test
    void businessFailureAutomaticallyAbortsPreviouslyCommittedStartEffects() {
        var fixture = new Fixture();
        var choice = fixture.choice("choice", List.of(fixture.leaf), true);
        var process = fixture.process(choice);
        fixture.probe.tickResult = new ProcessResult.Failure("tick failed");
        var locks = ResourceInputLocks.getOrCreate(process);
        locks.putIfAbsent(new ResourceInputLocks.Key(fixture.leaf.path(choice.path(null)), RecipeStage.TICK,
            ResourceInputLocks.Kind.FUZZY, 0), MATERIAL);

        assertSame(fixture.probe.tickResult, process.tick());
        assertEquals(RecipeProcess.Lifecycle.FAILED, process.state().lifecycle());
        assertTrue(process.state().started());
        assertTrue(process.attributes().localSnapshot().isEmpty());
        assertTrue(RecipeOverlays.getOrCreate(process).snapshot().isEmpty());
        assertTrue(locks.snapshot().isEmpty());
        var state = SelectiveState.getOrCreate(process);
        assertTrue(state.snapshot().isEmpty());
        assertEquals(1, state.abortReceipts().getFirst().modifiers().size());
        assertEquals(1, state.abortReceipts().getFirst().overlays().size());
        assertEquals(18, fixture.input.availableAmount(MATERIAL));
        assertEquals(0, fixture.output.availableAmount(MATERIAL));
        assertEquals(0, fixture.probe.ends);
        assertEquals(List.of(fixture.leaf.path(choice.path(null))), fixture.probe.cancellations);
        process.requirePersistenceBoundary();
        assertFalse(process.cancel(CANCELLED));
        assertSame(fixture.probe.tickResult, process.tick());
        assertEquals(1, fixture.probe.cancellations.size());
    }

    @Test
    void restoredSelectionsCancelWithoutReapplyingEffectsAndKeepCleanupReceiptsIndependent() {
        var fixture = new Fixture();
        var choice = fixture.choice("choice", List.of(fixture.leaf), true);
        var original = fixture.process(choice);
        assertSame(ProcessResult.SUCCESS, original.tick());
        var saved = SelectiveState.getOrCreate(original).snapshot();
        var restored = RecipeProcess.restored(fixture.machine, original.recipe(), original.seed(),
            original.probabilityTuning(), original.state());
        restored.attributes().restoreLocal(original.attributes().localSnapshot());
        RecipeOverlays.getOrCreate(restored).restore(RecipeOverlays.getOrCreate(original).snapshot());
        var state = SelectiveState.getOrCreate(restored);
        state.restore(saved);
        assertTrue(restored.cancel(CANCELLED));
        assertEquals(1, fixture.modifierApplications);
        assertEquals(1, fixture.probe.starts);
        assertTrue(restored.attributes().localSnapshot().isEmpty());
        assertTrue(RecipeOverlays.getOrCreate(restored).snapshot().isEmpty());
        assertEquals(1, state.abortReceipts().size());
        assertNotNull(SelectiveState.getOrCreate(original).selection(choice.path(null)));
        assertEquals(1, RecipeOverlays.getOrCreate(original).snapshot().size());

        var evidence = fixture.process(choice);
        var evidenceState = SelectiveState.getOrCreate(evidence);
        evidenceState.restoreAbortReceipts(state.abortReceipts());
        assertEquals(state.abortReceipts(), evidenceState.abortReceipts());
        assertTrue(evidenceState.snapshot().isEmpty());
        assertEquals(1, fixture.modifierApplications);
        var modifier = state.abortReceipts().getFirst().modifiers().getFirst();
        var orphaned = fixture.process(choice);
        orphaned.attributes().addModifier(modifier.attribute(), addition(modifier.id(), 4));
        assertThrows(IllegalArgumentException.class,
            () -> SelectiveState.getOrCreate(orphaned).restoreAbortReceipts(state.abortReceipts()));
        assertTrue(SelectiveState.getOrCreate(orphaned).abortReceipts().isEmpty());
    }

    private record Reserved(RecipeProcess process, RequirementPath path, ResourceReservation reservation)
        implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            checkScope();
            return ProcessResult.SUCCESS;
        }

        @Override
        public List<ResourceReservation> reservations() {
            checkScope();
            return List.of(reservation);
        }

        @Override
        public void commit() {
            checkScope();
        }

        @Override
        public void rollback() {
            checkScope();
        }

        private void checkScope() {
            assertEquals(path, process.requirementPath());
        }
    }

    private record Decision(ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }

    private static final class Probe implements RecipeRequirementSystem<Integer> {
        private final RecipeRequirementType<Integer> type;
        private final SlottedResourceBuffer input;
        private final SlottedResourceBuffer output;
        private final ObjectArrayList<RequirementPath> cancellations = new ObjectArrayList<>();
        private final IntArrayList cancelledPayloads = new IntArrayList();
        private ProcessResult startResult = ProcessResult.SUCCESS;
        private ProcessResult tickResult = ProcessResult.SUCCESS;
        private int starts;
        private int ends;

        private Probe(String name, SlottedResourceBuffer input, SlottedResourceBuffer output) {
            type = new RecipeRequirementType<>(id(name + "_type"), this);
            this.input = input;
            this.output = output;
        }

        private static Reserved reserved(RecipeProcess process, ReservationResult result) {
            assertEquals(ReservationResult.Status.ACCEPTED, result.status());
            return new Reserved(process, process.requirementPath(), Objects.requireNonNull(result.reservation()));
        }

        @Override
        public List<StorageContext> storageContexts(RecipeProcess process, RecipeRequirement<Integer> requirement) {
            return List.of(input.context(), output.context());
        }

        @Override
        public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
            starts++;
            return startResult == ProcessResult.SUCCESS ? reserved(process,
                input.reserveExtract(input.snapshot(), MATERIAL, requirement.payload())) : new Decision(startResult);
        }

        @Override
        public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Integer> requirement) {
            return tickResult == ProcessResult.SUCCESS ? RequirementTransaction.SUCCESS : new Decision(tickResult);
        }

        @Override
        public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Integer> requirement) {
            ends++;
            return reserved(process, output.reserveInsert(output.snapshot(), MATERIAL, requirement.payload()));
        }

        @Override
        public void cancel(RecipeProcess process, RecipeRequirement<Integer> requirement) {
            assertTrue(process.state().lifecycle().isTerminal());
            cancellations.add(process.requirementPath());
            cancelledPayloads.add(requirement.payload().intValue());
        }
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final Probe probe = new Probe("leaf", input, output);
        private final RecipeRequirement<Integer> leaf = probe.type.requirement(id("leaf"), 2);
        private final RecipeOverlayType<Integer, Integer> addition = new RecipeOverlayType<>(id("addition"), probe.type, Integer::sum);
        private int modifierApplications;

        private Fixture() {
            input.insert(MATERIAL, 20, Actionable.MODULATE);
        }

        private RecipeProcess process(RecipeRequirement<?>... requirements) {
            return new RecipeProcess(machine, new MachineRecipe(id("recipe"), 10, ObjectArrayList.of(requirements)), 17);
        }

        private RecipeRequirement<SelectiveRequirement> choice(String name, List<RecipeRequirement<?>> candidates, boolean effects) {
            var modifiers = effects ? List.of(new SelectiveModifierDescriptor(id("effect"), context -> {
                modifierApplications++;
                context.putProcessAttributeModifier(SCORE, addition("bonus", 3));
                context.putRequirementOverlay(leaf, leaf.path(context.selectionPath()), "amount", addition, 5);
            })) : List.<SelectiveModifierDescriptor>of();
            return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(candidates, modifiers));
        }
    }
}
