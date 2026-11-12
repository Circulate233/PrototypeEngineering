package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceInputGroup;
import com.circulation.prototype_eng.api.storage.ResourceRecipeObserver;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Independent transactional ledgers; ME callbacks and recipe workers share these exact balances.
 */
public final class MePatternAssemblyStorage implements ResourceHatch {
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt();
    private final ResourceKeyNbt keys;
    private final UnboundedResourceBuffer input;
    private final UnboundedResourceBuffer catalyst;
    private final UnboundedResourceBuffer output;
    private final ObjectArrayList<UnboundedResourceBuffer> isolated = new ObjectArrayList<>();
    private final ObjectArrayList<StorageContext> contexts = new ObjectArrayList<>();
    private final Object2ObjectOpenHashMap<UUID, ObjectArrayList<AEKey2LongMap>> started = new Object2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<RecipeProcess, Boolean> attached = new Reference2ObjectOpenHashMap<>();
    private final Supplier<AEColor> color;
    private final Runnable changed;
    private volatile MePatternAssemblyBlockEntity.Mode mode = MePatternAssemblyBlockEntity.Mode.DEFAULT;
    private volatile List<ResourceHatch> partitions;
    private volatile String identity = "unbound/";
    private boolean craftingLocked;
    private boolean lockRecipeSeen;
    private volatile boolean valid = true;

    public MePatternAssemblyStorage(int slots, Supplier<AEColor> color, Runnable changed) {
        this(slots, color, changed, KEYS);
    }

    MePatternAssemblyStorage(int slots, Supplier<AEColor> color, Runnable changed, ResourceKeyNbt keys) {
        if (slots < 1) throw new IllegalArgumentException("Pattern inventory needs positive slots");
        this.color = Objects.requireNonNull(color);
        this.changed = Objects.requireNonNull(changed);
        this.keys = Objects.requireNonNull(keys);
        input = buffer(changed);
        catalyst = buffer(changed);
        output = buffer(changed);
        for (int slot = 0; slot < slots; slot++) isolated.add(buffer(changed));
        rebuildPartitions();
    }

    private UnboundedResourceBuffer buffer(Runnable listener) {
        var buffer = new UnboundedResourceBuffer(listener);
        contexts.add(buffer.context());
        return buffer;
    }

    public synchronized void bindIdentity(String identity) {
        if (!this.identity.startsWith("unbound/")) return;
        this.identity = Objects.requireNonNull(identity) + "/";
        for (int index = 0; index < contexts.size(); index++)
            contexts.get(index).bindSourceIdentity(this.identity + index);
        rebuildPartitions();
    }

    private void rebuildPartitions() {
        var views = new ObjectArrayList<ResourceHatch>();
        if (mode == MePatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION) {
            for (int slot = 0; slot < isolated.size(); slot++)
                views.add(new Port(isolated.get(slot), identity + "pattern/" + slot, false));
        } else views.add(new Port(input, "", false));
        views.add(new Port(catalyst, mode == MePatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? identity : "", true));
        partitions = List.copyOf(views);
    }

    public MePatternAssemblyBlockEntity.Mode mode() {
        return mode;
    }

    public synchronized boolean setMode(MePatternAssemblyBlockEntity.Mode next) {
        Objects.requireNonNull(next);
        if (next == mode) return true;
        if (!valid || !idle()) return false;
        mode = next;
        craftingLocked = false;
        rebuildPartitions();
        changed.run();
        return true;
    }

    @Override
    public ResourceInputGroup inputGroup() {
        return new ResourceInputGroup(color.get());
    }

    @Override
    public List<? extends ResourceHatch> inputPartitions() {
        return partitions;
    }

    @Override
    public @Nullable ResourceStorage input() {
        return mode == MePatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? null : input;
    }

    @Override
    public @NotNull UnboundedResourceBuffer output() {
        return output;
    }

    public UnboundedResourceBuffer catalyst() {
        return catalyst;
    }

    public synchronized boolean accepts(AEKey2LongMap signature) {
        if (!valid) return false;
        return switch (mode) {
            case DEFAULT, PATTERN_ISOLATION -> true;
            case BLOCKING -> inputsEmpty();
            case CRAFTING_LOCK -> !craftingLocked;
            case RECIPE_BLOCKING -> inputsEmpty() || runningSignature(signature);
        };
    }

    public synchronized boolean busy() {
        return !valid || mode == MePatternAssemblyBlockEntity.Mode.BLOCKING && !inputsEmpty()
            || mode == MePatternAssemblyBlockEntity.Mode.CRAFTING_LOCK && craftingLocked;
    }

    private boolean runningSignature(AEKey2LongMap signature) {
        for (var signatures : started.values()) if (signatures.contains(signature)) return true;
        return false;
    }

    public synchronized boolean push(int slot, AEKey2LongMap amounts) {
        Objects.checkIndex(slot, isolated.size());
        if (amounts.isEmpty() || !accepts(PatternInputSignature.normalize(amounts))) return false;
        var buffer = mode == MePatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? isolated.get(slot) : input;
        var tokens = new ObjectArrayList<ResourceReservation>();
        var context = buffer.context();
        if (!context.tryOrdinaryLock()) return false;
        boolean reserved = true;
        Throwable reservationFailure = null;
        try {
            for (var entry : amounts.object2LongEntrySet()) {
                if (entry.getLongValue() <= 0 || buffer.insert(entry.getKey(), entry.getLongValue(), Actionable.SIMULATE) != entry.getLongValue()) {
                    return false;
                }
            }
            for (var entry : amounts.object2LongEntrySet()) {
                var reservation = buffer.reserveInsert(buffer.snapshot(), entry.getKey(), entry.getLongValue());
                if (reservation.reservation() == null) {
                    reserved = false;
                    break;
                }
                tokens.add(reservation.reservation());
            }
        } catch (RuntimeException | Error failure) {
            reserved = false;
            reservationFailure = failure;
        } finally {
            context.unlock();
        }
        if (!reserved) {
            ResourceTransactions.rollback(tokens);
            if (reservationFailure instanceof RuntimeException runtime) throw runtime;
            if (reservationFailure instanceof Error error) throw error;
            return false;
        }
        return ResourceTransactions.commit(tokens, () -> {
        }, () -> {
            if (mode == MePatternAssemblyBlockEntity.Mode.CRAFTING_LOCK) {
                craftingLocked = true;
                lockRecipeSeen = false;
            }
        }, () -> {
        });
    }

    public synchronized int maxMultiplier(int slot, AEKey2LongMap perPush, int requested) {
        if (requested <= 0 || perPush.isEmpty() || !accepts(PatternInputSignature.normalize(perPush))) return 0;
        var buffer = mode == MePatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION ? isolated.get(Objects.checkIndex(slot, isolated.size())) : input;
        long limit = requested;
        for (var entry : perPush.object2LongEntrySet()) {
            long each = entry.getLongValue();
            if (each <= 0) return 0;
            limit = Math.min(limit, buffer.insert(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE) / each);
        }
        return (int) limit;
    }

    public synchronized void attached(RecipeProcess process) {
        attached.put(process, Boolean.TRUE);
    }

    public synchronized void detached(RecipeProcess process) {
        attached.remove(process);
        started.remove(process.identity());
        unlockIfFinished();
    }

    public synchronized void stageCommitted(RecipeProcess process, RecipeStage stage) {
        if (stage == RecipeStage.END) {
            started.remove(process.identity());
            attached.remove(process);
            unlockIfFinished();
        }
    }

    public synchronized void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
        if (amounts.isEmpty()) return;
        var signatures = started.computeIfAbsent(process.identity(), _ -> new ObjectArrayList<>());
        var complete = process.committedInputs(stage);
        if (complete != null && !complete.isEmpty()) {
            var signature = PatternInputSignature.normalize(complete);
            if (!signatures.contains(signature)) signatures.add(signature);
        }
        if (craftingLocked) {
            lockRecipeSeen = true;
        }
    }

    private void unlockIfFinished() {
        if (craftingLocked && lockRecipeSeen && started.isEmpty() && inputsEmpty()) {
            craftingLocked = false;
            lockRecipeSeen = false;
            changed.run();
        }
    }

    private boolean inputsEmpty() {
        if (!empty(input)) return false;
        for (var buffer : isolated) if (!empty(buffer)) return false;
        return true;
    }

    private static boolean empty(UnboundedResourceBuffer buffer) {
        var context = buffer.context();
        context.lock();
        try {
            return !buffer.hasReservations() && buffer.snapshot().available().isEmpty();
        } finally {
            context.unlock();
        }
    }

    public boolean hasReservations() {
        if (input.hasReservations() || catalyst.hasReservations() || output.hasReservations()) return true;
        for (var buffer : isolated) if (buffer.hasReservations()) return true;
        for (var context : contexts) {
            context.lock();
            try {
                if (!context.idleForReplacement()) return true;
            } finally {
                context.unlock();
            }
        }
        return false;
    }

    public List<GenericStack> inputContents() {
        var values = new ObjectArrayList<GenericStack>();
        input.snapshot().available().forEachEntry((key, amount) -> values.add(new GenericStack(key, amount)));
        for (var buffer : isolated)
            buffer.snapshot().available().forEachEntry((key, amount) -> values.add(new GenericStack(key, amount)));
        return List.copyOf(values);
    }

    public MePatternAssemblyBlockEntity.BufferView inputView() {
        StorageContext.lockAll(contexts);
        try {
            long revision = input.context().revision();
            for (var buffer : isolated) revision = Math.addExact(revision, buffer.context().revision());
            return new MePatternAssemblyBlockEntity.BufferView(revision, inputContents());
        } finally {
            for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlock();
        }
    }

    public synchronized boolean idle() {
        return valid && attached.isEmpty() && started.isEmpty() && !hasReservations() && inputsEmpty();
    }

    public void exportOutput(MeResourceHatch.MeTransfer transfer) {
        transfer(output, transfer);
    }

    public synchronized boolean returnInputs(MeResourceHatch.MeTransfer transfer) {
        if (!valid) return false;
        transfer(input, transfer);
        for (var buffer : isolated) transfer(buffer, transfer);
        if (inputsEmpty() && started.isEmpty()) {
            craftingLocked = false;
            lockRecipeSeen = false;
            changed.run();
        }
        return inputsEmpty();
    }

    private static void transfer(UnboundedResourceBuffer buffer, MeResourceHatch.MeTransfer transfer) {
        var snapshot = buffer.snapshot();
        for (var entry : snapshot.available().object2LongEntrySet()) {
            var context = buffer.context();
            if (!context.tryOrdinaryLock()) continue;
            try {
                if (buffer.hasTransferReservation()) return;
                long offer = buffer.extract(entry.getKey(), entry.getLongValue(), Actionable.SIMULATE);
                if (offer == 0) continue;
                var receipt = buffer.reserveTransfer(entry.getKey(), offer, false);
                if (receipt != null) {
                    try {
                        receipt.complete(transfer.insert(entry.getKey(), offer));
                    } catch (RuntimeException | Error failure) {
                        context.invalidate();
                        throw failure;
                    }
                }
            } finally {
                context.unlock();
            }
        }
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    public synchronized void invalidate() {
        valid = false;
        for (var context : contexts) {
            context.lock();
            try {
                context.invalidate();
            } finally {
                context.unlock();
            }
        }
    }

    public synchronized void save(NBTTagCompound tag) {
        StorageContext.lockAll(contexts);
        try {
            tag.setString("Mode", mode.name());
            tag.setBoolean("CraftingLocked", craftingLocked);
            tag.setBoolean("LockRecipeSeen", lockRecipeSeen);
            var running = new NBTTagList();
            started.forEach((process, signatures) -> {
                var record = new NBTTagCompound();
                record.setString("Process", process.toString());
                var savedSignatures = new NBTTagList();
                for (var signature : signatures) {
                    var stacks = new ObjectArrayList<GenericStack>();
                    signature.forEachEntry((key, amount) -> stacks.add(new GenericStack(key, amount)));
                    var savedSignature = new NBTTagCompound();
                    savedSignature.setTag("Amounts", keys.writeStacks(stacks));
                    savedSignatures.appendTag(savedSignature);
                }
                record.setTag("Signatures", savedSignatures);
                running.appendTag(record);
            });
            tag.setTag("Running", running);
            tag.setTag("Input", keys.writeStacks(input.committedContents()));
            tag.setTag("Catalyst", keys.writeStacks(catalyst.committedContents()));
            tag.setTag("Output", keys.writeStacks(output.committedContents()));
            var slots = new NBTTagList();
            for (int slot = 0; slot < isolated.size(); slot++) {
                var contents = isolated.get(slot).committedContents();
                if (contents.isEmpty()) continue;
                var entry = new NBTTagCompound();
                entry.setInteger("Slot", slot);
                entry.setTag("Contents", keys.writeStacks(contents));
                slots.appendTag(entry);
            }
            tag.setTag("Isolated", slots);
        } finally {
            for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlock();
        }
    }

    public static MePatternAssemblyStorage load(NBTTagCompound tag, int slots, Supplier<AEColor> color, Runnable changed) {
        return load(tag, slots, color, changed, KEYS);
    }

    static MePatternAssemblyStorage load(NBTTagCompound tag, int slots, Supplier<AEColor> color, Runnable changed, ResourceKeyNbt keys) {
        var next = new MePatternAssemblyStorage(slots, color, changed, keys);
        next.mode = MePatternAssemblyBlockEntity.Mode.valueOf(StrictNbt.requireString(tag, "Mode"));
        next.craftingLocked = StrictNbt.requireBoolean(tag, "CraftingLocked");
        next.lockRecipeSeen = StrictNbt.requireBoolean(tag, "LockRecipeSeen");
        if (next.craftingLocked && next.mode != MePatternAssemblyBlockEntity.Mode.CRAFTING_LOCK)
            throw new IllegalArgumentException("Invalid saved crafting lock mode");
        var running = StrictNbt.requireList(tag, "Running", 10);
        for (int index = 0; index < running.tagCount(); index++) {
            var record = running.getCompoundTagAt(index);
            StrictNbt.requireKeys(record, "Process", "Signatures");
            var process = UUID.fromString(StrictNbt.requireString(record, "Process"));
            var signatures = new ObjectArrayList<AEKey2LongMap>();
            var savedSignatures = StrictNbt.requireList(record, "Signatures", 10);
            for (int entry = 0; entry < savedSignatures.tagCount(); entry++) {
                var signatureTag = savedSignatures.getCompoundTagAt(entry);
                StrictNbt.requireKeys(signatureTag, "Amounts");
                AEKey2LongMap signature = new AEKey2LongMap.OpenHashMap();
                for (var stack : keys.readStacks(StrictNbt.requireList(signatureTag, "Amounts", 10)))
                    if (!PatternInputSignature.add(signature, stack.what(), stack.amount()))
                        throw new IllegalArgumentException("Invalid saved resource receipt");
                signature = PatternInputSignature.normalize(signature);
                if (signature.isEmpty() || signatures.contains(signature))
                    throw new IllegalArgumentException("Empty or duplicate resource receipt");
                signatures.add(signature);
            }
            if (next.started.putIfAbsent(process, signatures) != null)
                throw new IllegalArgumentException("Duplicate running process receipt");
        }
        next.input.restore(keys.readStacks(StrictNbt.requireList(tag, "Input", 10)));
        next.catalyst.restore(keys.readStacks(StrictNbt.requireList(tag, "Catalyst", 10)));
        next.output.restore(keys.readStacks(StrictNbt.requireList(tag, "Output", 10)));
        var saved = StrictNbt.requireList(tag, "Isolated", 10);
        boolean[] visited = new boolean[slots];
        for (int index = 0; index < saved.tagCount(); index++) {
            var entry = saved.getCompoundTagAt(index);
            int slot = Objects.checkIndex(StrictNbt.requireInt(entry, "Slot"), slots);
            if (visited[slot]) throw new IllegalArgumentException("Repeated isolated pattern slot");
            visited[slot] = true;
            next.isolated.get(slot).restore(keys.readStacks(StrictNbt.requireList(entry, "Contents", 10)));
        }
        if (next.mode == MePatternAssemblyBlockEntity.Mode.PATTERN_ISOLATION) {
            if (!empty(next.input)) throw new IllegalArgumentException("Isolated mode has an unpartitioned input balance");
        } else for (var buffer : next.isolated)
            if (!empty(buffer)) throw new IllegalArgumentException("Non-isolated mode has hidden isolated inputs");
        next.rebuildPartitions();
        return next;
    }

    public synchronized List<GenericStack> removeForDrops(Consumer<List<GenericStack>> deferred) {
        valid = false;
        var drops = new ObjectArrayList<GenericStack>();
        StorageContext.lockAll(contexts);
        try {
            drops.addAll(input.removeForDrops(deferred));
            drops.addAll(catalyst.removeForDrops(deferred));
            drops.addAll(output.removeForDrops(deferred));
            for (var buffer : isolated) drops.addAll(buffer.removeForDrops(deferred));
        } finally {
            for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlock();
        }
        return drops;
    }

    private final class Port implements ResourceHatch, ResourceRecipeObserver {
        private final UnboundedResourceBuffer buffer;
        private final String partition;
        private final boolean shared;

        private Port(UnboundedResourceBuffer buffer, String partition, boolean shared) {
            this.buffer = buffer;
            this.partition = partition;
            this.shared = shared;
        }

        @Override
        public ResourceInputGroup inputGroup() {
            return new ResourceInputGroup(color.get(), partition);
        }

        @Override
        public boolean sharedInput() {
            return shared;
        }

        @Override
        public ResourceStorage input() {
            return buffer;
        }

        @Override
        public ResourceStorage output() {
            return output;
        }

        @Override
        public boolean isValid() {
            return valid && buffer.isValid();
        }

        @Override
        public Object observerIdentity() {
            return shared ? this : MePatternAssemblyStorage.this;
        }

        @Override
        public void attached(RecipeProcess process) {
            if (!shared) MePatternAssemblyStorage.this.attached(process);
        }

        @Override
        public void detached(RecipeProcess process) {
            if (!shared) MePatternAssemblyStorage.this.detached(process);
        }

        @Override
        public void stageCommitted(RecipeProcess process, RecipeStage stage) {
            if (!shared) MePatternAssemblyStorage.this.stageCommitted(process, stage);
        }

        @Override
        public void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
            if (!shared) MePatternAssemblyStorage.this.inputsCommitted(process, stage, amounts);
        }
    }
}
