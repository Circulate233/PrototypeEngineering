package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
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
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePrecommitTest {
    private static boolean settleStart(RecipeProcess process, boolean deferred) {
        var session = Objects.requireNonNull(process.beginTick());
        var stage = Objects.requireNonNull(session.nextStage());
        assertEquals(RecipeStage.START, stage.phase());
        stage.acquire();
        boolean accepted;
        if (deferred) {
            var batch = Objects.requireNonNull(RecipeProcess.Stage.tryAcquireBatch(List.of(stage)));
            accepted = false;
            while (batch.decision() == ResourceBatch.Decision.OPEN) {
                for (var source : batch.sources()) {
                    if (source.state() == ResourceBatch.SourceState.RESERVED) {
                        assertTrue(source.apply());
                    }
                }
                if (!stage.hasContinuation()) {
                    accepted = batch.commit(() -> true);
                    break;
                }
                if (!batch.stage(() -> true)) {
                    break;
                }
                if (!RecipeProcess.Stage.acquireNextBatch(List.of(stage), batch)) {
                    break;
                }
            }
            if (!accepted) {
                assertTrue(batch.rollback());
            }
            for (var source : batch.sources()) {
                assertTrue(source.resolve());
            }
            batch.finish();
            assertTrue(stage.settleBatch(null));
        } else {
            accepted = stage.settleSynchronously() == ProcessResult.SUCCESS;
        }
        session.finish();
        assertTrue(process.quiescent());
        return accepted;
    }

    private static void assertScope(RecipeProcess process, RequirementPath path, @Nullable RecipeProcess.Tick tick) {
        assertEquals(path, process.requirementPath());
        assertNotNull(Objects.requireNonNull(path.parent()).parent());
        if (tick == null) {
            assertNull(process.tickContext());
        } else {
            assertEquals(new RecipeProcess.Tick(0, 0, 0, 0), tick);
            assertSame(tick, process.tickContext());
        }
    }

    private static RecipeRequirement<String> observer(String name,
                                                      BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
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
        };
        return new RecipeRequirementType<>(id(name), system).requirement(id(name), "value");
    }

    private static RecipeProcess process(RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 2, List.of(requirements)), 17);
    }

    private static EnergyBuffer energy() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.receiveEnergy(100, Actionable.MODULATE);
        return energy;
    }

    private static ResourceLocation id(String value) {
        return new ResourceLocation("precommit_test", value);
    }

    @Test
    void rejectionRestoresPreparedEffectsAndAppliedEnergyWithoutOrdinaryCommits() {
        for (boolean deferred : List.of(false, true)) {
            var energy = energy();
            var events = new ObjectArrayList<String>();
            var first = new Effect("first", events, true);
            var denied = new Effect("denied", events, false);
            var later = new Effect("later", events, true);
            first.tokens = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 20).reservation()));
            var process = process(observer("a", (_, _) -> first), observer("b", (_, _) -> denied),
                observer("c", (_, _) -> later));
            assertFalse(settleStart(process, deferred));
            assertEquals(List.of("prepare:first", "prepare:denied", "rollback:later", "rollback:denied", "rollback:first"), events);
            assertEquals(0, first.cost + denied.cost + later.cost);
            assertEquals(100, energy.savedEnergy());
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertInstanceOf(ProcessResult.Blocked.class, process.state().result());
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void successfulPreparationPrecedesEveryBusinessCommitAndKeepsItsCost() {
        for (boolean deferred : List.of(false, true)) {
            var energy = energy();
            var events = new ObjectArrayList<String>();
            var first = new Effect("first", events, true);
            var second = new Effect("second", events, true);
            first.tokens = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 20).reservation()));
            var process = process(observer("a", (_, _) -> first), observer("b", (_, _) -> second));
            assertTrue(settleStart(process, deferred));
            assertEquals(List.of("prepare:first", "prepare:second", "commit:first", "commit:second",
                "complete:first", "complete:second"), events);
            assertEquals(2, first.cost + second.cost);
            assertEquals(80, energy.savedEnergy());
            assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void nestedCheckpointAndSelectionKeepPathsAndFrozenTicksThroughRejection() {
        for (boolean deferred : List.of(false, true)) {
            var events = new ObjectArrayList<String>();
            var effects = new ObjectArrayList<Effect>();
            var leaf = observer("leaf", (current, phase) -> {
                var path = current.requirementPath();
                RecipeProcess.Tick tick = phase == RecipeStage.TICK ? current.tickContext() : null;
                var effect = new Effect(phase.name(), events, phase != RecipeStage.TICK) {
                    @Override
                    public boolean prepareCommit() {
                        assertScope(current, path, tick);
                        return super.prepareCommit();
                    }

                    @Override
                    public void rollback() {
                        assertScope(current, path, tick);
                        super.rollback();
                    }
                };
                effects.add(effect);
                return effect;
            });
            var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"),
                new SelectiveRequirement(List.of(leaf), List.of(new SelectiveModifierDescriptor(id("modifier"),
                    context -> context.putProcessAttributeModifier(id("staged_modifier"),
                        new NumericAttributeModifier("bonus", 3, AttributeModifier.Operation.ADDITION))))));
            var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(0, selection));
            var process = process(checkpoint);
            assertFalse(settleStart(process, deferred));
            assertEquals(List.of("prepare:START", "commit:START", "prepare:TICK", "rollback:TICK", "rollback:START"), events);
            assertEquals(2, effects.size());
            assertEquals(1, process.attributes().value(id("staged_modifier")));
            assertEquals(0, effects.stream().mapToInt(effect -> effect.cost).sum());
            assertNull(SelectiveState.getOrCreate(process).selection(selection.path(checkpoint.path(null))));
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void preparationFailureKeepsExactCleanupAndCannotReplayItsPartialEffects() {
        var events = new ObjectArrayList<String>();
        var first = new Effect("first", events, true);
        var failure = new IllegalStateException("prepared then failed");
        Effect failing = new Effect("failing", events, true) {
            @Override
            public boolean prepareCommit() {
                super.prepareCommit();
                throw failure;
            }
        };
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of(first, failing)));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertEquals(ResourceBatch.Decision.ROLLBACK, batch.decision());
        assertThrows(IllegalStateException.class, () -> batch.commit(() -> true));
        assertTrue(batch.rollback());
        batch.finish();
        assertEquals(List.of("prepare:first", "prepare:failing", "rollback:failing", "rollback:first"), events);
        assertEquals(0, first.cost + failing.cost);
    }

    @Test
    void scopedPreparationRejectsReentryAndAllowsOnlyItsOriginalRollback() {
        var events = new ObjectArrayList<String>();
        var process = process(observer("scoped", (_, _) -> RequirementTransaction.SUCCESS));
        RequirementTransaction[] captured = {null};
        var requirement = observer("captured", (_, _) -> new Effect("captured", events, false) {
            @Override
            public boolean prepareCommit() {
                var transaction = Objects.requireNonNull(captured[0]);
                assertThrows(IllegalStateException.class, transaction::prepareCommit);
                assertThrows(IllegalStateException.class, transaction::commit);
                assertThrows(IllegalStateException.class, transaction::rollback);
                assertThrows(IllegalStateException.class, transaction::resolution);
                return super.prepareCommit();
            }
        });
        captured[0] = requirement.acquire(process, RecipeStage.START);
        assertFalse(captured[0].prepareCommit());
        assertThrows(IllegalStateException.class, captured[0]::prepareCommit);
        assertThrows(IllegalStateException.class, captured[0]::commit);
        captured[0].rollback();
        assertEquals(List.of("prepare:captured", "rollback:captured"), events);
        process.requirePersistenceBoundary();
    }

    @Test
    void failedSynchronousPreparationRetainsUncertainCleanupWithoutReplayingPreparation() {
        var events = new ObjectArrayList<String>();
        var failure = new IllegalStateException("prepared then failed");
        var effect = new RecoveringEffect(events, failure);
        var process = process(observer("recovering", (_, _) -> effect));
        assertSame(failure, assertThrows(IllegalStateException.class, process::tick));
        var session = Objects.requireNonNull(process.pendingTick());
        var stage = Objects.requireNonNull(session.currentStage());
        assertFalse(stage.settled());
        assertFalse(stage.resolveSynchronously());
        effect.recoveryAllowed = true;
        assertTrue(stage.resolveSynchronously());
        assertTrue(stage.settled());
        session.finish();
        assertEquals(List.of("prepare:recovering", "rollback:recovering"), events);
        assertEquals(2, effect.rollbackAttempts);
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertTrue(process.quiescent());
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary);
    }

    @Test
    void postCommitRejectionCompensatesReceiptsEnergyAndProcessProgress() {
        for (boolean deferred : List.of(false, true)) {
            var energy = energy();
            var events = new ObjectArrayList<String>();
            boolean[] authorized = {true};
            Effect receipt = new Effect("receipt", events, true) {
                @Override
                public boolean validateCommit() {
                    return authorized[0];
                }
            };
            var mutation = new Effect("mutation", events, true) {
                @Override
                public void commit() {
                    super.commit();
                    authorized[0] = false;
                }

                @Override
                public void rollback() {
                    super.rollback();
                    authorized[0] = true;
                }
            };
            receipt.tokens = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 20).reservation()));
            var process = process(observer("a", (_, _) -> receipt), observer("b", (_, _) -> mutation));
            assertFalse(settleStart(process, deferred));
            assertEquals(List.of("prepare:receipt", "prepare:mutation", "commit:receipt", "commit:mutation",
                "rollback:mutation", "rollback:receipt"), events);
            assertEquals(100, energy.savedEnergy());
            assertEquals(0, receipt.cost);
            assertTrue(authorized[0]);
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            process.requirePersistenceBoundary();
        }
    }

    @Test
    void nestedValidationKeepsCapturedScopeAndReversesCommittedSelectorModifiers() {
        for (boolean deferred : List.of(false, true)) {
            var events = new ObjectArrayList<String>();
            var leaf = observer("leaf", (current, phase) -> {
                var path = current.requirementPath();
                RecipeProcess.Tick tick = phase == RecipeStage.TICK ? current.tickContext() : null;
                return new Effect(phase.name(), events, true) {
                    @Override
                    public boolean validateCommit() {
                        assertScope(current, path, tick);
                        events.add("validate:" + phase);
                        return phase != RecipeStage.TICK;
                    }
                };
            });
            var score = id("score");
            var selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(leaf),
                List.of(new SelectiveModifierDescriptor(id("modifier"), context -> context.putProcessAttributeModifier(score,
                    new NumericAttributeModifier("bonus", 3, AttributeModifier.Operation.ADDITION))))));
            var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(0, selection));
            var process = process(checkpoint);
            assertFalse(settleStart(process, deferred));
            assertEquals(List.of("prepare:START", "commit:START", "prepare:TICK", "commit:TICK", "prepare:END", "commit:END",
                "validate:START", "validate:TICK", "rollback:END", "rollback:TICK", "rollback:START"), events);
            assertEquals(1, process.attributes().value(score));
            assertNull(SelectiveState.getOrCreate(process).selection(selection.path(checkpoint.path(null))));
            process.requirePersistenceBoundary();
        }
    }

    private static class Effect implements RequirementTransaction {
        private final String name;
        private final List<String> events;
        private final boolean accepted;
        private List<ResourceReservation> tokens = List.of();
        private Resolution resolution = Resolution.REVERSIBLE;
        private int cost;

        private Effect(String name, List<String> events, boolean accepted) {
            this.name = name;
            this.events = events;
            this.accepted = accepted;
        }

        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public List<ResourceReservation> reservations() {
            return tokens;
        }

        @Override
        public boolean prepareCommit() {
            events.add("prepare:" + name);
            cost++;
            return accepted;
        }

        @Override
        public void commit() {
            events.add("commit:" + name);
        }

        @Override
        public void complete() {
            events.add("complete:" + name);
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            events.add("rollback:" + name);
            cost = 0;
            resolution = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }
    }

    private static final class RecoveringEffect extends Effect {
        private final IllegalStateException failure;
        private int rollbackAttempts;
        private boolean recoveryAllowed;
        private boolean rolledBack;

        private RecoveringEffect(List<String> events, IllegalStateException failure) {
            super("recovering", events, true);
            this.failure = failure;
        }

        @Override
        public boolean prepareCommit() {
            super.prepareCommit();
            throw failure;
        }

        @Override
        public void rollback() {
            rollbackAttempts++;
            if (!recoveryAllowed) {
                throw new IllegalStateException("uncertain rollback");
            }
            super.rollback();
            rolledBack = true;
        }

        @Override
        public Resolution resolution() {
            return rolledBack ? Resolution.ROLLED_BACK : recoveryAllowed ? Resolution.REVERSIBLE : Resolution.UNKNOWN;
        }
    }
}
