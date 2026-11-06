package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * PM's nested checkpoint lifecycle with one outer-stage settlement boundary. Child transactions
 * only contribute business callbacks and reservation tokens; they never settle resources locally.
 */
public final class CheckpointRequirementSystem implements RecipeRequirementSystem<CheckpointRequirement> {
    private static final RecipeStage[] CHILD_STAGES = RecipeStage.values();

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<CheckpointRequirement> requirement) {
        var checkpoint = requirement.payload();
        if (checkpoint.time() != 0) {
            return RequirementTransaction.SUCCESS;
        }
        var state = process.state();
        var tick = new RecipeProcess.Tick(state.progress(), state.progress(), state.successfulTicks(), state.successfulTicks());
        return acquireLifecycle(process, checkpoint.requirement(), tick);
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<CheckpointRequirement> requirement) {
        var checkpoint = requirement.payload();
        if (checkpoint.time() == 0) {
            return RequirementTransaction.SUCCESS;
        }
        var tick = process.tickContext();
        boolean crossed = checkpoint.scaleWithProcess()
            ? tick.progressBefore() < checkpoint.time() && tick.progressAfter() >= checkpoint.time()
            : tick.indexBefore() < checkpoint.time() && tick.indexAfter() >= checkpoint.time();
        return crossed ? acquireLifecycle(process, checkpoint.requirement(), tick) : RequirementTransaction.SUCCESS;
    }

    private static RequirementTransaction acquireLifecycle(RecipeProcess process, RecipeRequirement<?> requirement,
                                                           RecipeProcess.Tick tick) {
        var transactions = new ObjectArrayList<RequirementTransaction>();
        var reservations = new ObjectArrayList<ResourceReservation>();
        ProcessResult result = ProcessResult.SUCCESS;
        try {
            for (var stage : CHILD_STAGES) {
                var scoped = stage == RecipeStage.TICK ? tick : null;
                var transaction = new ChildTransaction(process,
                    Objects.requireNonNull(scoped == null ? requirement.acquire(process, stage)
                        : process.withTickContext(scoped, () -> requirement.acquire(process, stage)), "child transaction"), scoped);
                transactions.add(transaction);
                reservations.addAll(Objects.requireNonNull(transaction.reservations(), "child reservations"));
                result = Objects.requireNonNull(transaction.result(), "child result");
                if (result != ProcessResult.SUCCESS) {
                    break;
                }
            }
        } catch (RuntimeException | Error failure) {
            try {
                ResourceTransactions.rollback(reservations, () -> rollbackAll(transactions, failure));
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        return new LifecycleTransaction(result, transactions, reservations);
    }

    private static void rollbackAll(List<RequirementTransaction> transactions, @Nullable Throwable primary) {
        Throwable failure = primary;
        for (int index = transactions.size() - 1; index >= 0; index--) {
            try {
                transactions.get(index).rollback();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (primary == null) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private record LifecycleTransaction(ProcessResult result, List<RequirementTransaction> transactions,
                                        List<ResourceReservation> reservations) implements RequirementTransaction {
        private LifecycleTransaction {
            transactions = ObjectLists.unmodifiable(new ObjectArrayList<>(transactions));
            reservations = ObjectLists.unmodifiable(new ObjectArrayList<>(reservations));
        }

        @Override
        public void commit() {
            for (var transaction : transactions) {
                transaction.commit();
            }
        }

        @Override
        public void complete() {
            for (var transaction : transactions) {
                transaction.complete();
            }
        }

        @Override
        public void rollback() {
            rollbackAll(transactions, null);
        }
    }

    /** The child TICK keeps its captured timeline during acquisition and every business callback. */
    private record ChildTransaction(RecipeProcess process, RequirementTransaction delegate,
                                    @Nullable RecipeProcess.Tick tick) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return scoped(delegate::result);
        }

        @Override
        public List<? extends ResourceReservation> reservations() {
            return scoped(delegate::reservations);
        }

        @Override
        public void commit() {
            scopedRun(delegate::commit);
        }

        @Override
        public void complete() {
            scopedRun(delegate::complete);
        }

        @Override
        public void rollback() {
            scopedRun(delegate::rollback);
        }

        private <T> T scoped(Supplier<T> action) {
            return tick == null ? action.get() : process.withTickContext(tick, action);
        }

        private void scopedRun(Runnable action) {
            scoped(() -> {
                action.run();
                return null;
            });
        }
    }
}
