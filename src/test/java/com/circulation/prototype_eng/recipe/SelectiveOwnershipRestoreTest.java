package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.RenamedAttributeModifier;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
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
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectiveOwnershipRestoreTest {
    private static final KeyType KEY_TYPE = new KeyType("selective_restore");
    private static final Key MATERIAL = new Key(KEY_TYPE, "material");
    private static final ResourceLocation SCORE = id("score");
    private static final Runnable NO_OP = () -> { };

    @Test
    void restoringInstalledEffectsDoesNotReapplyModifiersAndEndKeepsForeignLayers() {
        var fixture = new Fixture();
        fixture.machine.attributes().setBase(SCORE, 2);
        fixture.machine.attributes().addModifier(SCORE, new NumericAttributeModifier("parent", 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
        var selection = fixture.selection(false);
        var original = fixture.process(selection, fixture.target);
        original.attributes().addModifier(SCORE, addition("foreign", 5));
        var originalLayers = RecipeOverlays.getOrCreate(original);
        originalLayers.put(fixture.target, "foreign_before", fixture.addition, 4).complete();
        assertSame(ProcessResult.SUCCESS, original.tick());
        originalLayers.put(fixture.target, "foreign_after", fixture.addition, 7).complete();
        assertEquals(20, original.attributes().value(SCORE));
        assertEquals(18, fixture.effective(original));
        var attributes = original.attributes().localSnapshot();
        var layers = originalLayers.snapshot();
        var selections = SelectiveState.getOrCreate(original).snapshot();
        var ownedId = selections.get(selection.path(null)).modifiers().getFirst().id();
        var wrapper = assertInstanceOf(RenamedAttributeModifier.class, attributes.get(SCORE).modifiers().get(ownedId));
        assertEquals("bonus", wrapper.delegate().id());

        var restored = fixture.process(selection, fixture.target);
        restored.attributes().restoreLocal(attributes);
        RecipeOverlays.getOrCreate(restored).restore(layers);
        var state = SelectiveState.getOrCreate(restored);
        state.restore(selections);
        assertEquals(1, fixture.modifierApplications);
        assertEquals(1, fixture.startAcquisitions);
        assertEquals(20, restored.attributes().value(SCORE));
        assertEquals(18, fixture.effective(restored));
        fixture.blockEnd = true;
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(restored, RecipeStage.END));
        assertEquals(20, restored.attributes().value(SCORE));
        assertEquals(18, fixture.effective(restored));
        assertEquals(0, fixture.output.availableAmount(MATERIAL));
        fixture.blockEnd = false;
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(restored, RecipeStage.END));
        assertEquals(18, fixture.output.availableAmount(MATERIAL));
        assertEquals(14, fixture.input.availableAmount(MATERIAL));
        assertEquals(14, restored.attributes().value(SCORE));
        assertEquals(13, fixture.effective(restored));
        assertEquals(20, original.attributes().value(SCORE));
        assertEquals(18, fixture.effective(original));
        assertEquals(1, fixture.modifierApplications);
        assertEquals(1, fixture.startAcquisitions);
        var completed = state.snapshot().get(selection.path(null));
        assertTrue(completed.completed());
        assertTrue(completed.modifiers().isEmpty());
        assertTrue(completed.overlays().isEmpty());
        assertEquals(2, RecipeOverlays.getOrCreate(restored).snapshot().size());
    }

    @Test
    void selectionSnapshotsRejectProvisionalAndPendingStartButLifetimeClaimsAreStable() {
        var fixture = new Fixture();
        var selection = fixture.selection(false);
        var process = fixture.process(selection, fixture.target);
        var start = selection.acquire(process, RecipeStage.START);
        var state = SelectiveState.getOrCreate(process);
        assertThrows(IllegalStateException.class, state::snapshot);
        start.commit();
        var overlays = RecipeOverlays.getOrCreate(process);
        assertThrows(IllegalStateException.class, state::snapshot);
        assertThrows(IllegalStateException.class, overlays::snapshot);
        assertThrows(IllegalStateException.class, () -> overlays.restore(List.of()));
        start.complete();
        assertEquals(1, overlays.snapshot().size());
        assertEquals(1, state.snapshot().get(selection.path(null)).overlays().size());
        var end = selection.acquire(process, RecipeStage.END);
        end.commit();
        end.complete();
        assertTrue(overlays.snapshot().isEmpty());
        assertTrue(state.snapshot().get(selection.path(null)).completed());
    }

    @Test
    void unknownExistingSelectiveLayerIsRejectedWithoutDeletingForeignState() {
        var fixture = new Fixture();
        var selection = fixture.selection(false);
        var process = fixture.process(selection, fixture.target);
        String layerId = "selective_overlay/" + frame(selection.path(null).toString()) + frame(id("effect").toString()) + frame("0") + frame("amount");
        var overlays = RecipeOverlays.getOrCreate(process);
        overlays.put(fixture.target, layerId, fixture.addition, 7).complete();
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(9, fixture.effective(process));
        assertEquals(20, fixture.input.availableAmount(MATERIAL));
        assertEquals(1, process.attributes().value(SCORE));
        assertNull(SelectiveState.getOrCreate(process).selection(selection.path(null)));
        assertEquals(1, overlays.snapshot().size());
    }

    @Test
    void staleClaimsCannotDeleteReplacedOrRestoredLayers() {
        var fixture = new Fixture();
        var process = fixture.process(fixture.target);
        var overlays = RecipeOverlays.getOrCreate(process);
        overlays.put(fixture.target, "owned", fixture.addition, 2).complete();
        overlays.put(fixture.target, "foreign", fixture.addition, 7).complete();
        var stale = overlays.claim(fixture.target, fixture.target.path(null), "owned");
        overlays.put(fixture.target, "owned", fixture.addition, 3).complete();
        assertThrows(IllegalStateException.class, stale::remove);
        assertEquals(12, fixture.effective(process));
        var beforeRestore = overlays.claim(fixture.target, fixture.target.path(null), "owned");
        overlays.restore(overlays.snapshot());
        assertThrows(IllegalStateException.class, beforeRestore::remove);
        assertEquals(12, fixture.effective(process));
        var renewed = overlays.claim(fixture.target, fixture.target.path(null), "owned");
        renewed.validate();
        renewed.remove();
        renewed.remove();
        assertEquals(9, fixture.effective(process));
    }

    @Test
    void layerRestoreKeepsOrderAndRejectsMalformedIdentityOrOrderWithoutPartialPublication() {
        var fixture = new Fixture();
        var process = fixture.process(fixture.target);
        var overlays = RecipeOverlays.getOrCreate(process);
        var multiply = new RecipeOverlayType<Integer, Integer>(id("multiply"), fixture.type, (base, factor) -> base * factor);
        overlays.put(fixture.target, "add", fixture.addition, 3).complete();
        overlays.put(fixture.target, "multiply", multiply, 4).complete();
        var saved = overlays.snapshot();
        assertEquals(List.of(0L, 1L), saved.stream().map(RecipeOverlays.LayerSnapshot::order).toList());
        overlays.restore(List.of(saved.getLast(), saved.getFirst()));
        assertEquals(20, fixture.effective(process));
        overlays.put(fixture.target, "later", fixture.addition, 2).complete();
        assertEquals(22, fixture.effective(process));
        var path = fixture.target.path(null);
        assertThrows(IllegalArgumentException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "a", 0, fixture.addition, 1),
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "b", 0, fixture.addition, 2))));
        assertThrows(IllegalArgumentException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "a", 0, fixture.addition, 1),
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "a", 1, fixture.addition, 2))));
        assertThrows(IllegalArgumentException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "a", -1, fixture.addition, 1))));
        assertThrows(IllegalArgumentException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target.withPayload(8), "a", 0, fixture.addition, 1))));
        var anotherDefinition = fixture.type.requirement(fixture.target.id(), 8);
        assertThrows(IllegalArgumentException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "a", 0, fixture.addition, 1),
            new RecipeOverlays.LayerSnapshot<>(path, anotherDefinition, "b", 1, fixture.addition, 1))));
        var mismatchedType = new RecipeRequirementType<Integer>(id("other_type"), new RecipeRequirementSystem<>() { });
        var mismatchedOverlay = new RecipeOverlayType<>(id("other_overlay"), mismatchedType, Integer::sum);
        assertThrows(IllegalArgumentException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(path, fixture.target, "a", 0, mismatchedOverlay, 1))));
        assertEquals(22, fixture.effective(process));
        assertEquals(3, overlays.snapshot().size());
    }

    @Test
    void throwingRestoreApplierLeavesAllExistingBindingsIntact() {
        var fixture = new Fixture();
        var process = fixture.process(fixture.target);
        var overlays = RecipeOverlays.getOrCreate(process);
        overlays.put(fixture.target, "original", fixture.addition, 10).complete();
        var primary = new IllegalStateException("restored applier failed");
        var throwing = new RecipeOverlayType<Integer, Integer>(id("throwing"), fixture.type, (_, _) -> { throw primary; });
        assertSame(primary, assertThrows(IllegalStateException.class, () -> overlays.restore(List.of(
            new RecipeOverlays.LayerSnapshot<>(fixture.target.path(null), fixture.target, "replacement", 0, throwing, 1)))));
        assertEquals(12, fixture.effective(process));
        assertEquals("original", overlays.snapshot().getFirst().layerId());
    }

    @Test
    void selectorRestoreRejectsMissingDuplicateOrInactiveOwnershipBeforePublishingAnyDecision() {
        var fixture = new Fixture();
        var selection = fixture.selection(false);
        var original = fixture.process(selection, fixture.target);
        assertSame(ProcessResult.SUCCESS, original.tick());
        var saved = SelectiveState.getOrCreate(original).snapshot().get(selection.path(null));
        var restored = fixture.process(selection, fixture.target);
        restored.attributes().restoreLocal(original.attributes().localSnapshot());
        RecipeOverlays.getOrCreate(restored).restore(RecipeOverlays.getOrCreate(original).snapshot());
        var state = SelectiveState.getOrCreate(restored);
        var otherPath = new RequirementPath(null, RecipeRequirements.SELECTIVE.id(), id("other"));
        var invalid = new Object2ObjectLinkedOpenHashMap<RequirementPath, SelectiveState.SelectionSnapshot>();
        invalid.put(selection.path(null), saved);
        invalid.put(otherPath, new SelectiveState.SelectionSnapshot(false, 0, false, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> state.restore(invalid));
        assertTrue(state.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> state.restore(Map.of(selection.path(null), saved, otherPath, saved)));
        assertThrows(IllegalArgumentException.class, () -> state.restore(Map.of(selection.path(null),
            new SelectiveState.SelectionSnapshot(true, -1, false, saved.modifiers(), saved.overlays()))));
        assertThrows(IllegalArgumentException.class, () -> state.restore(Map.of(selection.path(null),
            new SelectiveState.SelectionSnapshot(true, 0, true, saved.modifiers(), saved.overlays()))));
        assertThrows(IllegalArgumentException.class, () -> state.restore(Map.of(selection.path(null),
            new SelectiveState.SelectionSnapshot(true, 0, false, List.of(new SelectiveState.ModifierReference(SCORE, "missing")), List.of()))));
        var missingOverlay = new SelectiveState.SelectionSnapshot(true, 0, false, List.of(), List.of(
            new SelectiveState.OverlayReference(fixture.target.path(null), fixture.target, "missing")));
        assertThrows(IllegalArgumentException.class, () -> state.restore(Map.of(selection.path(null), missingOverlay)));
        state.restore(Map.of(selection.path(null), saved));
        assertEquals(1, state.snapshot().size());
        assertEquals(1, fixture.modifierApplications);
        assertThrows(IllegalStateException.class, () -> state.restore(Map.of()));
    }

    @Test
    void childStartCompletionFaultStillPromotesClaimsAndDoesNotReplayTheModifier() {
        var fixture = new Fixture();
        var primary = new IllegalStateException("child START completion failed");
        fixture.startCompletion = () -> { throw primary; };
        var selection = fixture.selection(false);
        var process = fixture.process(selection, fixture.target);
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(1, SelectiveState.getOrCreate(process).snapshot().size());
        assertEquals(1, RecipeOverlays.getOrCreate(process).snapshot().size());
        var committedState = process.state();
        var selections = SelectiveState.getOrCreate(process).snapshot();
        var overlays = RecipeOverlays.getOrCreate(process).snapshot();
        var attributes = process.attributes().localSnapshot();
        long available = fixture.input.availableAmount(MATERIAL);
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(committedState, process.state());
        assertSame(ProcessResult.SUCCESS, process.state().result());
        assertEquals(selections, SelectiveState.getOrCreate(process).snapshot());
        assertEquals(overlays, RecipeOverlays.getOrCreate(process).snapshot());
        assertEquals(attributes, process.attributes().localSnapshot());
        assertEquals(available, fixture.input.availableAmount(MATERIAL));
        assertEquals(0, fixture.output.availableAmount(MATERIAL));
        assertEquals(1, fixture.modifierApplications);
        assertEquals(1, fixture.startAcquisitions);
    }

    @Test
    void checkpointStartAndEndInOneStagePromoteThenReleaseAllOwnedLayers() {
        var fixture = new Fixture();
        var selection = fixture.selection(true);
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(0, selection));
        var process = fixture.process(checkpoint);
        var childPath = fixture.target.path(selection.path(checkpoint.path(null)));
        var overlays = RecipeOverlays.getOrCreate(process);
        overlays.put(fixture.target, childPath, "foreign", fixture.addition, 4).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(14, fixture.input.availableAmount(MATERIAL));
        assertEquals(6, fixture.output.availableAmount(MATERIAL));
        assertEquals(6, overlays.resolve(fixture.target, childPath).payload());
        assertEquals(1, overlays.snapshot().size());
        var snapshot = SelectiveState.getOrCreate(process).snapshot().get(selection.path(checkpoint.path(null)));
        assertTrue(snapshot.completed());
        assertTrue(snapshot.overlays().isEmpty());
        assertTrue(snapshot.modifiers().isEmpty());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, fixture.startAcquisitions);
        assertEquals(1, fixture.modifierApplications);
        assertEquals(6, fixture.output.availableAmount(MATERIAL));
    }

    private static NumericAttributeModifier addition(String name, double amount) {
        return new NumericAttributeModifier(name, amount, AttributeModifier.Operation.ADDITION);
    }

    private static String frame(String value) { return value.length() + ":" + value; }
    private static ResourceLocation id(String name) { return new ResourceLocation("selective_restore_test", name); }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(KEY_TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, NO_OP);
    }

    private record Completion(Runnable startCompletion) implements RequirementTransaction {
        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public void commit() { }
        @Override public void complete() { startCompletion.run(); }
        @Override public void rollback() { }
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final RecipeRequirementType<Integer> type;
        private final RecipeRequirement<Integer> target;
        private final RecipeOverlayType<Integer, Integer> addition;
        private int modifierApplications;
        private int startAcquisitions;
        private boolean blockEnd;
        private Runnable startCompletion = NO_OP;

        private Fixture() {
            input.insert(MATERIAL, 20, Actionable.MODULATE);
            type = new RecipeRequirementType<>(new ResourceLocation("zz_restore", "resource"), new RecipeRequirementSystem<>() {
                @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                    startAcquisitions++;
                    return acquire(process, requirement, RecipeStage.START);
                }
                @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                    return acquire(process, requirement, RecipeStage.END);
                }
            });
            target = type.requirement(id("resource"), 2);
            addition = new RecipeOverlayType<>(id("addition"), type, Integer::sum);
        }

        private RecipeProcess process(RecipeRequirement<?>... requirements) {
            return new RecipeProcess(machine, new MachineRecipe(id("recipe"), 3, ObjectArrayList.of(requirements)), 17);
        }

        private int effective(RecipeProcess process) { return RecipeOverlays.getOrCreate(process).resolve(target).payload(); }

        private RecipeRequirement<SelectiveRequirement> selection(boolean nested) {
            var candidate = new RecipeRequirementType<Integer>(id("candidate_type"), new RecipeRequirementSystem<>() {
                @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                    return new Completion(startCompletion);
                }
            }).requirement(id("candidate"), 0);
            return RecipeRequirements.SELECTIVE.requirement(id("choice"), new SelectiveRequirement(List.of(nested ? target : candidate), List.of(
                new SelectiveModifierDescriptor(id("effect"), context -> {
                    modifierApplications++;
                    context.putProcessAttributeModifier(SCORE, addition("bonus", 3));
                    var path = nested ? target.path(context.selectionPath()) : target.path(null);
                    context.putRequirementOverlay(target, path, "amount", addition, 3);
                    context.putRequirementOverlay(target, path, "amount", addition, 5);
                }))));
        }

        private RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<Integer> requirement, RecipeStage stage) {
            var path = process.requirementPath();
            var result = stage == RecipeStage.START ? input.reserveExtract(input.snapshot(), MATERIAL, requirement.payload())
                : output.reserveInsert(output.snapshot(), MATERIAL, requirement.payload());
            assertEquals(ReservationResult.Status.ACCEPTED, result.status());
            var reservation = Objects.requireNonNull(result.reservation());
            ProcessResult decision = stage == RecipeStage.END && blockEnd ? new ProcessResult.Blocked("output") : ProcessResult.SUCCESS;
            return new RequirementTransaction() {
                @Override public ProcessResult result() { checkScope(); return decision; }
                @Override public List<ResourceReservation> reservations() { checkScope(); return List.of(reservation); }
                @Override public void commit() { checkScope(); }
                @Override public void rollback() { checkScope(); }
                private void checkScope() { assertEquals(path, process.requirementPath()); }
            };
        }
    }
}
