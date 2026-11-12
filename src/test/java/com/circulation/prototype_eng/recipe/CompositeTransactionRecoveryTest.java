package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
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
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeTransactionRecoveryTest {
    private static final RecipeProcess.Tick TICK = new RecipeProcess.Tick(4, 6, 2, 3);
    private static final ResourceLocation SCORE = id("score");
    private static final RecipeRequirementType<Integer> NUMBER = new RecipeRequirementType<>(id("number"), new RecipeRequirementSystem<>() {
    });
    private static final RecipeOverlayType<Integer, Integer> ADD = new RecipeOverlayType<>(id("add"), NUMBER, Math::addExact);

    private static ResourceBatch checkpointBatch(RecipeProcess process) {
        var acquisition = RecipeStageExecutor.prepare(process, RecipeStage.TICK, RequirementTransaction.SUCCESS);
        process.withTickContext(TICK, () -> {
            acquisition.acquire();
            return null;
        });
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquireSequential(process.declaredStorageContexts(),
            acquisition.reservations(), acquisition.transactions()));
        while (acquisition.hasContinuation()) {
            for (var source : batch.sources()) {
                if (source.state() == ResourceBatch.SourceState.RESERVED) {
                    assertTrue(source.apply());
                }
            }
            assertTrue(batch.stage(() -> true));
            batch.acquireNext(acquisition::acquireNext);
            batch.append(acquisition.stepReservations(), acquisition.stepTransactions());
        }
        return batch;
    }

    private static ResourceBatch batch(RequirementTransaction transaction) {
        return Objects.requireNonNull(ResourceBatch.tryAcquire(transaction.reservations(), List.of(transaction)));
    }

    private static RecipeProcess process(RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 10, List.of(requirements)), 17);
    }

    private static NumericAttributeModifier add(String name, double value) {
        return new NumericAttributeModifier(name, value, AttributeModifier.Operation.ADDITION);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("composite_recovery", name);
    }

    @Test
    void checkpointRetriesOnlyItsUnconfirmedChildAndRestoresEveryFrozenScope() {
        var effects = new Effects(TICK);
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(5, effects.requirement()));
        var process = process(checkpoint);
        var batch = checkpointBatch(process);
        var tick = effects.at(RecipeStage.TICK);
        tick.failBefore = true;
        assertSame(tick.failure, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertThrows(IllegalStateException.class, process::requireQuiescent);
        assertFalse(batch.resolveBusiness());
        tick.failBefore = false;
        tick.proof = true;
        assertTrue(process.withTickContext(new RecipeProcess.Tick(70, 71, 40, 41), batch::resolveBusiness));
        for (var stage : RecipeStage.VALUES) {
            assertEquals(1, effects.at(stage).commits);
            assertEquals(stage == RecipeStage.TICK ? 2 : 1, effects.at(stage).completions);
        }
        process.requireQuiescent();
        batch.finish();
        assertThrows(NullPointerException.class, process::tickContext);
    }

    @Test
    void checkpointConfirmsAfterMutationFaultsWithoutReplayingAnyChildOrChangingDirection() {
        for (boolean complete : List.of(false, true)) {
            var effects = new Effects(TICK);
            var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(5, effects.requirement()));
            var process = process(checkpoint);
            var batch = checkpointBatch(process);
            var tick = effects.at(RecipeStage.TICK);
            tick.proof = true;
            tick.failAfter = true;
            assertSame(tick.failure, assertThrows(IllegalStateException.class, () -> {
                if (complete) {
                    batch.commit(() -> true);
                } else {
                    batch.rollback();
                }
            }));
            process.requireQuiescent();
            assertTrue(batch.resolveBusiness());
            for (var stage : RecipeStage.VALUES) {
                assertEquals(complete ? 1 : 0, effects.at(stage).completions);
                assertEquals(complete ? 0 : 1, effects.at(stage).rollbacks);
            }
            if (complete) {
                assertThrows(IllegalStateException.class, batch::rollback);
            } else {
                assertThrows(IllegalStateException.class, () -> batch.commit(() -> true));
            }
            batch.finish();
        }
    }

    @Test
    void selectorStartAndEndKeepTheirOwnEffectsWhileRecoveringTheExactChild() {
        var selection = new Selection();
        var start = selection.requirement.acquire(selection.process, RecipeStage.START);
        var childStart = selection.effects.at(RecipeStage.START);
        childStart.failBefore = true;
        var starting = batch(start);
        assertSame(childStart.failure, assertThrows(IllegalStateException.class, () -> starting.commit(() -> true)));
        assertFalse(starting.resolveBusiness());
        assertEquals(7, selection.process.attributes().value(SCORE));
        assertEquals(17, selection.overlays.resolve(selection.target).payload());
        childStart.failBefore = false;
        childStart.proof = true;
        assertTrue(starting.resolveBusiness());
        starting.finish();
        selection.process.requireQuiescent();
        assertEquals(1, childStart.commits);
        assertEquals(2, childStart.completions);
        assertEquals(1, selection.decision().snapshot().overlays().size());

        var end = selection.requirement.acquire(selection.process, RecipeStage.END);
        var childEnd = selection.effects.at(RecipeStage.END);
        childEnd.proof = true;
        childEnd.failAfter = true;
        var ending = batch(end);
        assertSame(childEnd.failure, assertThrows(IllegalStateException.class, () -> ending.commit(() -> true)));
        assertTrue(ending.resolveBusiness());
        assertEquals(1, childEnd.completions);
        assertTrue(selection.decision().completed());
        assertEquals(3, selection.process.attributes().value(SCORE));
        assertEquals(13, selection.overlays.resolve(selection.target).payload());
        selection.process.requireQuiescent();
        ending.finish();
    }

    @Test
    void selectorRollbackRetriesTheChildWithoutReplayingItsCompletedEffectCleanup() {
        var selection = new Selection();
        var start = selection.requirement.acquire(selection.process, RecipeStage.START);
        var child = selection.effects.at(RecipeStage.START);
        child.failBefore = true;
        var batch = batch(start);
        boolean[] authorized = {true};
        child.onCommit = () -> authorized[0] = false;
        assertFalse(batch.commit(() -> authorized[0]));
        assertSame(child.failure, assertThrows(IllegalStateException.class, batch::rollback));
        assertNull(selection.state().selection(selection.requirement.path(null)));
        assertEquals(3, selection.process.attributes().value(SCORE));
        assertEquals(13, selection.overlays.resolve(selection.target).payload());
        assertFalse(batch.resolveBusiness());
        child.failBefore = false;
        child.proof = true;
        assertTrue(batch.resolveBusiness());
        assertEquals(2, child.rollbacks);
        selection.process.requireQuiescent();
        batch.finish();
    }

    @Test
    void selectorOwnUnknownEndCleanupCannotBeHiddenByAConfirmedChild() {
        var selection = new Selection();
        var starting = batch(selection.requirement.acquire(selection.process, RecipeStage.START));
        assertTrue(starting.commit(() -> true));
        starting.finish();
        var reference = selection.decision().snapshot().overlays().getFirst();
        selection.overlays.put(selection.target, reference.path(), reference.layerId(), ADD, 99).complete();
        var ending = batch(selection.requirement.acquire(selection.process, RecipeStage.END));
        assertThrows(IllegalStateException.class, () -> ending.commit(() -> true));
        assertFalse(ending.resolveBusiness(), "The child's terminal proof cannot prove selection ownership cleanup");
        assertEquals(1, selection.effects.at(RecipeStage.END).completions);
        assertFalse(selection.decision().completed());
        assertEquals(112, selection.overlays.resolve(selection.target).payload());
        assertThrows(IllegalStateException.class, selection.process::requireQuiescent);
        assertThrows(IllegalStateException.class, ending::finish);
    }

    @Test
    void selectorOwnUnknownStartUndoRetainsPendingAndPreservesTheReplacingWriter() {
        var selection = new Selection();
        var start = selection.requirement.acquire(selection.process, RecipeStage.START);
        var child = selection.effects.at(RecipeStage.START);
        var batch = batch(start);
        boolean[] authorized = {true};
        child.onCommit = () -> authorized[0] = false;
        assertFalse(batch.commit(() -> authorized[0]));
        // Authorization is lost after reversible commit, with exact selector effects still installed.
        var reference = selection.decision().modifiers().getFirst();
        assertTrue(selection.process.attributes().removeModifier(reference.attribute(), reference.id()));
        assertTrue(selection.process.attributes().addModifier(reference.attribute(), add(reference.id(), 20)));
        assertThrows(IllegalStateException.class, batch::rollback);
        assertFalse(batch.resolveBusiness());
        assertEquals(1, child.rollbacks);
        assertEquals(23, selection.process.attributes().value(SCORE));
        assertEquals(13, selection.overlays.resolve(selection.target).payload());
        assertThrows(IllegalStateException.class, selection.process::requireQuiescent);
        assertThrows(IllegalStateException.class, batch::finish);
    }

    private static final class Selection {
        private final Effects effects = new Effects(null);
        private final RecipeRequirement<Integer> target = NUMBER.requirement(id("target"), 10);
        private final RecipeRequirement<SelectiveRequirement> requirement = RecipeRequirements.SELECTIVE.requirement(id("selector"),
            new SelectiveRequirement(List.of(effects.requirement()), List.of(new SelectiveModifierDescriptor(id("effects"), context -> {
                context.putProcessAttributeModifier(SCORE, add("selected", 4));
                context.putRequirementOverlay(target, target.path(null), "selected", ADD, 4);
            }))));
        private final RecipeProcess process = process(requirement, target);
        private final RecipeOverlays overlays = RecipeOverlays.getOrCreate(process);

        private Selection() {
            assertTrue(process.attributes().addModifier(SCORE, add("foreign", 2)));
            overlays.put(target, "foreign", ADD, 3).complete();
        }

        private SelectiveState state() {
            return Objects.requireNonNull(process.components().get(SelectiveState.TYPE));
        }

        private SelectiveState.Selection decision() {
            return Objects.requireNonNull(state().selection(requirement.path(null)));
        }
    }

    private static final class Effects {
        private final Object2ObjectOpenHashMap<RecipeStage, Effect> stages = new Object2ObjectOpenHashMap<>();
        @Nullable
        private final RecipeProcess.Tick tick;

        private Effects(@Nullable RecipeProcess.Tick tick) {
            this.tick = tick;
        }

        private RecipeRequirement<Effects> requirement() {
            var type = new RecipeRequirementType<>(id("effect"), new RecipeRequirementSystem<Effects>() {
                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Effects> requirement) {
                    return acquire(process, RecipeStage.START);
                }

                @Override
                public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Effects> requirement) {
                    return acquire(process, RecipeStage.TICK);
                }

                @Override
                public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Effects> requirement) {
                    return acquire(process, RecipeStage.END);
                }
            });
            return type.requirement(id("child"), this);
        }

        private Effect acquire(RecipeProcess process, RecipeStage stage) {
            var effect = new Effect(process, process.requirementPath(), tick);
            stages.put(stage, effect);
            return effect;
        }

        private Effect at(RecipeStage stage) {
            return Objects.requireNonNull(stages.get(stage));
        }
    }

    private static final class Effect implements RequirementTransaction {
        private final IllegalStateException failure = new IllegalStateException("terminal acknowledgement unavailable");
        private final RecipeProcess process;
        private final RequirementPath path;
        @Nullable
        private final RecipeProcess.Tick tick;
        private Runnable onCommit = () -> {
        };
        private boolean failBefore;
        private boolean failAfter;
        private boolean proof;
        private Resolution terminal = Resolution.REVERSIBLE;
        private int commits;
        private int completions;
        private int rollbacks;

        private Effect(RecipeProcess process, RequirementPath path, @Nullable RecipeProcess.Tick tick) {
            this.process = process;
            this.path = path;
            this.tick = tick;
        }

        private void scope() {
            assertEquals(path, process.requirementPath());
            if (tick == null) {
                assertThrows(NullPointerException.class, process::tickContext);
            } else {
                assertEquals(tick, process.tickContext());
            }
        }

        @Override
        public ProcessResult result() {
            scope();
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
            scope();
            commits++;
            onCommit.run();
        }

        @Override
        public void complete() {
            scope();
            completions++;
            finish(Resolution.COMPLETED);
        }

        @Override
        public void rollback() {
            scope();
            rollbacks++;
            finish(Resolution.ROLLED_BACK);
        }

        @Override
        public Resolution resolution() {
            scope();
            return proof ? terminal : Resolution.UNKNOWN;
        }

        private void finish(Resolution expected) {
            if (failBefore) {
                throw failure;
            }
            terminal = expected;
            if (failAfter) {
                throw failure;
            }
        }
    }
}
