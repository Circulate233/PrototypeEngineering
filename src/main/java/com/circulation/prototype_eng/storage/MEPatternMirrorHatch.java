package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.BudgetResult;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.api.storage.ResourceRecipeObserver;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One revocable mirror binding to the original pattern balances. Published ports and captures keep
 * this binding; replacement never redirects acquired work or cleanup to another assembly.
 */
public final class MEPatternMirrorHatch implements ResourceHatch {
    private final MEPatternAssemblyStorage source;
    private final StorageAlias output;
    private volatile boolean active = true;
    private volatile Partitions partitions;

    public MEPatternMirrorHatch(MEPatternAssemblyStorage source) {
        this.source = source;
        output = new StorageAlias(source.output(), this);
        partitions = new Partitions(source.inputPartitions(), source.inputColor());
    }

    public void revoke() {
        active = false;
    }

    @Override
    public boolean isValid() {
        return active && source.isValid();
    }

    @Override
    public ResourceInputGroup inputGroup() {
        return currentPartitions().group;
    }

    @Override
    public List<? extends ResourceHatch> inputPartitions() {
        return currentPartitions().ports;
    }

    @Override
    public @Nullable ResourceStorage input() {
        return currentPartitions().input;
    }

    @Override
    public @NotNull ResourceStorage output() {
        return output;
    }

    private Partitions currentPartitions() {
        var captured = partitions;
        if (!active || captured.current()) return captured;
        return refreshPartitions();
    }

    private synchronized Partitions refreshPartitions() {
        var captured = partitions;
        if (!active || captured.current()) return captured;
        captured = new Partitions(source.inputPartitions(), source.inputColor());
        partitions = captured;
        return captured;
    }

    private final class Partitions {
        private final List<? extends ResourceHatch> original;
        private final AEColor color;
        private final ResourceInputGroup group;
        private final List<ResourceHatch> ports;
        private final @Nullable ResourceStorage input;

        private Partitions(List<? extends ResourceHatch> original, AEColor color) {
            this.original = original;
            this.color = color;
            group = new ResourceInputGroup(color);
            var captured = new ObjectArrayList<ResourceHatch>(original.size());
            ResourceStorage rootInput = null;
            var sourceInput = source.input();
            for (int index = 0; index < original.size(); index++) {
                var port = new Port(original.get(index), this);
                captured.add(port);
                if (port.input.delegate == sourceInput) rootInput = port.input;
            }
            ports = captured;
            input = rootInput;
        }

        private boolean current() {
            return source.inputPartitions() == original && source.inputColor() == color;
        }
    }

    private final class Port implements ResourceHatch, ResourceRecipeObserver {
        private final ResourceHatch original;
        private final ResourceRecipeObserver observer;
        private final Partitions publication;
        private final ResourceInputGroup group;
        private final StorageAlias input;

        private Port(ResourceHatch original, Partitions publication) {
            this.original = original;
            observer = (ResourceRecipeObserver) original;
            this.publication = publication;
            group = original.inputGroup();
            input = new StorageAlias(original.input(), this);
        }

        @Override
        public ResourceInputGroup inputGroup() {
            return group;
        }

        @Override
        public boolean sharedInput() {
            return original.sharedInput();
        }

        @Override
        public ResourceStorage input() {
            return input;
        }

        @Override
        public @NotNull ResourceStorage output() {
            return output;
        }

        @Override
        public boolean isValid() {
            return active && publication.current() && original.isValid();
        }

        @Override
        public Object observerIdentity() {
            return observer.observerIdentity();
        }

        @Override
        public void attached(RecipeProcess process) {
            observer.attached(process);
        }

        @Override
        public void detached(RecipeProcess process) {
            observer.detached(process);
        }

        @Override
        public void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
            observer.inputsCommitted(process, stage, amounts);
        }

        @Override
        public void stageCommitted(RecipeProcess process, RecipeStage stage) {
            observer.stageCommitted(process, stage);
        }
    }

    private record StorageAlias(ResourceStorage delegate, ResourceHatch access) implements ResourceStorage {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean isValid() {
            return access.isValid() && delegate.isValid();
        }

        @Override
        public StorageSnapshot snapshot() {
            return delegate.snapshot();
        }

        @Override
        public StorageSnapshot snapshotFor(AEKey key) {
            return delegate.snapshotFor(key);
        }

        @Override
        public QuantityCapture beginQuantityCapture() {
            return new Quantities(delegate.beginQuantityCapture(), this);
        }

        @Override
        public QuantityCapture beginQuantityCapture(AEKey key) {
            return new Quantities(delegate.beginQuantityCapture(key), this);
        }

        @Override
        public long amount(AEKey key) {
            return isValid() ? delegate.amount(key) : 0;
        }

        @Override
        public long availableAmount(AEKey key) {
            return isValid() ? delegate.availableAmount(key) : 0;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode) {
            return isValid() ? delegate.insert(key, amount, mode) : 0;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            return isValid() ? delegate.extract(key, amount, mode) : 0;
        }

        @Override
        public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            return isValid() ? wrap(delegate.reserveExtract(snapshot, key, amount)) : ReservationResult.INVALID_STORAGE;
        }

        @Override
        public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return isValid() ? wrap(delegate.reserveInsert(snapshot, key, amount)) : ReservationResult.INVALID_STORAGE;
        }

        @Override
        public ReservationCapture beginReserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            return isValid() ? new Capture(delegate.beginReserveExtract(snapshot, key, amount), this) : INVALID_CAPTURE;
        }

        @Override
        public ReservationCapture beginReserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return isValid() ? new Capture(delegate.beginReserveInsert(snapshot, key, amount), this) : INVALID_CAPTURE;
        }

        private ReservationResult wrap(ReservationResult result) {
            return result.status() == ReservationResult.Status.ACCEPTED
                ? new ReservationResult(ReservationResult.Status.ACCEPTED, new Token(result.reservation(), this)) : result;
        }

        private void requireValid() {
            if (!isValid())
                throw new IllegalStateException("Pattern mirror binding changed before resource application");
        }
    }

    private record Quantities(ResourceStorage.QuantityCapture delegate, StorageAlias access)
        implements ResourceStorage.QuantityCapture {
        @Override
        public boolean advance(RecipeWorkBudget budget) {
            return delegate.advance(budget);
        }

        @Override
        public AEKey2LongMap available() {
            return delegate.available();
        }

        @Override
        public boolean current() {
            return access.isValid() && delegate.current();
        }
    }

    private static final class Capture implements ResourceStorage.ReservationCapture {
        private final ResourceStorage.ReservationCapture delegate;
        private final StorageAlias access;
        private boolean finished;
        private boolean invalid;
        private @Nullable ReservationResult result;

        private Capture(ResourceStorage.ReservationCapture delegate, StorageAlias access) {
            this.delegate = delegate;
            this.access = access;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (finished) return true;
            if (!access.isValid()) {
                invalid = true;
                finished = true;
                return true;
            }
            if (!delegate.advance(budget)) return false;
            invalid = !access.isValid();
            finished = true;
            return true;
        }

        @Override
        public ReservationResult result() {
            if (result == null)
                result = invalid || !access.isValid() ? ReservationResult.INVALID_STORAGE : access.wrap(delegate.result());
            return result;
        }

        @Override
        public long amount() {
            return result().status() == ReservationResult.Status.ACCEPTED ? delegate.amount() : 0;
        }

        @Override
        public boolean hasObligations() {
            return delegate.hasObligations();
        }

        @Override
        public boolean cancel(RecipeWorkBudget budget) {
            return delegate.cancel(budget);
        }

        @Override
        public ResourceReservation.Resolution resolution() {
            return delegate.resolution();
        }

        @Override
        public @Nullable ResourceReservation.Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            return delegate.resolutionWithinBudget(budget);
        }
    }

    private record Token(ResourceReservation delegate, StorageAlias access) implements ResourceReservation {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return access.isValid() && delegate.canApply();
        }

        @Override
        public BudgetResult canApplyWithinBudget(RecipeWorkBudget budget) {
            return access.isValid() ? delegate.canApplyWithinBudget(budget) : BudgetResult.REJECTED;
        }

        @Override
        public void apply() {
            access.requireValid();
            delegate.apply();
        }

        @Override
        public boolean applyWithinBudget(RecipeWorkBudget budget) {
            access.requireValid();
            return delegate.applyWithinBudget(budget);
        }

        @Override
        public boolean supportsSequentialUse() {
            return delegate.supportsSequentialUse();
        }

        @Override
        public void exposeApplied() {
            access.requireValid();
            delegate.exposeApplied();
        }

        @Override
        public boolean exposeAppliedWithinBudget(RecipeWorkBudget budget) {
            access.requireValid();
            return delegate.exposeAppliedWithinBudget(budget);
        }

        @Override
        public void complete() {
            delegate.complete();
        }

        @Override
        public boolean completeWithinBudget(RecipeWorkBudget budget) {
            return delegate.completeWithinBudget(budget);
        }

        @Override
        public void rollback() {
            delegate.rollback();
        }

        @Override
        public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
            return delegate.rollbackWithinBudget(budget);
        }

        @Override
        public Resolution resolution() {
            return delegate.resolution();
        }

        @Override
        public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            return delegate.resolutionWithinBudget(budget);
        }
    }

    private static final ResourceStorage.ReservationCapture INVALID_CAPTURE = new ResourceStorage.ReservationCapture() {
        @Override
        public boolean advance(RecipeWorkBudget budget) {
            return true;
        }

        @Override
        public ReservationResult result() {
            return ReservationResult.INVALID_STORAGE;
        }

        @Override
        public long amount() {
            return 0;
        }

        @Override
        public boolean hasObligations() {
            return false;
        }

        @Override
        public boolean cancel(RecipeWorkBudget budget) {
            return true;
        }

        @Override
        public ResourceReservation.Resolution resolution() {
            return ResourceReservation.Resolution.ROLLED_BACK;
        }
    };
}
