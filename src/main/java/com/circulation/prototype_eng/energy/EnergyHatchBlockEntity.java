package com.circulation.prototype_eng.energy;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.HatchCapabilities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import org.jetbrains.annotations.Nullable;

public final class EnergyHatchBlockEntity extends TileEntity {
    private EnergyBuffer buffer = new EnergyBuffer(HatchConfig.energyCapacity, this::markDirty);
    private ForgeEnergyInput port = new ForgeEnergyInput(buffer);

    public EnergyBuffer storage() { return buffer; }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing side) {
        return !isInvalid() && (capability == HatchCapabilities.ENERGY_INPUT || capability == CapabilityEnergy.ENERGY)
                || super.hasCapability(capability, side);
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid()) {
            if (capability == HatchCapabilities.ENERGY_INPUT) { return HatchCapabilities.ENERGY_INPUT.cast(buffer); }
            if (capability == CapabilityEnergy.ENERGY) { return CapabilityEnergy.ENERGY.cast(port); }
        }
        return super.getCapability(capability, side);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setLong("Capacity", buffer.capacity());
        tag.setLong("Energy", buffer.savedEnergy());
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        buffer.invalidate();
        long capacity = tag.hasKey("Capacity", 4) ? tag.getLong("Capacity") : HatchConfig.energyCapacity;
        buffer = new EnergyBuffer(capacity, this::markDirty);
        buffer.restore(tag.getLong("Energy"));
        port = new ForgeEnergyInput(buffer);
        if (isInvalid()) { buffer.invalidate(); }
    }

    @Override
    public void invalidate() {
        buffer.invalidate();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        buffer.invalidate();
        super.onChunkUnload();
    }

    @Override
    public void validate() {
        super.validate();
        if (!buffer.isValid()) { readFromNBT(writeToNBT(new NBTTagCompound())); }
    }

    @Override
    public NBTTagCompound getUpdateTag() { return writeToNBT(new NBTTagCompound()); }
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() { return new SPacketUpdateTileEntity(pos, 0, getUpdateTag()); }
    @Override
    public void onDataPacket(NetworkManager network, SPacketUpdateTileEntity packet) { readFromNBT(packet.getNbtCompound()); }
    @Override
    public void handleUpdateTag(NBTTagCompound tag) { readFromNBT(tag); }
}
