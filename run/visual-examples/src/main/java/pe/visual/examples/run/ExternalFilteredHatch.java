package pe.visual.examples.run;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import ae2.tile.AEBaseTile;
import ae2.util.Platform;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.storage.HatchFluidHandler;
import com.circulation.prototype_eng.storage.HatchInventoryPort;
import com.circulation.prototype_eng.storage.HatchItemHandler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;

/** A genuinely separate AE tile; only the existing bounded storage engine and public adapters are reused. */
public final class ExternalFilteredHatch extends AEBaseTile implements ResourceHatchGuiHost {
    private static final VarHandle DIRTY;
    static {
        try { DIRTY = MethodHandles.lookup().findVarHandle(ExternalFilteredHatch.class, "storageDirty", boolean.class); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    @SuppressWarnings("unused")
    private boolean storageDirty;
    private BufferedResourceHatch backend = freshBackend();
    private View view = new View(backend);
    private HatchInventoryPort inventory = new HatchInventoryPort(view, true, true);
    private HatchFluidHandler fluids = new HatchFluidHandler(view);
    private HatchItemHandler itemAdapter;
    private String allocationIdentity;

    public ExternalFilteredHatch() { }

    private BufferedResourceHatch freshBackend() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 64L);
        capacities.put(AEKeyType.fluids(), 1000L);
        return new BufferedResourceHatch(ResourceHatchConfiguration.symmetric(HatchMode.MIXED, 2, capacities),
            () -> DIRTY.setRelease(this, true));
    }
    @Override public AEBaseTile blockEntity() { return this; }
    @Override public boolean canBeRotated() { return false; }
    @Override public HatchMode mode() { return HatchMode.MIXED; }
    @Override public HatchTier tier() { return HatchTier.LV1; }
    @Override public SlottedResourceHatch storage() { return view; }

    @Override public void onLoad() {
        super.onLoad();
        if (world != null && !world.isRemote && world.getTileEntity(pos) == this && backend.isValid()) {
            String source = "pe_run_tests:external/" + world.provider.getDimension() + "/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ();
            view.input().context().bindSourceIdentity(source + "/input");
            view.output().context().bindSourceIdentity(source + "/output");
            allocationIdentity = source;
        }
    }
    @Override public void setPos(BlockPos position) {
        if (allocationIdentity != null && !pos.equals(position)) invalidate();
        super.setPos(position);
    }
    @Override public void setWorld(World nextWorld) {
        if (allocationIdentity != null && world != nextWorld) invalidate();
        super.setWorld(nextWorld);
    }
    @Override public void update() {
        super.update();
        flushStorageChanges();
    }
    private void flushStorageChanges() {
        if (world != null && !world.isRemote && (boolean) DIRTY.getAndSet(this, false)) markDirty();
    }
    @Override protected void setRemoved() {
        backend.invalidate();
        flushStorageChanges();
        itemAdapter = null;
        super.setRemoved();
    }
    @Override protected void onChunkUnloaded() {
        backend.invalidate();
        flushStorageChanges();
        itemAdapter = null;
        super.onChunkUnloaded();
    }

    @Override public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing side) {
        return !isInvalid() && view.isValid() && (capability == HatchCapabilities.RESOURCE || capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
            || capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) || super.hasCapability(capability, side);
    }
    @Override @SuppressWarnings("unchecked") // Exact registered capability identity determines the matching public value type.
    public <T> @Nullable T getCapability(Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid() && view.isValid()) {
            if (capability == HatchCapabilities.RESOURCE) return (T) view;
            if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) {
                if (itemAdapter == null) itemAdapter = new HatchItemHandler(inventory);
                return (T) itemAdapter;
            }
            if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) return (T) fluids;
        }
        return super.getCapability(capability, side);
    }

    @Override public void saveAdditional(NBTTagCompound tag) {
        super.saveAdditional(tag);
        var contents = new NBTTagCompound();
        backend.save(contents);
        tag.setTag("ExternalResources", contents);
    }
    @Override public void loadTag(NBTTagCompound tag) {
        var replacement = freshBackend();
        replacement.load(tag.getCompoundTag("ExternalResources"));
        var nextView = new View(replacement);
        validateFilter(nextView.input()); validateFilter(nextView.output());
        if (isInvalid()) replacement.invalidate();
        backend.invalidate();
        super.loadTag(tag);
        backend = replacement; view = nextView;
        inventory = new HatchInventoryPort(nextView, true, true); fluids = new HatchFluidHandler(nextView);
        itemAdapter = null;
        allocationIdentity = null;
    }
    private static void validateFilter(FilteredStorage storage) {
        for (int slot = 0; slot < storage.size(); slot++) {
            var stack = storage.getStack(slot);
            if (stack != null && !storage.accepts(stack.what())) throw new IllegalArgumentException("Saved resource violates external hatch filter");
        }
    }

    @Override public void addAdditionalDrops(List<ItemStack> drops) {
        super.addAdditionalDrops(drops);
        if (world == null || world.isRemote) return;
        var removalWorld = world; var removalPosition = pos.toImmutable();
        var server = Objects.requireNonNull(removalWorld.getMinecraftServer());
        var contents = backend.removeForDrops(deferred -> {
            Runnable deliver = () -> {
                var delayed = new ObjectArrayList<ItemStack>();
                for (var resource : deferred) resource.what().addDrops(resource.amount(), delayed, removalWorld, removalPosition);
                Platform.spawnDrops(removalWorld, removalPosition, delayed);
            };
            if (server.isCallingFromMinecraftThread()) deliver.run(); else server.addScheduledTask(deliver);
        });
        for (var resource : contents) resource.what().addDrops(resource.amount(), drops, removalWorld, removalPosition);
    }

    private final class View implements SlottedResourceHatch {
        private final BufferedResourceHatch backend;
        private final FilteredStorage input, output;
        private View(BufferedResourceHatch backend) {
            this.backend = backend;
            input = new FilteredStorage(Objects.requireNonNull(backend.input()), true);
            output = new FilteredStorage(Objects.requireNonNull(backend.output()), false);
        }
        @Override public FilteredStorage input() { return input; }
        @Override public FilteredStorage output() { return output; }
        @Override public boolean isValid() { return !ExternalFilteredHatch.this.isInvalid() && backend.isValid(); }
    }

    /** Filter guards every admission surface; transactions retain the delegate's exact real tokens/context. */
    private static final class FilteredStorage implements SlottedResourceStorage {
        private final SlottedResourceStorage delegate;
        private final boolean input;
        private FilteredStorage(SlottedResourceStorage delegate, boolean input) { this.delegate = delegate; this.input = input; }
        private boolean accepts(AEKey key) {
            if (key instanceof AEFluidKey fluid) return fluid.getFluid() == FluidRegistry.WATER;
            return key instanceof AEItemKey item && item.getItem() == (input ? Items.IRON_INGOT : Items.GOLD_NUGGET);
        }
        @Override public StorageContext context() { return delegate.context(); }
        @Override public StorageSnapshot snapshot() { return delegate.snapshot(); }
        @Override public StorageSnapshot snapshotFor(AEKey key) { return delegate.snapshotFor(key); }
        @Override public long amount(AEKey key) { return delegate.amount(key); }
        @Override public long availableAmount(AEKey key) { return delegate.availableAmount(key); }
        @Override public long insert(AEKey key, long amount, Actionable mode) {
            checkRequest(key, amount, mode);
            return accepts(key) ? delegate.insert(key, amount, mode) : 0;
        }
        @Override public long extract(AEKey key, long amount, Actionable mode) { return delegate.extract(key, amount, mode); }
        @Override public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) { return delegate.reserveExtract(snapshot, key, amount); }
        @Override public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(key);
            if (amount <= 0) throw new IllegalArgumentException("External reservation needs a positive amount");
            return accepts(key) ? delegate.reserveInsert(snapshot, key, amount) : new ReservationResult(
                delegate.isValid() ? ReservationResult.Status.INSUFFICIENT : ReservationResult.Status.INVALID_STORAGE, null);
        }
        @Override public int size() { return delegate.size(); }
        @Override public @Nullable GenericStack getStack(int slot) { return delegate.getStack(slot); }
        @Override public long getAmount(int slot) { return delegate.getAmount(slot); }
        @Override public long availableAmount(int slot) { return delegate.availableAmount(slot); }
        @Override public long getCapacity(AEKeyType type) { return delegate.getCapacity(type); }
        @Override public long getMaxAmount(AEKey key) { return accepts(key) ? delegate.getMaxAmount(key) : 0; }
        @Override public boolean isSupportedType(AEKeyType type) { return delegate.isSupportedType(type); }
        @Override public boolean isAllowedIn(int slot, AEKey key) { return accepts(key) && delegate.isAllowedIn(slot, key); }
        @Override public long insert(int slot, AEKey key, long amount, Actionable mode) {
            Objects.checkIndex(slot, size()); checkRequest(key, amount, mode);
            return accepts(key) ? delegate.insert(slot, key, amount, mode) : 0;
        }
        @Override public long extract(int slot, AEKey key, long amount, Actionable mode) { return delegate.extract(slot, key, amount, mode); }
        @Override public boolean tryReplaceSlot(int slot, @Nullable GenericStack expected, @Nullable GenericStack next) {
            return (next == null || accepts(next.what())) && delegate.tryReplaceSlot(slot, expected, next);
        }
        @Override public int[] drainChangedSlots() { return delegate.drainChangedSlots(); }
        private static void checkRequest(AEKey key, long amount, Actionable mode) {
            Objects.requireNonNull(key); Objects.requireNonNull(mode);
            if (amount < 0) throw new IllegalArgumentException("Negative external resource transfer");
        }
    }
}
