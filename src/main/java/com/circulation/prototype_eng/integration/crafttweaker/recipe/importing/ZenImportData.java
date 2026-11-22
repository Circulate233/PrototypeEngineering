package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing;

import crafttweaker.api.data.DataBool;
import crafttweaker.api.data.DataByte;
import crafttweaker.api.data.DataDouble;
import crafttweaker.api.data.DataFloat;
import crafttweaker.api.data.DataInt;
import crafttweaker.api.data.DataList;
import crafttweaker.api.data.DataLong;
import crafttweaker.api.data.DataShort;
import crafttweaker.api.data.DataString;
import crafttweaker.api.data.IData;
import crafttweaker.mc1120.data.NBTConverter;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTBase;

import java.util.List;

public final class ZenImportData {
    private ZenImportData() {}

    public static IData[] extras(List<?> values) {
        var result = new IData[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = value(values.get(i));
        return result;
    }

    private static IData value(Object value) {
        return switch (value) {
            case IData data -> data.immutable();
            case NBTBase tag -> NBTConverter.from(tag.copy(), true);
            case Boolean flag -> new DataBool(flag);
            case Byte number -> new DataByte(number);
            case Short number -> new DataShort(number);
            case Integer number -> new DataInt(number);
            case Long number -> new DataLong(number);
            case Float number -> new DataFloat(number);
            case Double number -> new DataDouble(number);
            case String text -> new DataString(text);
            case List<?> list -> {
                var items = new ObjectArrayList<IData>(list.size());
                for (int i = 0; i < list.size(); i++) items.add(value(list.get(i)));
                yield new DataList(items, true);
            }
            case null -> throw new IllegalArgumentException("Recipe extra value is null");
            default -> throw new IllegalArgumentException("Unsupported recipe extra type: " + value.getClass().getName());
        };
    }
}
