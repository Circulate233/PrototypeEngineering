package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.data.ComponentData;
import crafttweaker.api.data.IData;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagString;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.OperatorType;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMemberGetter;
import stanhebben.zenscript.annotations.ZenMemberSetter;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.annotations.ZenOperator;


/**
 * IData conversion only; mutable storage and change detection belong to the public Java API.
 */
@ZenClass("mods.prototype_eng.data.ComponentData")
@SuppressWarnings("unused") // Public entry points are resolved by ZenScript's registered type.
public final class ZenComponentData {
    private final ComponentData data;
    private ComponentData.Snapshot captured;
    private IData capturedData;

    public ZenComponentData(ComponentData data) {
        this.data = data;
    }

    @ZenMethod
    public static ZenComponentData create() {
        return new ZenComponentData(new ComponentData());
    }

    @ZenMethod
    public static ZenComponentData from(IData values) {
        var result = create();
        result.data.replace(compound(values));
        return result;
    }

    private static NBTTagCompound compound(IData values) {
        var tag = NBTConverter.from(values);
        if (!(tag instanceof NBTTagCompound compound))
            throw new IllegalArgumentException("Component data requires an IData map");
        return compound;
    }

    @Nullable
    @ZenOperator(OperatorType.INDEXGET)
    @ZenMemberGetter
    public IData get(String key) {
        return NBTConverter.from(data.get(key), true);
    }

    @ZenOperator(OperatorType.INDEXSET)
    @ZenMemberSetter
    public void set(String key, @Nullable IData value) {
        data.set(key, value == null ? null : NBTConverter.from(value));
    }

    @ZenGetter("length")
    public int size() {
        return data.size();
    }

    @ZenMethod
    public boolean has(String key) {
        return data.has(key);
    }

    @ZenMethod
    public void remove(String key) {
        data.remove(key);
    }

    @ZenMethod
    public void clear() {
        data.clear();
    }

    @ZenMethod
    public IData asIData() {
        var snapshot = data.snapshot();
        if (captured != snapshot) {
            captured = snapshot;
            capturedData = NBTConverter.from(snapshot.data(), true);
        }
        return capturedData;
    }

    @ZenMethod
    public void merge(IData values) {
        data.merge(compound(values));
    }

    @ZenMethod
    public int getInt(String key, int defaultValue) {
        var value = data.get(key);
        return value instanceof NBTPrimitive number ? number.getInt() : defaultValue;
    }

    @ZenMethod
    public void setInt(String key, int value) {
        data.set(key, new NBTTagInt(value));
    }

    @ZenMethod
    public long getLong(String key, long defaultValue) {
        var value = data.get(key);
        return value instanceof NBTPrimitive number ? number.getLong() : defaultValue;
    }

    @ZenMethod
    public void setLong(String key, long value) {
        data.set(key, new NBTTagLong(value));
    }

    @ZenMethod
    public double getDouble(String key, double defaultValue) {
        var value = data.get(key);
        return value instanceof NBTPrimitive number ? number.getDouble() : defaultValue;
    }

    @ZenMethod
    public void setDouble(String key, double value) {
        data.set(key, new NBTTagDouble(value));
    }

    @ZenMethod
    public boolean getBool(String key, boolean defaultValue) {
        var value = data.get(key);
        return value instanceof NBTPrimitive number ? number.getByte() != 0 : defaultValue;
    }

    @ZenMethod
    public void setBool(String key, boolean value) {
        data.set(key, new NBTTagByte((byte) (value ? 1 : 0)));
    }

    @ZenMethod
    public String getString(String key, String defaultValue) {
        var value = data.get(key);
        return value instanceof NBTTagString string ? string.getString() : defaultValue;
    }

    @ZenMethod
    public void setString(String key, String value) {
        data.set(key, new NBTTagString(value));
    }
}
