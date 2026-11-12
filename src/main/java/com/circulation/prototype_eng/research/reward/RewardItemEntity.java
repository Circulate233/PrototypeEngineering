package com.circulation.prototype_eng.research.reward;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

/** A reward owns its stack until its delivery record is confirmed. */
public final class RewardItemEntity extends EntityItem {
    public static final String PENDING_TAG = "DeliveryPending";
    private boolean pending;

    @SuppressWarnings("unused") // Forge loads saved and tracked entities through the World constructor.
    public RewardItemEntity(World world) {
        super(world);
    }

    public RewardItemEntity(World world, double x, double y, double z, ItemStack stack) {
        super(world, x, y, z, stack);
        pending = true;
    }

    public ItemStack deliveryStack() {
        return super.getItem();
    }

    @Override
    public @NotNull ItemStack getItem() {
        return pending && !world.isRemote ? ItemStack.EMPTY : super.getItem();
    }

    @Override
    public void onUpdate() {
        if (!pending || world.isRemote) super.onUpdate();
    }

    @Override
    public void onCollideWithPlayer(@NotNull EntityPlayer player) {
        if (!pending) super.onCollideWithPlayer(player);
    }

    @Override
    public boolean attackEntityFrom(@NotNull DamageSource source, float amount) {
        return !pending && super.attackEntityFrom(source, amount);
    }

    @Override
    public void setDead() {
        // Vanilla hoppers remove an item entity when its exposed stack is empty.
        if (!pending || world.isRemote) super.setDead();
    }

    @Override
    public void writeEntityToNBT(@NotNull NBTTagCompound tag) {
        super.writeEntityToNBT(tag);
        tag.setTag("Item", deliveryStack().writeToNBT(new NBTTagCompound()));
        tag.setBoolean(PENDING_TAG, pending);
    }

    @Override
    public void readEntityFromNBT(@NotNull NBTTagCompound tag) {
        pending = false;
        super.readEntityFromNBT(tag);
        pending = tag.getBoolean(PENDING_TAG);
    }

    public void release(ItemStack stack) {
        setItem(stack);
        pending = false;
        setDefaultPickupDelay();
    }
}
