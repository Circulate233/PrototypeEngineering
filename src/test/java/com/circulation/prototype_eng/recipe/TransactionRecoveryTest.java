package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionRecoveryTest {
    private static final RecipeProcess.Tick TICK = new RecipeProcess.Tick(4, 6, 2, 3);

    private static RecipeProcess process() {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), 10, List.of()), 17);
    }

    private static EnergyBuffer energy() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.receiveEnergy(100, Actionable.MODULATE);
        return energy;
    }

    private static RequirementTransaction acquire(RecipeProcess process, String name, Effect effect, boolean nested) {
        var type = new RecipeRequirementType<>(id("effect"), new RecipeRequirementSystem<Effect>() {
            @Override
            public RequirementTransaction tick(RecipeProcess owner, RecipeRequirement<Effect> requirement) {
                var value = requirement.payload();
                value.process = owner;
                value.path = owner.requirementPath();
                return value;
            }
        });
        var requirement = type.requirement(id(name), effect);
        return process.withTickContext(TICK, () -> nested
            ? process.acquireRequirement(type.requirement(id("parent"), effect), () -> requirement.acquire(process, RecipeStage.TICK))
            : requirement.acquire(process, RecipeStage.TICK));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("external_recovery", path);
    }

    @Test
    void unknownRollbackKeepsTheRealProcessAcquiredUntilExactProofAllowsRetry() {
        var process = process();
        var energy = energy();
        var first = new Effect();
        var second = new Effect();
        first.reservations = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation()));
        second.reservations = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 15).reservation()));
        second.failRollbackBefore = true;
        boolean[] authorized = {true};
        second.onCommit = () -> authorized[0] = false;
        var a = acquire(process, "first", first, false);
        var b = acquire(process, "second", second, false);
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(a.reservations().getFirst(), b.reservations().getFirst()), List.of(a, b)));
        assertTrue(batch.sources().getFirst().apply());
        assertFalse(batch.commit(() -> authorized[0]));
        assertSame(second.failure, assertThrows(IllegalStateException.class, batch::rollback));
        assertThrows(IllegalStateException.class, process::requireQuiescent);
        assertFalse(batch.resolveBusiness());
        assertTrue(batch.sources().getFirst().resolve());
        assertThrows(IllegalStateException.class, batch::finish);
        assertEquals(1, first.rollbacks);
        assertEquals(1, second.rollbacks);
        second.proof = true;
        second.failRollbackBefore = false;
        assertTrue(batch.resolveBusiness());
        assertEquals(1, first.rollbacks);
        assertEquals(2, second.rollbacks);
        batch.finish();
        process.requireQuiescent();
        assertThrows(IllegalStateException.class, process::requirePersistenceBoundary, "Recovery does not erase the original process fault");
        assertEquals(100, energy.savedEnergy());
        assertFalse(first.applied);
        assertFalse(second.applied);
        assertNull(process.tickContext(), "Recovery must restore the enclosing timeline scope");
    }

    @Test
    void confirmedRollbackAfterMutationClosesTheAcquisitionWithoutReplayingItsCallback() {
        var process = process();
        var first = new Effect();
        var second = new Effect();
        second.proof = true;
        second.failRollbackAfter = true;
        var a = acquire(process, "first", first, false);
        var b = acquire(process, "second", second, false);
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of(a, b)));
        assertSame(second.failure, assertThrows(IllegalStateException.class, batch::rollback));
        process.requireQuiescent();
        assertTrue(batch.resolveBusiness());
        assertEquals(1, first.rollbacks);
        assertEquals(1, second.rollbacks);
        batch.finish();
        assertThrows(IllegalStateException.class, b::complete, "A confirmed rollback cannot change direction");
    }

    @Test
    void completionRecoveryRetainsNestedRequirementPathsAndTheOriginalFrozenTick() {
        var process = process();
        var energy = energy();
        var first = new Effect();
        var second = new Effect();
        first.reservations = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 10).reservation()));
        second.reservations = List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 15).reservation()));
        second.failCompleteBefore = true;
        var a = acquire(process, "first", first, false);
        var b = acquire(process, "nested", second, true);
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(a.reservations().getFirst(), b.reservations().getFirst()), List.of(a, b)));
        assertTrue(batch.sources().getFirst().apply());
        assertSame(second.failure, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertThrows(IllegalStateException.class, batch::rollback);
        assertThrows(IllegalStateException.class, process::requireQuiescent);
        assertFalse(batch.resolveBusiness());
        assertTrue(batch.sources().getFirst().resolve());
        assertThrows(IllegalStateException.class, batch::finish);
        second.failCompleteBefore = false;
        second.proof = true;
        assertTrue(process.withTickContext(new RecipeProcess.Tick(50, 51, 30, 31), batch::resolveBusiness));
        assertEquals(1, first.commits);
        assertEquals(1, second.commits);
        assertEquals(1, first.completions);
        assertEquals(2, second.completions);
        assertEquals(0, first.rollbacks + second.rollbacks);
        batch.finish();
        process.requireQuiescent();
        assertEquals(75, energy.savedEnergy());
        assertTrue(second.completed);
        assertNotNull(second.path.parent());
    }

    @Test
    void aQueryFailurePreservesTheExactAcquisitionUntilItsTerminalStateCanBeConfirmed() {
        var process = process();
        var effect = new Effect();
        effect.proof = true;
        effect.failRollbackAfter = true;
        effect.queryFailure = new IllegalArgumentException("query unavailable");
        var transaction = acquire(process, "query", effect, true);
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of(transaction)));
        var failure = assertThrows(IllegalStateException.class, batch::rollback);
        assertSame(effect.failure, failure);
        assertTrue(List.of(failure.getSuppressed()).contains(effect.queryFailure));
        assertThrows(IllegalStateException.class, process::requireQuiescent);
        assertSame(effect.queryFailure, assertThrows(IllegalArgumentException.class, batch::resolveBusiness));
        assertThrows(IllegalStateException.class, batch::finish);
        effect.queryFailure = null;
        assertTrue(batch.resolveBusiness());
        assertEquals(1, effect.rollbacks);
        process.requireQuiescent();
        batch.finish();
    }

    @Test
    void aCompletionFaultAfterItsEffectStillCompletesEveryOtherTransactionAndNeverReplaysCommit() {
        var process = process();
        var first = new Effect();
        var second = new Effect();
        first.proof = true;
        first.failCompleteAfter = true;
        var a = acquire(process, "first", first, false);
        var b = acquire(process, "second", second, false);
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of(a, b)));
        assertSame(first.failure, assertThrows(IllegalStateException.class, () -> batch.commit(() -> true)));
        assertEquals(1, first.completions);
        assertEquals(1, second.completions);
        assertTrue(batch.resolveBusiness());
        assertThrows(IllegalStateException.class, () -> batch.commit(() -> true));
        assertThrows(IllegalStateException.class, a::commit);
        assertEquals(1, first.commits);
        assertEquals(1, second.commits);
        process.requireQuiescent();
        batch.finish();
    }

    @Test
    void terminalListenersCannotReenterTheSameAcquisitionAndReplayItsEffects() {
        var process = process();
        var effect = new Effect();
        var transaction = acquire(process, "reentrant", effect, false);
        effect.onRollback = () -> {
            assertThrows(IllegalStateException.class, transaction::rollback);
            assertThrows(IllegalStateException.class, transaction::resolution);
        };
        effect.onQuery = () -> {
            assertThrows(IllegalStateException.class, transaction::resolution);
            assertThrows(IllegalStateException.class, transaction::rollback);
            assertThrows(IllegalStateException.class, transaction::commit);
        };
        assertEquals(RequirementTransaction.Resolution.UNKNOWN, transaction.resolution());
        var batch = Objects.requireNonNull(ResourceBatch.tryAcquire(List.of(), List.of(transaction)));
        assertTrue(batch.rollback());
        assertEquals(1, effect.rollbacks);
        process.requireQuiescent();
        batch.finish();
    }

    private static final class Effect implements RequirementTransaction {
        private final IllegalStateException failure = new IllegalStateException("terminal acknowledgement failed");
        private RecipeProcess process;
        private RequirementPath path;
        private List<ResourceReservation> reservations = List.of();
        private Runnable onCommit = () -> {
        };
        private Runnable onRollback = () -> {
        };
        private Runnable onQuery = () -> {
        };
        @Nullable
        private RuntimeException queryFailure;
        private boolean proof;
        private boolean failRollbackBefore;
        private boolean failRollbackAfter;
        private boolean failCompleteBefore;
        private boolean failCompleteAfter;
        private boolean applied;
        private boolean completed;
        private boolean rolledBack;
        private int commits;
        private int completions;
        private int rollbacks;

        private void scope() {
            assertEquals(TICK, process.tickContext());
            assertEquals(path, process.requirementPath());
        }

        @Override
        public ProcessResult result() {
            scope();
            return ProcessResult.SUCCESS;
        }

        @Override
        public List<? extends ResourceReservation> reservations() {
            scope();
            return reservations;
        }

        @Override
        public void commit() {
            scope();
            commits++;
            applied = true;
            onCommit.run();
        }

        @Override
        public void complete() {
            scope();
            completions++;
            if (failCompleteBefore) {
                throw failure;
            }
            completed = true;
            if (failCompleteAfter) {
                throw failure;
            }
        }

        @Override
        public void rollback() {
            scope();
            rollbacks++;
            onRollback.run();
            if (failRollbackBefore) {
                throw failure;
            }
            applied = false;
            rolledBack = true;
            if (failRollbackAfter) {
                throw failure;
            }
        }

        @Override
        public Resolution resolution() {
            scope();
            onQuery.run();
            if (queryFailure != null) {
                throw queryFailure;
            }
            if (!proof) {
                return Resolution.UNKNOWN;
            }
            if (completed) {
                return Resolution.COMPLETED;
            }
            if (rolledBack) {
                return Resolution.ROLLED_BACK;
            }
            return Resolution.REVERSIBLE;
        }
    }
}
