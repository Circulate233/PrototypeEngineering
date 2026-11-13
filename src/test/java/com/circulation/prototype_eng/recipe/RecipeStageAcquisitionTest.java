package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeStageAcquisitionTest {
    private static EnergyBuffer energy(Runnable changed) {
        var energy = new EnergyBuffer(100, changed);
        energy.receiveEnergy(100, Actionable.MODULATE);
        return energy;
    }

    @SafeVarargs
    private static RecipeProcess process(Supplier<RequirementTransaction>... acquisitions) {
        var type = new RecipeRequirementType<>(new ResourceLocation("acquisition_test", "external"), new RecipeRequirementSystem<Supplier<RequirementTransaction>>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Supplier<RequirementTransaction>> requirement) {
                return requirement.payload().get();
            }
        });
        var requirements = new ObjectArrayList<RecipeRequirement<?>>();
        for (int index = 0; index < acquisitions.length; index++) {
            requirements.add(type.requirement(new ResourceLocation("acquisition_test", "effect_" + index), acquisitions[index]));
        }
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(new ResourceLocation("acquisition_test", "recipe"), 10, requirements), 17);
    }

    @Test
    void acquisitionExceptionRetainsOriginalTokensAndTheFinalTransitionForExactCleanup() {
        var energy = energy(() -> {
        });
        var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation());
        var effect = new Effect(List.of(token));
        var transition = new Effect(List.of());
        var failure = new IllegalArgumentException("later acquisition failed");
        int[] calls = {0};
        var process = process(() -> effect, () -> {
            calls[0]++;
            throw failure;
        });
        var acquisition = RecipeStageExecutor.prepare(process, RecipeStage.START, transition);
        assertFalse(acquisition.resolved());
        assertSame(failure, assertThrows(IllegalArgumentException.class, acquisition::acquire));
        assertSame(failure, acquisition.failure());
        assertTrue(acquisition.inventoryComplete());
        assertSame(token, acquisition.reservations().getFirst());
        assertSame(transition, acquisition.transactions().getLast());
        assertEquals(2, acquisition.transactions().size());
        assertThrows(IllegalStateException.class, acquisition::settleSynchronously);
        acquisition.rollbackSynchronously();
        assertTrue(acquisition.resolved());
        assertEquals(1, effect.rollbacks);
        assertEquals(1, transition.rollbacks);
        assertEquals(100, energy.savedEnergy());
        assertEquals(100, energy.energySnapshot().availableEnergy());
        process.requireQuiescent();
        assertThrows(IllegalStateException.class, acquisition::acquire);
        assertEquals(1, calls[0]);
    }

    @Test
    void resultFailureRecordsItsTransactionBeforeReadingTheResultAndDoesNotLoseItsToken() {
        var energy = energy(() -> {
        });
        var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation());
        var failure = new IllegalStateException("result unavailable");
        var effect = new Effect(List.of(token)) {
            @Override
            public ProcessResult result() {
                throw failure;
            }
        };
        var process = process(() -> effect);
        var acquisition = RecipeStageExecutor.prepare(process, RecipeStage.START, RequirementTransaction.SUCCESS);
        assertSame(failure, assertThrows(IllegalStateException.class, acquisition::acquire));
        assertEquals(2, acquisition.transactions().size());
        assertSame(token, acquisition.reservations().getFirst());
        acquisition.rollbackSynchronously();
        assertTrue(acquisition.resolved());
        assertEquals(1, effect.rollbacks);
        assertEquals(100, energy.energySnapshot().availableEnergy());
    }

    @Test
    void aThrowingTokenInventoryCannotBecomeResolvedEvenAfterKnownBusinessUndo() {
        var energy = energy(() -> {
        });
        var unlisted = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation());
        var failure = new IllegalStateException("token inventory unavailable");
        var effect = new Effect(List.of(unlisted)) {
            @Override
            public List<? extends ResourceReservation> reservations() {
                throw failure;
            }
        };
        var acquisition = RecipeStageExecutor.prepare(process(() -> effect), RecipeStage.START, RequirementTransaction.SUCCESS);
        assertSame(failure, assertThrows(IllegalStateException.class, acquisition::acquire));
        assertFalse(acquisition.inventoryComplete());
        assertTrue(acquisition.reservations().isEmpty());
        acquisition.rollbackSynchronously();
        assertEquals(1, effect.rollbacks);
        assertFalse(acquisition.resolved());
        assertFalse(acquisition.resolveSynchronously());
        ResourceTransactions.rollback(List.of(unlisted));
        assertFalse(acquisition.resolved(), "An external token release does not prove the missing inventory");
    }

    @Test
    void noOpRequirementsAreFilteredAndTheSingleTransitionStillNeedsRealSettlement() {
        var transition = new Effect(List.of());
        var acquisition = RecipeStageExecutor.prepare(process(() -> RequirementTransaction.SUCCESS,
            () -> RequirementTransaction.SUCCESS), RecipeStage.START, transition);
        acquisition.acquire();
        assertEquals(List.of(transition), acquisition.transactions());
        assertFalse(acquisition.resolved());
        assertSame(ProcessResult.SUCCESS, acquisition.settleSynchronously());
        assertTrue(acquisition.resolved());
        assertEquals(1, transition.commits);
        assertEquals(1, transition.completions);
        assertThrows(IllegalStateException.class, acquisition::settleSynchronously);
        assertThrows(IllegalStateException.class, acquisition::rollbackSynchronously);
    }

    @Test
    void notificationFailureAfterTerminalCallbacksIsResolvedAndNeverRefundsOrReplaysThem() {
        boolean[] armed = {false};
        var failure = new IllegalStateException("terminal notification failed");
        var energy = energy(() -> {
            if (armed[0]) {
                throw failure;
            }
        });
        var token = new TrackedToken(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation()));
        var effect = new Effect(List.of(token)) {
            @Override
            public void complete() {
                super.complete();
                armed[0] = true;
            }
        };
        var acquisition = RecipeStageExecutor.prepare(process(() -> effect), RecipeStage.START, RequirementTransaction.SUCCESS);
        acquisition.acquire();
        assertSame(failure, assertThrows(IllegalStateException.class, acquisition::settleSynchronously));
        assertTrue(acquisition.resolved());
        assertSame(failure, acquisition.failure());
        assertTrue(acquisition.resolveSynchronously());
        assertEquals(1, effect.commits);
        assertEquals(1, effect.completions);
        assertEquals(1, token.completions);
        assertEquals(0, token.rollbacks);
        assertEquals(75, energy.savedEnergy());
        assertThrows(IllegalStateException.class, acquisition::rollbackSynchronously);
    }

    @Test
    void unknownBusinessCleanupReturnsPendingAndRetriesOnlyAfterItsExactProof() {
        var energy = energy(() -> {
        });
        var token = new TrackedToken(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation()));
        var effect = new Effect(List.of(token));
        var transition = new Effect(List.of());
        effect.failComplete = true;
        var process = process(() -> effect);
        var acquisition = RecipeStageExecutor.prepare(process, RecipeStage.START, transition);
        acquisition.acquire();
        assertSame(effect.failure, assertThrows(IllegalStateException.class, acquisition::settleSynchronously));
        assertFalse(acquisition.resolved());
        assertFalse(acquisition.resolveSynchronously());
        assertEquals(1, effect.completions);
        effect.failComplete = false;
        effect.proof = true;
        assertTrue(acquisition.resolveSynchronously());
        assertTrue(acquisition.resolved());
        assertEquals(1, effect.commits);
        assertEquals(2, effect.completions);
        assertEquals(1, transition.completions);
        assertEquals(1, token.completions);
        assertEquals(0, token.rollbacks);
        process.requireQuiescent();
    }

    @Test
    void exactSourceProofAfterMutationConfirmsCleanupWithoutReplayingTheTerminalCallback() {
        var energy = energy(() -> {
        });
        boolean[] proof = {false};
        var failure = new IllegalStateException("source acknowledgement unavailable");
        var token = new TrackedToken(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation())) {
            @Override
            public void complete() {
                super.complete();
                throw failure;
            }

            @Override
            public Resolution resolution() {
                return proof[0] ? super.resolution() : Resolution.UNKNOWN;
            }
        };
        var acquisition = RecipeStageExecutor.prepare(process(() -> new Effect(List.of(token))), RecipeStage.START, RequirementTransaction.SUCCESS);
        acquisition.acquire();
        assertSame(token, acquisition.reservations().getFirst());
        assertSame(failure, assertThrows(IllegalStateException.class, acquisition::settleSynchronously));
        assertFalse(acquisition.resolved());
        assertFalse(acquisition.resolveSynchronously());
        proof[0] = true;
        assertTrue(acquisition.resolveSynchronously());
        assertEquals(1, token.completions);
        assertEquals(0, token.rollbacks);
        assertEquals(75, energy.savedEnergy());
    }

    @Test
    void rejectedStorageLockBoundariesDoNotConsumeTheUnstartedSettlementAttempt() {
        var energy = energy(() -> {
        });
        var other = new StorageContext(() -> {
        });
        var effect = new Effect(List.of(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation())));
        var acquisition = RecipeStageExecutor.prepare(process(() -> effect), RecipeStage.START, RequirementTransaction.SUCCESS);
        acquisition.acquire();
        other.lock();
        try {
            assertThrows(IllegalStateException.class, acquisition::settleSynchronously);
        } finally {
            other.unlock();
        }
        assertFalse(acquisition.resolved());
        assertSame(ProcessResult.SUCCESS, acquisition.settleSynchronously());
        assertTrue(acquisition.resolved());
        assertEquals(1, effect.commits);

        var undo = RecipeStageExecutor.prepare(process(() -> new Effect(List.of())), RecipeStage.START, RequirementTransaction.SUCCESS);
        undo.acquire();
        other.lock();
        try {
            assertThrows(IllegalStateException.class, undo::rollbackSynchronously);
        } finally {
            other.unlock();
        }
        undo.rollbackSynchronously();
        assertTrue(undo.resolved());
    }

    @Test
    void directRollbackRetryQueriesBeforeAnyCallbackReplayAndAQueryBoundaryFailureCanRetry() {
        var energy = energy(() -> {
        });
        boolean[] proof = {false};
        boolean[] contextFails = {false};
        var contextFailure = new IllegalArgumentException("context query unavailable");
        var sourceFailure = new IllegalStateException("source rollback acknowledged late");
        TrackedToken token = new TrackedToken(Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation())) {
            @Override
            public StorageContext context() {
                if (contextFails[0]) {
                    throw contextFailure;
                }
                return super.context();
            }

            @Override
            public void rollback() {
                super.rollback();
                throw sourceFailure;
            }

            @Override
            public Resolution resolution() {
                return proof[0] ? super.resolution() : Resolution.UNKNOWN;
            }
        };
        var businessFailure = new IllegalStateException("business rollback acknowledged late");
        Effect effect = new Effect(List.of(token)) {
            @Override
            public void rollback() {
                super.rollback();
                throw businessFailure;
            }
        };
        var acquisition = RecipeStageExecutor.prepare(process(() -> effect), RecipeStage.START, RequirementTransaction.SUCCESS);
        acquisition.acquire();
        assertSame(businessFailure, assertThrows(IllegalStateException.class, acquisition::rollbackSynchronously));
        assertFalse(acquisition.resolved());
        contextFails[0] = true;
        assertSame(contextFailure, assertThrows(IllegalArgumentException.class, acquisition::resolveSynchronously));
        contextFails[0] = false;
        effect.proof = true;
        proof[0] = true;
        acquisition.rollbackSynchronously();
        assertTrue(acquisition.resolved());
        assertEquals(1, effect.rollbacks);
        assertEquals(1, token.rollbacks);
        assertEquals(100, energy.energySnapshot().availableEnergy());
    }

    @Test
    void deferredListsKeepRawTokenIdentitySoABatchRejectsCrossStageTokenAliases() {
        var energy = energy(() -> {
        });
        var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation());
        var first = RecipeStageExecutor.prepare(process(() -> new Effect(List.of(token))), RecipeStage.START, RequirementTransaction.SUCCESS);
        var second = RecipeStageExecutor.prepare(process(() -> new Effect(List.of(token))), RecipeStage.START, RequirementTransaction.SUCCESS);
        first.acquire();
        second.acquire();
        assertSame(token, first.reservations().getFirst());
        assertSame(token, second.reservations().getFirst());
        assertThrows(IllegalArgumentException.class, () -> ResourceBatch.tryAcquire(
            List.of(first.reservations().getFirst(), second.reservations().getFirst()), List.of()));
        first.rollbackSynchronously();
        assertTrue(first.resolved());
    }

    private static class Effect implements RequirementTransaction {
        private final IllegalStateException failure = new IllegalStateException("business acknowledgement unavailable");
        private final List<ResourceReservation> tokens;
        int commits;
        int completions;
        int rollbacks;
        private Resolution terminal = Resolution.REVERSIBLE;
        private boolean failComplete;
        private boolean proof;

        private Effect(List<ResourceReservation> tokens) {
            this.tokens = tokens;
        }

        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public List<? extends ResourceReservation> reservations() {
            return tokens;
        }

        @Override
        public void commit() {
            commits++;
        }

        @Override
        public void complete() {
            completions++;
            if (failComplete) {
                throw failure;
            }
            terminal = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            rollbacks++;
            terminal = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return proof ? terminal : Resolution.UNKNOWN;
        }
    }

    private static class TrackedToken implements ResourceReservation {
        private final ResourceReservation delegate;
        int completions;
        int rollbacks;

        private TrackedToken(ResourceReservation delegate) {
            this.delegate = delegate;
        }

        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
        }

        @Override
        public void apply() {
            delegate.apply();
        }

        @Override
        public void complete() {
            completions++;
            delegate.complete();
        }

        @Override
        public void rollback() {
            rollbacks++;
            delegate.rollback();
        }

        @Override
        public Resolution resolution() {
            return delegate.resolution();
        }
    }
}
