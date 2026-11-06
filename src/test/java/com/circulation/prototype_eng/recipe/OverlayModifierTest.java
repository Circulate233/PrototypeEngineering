package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayContext;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayPipeline;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveContext;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayModifierTest {
    private static final KeyType KEY_TYPE = new KeyType("overlay_modifier");
    private static final Key MATERIAL = new Key(KEY_TYPE, "material");
    private static final ResourceLocation SCORE = id("score");
    private static final Runnable NO_OP = () -> { };

    @Test
    void pipelineRunsRepeatedDescriptorsInOrderWithSeparateLayersAndExpiresEveryContext() {
        var probe = new Probe("zz_overlay", 1, false);
        var process = process(1, probe.target);
        var contexts = new ObjectArrayList<RecipeOverlayContext>();
        var descriptor = new RecipeOverlayModifierDescriptor(id("repeated"), context -> {
            contexts.add(context);
            assertSame(process, context.process());
            assertSame(process.owner(), context.machine());
            assertEquals(id("repeated"), context.modifierId());
            assertEquals(contexts.size() - 1, context.modifierOrdinal());
            context.put(probe.target, "same", probe.addition, 2);
        });
        var transaction = RecipeOverlayPipeline.acquire(process, List.of(descriptor, descriptor));
        assertSame(ProcessResult.SUCCESS, transaction.result());
        assertEquals(5, effective(process, probe));
        for (var context : contexts) {
            assertThrows(IllegalStateException.class, () -> context.put(probe.target, "late", probe.addition, 100));
        }
        transaction.commit();
        assertEquals(5, effective(process, probe));
        transaction.rollback();
        transaction.rollback();
        assertEquals(1, effective(process, probe));
    }

    @Test
    void pipelineUsesDeclarationOrderAndUndoRecomputesUnrelatedEarlierAndLaterLayers() {
        var probe = new Probe("zz_overlay", 1, false);
        var process = process(1, probe.target);
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign_before", probe.addition, 10).complete();
        var multiply = new RecipeOverlayType<Integer, Integer>(id("multiply"), probe.type, (base, factor) -> base * factor);
        var transaction = RecipeOverlayPipeline.acquire(process, List.of(
            new RecipeOverlayModifierDescriptor(id("add"), context -> context.put(probe.target, "value", probe.addition, 2)),
            new RecipeOverlayModifierDescriptor(id("multiply"), context -> context.put(probe.target, "value", multiply, 3))));
        assertEquals(39, effective(process, probe));
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign_after", probe.addition, 7).complete();
        assertEquals(46, effective(process, probe));
        transaction.rollback();
        assertEquals(18, effective(process, probe));
    }

    @Test
    void repeatedWritesToOneOwnedLayerReplaceItAndUndoItsEntireApplication() {
        var probe = new Probe("zz_overlay", 1, false);
        var process = process(1, probe.target);
        var transaction = RecipeOverlayPipeline.acquire(process, List.of(
            new RecipeOverlayModifierDescriptor(id("replace"), context -> {
                context.put(probe.target, "same", probe.addition, 2);
                context.put(probe.target, "same", probe.addition, 5);
            })));
        assertEquals(6, effective(process, probe));
        transaction.rollback();
        assertEquals(1, effective(process, probe));
    }

    @Test
    void applyKeepsLayersAfterCompletionAndDoesNotInstallThemIntoAnotherProcess() {
        var probe = new Probe("zz_overlay", 2, false);
        var process = process(1, probe.target);
        RecipeOverlayPipeline.apply(process, List.of(
            new RecipeOverlayModifierDescriptor(id("persistent"), context -> context.put(probe.target, "value", probe.addition, 4))));
        assertEquals(6, effective(process, probe));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(6, 6, 6), probe.amounts());
        assertEquals(6, effective(process, probe));
        assertEquals(2, effective(process(1, probe.target), probe));
        assertEquals(2, probe.target.payload());
    }

    @Test
    void partiallyThrowingApplicationUndoesAllTrackedWritesButPreservesDirectExternalLayers() {
        var probe = new Probe("zz_overlay", 1, false);
        var process = process(1, probe.target);
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign_before", probe.addition, 10).complete();
        var primary = new IllegalStateException("modifier failed");
        var saved = new ObjectArrayList<RecipeOverlayContext>();
        assertSame(primary, assertThrows(IllegalStateException.class, () -> RecipeOverlayPipeline.acquire(process, List.of(
            new RecipeOverlayModifierDescriptor(id("first"), context -> context.put(probe.target, "value", probe.addition, 2)),
            new RecipeOverlayModifierDescriptor(id("throwing"), context -> {
                saved.add(context);
                context.put(probe.target, "value", probe.addition, 3);
                context.put(probe.target, "value", probe.addition, 5);
                RecipeOverlays.getOrCreate(context.process()).put(probe.target, "external_direct", probe.addition, 7).complete();
                throw primary;
            })))));
        assertEquals(18, effective(process, probe));
        assertThrows(IllegalStateException.class, () -> saved.getFirst().put(probe.target, "late", probe.addition, 99));
    }

    @Test
    void explicitHostAcquisitionAffectsLaterInputsAndJoinsTheStageRollbackBoundary() {
        var probe = new Probe("zz_overlay", 2, true);
        boolean[] blocked = {true};
        var descriptors = List.of(new RecipeOverlayModifierDescriptor(id("host_modifier"),
            context -> context.put(probe.target, "amount", probe.addition, 3)));
        var host = new RecipeRequirementType<Integer>(new ResourceLocation("aa_overlay", "host"), new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                return RecipeOverlayPipeline.acquire(process, descriptors);
            }
        }).requirement(id("host"), 0);
        var later = new RecipeRequirementType<Integer>(new ResourceLocation("zzzz_overlay", "block"), new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                if (!blocked[0]) { return RequirementTransaction.SUCCESS; }
                return new BusinessDecision(new ProcessResult.Blocked("later"),
                    () -> { throw new AssertionError("Blocked requirement cannot commit"); });
            }
        }).requirement(id("block"), 0);
        var process = process(1, host, probe.target, later);
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign", probe.addition, 4).complete();
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(List.of(9), probe.amounts());
        assertEquals(20, probe.input.availableAmount(MATERIAL));
        assertEquals(6, effective(process, probe));
        blocked[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(9, 9, 9, 9), probe.amounts());
        assertEquals(11, probe.input.availableAmount(MATERIAL));
        assertEquals(9, probe.output.availableAmount(MATERIAL));
        assertEquals(9, effective(process, probe));
    }

    @Test
    void failedCompensationStillAttemptsOtherTargetsAndPreservesTheApplicationFault() {
        var good = new Probe("aa_overlay", 1, false);
        var bad = new Probe("zz_overlay", 1, false);
        var process = process(1, good.target, bad.target);
        var primary = new IllegalStateException("application failed");
        var cleanup = new IllegalStateException("foreign applier violated cleanup contract");
        boolean[] fail = {false};
        var unstable = new RecipeOverlayType<Integer, Integer>(id("unstable"), bad.type, (base, amount) -> {
            if (fail[0]) { throw cleanup; }
            return base + amount;
        });
        RecipeOverlays.getOrCreate(process).put(bad.target, "foreign", unstable, 10).complete();
        assertSame(primary, assertThrows(IllegalStateException.class, () -> RecipeOverlayPipeline.acquire(process, List.of(
            new RecipeOverlayModifierDescriptor(id("good"), context -> context.put(good.target, "value", good.addition, 2)),
            new RecipeOverlayModifierDescriptor(id("bad"), context -> {
                context.put(bad.target, "value", bad.addition, 3);
                fail[0] = true;
                throw primary;
            })))));
        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
        assertEquals(1, effective(process, good));
    }

    @Test
    void selectorCommitCannotChangeAcquiredStartInputsButTickAndEndUseTheOverlay() {
        var probe = new Probe("zz_overlay", 2, true);
        var saved = new ObjectArrayList<SelectiveContext>();
        var selection = selector("choice", List.of(noOpCandidate()), List.of(
            new SelectiveModifierDescriptor(id("change_io"), context -> {
                saved.add(context);
                context.putRequirementOverlay(probe.target, "amount", probe.addition, 3);
            })));
        var process = process(1, selection, probe.target);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(2, 5, 5), probe.amounts());
        assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), probe.stages());
        assertEquals(18, probe.input.availableAmount(MATERIAL));
        assertEquals(5, probe.output.availableAmount(MATERIAL));
        assertEquals(2, effective(process, probe));
        assertThrows(IllegalStateException.class, () -> saved.getFirst().putRequirementOverlay(probe.target, "late", probe.addition, 99));
    }

    @Test
    void blockedEndKeepsSelectedLayersAndEndCompletionPreservesBothForeignLayerOrders() {
        var probe = new Probe("zz_overlay", 2, true);
        probe.blockEnd = true;
        var selection = selector("choice", List.of(noOpCandidate()), List.of(
            new SelectiveModifierDescriptor(id("change_io"), context -> context.putRequirementOverlay(probe.target, "amount", probe.addition, 3))));
        var process = process(1, selection, probe.target);
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign_before", probe.addition, 4).complete();
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(List.of(6, 9, 9), probe.amounts());
        assertEquals(14, probe.input.availableAmount(MATERIAL));
        assertEquals(0, probe.output.availableAmount(MATERIAL));
        assertEquals(9, effective(process, probe));
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign_after", probe.addition, 7).complete();
        probe.blockEnd = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(6, 9, 9, 16), probe.amounts());
        assertEquals(16, probe.output.availableAmount(MATERIAL));
        assertEquals(14, probe.input.availableAmount(MATERIAL));
        assertEquals(13, effective(process, probe));
    }

    @Test
    void laterEndCommitFailureRefundsOutputKeepsTheSelectedOverlayAndRejectsRetry() {
        var probe = new Probe("zz_overlay", 2, true);
        var primary = new IllegalStateException("later END failed");
        boolean[] fail = {true};
        var later = new RecipeRequirementType<Integer>(new ResourceLocation("zzzz_overlay", "fault"), new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                return new BusinessDecision(ProcessResult.SUCCESS, () -> { if (fail[0]) { throw primary; } });
            }
        }).requirement(id("later"), 0);
        var selection = selector("choice", List.of(noOpCandidate()), List.of(
            new SelectiveModifierDescriptor(id("change_io"), context -> context.putRequirementOverlay(probe.target, "amount", probe.addition, 3))));
        var process = process(1, selection, probe.target, later);
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(18, probe.input.availableAmount(MATERIAL));
        assertEquals(0, probe.output.availableAmount(MATERIAL));
        assertEquals(5, effective(process, probe));
        var faulted = process.state();
        int visits = probe.observations.size();
        fail[0] = false;
        assertThrows(IllegalStateException.class, process::tick);
        assertSame(faulted, process.state());
        assertEquals(visits, probe.observations.size());
        assertEquals(18, probe.input.availableAmount(MATERIAL));
        assertEquals(0, probe.output.availableAmount(MATERIAL));
        assertEquals(List.of(2, 5, 5), probe.amounts());
        assertEquals(5, effective(process, probe));
    }

    @Test
    void selectorPartialApplyFailureUndoesItsAttributesLayersAndInputsWithoutRestoringForeignState() {
        var probe = new Probe("zz_overlay", 2, true);
        var primary = new IllegalStateException("selector modifier failed");
        var selection = selector("choice", List.of(noOpCandidate()), List.of(
            new SelectiveModifierDescriptor(id("throwing"), context -> {
                context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 4, AttributeModifier.Operation.ADDITION));
                context.putRequirementOverlay(probe.target, "amount", probe.addition, 3);
                context.putRequirementOverlay(probe.target, "amount", probe.addition, 5);
                RecipeOverlays.getOrCreate(context.process()).put(probe.target, "external_direct", probe.addition, 7).complete();
                throw primary;
            })));
        var process = process(2, selection, probe.target);
        RecipeOverlays.getOrCreate(process).put(probe.target, "foreign_before", probe.addition, 4).complete();
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertNull(Objects.requireNonNull(process.components().get(SelectiveState.TYPE)).selection(selection.path(null)));
        assertEquals(20, probe.input.availableAmount(MATERIAL));
        assertEquals(13, effective(process, probe));
        assertEquals(1, process.attributes().value(SCORE));
    }

    @Test
    void reusedNestedSelectorOwnsIndependentPathsAndRetainsAnUnrelatedLayerAtOnlyOnePath() {
        var probe = new Probe("zz_overlay", 2, true);
        var inner = selector("inner", List.of(probe.target), List.of(
            new SelectiveModifierDescriptor(id("nested_io"), context -> context.putRequirementOverlay(
                probe.target, probe.target.path(context.selectionPath()), "amount", probe.addition, 3))));
        var first = selector("first", List.of(inner), List.of());
        var second = selector("second", List.of(inner), List.of());
        var firstPath = probe.target.path(inner.path(first.path(null)));
        var secondPath = probe.target.path(inner.path(second.path(null)));
        var process = process(2, first, second);
        RecipeOverlays.getOrCreate(process).put(probe.target, firstPath, "foreign", probe.addition, 4).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        var overlays = RecipeOverlays.getOrCreate(process);
        assertEquals(9, overlays.resolve(probe.target, firstPath).payload());
        assertEquals(5, overlays.resolve(probe.target, secondPath).payload());
        assertEquals(12, probe.input.availableAmount(MATERIAL));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(6, 9, 9, 9), probe.amounts(firstPath));
        assertEquals(List.of(2, 5, 5, 5), probe.amounts(secondPath));
        assertEquals(14, probe.output.availableAmount(MATERIAL));
        assertEquals(6, overlays.resolve(probe.target, firstPath).payload());
        assertEquals(2, overlays.resolve(probe.target, secondPath).payload());
    }

    @Test
    void childCompletionFaultStillCleansSelectionAttributesAndOverlayWithoutReplayingEnd() {
        var probe = new Probe("zz_overlay", 2, true);
        var primary = new IllegalStateException("child END completion failed");
        probe.endCompletion = () -> { throw primary; };
        var selection = selector("choice", List.of(probe.target), List.of(
            new SelectiveModifierDescriptor(id("child_io"), context -> {
                context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 4, AttributeModifier.Operation.ADDITION));
                context.putRequirementOverlay(probe.target, probe.target.path(context.selectionPath()), "amount", probe.addition, 3);
            })));
        var process = process(1, selection);
        var path = probe.target.path(selection.path(null));
        RecipeOverlays.getOrCreate(process).put(probe.target, path, "foreign", probe.addition, 4).complete();
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(14, probe.input.availableAmount(MATERIAL));
        assertEquals(9, probe.output.availableAmount(MATERIAL));
        assertEquals(6, RecipeOverlays.getOrCreate(process).resolve(probe.target, path).payload());
        assertEquals(1, process.attributes().value(SCORE));
        var state = Objects.requireNonNull(process.components().get(SelectiveState.TYPE));
        assertTrue(Objects.requireNonNull(state.selection(selection.path(null))).completed());
        int visits = probe.observations.size();
        var faulted = process.state();
        assertThrows(IllegalStateException.class, process::tick);
        assertSame(faulted, process.state());
        assertEquals(visits, probe.observations.size());
        assertEquals(14, probe.input.availableAmount(MATERIAL));
        assertEquals(9, probe.output.availableAmount(MATERIAL));
        assertEquals(6, RecipeOverlays.getOrCreate(process).resolve(probe.target, path).payload());
        assertEquals(1, process.attributes().value(SCORE));
        assertTrue(Objects.requireNonNull(state.selection(selection.path(null))).completed());
    }

    private static int effective(RecipeProcess process, Probe probe) {
        return RecipeOverlays.getOrCreate(process).resolve(probe.target).payload();
    }

    private static RecipeRequirement<Integer> noOpCandidate() {
        return new RecipeRequirementType<Integer>(id("noop"), new RecipeRequirementSystem<>() { }).requirement(id("candidate"), 0);
    }

    private static RecipeRequirement<SelectiveRequirement> selector(String name, List<RecipeRequirement<?>> candidates,
                                                                   List<SelectiveModifierDescriptor> modifiers) {
        return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(candidates, modifiers));
    }

    private static RecipeProcess process(int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 17);
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("overlay_modifier_test", name); }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(KEY_TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, NO_OP);
    }

    private record Observation(RequirementPath path, RecipeStage stage, int amount) { }

    private record BusinessDecision(ProcessResult result, Runnable action) implements RequirementTransaction {
        @Override public void commit() { action.run(); }
        @Override public void rollback() { }
    }

    private static final class Probe {
        private final RecipeRequirementType<Integer> type;
        private final RecipeRequirement<Integer> target;
        private final RecipeOverlayType<Integer, Integer> addition;
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final ObjectArrayList<Observation> observations = new ObjectArrayList<>();
        private boolean blockEnd;
        private Runnable endCompletion = NO_OP;

        private Probe(String namespace, int base, boolean transfer) {
            input.insert(MATERIAL, 20, Actionable.MODULATE);
            type = new RecipeRequirementType<>(new ResourceLocation(namespace, "probe"), new RecipeRequirementSystem<>() {
                @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                    return acquire(process, requirement, RecipeStage.START, transfer);
                }
                @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                    return acquire(process, requirement, RecipeStage.TICK, transfer);
                }
                @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                    return acquire(process, requirement, RecipeStage.END, transfer);
                }
            });
            target = type.requirement(new ResourceLocation(namespace, "target"), base);
            addition = new RecipeOverlayType<>(id("addition"), type, Integer::sum);
        }

        private RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<Integer> requirement, RecipeStage stage, boolean transfer) {
            var path = process.requirementPath();
            int amount = requirement.payload();
            observations.add(new Observation(path, stage, amount));
            var tokens = new ObjectArrayList<ResourceReservation>();
            if (transfer && stage != RecipeStage.TICK) {
                var reservation = stage == RecipeStage.START ? input.reserveExtract(input.snapshot(), MATERIAL, amount)
                    : output.reserveInsert(output.snapshot(), MATERIAL, amount);
                assertEquals(ReservationResult.Status.ACCEPTED, reservation.status());
                tokens.add(Objects.requireNonNull(reservation.reservation()));
            }
            ProcessResult result = stage == RecipeStage.END && blockEnd ? new ProcessResult.Blocked("output") : ProcessResult.SUCCESS;
            return new RequirementTransaction() {
                @Override public ProcessResult result() { checkScope(); return result; }
                @Override public List<ResourceReservation> reservations() { checkScope(); return tokens; }
                @Override public void commit() { checkScope(); }
                @Override public void complete() { checkScope(); if (stage == RecipeStage.END) { endCompletion.run(); } }
                @Override public void rollback() { checkScope(); }
                private void checkScope() { assertEquals(path, process.requirementPath()); }
            };
        }

        private List<Integer> amounts() { return observations.stream().map(Observation::amount).toList(); }
        private List<RecipeStage> stages() { return observations.stream().map(Observation::stage).toList(); }
        private List<Integer> amounts(RequirementPath path) {
            return observations.stream().filter(observation -> observation.path().equals(path)).map(Observation::amount).toList();
        }
    }
}
