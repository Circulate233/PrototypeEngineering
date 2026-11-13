package com.circulation.prototype_eng.storage;

import ae2.api.AECapabilities;
import ae2.api.behaviors.GenericInternalInventoryAdapters;
import ae2.tile.AEBaseTile;
import ae2.util.Platform;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.gui.ResourceHatchLayout;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.localization.GuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;

/**
 * Shared inventory with external IO only; this tile never initiates automatic transfers.
 */
public class ComputationHatchBlockEntity extends AEBaseTile implements ResourceHatchGuiHost {
    private static final VarHandle STORAGE_DIRTY;

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(ComputationHatchBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private final Reference2ObjectOpenHashMap<Capability<?>, Object> adapters = new Reference2ObjectOpenHashMap<>();
    private int level = 1;
    private BufferedComputationHatch hatch;
    private HatchInventoryPort port;
    private HatchFluidHandler fluids;
    @SuppressWarnings("unused") // Storage workers signal; the tile owner consumes the flag.
    private boolean storageDirty;

    @SuppressWarnings("unused") // Forge constructs saved tile entities reflectively.
    public ComputationHatchBlockEntity() {
    }

    public ComputationHatchBlockEntity(int level) {
        this.level = level;
    }

    public BufferedComputationHatch computationHatch() {
        if (hatch == null) {
            hatch = new BufferedComputationHatch(level, () -> STORAGE_DIRTY.setRelease(this, true));
            port = new HatchInventoryPort(hatch, true, true);
            fluids = new HatchFluidHandler(hatch);
        }
        return hatch;
    }

    @Override
    public SlottedResourceHatch storage() {
        return computationHatch().guiView();
    }

    @Override
    public AEBaseTile blockEntity() {
        return this;
    }

    @Override
    public HatchMode mode() {
        return HatchMode.INPUT;
    }

    @Override
    public HatchTier tier() {
        return HatchTier.of(level);
    }

    @Override
    public boolean canBeRotated() {
        return false;
    }

    @Override
    public ITextComponent guiTitle() {
        return GuiText.ComputationHatch.text();
    }

    @Override
    public ResourceHatchLayout layout() {
        return new ResourceHatchLayout(level, 1, 195, 132, 256, 25, (215 - level * 18) / 2, 0, 27, false,
            new ResourceLocation(Tags.MOD_ID, "textures/gui/computation_hatch/level_" + level + ".png"));
    }

    @Override
    public void update() {
        super.update();
        flushChanges();
    }

    private void flushChanges() {
        if (world != null && !world.isRemote && (boolean) STORAGE_DIRTY.getAndSet(this, false)) markDirty();
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> capability, @Nullable EnumFacing side) {
        return !isInvalid() && (capability == HatchCapabilities.RESOURCE || capability == AECapabilities.ME_STORAGE
            || capability == AECapabilities.GENERIC_INTERNAL_INV || capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
            || GenericInternalInventoryAdapters.hasAdapter(capability)) || super.hasCapability(capability, side);
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable <T> T getCapability(@NotNull Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid()) {
            var current = computationHatch();
            if (capability == HatchCapabilities.RESOURCE) return HatchCapabilities.RESOURCE.cast(current);
            if (capability == AECapabilities.ME_STORAGE) return AECapabilities.ME_STORAGE.cast(current.automation());
            if (capability == AECapabilities.GENERIC_INTERNAL_INV)
                return AECapabilities.GENERIC_INTERNAL_INV.cast(port);
            if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
                return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(fluids);
            if (GenericInternalInventoryAdapters.hasAdapter(capability))
                return (T) adapters.computeIfAbsent(capability, _ -> capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
                    ? new HatchItemHandler(port) : GenericInternalInventoryAdapters.getCapability(port, capability));
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        tag.setInteger("Tier", level);
        computationHatch().save(tag);
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        if (hatch != null && hatch.inventory().hasTransferReservation())
            throw new IllegalStateException("Cannot reload a computation hatch while an external transfer is unsettled");
        super.loadTag(tag);
        if (hatch != null) hatch.invalidate();
        level = StrictNbt.requireInt(tag, "Tier");
        hatch = null;
        adapters.clear();
        computationHatch().load(tag);
    }

    public NBTTagCompound saveForItemDrop() {
        var tag = new NBTTagCompound();
        tag.setInteger("Tier", level);
        tag.setTag("Contents", new NBTTagList());
        return tag;
    }

    @Override
    public void addAdditionalDrops(List<ItemStack> drops) {
        super.addAdditionalDrops(drops);
        if (world.isRemote) return;
        var removalWorld = world;
        var position = pos.toImmutable();
        var server = Objects.requireNonNull(world.getMinecraftServer());
        var contents = computationHatch().removeForDrops(deferred -> {
            Runnable deliver = () -> {
                var delayed = new ObjectArrayList<ItemStack>();
                for (var stack : deferred) stack.what().addDrops(stack.amount(), delayed, removalWorld, position);
                Platform.spawnDrops(removalWorld, position, delayed);
            };
            if (server.isCallingFromMinecraftThread()) deliver.run();
            else server.addScheduledTask(deliver);
        });
        for (var stack : contents) stack.what().addDrops(stack.amount(), drops, removalWorld, position);
    }

    @Override
    protected void setRemoved() {
        if (hatch != null) hatch.invalidate();
        flushChanges();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        if (hatch != null) hatch.invalidate();
        flushChanges();
        super.onChunkUnloaded();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        if (hatch != null && !hatch.isValid() && !hatch.inventory().hasTransferReservation()) {
            var saved = new NBTTagCompound();
            saveAdditional(saved);
            loadTag(saved);
        }
    }

    @Override
    public @NotNull NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(@NotNull NetworkManager network, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(@NotNull NBTTagCompound tag) {
        readFromNBT(tag);
    }
}
