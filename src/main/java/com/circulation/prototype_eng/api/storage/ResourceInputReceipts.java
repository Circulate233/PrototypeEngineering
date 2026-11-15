package com.circulation.prototype_eng.api.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Framework input-source receipts. Native reservation transitions retain their original semantics.
 */
public final class ResourceInputReceipts {
    private ResourceInputReceipts() {
    }

    public static MachineHatches bind(MachineHatches source, RecipeProcess process) {
        var resources = source.resources();
        if (resources.isEmpty()) return source;
        var observed = new ObjectArrayList<ResourceHatch>(resources.size());
        for (int index1 = 0; index1 < resources.size(); index1++) {
            var hatch = resources.get(index1);
            observed.add(new ObservedHatch(hatch, hatch instanceof ResourceRecipeObserver observer ? observer : null, process));
        }
        return new MachineHatches(observed, source.energyInputs(), source.energyOutputs());
    }

    /**
     * One outer stage owns this ledger; completion workers only write their own receipt flag.
     */
    public static final class Ledger {
        private final ObjectArrayList<Receipt> receipts = new ObjectArrayList<>();

        public Receipt prepare(@Nullable ResourceRecipeObserver observer, AEKey key, long amount) {
            if (amount <= 0) throw new IllegalArgumentException("Input receipt needs a positive amount");
            Object identity = observer == null ? null : observer.observerIdentity();
            var receipt = new Receipt(observer, identity, key, amount);
            receipts.add(receipt);
            return receipt;
        }

        public Publication complete() {
            StorageContext.requireNoLocks();
            StorageFence.requireOutsideAccess();
            var groups = new Reference2ObjectLinkedOpenHashMap<Object, Committed>();
            var total = new AEKey2LongMap.OpenHashMap();
            boolean overflow = false;
            for (int index2 = 0; index2 < receipts.size(); index2++) {
                var receipt = receipts.get(index2);
                if (!receipt.completed) continue;
                if (!overflow) {
                    if (total.getLong(receipt.key) > Long.MAX_VALUE - receipt.amount) overflow = true;
                    else total.addTo(receipt.key, receipt.amount);
                }
                if (receipt.observer == null) continue;
                var committed = groups.get(receipt.identity);
                if (committed == null) {
                    committed = new Committed(receipt.observer);
                    groups.put(receipt.identity, committed);
                }
                committed.add(receipt.key, receipt.amount);
            }
            receipts.clear();
            return new Publication(overflow ? null : total, groups);
        }
    }

    /**
     * The process installs the whole-stage snapshot before publishing any individual source.
     */
    public static final class Publication {
        private final @Nullable AEKey2LongMap amounts;
        private final Reference2ObjectLinkedOpenHashMap<Object, Committed> groups;

        private Publication(@Nullable AEKey2LongMap amounts, Reference2ObjectLinkedOpenHashMap<Object, Committed> groups) {
            this.amounts = amounts;
            this.groups = groups;
        }

        public @Nullable AEKey2LongMap amounts() {
            return amounts;
        }

        public void publish(RecipeProcess process, RecipeStage stage) {
            StorageContext.requireNoLocks();
            StorageFence.requireOutsideAccess();
            Throwable failure = null;
            for (var committed : groups.values()) {
                committed.finish();
                for (int index = 0; index < committed.chunks.size(); index++) {
                    var chunk = committed.chunks.get(index);
                    try {
                        committed.observer.inputsCommitted(process, stage, chunk);
                    } catch (RuntimeException | Error observed) {
                        if (failure == null) failure = observed;
                        else if (failure != observed) failure.addSuppressed(observed);
                    }
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException exception) throw exception;
        }
    }

    private static final class Committed {
        private final ResourceRecipeObserver observer;
        private final ObjectArrayList<AEKey2LongMap> chunks = new ObjectArrayList<>();
        private AEKey2LongMap amounts = new AEKey2LongMap.OpenHashMap();

        private Committed(ResourceRecipeObserver observer) {
            this.observer = observer;
        }

        private void add(AEKey key, long amount) {
            if (amounts.getLong(key) > Long.MAX_VALUE - amount) {
                finish();
                amounts = new AEKey2LongMap.OpenHashMap();
            }
            amounts.addTo(key, amount);
        }

        private void finish() {
            if (!amounts.isEmpty()) chunks.add(amounts);
        }
    }

    /**
     * Allocated and enrolled during acquisition. Terminal native completion only sets a flag.
     */
    public static final class Receipt implements ResourceReservation {
        private final @Nullable ResourceRecipeObserver observer;
        private final @Nullable Object identity;
        private final AEKey key;
        private final long amount;
        private final ReservationResult accepted = new ReservationResult(ReservationResult.Status.ACCEPTED, this);
        private ResourceReservation delegate;
        private volatile boolean completed;

        private Receipt(@Nullable ResourceRecipeObserver observer, @Nullable Object identity, AEKey key, long amount) {
            this.observer = observer;
            this.identity = identity;
            this.key = key;
            this.amount = amount;
        }

        private ReservationResult bind(ReservationResult result) {
            if (result.status() != ReservationResult.Status.ACCEPTED) {
                return result;
            }
            delegate = result.reservation();
            return accepted;
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
        public @Nullable Boolean canApplyWithinBudget(RecipeWorkBudget budget) {
            return delegate.canApplyWithinBudget(budget);
        }

        @Override
        public void apply() {
            delegate.apply();
        }

        @Override
        public boolean applyWithinBudget(RecipeWorkBudget budget) {
            return delegate.applyWithinBudget(budget);
        }

        @Override
        public boolean supportsSequentialUse() {
            return delegate.supportsSequentialUse();
        }

        @Override
        public void exposeApplied() {
            delegate.exposeApplied();
        }

        @Override
        public boolean exposeAppliedWithinBudget(RecipeWorkBudget budget) {
            return delegate.exposeAppliedWithinBudget(budget);
        }

        @Override
        public void complete() {
            delegate.complete();
            completed = true;
        }

        @Override
        public boolean completeWithinBudget(RecipeWorkBudget budget) {
            if (!delegate.completeWithinBudget(budget)) return false;
            completed = true;
            return true;
        }

        @Override
        public void rollback() {
            delegate.rollback();
        }

        @Override
        public boolean rollbackWithinBudget(RecipeWorkBudget budget) {
            return delegate.rollbackWithinBudget(budget);
        }

        private Resolution observe(Resolution resolution) {
            if (resolution == Resolution.COMPLETED) completed = true;
            return resolution;
        }

        @Override
        public Resolution resolution() {
            return observe(delegate.resolution());
        }

        @Override
        public @Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            var resolution = delegate.resolutionWithinBudget(budget);
            return resolution == null ? null : observe(resolution);
        }
    }

    private static final class ObservedHatch implements ResourceHatch, ResourceRecipeObserver {
        private final ResourceHatch source;
        private final @Nullable ResourceRecipeObserver observer;
        private final RecipeProcess process;
        private @Nullable ResourceStorage previous;
        private @Nullable ResourceStorage observed;

        private ObservedHatch(ResourceHatch source, @Nullable ResourceRecipeObserver observer, RecipeProcess process) {
            this.source = source;
            this.observer = observer;
            this.process = process;
        }

        @Override
        public @Nullable ResourceStorage input() {
            var current = source.input();
            if (current != previous) {
                previous = current;
                observed = current == null ? null : new ObservedStorage(current, observer, process);
            }
            return observed;
        }

        @Override
        public @Nullable ResourceStorage output() {
            return source.output();
        }

        @Override
        public @Nullable Object inputStorageIdentity() {
            return source.inputStorageIdentity();
        }

        @Override
        public @Nullable Object outputStorageIdentity() {
            return source.outputStorageIdentity();
        }

        @Override
        public ResourceInputGroup inputGroup() {
            return source.inputGroup();
        }

        @Override
        public boolean sharedInput() {
            return source.sharedInput();
        }

        @Override
        public List<? extends ResourceHatch> inputPartitions() {
            return source.inputPartitions();
        }

        @Override
        public boolean isValid() {
            return source.isValid();
        }

        @Override
        public Object observerIdentity() {
            return observer == null ? this : observer.observerIdentity();
        }

        @Override
        public void attached(RecipeProcess value) {
            if (observer != null) observer.attached(value);
        }

        @Override
        public void detached(RecipeProcess value) {
            if (observer != null) observer.detached(value);
        }

        @Override
        public void stageCommitted(RecipeProcess value, RecipeStage stage) {
            if (observer != null) observer.stageCommitted(value, stage);
        }

        @Override
        public void inputsCommitted(RecipeProcess value, RecipeStage stage, AEKey2LongMap amounts) {
            if (observer != null) observer.inputsCommitted(value, stage, amounts);
        }
    }

    private record ObservedStorage(ResourceStorage delegate, @Nullable ResourceRecipeObserver observer,
                                   RecipeProcess process) implements ResourceStorage {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
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
            return delegate.beginQuantityCapture();
        }

        @Override
        public QuantityCapture beginQuantityCapture(AEKey key) {
            return delegate.beginQuantityCapture(key);
        }

        @Override
        public long amount(AEKey key) {
            return delegate.amount(key);
        }

        @Override
        public long availableAmount(AEKey key) {
            return delegate.availableAmount(key);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode) {
            return delegate.insert(key, amount, mode);
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            return delegate.extract(key, amount, mode);
        }

        @Override
        public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return delegate.reserveInsert(snapshot, key, amount);
        }

        @Override
        public ReservationCapture beginReserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return delegate.beginReserveInsert(snapshot, key, amount);
        }

        @Override
        public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            var receipt = process.prepareResourceInputReceipt(observer, key, amount);
            var result = delegate.reserveExtract(snapshot, key, amount);
            return receipt == null ? result : receipt.bind(result);
        }

        @Override
        public ReservationCapture beginReserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            return new ObservedCapture(delegate.beginReserveExtract(snapshot, key, amount), observer, process, key);
        }
    }

    private static final class ObservedCapture implements ResourceStorage.ReservationCapture {
        private final ResourceStorage.ReservationCapture delegate;
        private final @Nullable ResourceRecipeObserver observer;
        private final RecipeProcess process;
        private final AEKey key;
        private @Nullable ReservationResult result;

        private ObservedCapture(ResourceStorage.ReservationCapture delegate, @Nullable ResourceRecipeObserver observer, RecipeProcess process, AEKey key) {
            this.delegate = delegate;
            this.observer = observer;
            this.process = process;
            this.key = key;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            return delegate.advance(budget);
        }

        @Override
        public long amount() {
            return delegate.amount();
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
        public boolean cancel() {
            return delegate.cancel();
        }

        @Override
        public ResourceReservation.Resolution resolution() {
            return delegate.resolution();
        }

        @Override
        public ResourceReservation.@Nullable Resolution resolutionWithinBudget(RecipeWorkBudget budget) {
            return delegate.resolutionWithinBudget(budget);
        }

        @Override
        public ReservationResult result() {
            if (result != null) return result;
            long amount = delegate.amount();
            // Allocate before result() transfers the native token, retaining capture-owned cleanup on failure.
            var receipt = amount == 0 ? null : process.prepareResourceInputReceipt(observer, key, amount);
            var captured = delegate.result();
            result = receipt == null ? captured : receipt.bind(captured);
            return result;
        }
    }
}
