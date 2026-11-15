package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipePureComputation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * One compensation boundary around ordered local preparation and a candidate's admission check.
 */
public final class RecipeScanProbes {
    private RecipeScanProbes() {
    }

    /**
     * Borrows both lists unchanged until the returned session finishes, including cancellation.
     */
    public static Session begin(RecipeProcess process, List<RecipeScanPreparation> preparations,
                                List<RecipeParallelismConstraint> constraints) {
        return new Session(process, preparations, constraints);
    }

    /**
     * Preparations and callbacks must leave the borrowed preparation list unchanged during this call.
     */
    public static ProcessResult check(RecipeProcess process, List<RecipeScanPreparation> preparations,
                                      Function<RecipeProcess, ProcessResult> check) {
        requireBoundary(process);
        var transactions = new ObjectArrayList<RequirementTransaction>(preparations.size());
        var reservations = new ObjectArrayList<ResourceReservation>(6);
        ProcessResult result = ProcessResult.SUCCESS;
        try {
            for (int index = 0; index < preparations.size(); index++) {
                var preparation = preparations.get(index);
                if (retired(process)) {
                    result = RecipeText.Cancelled.blocked();
                    break;
                }
                var transaction = preparation.prepare(process);
                transactions.add(transaction);
                reservations.addAll(transaction.reservations());
                if (!reservations.isEmpty()) {
                    throw new IllegalArgumentException("Scan preparation must reserve no resources or energy");
                }
                result = transaction.result();
                if (result != ProcessResult.SUCCESS) {
                    break;
                }
            }
            if (result == ProcessResult.SUCCESS && retired(process)) {
                result = RecipeText.Cancelled.blocked();
            }
            if (result == ProcessResult.SUCCESS) {
                result = check.apply(process);
            }
        } catch (RuntimeException | Error failure) {
            process.invalidatePersistenceBoundary();
            rollbackAfterFailure(transactions, reservations, failure);
            throw failure;
        }
        try {
            ResourceTransactions.rollback(reservations, () -> rollback(transactions, null));
            requireBoundary(process);
        } catch (RuntimeException | Error failure) {
            process.invalidatePersistenceBoundary();
            throw failure;
        }
        return result;
    }

    private static boolean retired(RecipeProcess process) {
        var lifecycle = process.owner().lifecycle();
        return lifecycle != null && lifecycle.retired();
    }

    private static void requireBoundary(RecipeProcess process) {
        process.requirePersistenceBoundary();
        if (process.host() != null || process.state().lifecycle() != RecipeProcess.Lifecycle.NEW) {
            throw new IllegalArgumentException("Scan preparation requires an unattached NEW process");
        }
    }

    private static void rollbackAfterFailure(List<RequirementTransaction> transactions,
                                             List<ResourceReservation> reservations, Throwable primary) {
        try {
            ResourceTransactions.rollback(reservations, () -> rollback(transactions, primary));
        } catch (RuntimeException | Error cleanup) {
            if (primary != cleanup) {
                primary.addSuppressed(cleanup);
            }
        }
    }

    private static void rollback(List<RequirementTransaction> transactions, Throwable primary) {
        Throwable failure = primary;
        for (int i = transactions.size() - 1; i >= 0; i--) {
            try {
                transactions.get(i).rollback();
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

    /**
     * The original candidate preparation/probe compensation boundary, retained across work yields.
     * It never commits/completes preparation transactions, never admits, and does not grant a tick.
     * Input lists are frozen by the scanner; external callbacks remain cooperative.
     */
    public static final class Session {
        private final Thread owner = Thread.currentThread();
        private final RecipeProcess process;
        private final List<RecipeScanPreparation> preparations;
        private final List<RecipeParallelismConstraint> constraints;
        private final ObjectArrayList<Projection> projections = new ObjectArrayList<>();
        private final ObjectArrayList<ResourceReservation> illegalReservations = new ObjectArrayList<>();
        private final ObjectArrayList<RequirementTransaction> illegalTransactions = new ObjectArrayList<>();
        private int preparationCursor;
        private int constraintCursor;
        private int rollbackCursor = -1;
        private int illegalCleanupPhase;
        private int illegalTransactionCursor;
        private int phase;
        private boolean cancelling;
        private boolean executing;
        private boolean finished;
        private boolean inventoryUnknown;
        private ProcessResult result = ProcessResult.SUCCESS;
        @Nullable
        private Iterator<? extends ResourceReservation> listedReservations;
        @Nullable
        private Projection listing;
        @Nullable
        private RecipeParallelismConstraint.Check constraint;
        @Nullable
        private RecipeStageExecutor.Probe start;
        @Nullable
        private ResourceBatch illegalBatch;
        @Nullable
        private ResourceBatch.Acquire illegalAcquire;
        private boolean illegalAcquireFailed;
        private boolean illegalAcquireCancelled;
        @Nullable
        private Throwable failure;

        private Session(RecipeProcess process, List<RecipeScanPreparation> preparations,
                        List<RecipeParallelismConstraint> constraints) {
            requireBoundary(process);
            this.process = process;
            this.preparations = preparations;
            this.constraints = constraints;
        }

        public RecipeProcess process() {
            return process;
        }

        public boolean advance(RecipeWorkBudget budget, BooleanSupplier forwardAllowed) {
            requireOwner();
            if (finished) return true;
            executing = true;
            try {
                if (!cancelling && (!forwardAllowed.getAsBoolean() || retired(process))) cancelBody();
                if (inventoryUnknown) return false;
                if (listing != null && !finishListing(budget)) return false;
                if (!cancelling && phase == 0) {
                    while (preparationCursor < preparations.size()) {
                        if (!forwardAllowed.getAsBoolean() || retired(process)) {
                            cancelBody();
                            break;
                        }
                        if (!budget.take()) return false;
                        listing = new Projection(preparations.get(preparationCursor++).prepare(process));
                        projections.add(listing);
                        try {
                            listedReservations = listing.transaction.reservations().iterator();
                        } catch (RuntimeException | Error observed) {
                            inventoryUnknown = true;
                            throw observed;
                        }
                        if (!finishListing(budget)) return false;
                        if (result != ProcessResult.SUCCESS) break;
                    }
                    phase = 1;
                }
                if (!cancelling && phase == 1) {
                    while (constraintCursor < constraints.size()) {
                        if (!forwardAllowed.getAsBoolean() || retired(process)) {
                            cancelBody();
                            break;
                        }
                        if (constraint == null) {
                            if (!budget.take()) return false;
                            constraint = constraints.get(constraintCursor).beginCheck(process);
                        }
                        if (!constraint.advance(budget, forwardAllowed)) return false;
                        result = constraint.result();
                        constraint = null;
                        constraintCursor++;
                        if (result != ProcessResult.SUCCESS) {
                            cancelling = true;
                            break;
                        }
                    }
                    phase = 2;
                }
                if (!cancelling && phase == 2) {
                    if (start == null) {
                        if (!budget.take()) return false;
                        start = RecipeStageExecutor.beginProbe(process, RecipeStage.START);
                    }
                    if (!start.advance(budget, forwardAllowed)) return false;
                    result = start.result();
                    start = null;
                    cancelling = true;
                }
                return cleanup(budget);
            } catch (RuntimeException | Error observed) {
                cancelling = true;
                if (illegalAcquire != null) illegalAcquireFailed = true;
                process.invalidatePersistenceBoundary();
                failure = failure == null ? observed : failure;
                throw observed;
            } finally {
                executing = false;
            }
        }

        @SuppressWarnings("DataFlowIssue")
        private boolean finishListing(RecipeWorkBudget budget) {
            try {
                while (listedReservations.hasNext()) {
                    if (!budget.take()) return false;
                    illegalReservations.add(listedReservations.next());
                }
            } catch (RuntimeException | Error observed) {
                inventoryUnknown = true;
                throw observed;
            }
            if (!illegalReservations.isEmpty()) {
                listing = null;
                listedReservations = null;
                cancelling = true;
                throw new IllegalArgumentException("Scan preparation must reserve no resources or energy");
            }
            var transaction = listing.transaction;
            listing = null;
            listedReservations = null;
            if (cancelling) return true;
            result = transaction.result();
            if (result != ProcessResult.SUCCESS) cancelling = true;
            return true;
        }

        private boolean cleanup(RecipeWorkBudget budget) {
            if (start != null) {
                start.cancel();
                if (!start.advance(budget, () -> false)) return false;
                start = null;
            }
            if (constraint != null) {
                constraint.cancel();
                if (!constraint.advance(budget, () -> false)) return false;
                constraint = null;
            }
            if (!illegalReservations.isEmpty()) {
                if (illegalBatch == null) {
                    while (illegalTransactionCursor < projections.size()) {
                        if (!budget.take()) return false;
                        illegalTransactions.add(projections.get(illegalTransactionCursor++).transaction);
                    }
                    if (illegalAcquire != null && illegalAcquireFailed) {
                        if (!illegalAcquireCancelled) {
                            illegalAcquire.cancel();
                            illegalAcquireCancelled = true;
                        }
                        if (!illegalAcquire.advance(budget)) return false;
                        illegalAcquire.result();
                        illegalAcquire = null;
                        illegalAcquireFailed = false;
                        illegalAcquireCancelled = false;
                    }
                    if (illegalAcquire == null)
                        illegalAcquire = ResourceBatch.beginAcquire(illegalReservations, illegalTransactions);
                    if (!illegalAcquire.advance(budget)) return false;
                    illegalBatch = illegalAcquire.result();
                    illegalAcquire = null;
                    if (illegalBatch == null) return false;
                }
                if (illegalCleanupPhase == 0) {
                    boolean settled = illegalBatch.decision() == ResourceBatch.Decision.OPEN
                        ? illegalBatch.rollbackWithinBudget(budget) : illegalBatch.resolveBusinessWithinBudget(budget);
                    if (!settled) return false;
                    illegalCleanupPhase = 1;
                }
                if (!illegalBatch.readyToFinish()) {
                    illegalCleanupPhase = 0;
                    return false;
                }
                if (!illegalBatch.finishWithinBudget(budget)) return false;
                finished = true;
                return true;
            }
            if (rollbackCursor == -1) rollbackCursor = projections.size() - 1;
            while (rollbackCursor >= 0) {
                if (!budget.take()) return false;
                if (!projections.get(rollbackCursor).rollback()) return false;
                rollbackCursor--;
            }
            if (failure == null) requireBoundary(process);
            else process.requireQuiescent();
            finished = true;
            return true;
        }

        public void cancel() {
            requireOwner();
            cancelBody();
        }

        private void cancelBody() {
            cancelling = true;
            result = RecipeText.Cancelled.blocked();
        }

        public boolean finished() {
            requireOwner();
            return finished;
        }

        public boolean hasObligations() {
            requireOwner();
            return !finished && (!projections.isEmpty() || start != null || constraint != null && constraint.hasObligations());
        }

        public ProcessResult result() {
            requireOwner();
            if (!finished) throw new IllegalStateException("Candidate probe still owns cleanup obligations");
            return result;
        }

        public @Nullable Throwable failure() {
            requireOwner();
            return failure;
        }

        public @Nullable RecipePureComputation computation() {
            requireOwner();
            if (start != null) return start.computation();
            return constraint == null ? null : constraint.computation();
        }

        public void finishComputation(RecipePureComputation expected) {
            requireOwner();
            if (start != null) start.finishComputation(expected);
            else if (constraint != null) constraint.finishComputation(expected);
            else throw new IllegalArgumentException("No candidate computation");
        }

        public List<ResourceBatch.Source> sources() {
            requireOwner();
            if (illegalBatch != null)
                return illegalBatch.decision() == ResourceBatch.Decision.OPEN ? List.of() : illegalBatch.sources();
            if (start != null) return start.sources();
            return constraint == null ? List.of() : constraint.sources();
        }

        public boolean resolvingSources() {
            requireOwner();
            if (illegalBatch != null) return true;
            if (start != null) return start.resolvingSources();
            return constraint != null && constraint.resolvingSources();
        }

        public void finishSourceResolution(ResourceBatch.Source expected, boolean resolved) {
            requireOwner();
            if (illegalBatch != null) return;
            if (start != null) start.finishSourceResolution(expected, resolved);
            else if (constraint != null) constraint.finishSourceResolution(expected, resolved);
            else throw new IllegalArgumentException("No candidate source action");
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner || executing)
                throw new IllegalStateException("Candidate probe requires its idle captured owner");
        }

        private static final class Projection {
            private final RequirementTransaction transaction;
            private boolean uncertain;
            private boolean terminal;

            private Projection(RequirementTransaction transaction) {
                this.transaction = transaction;
            }

            private boolean rollback() {
                if (terminal) return true;
                if (uncertain) {
                    var resolution = transaction.resolution();
                    if (resolution == ReversibleTransaction.Resolution.UNKNOWN) return false;
                    if (resolution == ReversibleTransaction.Resolution.ROLLED_BACK) return terminal = true;
                    if (resolution != ReversibleTransaction.Resolution.REVERSIBLE)
                        throw new IllegalStateException("Preparation cleanup contradicts rollback");
                }
                try {
                    transaction.rollback();
                    terminal = true;
                    uncertain = false;
                    return true;
                } catch (RuntimeException | Error observed) {
                    uncertain = true;
                    throw observed;
                }
            }
        }
    }
}
