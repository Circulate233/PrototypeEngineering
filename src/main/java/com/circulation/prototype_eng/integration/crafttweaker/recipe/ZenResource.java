package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.liquid.ILiquidStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.nbt.NBTTagCompound;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;


/**
 * AEKey identity and a long quantity; item and fluid conversions do not narrow the stored amount.
 */
// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Resource")
public final class ZenResource {
    private final GenericStack stack;
    private IData keyData;

    public ZenResource(AEKey key, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Resource amount must be positive");
        stack = new GenericStack(key, amount);
    }

    @ZenMethod
    public static ZenResource item(IItemStack item, long amount) {
        var key = AEItemKey.of(CraftTweakerMC.getItemStack(item));
        if (key == null) throw new IllegalArgumentException("Empty item resource");
        return new ZenResource(key, amount);
    }

    @ZenMethod
    public static ZenResource fluid(ILiquidStack fluid, long amount) {
        var key = AEFluidKey.of(CraftTweakerMC.getLiquidStack(fluid));
        if (key == null) throw new IllegalArgumentException("Empty fluid resource");
        return new ZenResource(key, amount);
    }

    /**
     * Accepts the public ResourceKeyNbt Type/Data format, including addon AEKey types.
     */
    @ZenMethod
    public static ZenResource fromTag(IData data, long amount) {
        var tag = NBTConverter.from(data);
        if (!(tag instanceof NBTTagCompound compound))
            throw new IllegalArgumentException("Resource key requires a compound");
        return new ZenResource(ResourceKeyNbt.INSTANCE.decode(compound), amount);
    }

    @ZenMethod
    public IData toTag() {
        return NBTConverter.from(ResourceKeyNbt.INSTANCE.encode(key()), true);
    }

    @ZenMethod
    public IData keyData() {
        if (keyData == null) keyData = NBTConverter.from(key().toTag(), true);
        return keyData;
    }

    @ZenMethod
    public ZenResource withAmount(long amount) {
        return new ZenResource(key(), amount);
    }

    @ZenMethod
    public boolean sameKey(ZenResource other) {
        return key().equals(other.key());
    }

    @ZenGetter("amount")
    public long amount() {
        return stack.amount();
    }

    @ZenGetter("type")
    public String type() {
        return key().getType().getId().toString();
    }

    @ZenGetter("id")
    public String id() {
        var id = key().getId();
        return id == null ? "" : id.toString();
    }

    @ZenGetter("isItem")
    public boolean isItem() {
        return key() instanceof AEItemKey;
    }

    @ZenGetter("isFluid")
    public boolean isFluid() {
        return key() instanceof AEFluidKey;
    }

    @ZenGetter("item")
    public IItemStack item() {
        if (!(key() instanceof AEItemKey item)) throw new IllegalStateException("Resource is not an item");
        return CraftTweakerMC.getIItemStack(item.toStack(1));
    }

    public AEKey key() {
        return stack.what();
    }

    public GenericStack stack() {
        return stack;
    }
}
