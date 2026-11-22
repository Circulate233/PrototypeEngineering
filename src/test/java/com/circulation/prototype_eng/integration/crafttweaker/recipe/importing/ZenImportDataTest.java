package com.circulation.prototype_eng.integration.crafttweaker.recipe.importing;

import crafttweaker.api.data.DataBool;
import crafttweaker.api.data.DataByte;
import crafttweaker.api.data.DataDouble;
import crafttweaker.api.data.DataFloat;
import crafttweaker.api.data.DataInt;
import crafttweaker.api.data.DataList;
import crafttweaker.api.data.DataLong;
import crafttweaker.api.data.DataMap;
import crafttweaker.api.data.DataShort;
import crafttweaker.api.data.DataString;
import crafttweaker.api.data.IData;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZenImportDataTest {
    @Test
    void sourceExtraNumbersRetainTheirNativeTypesAndValues() {
        var source = List.of(true, (byte) -7, (short) 301, 70_000, 4_000_000_007L, 1.25F, Math.PI, "source");
        var converted = ZenImportData.extras(source);
        assertEquals(source.size(), converted.length);
        assertTrue(assertInstanceOf(DataBool.class, converted[0]).asBool());
        assertEquals(source.get(1), assertInstanceOf(DataByte.class, converted[1]).asByte());
        assertEquals(source.get(2), assertInstanceOf(DataShort.class, converted[2]).asShort());
        assertEquals(source.get(3), assertInstanceOf(DataInt.class, converted[3]).asInt());
        assertEquals(source.get(4), assertInstanceOf(DataLong.class, converted[4]).asLong());
        assertEquals(source.get(5), assertInstanceOf(DataFloat.class, converted[5]).asFloat());
        assertEquals(source.get(6), assertInstanceOf(DataDouble.class, converted[6]).asDouble());
        assertEquals(source.get(7), assertInstanceOf(DataString.class, converted[7]).asString());
    }

    @Test
    void nestedSourceListsAndMutableIDataProduceIndependentReadonlyData() {
        var nested = new ObjectArrayList<>(List.of(7, 11));
        var dataList = new DataList(new ObjectArrayList<>(List.of(new DataInt(23))), false);
        var members = new Object2ObjectOpenHashMap<String, IData>();
        members.put("nested", dataList);
        var mutableData = new DataMap(members, false);
        var source = new ObjectArrayList<>(List.of(nested, mutableData));
        var converted = ZenImportData.extras(List.of(source))[0];
        nested.set(0, 101);
        dataList.setAt(0, new DataInt(103));
        mutableData.memberSet("added", new DataString("later"));
        source.clear();

        var snapshot = assertInstanceOf(DataList.class, converted);
        assertEquals(2, snapshot.length());
        var copiedList = assertInstanceOf(DataList.class, snapshot.getAt(0));
        assertEquals(7, copiedList.getAt(0).asInt());
        assertEquals(11, copiedList.getAt(1).asInt());
        var copiedMap = assertInstanceOf(DataMap.class, snapshot.getAt(1));
        assertEquals(1, copiedMap.length());
        var copiedDataList = assertInstanceOf(DataList.class, copiedMap.memberGet("nested"));
        assertEquals(23, copiedDataList.getAt(0).asInt());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.setAt(0, new DataInt(107)));
        assertThrows(UnsupportedOperationException.class, () -> copiedList.setAt(0, new DataInt(109)));
        assertThrows(UnsupportedOperationException.class, () -> copiedMap.memberSet("nested", new DataInt(113)));
        assertThrows(UnsupportedOperationException.class, () -> copiedDataList.setAt(0, new DataInt(127)));
    }

    @Test
    void nbtExtrasCopyNestedArraysBeforeExposingReadonlyData() {
        var tag = new NBTTagCompound();
        tag.setString("name", "original");
        var nested = new NBTTagCompound();
        nested.setByteArray("bytes", new byte[]{3, 5});
        nested.setIntArray("ints", new int[]{70_001, 90_003});
        tag.setTag("nested", nested);
        var converted = assertInstanceOf(DataMap.class, ZenImportData.extras(List.of(tag))[0]);
        tag.setString("name", "changed");
        nested.getByteArray("bytes")[0] = 17;
        nested.getIntArray("ints")[0] = 101_007;
        var nestedData = assertInstanceOf(DataMap.class, converted.memberGet("nested"));
        assertEquals("original", converted.memberGet("name").asString());
        assertEquals(3, nestedData.memberGet("bytes").getAt(0).asByte());
        assertEquals(70_001, nestedData.memberGet("ints").getAt(0).asInt());
        assertThrows(UnsupportedOperationException.class, () -> converted.memberSet("name", new DataString("replaced")));
        assertThrows(UnsupportedOperationException.class, () -> nestedData.memberGet("bytes").setAt(0, new DataByte((byte) 19)));
        assertThrows(UnsupportedOperationException.class, () -> nestedData.memberGet("ints").setAt(0, new DataInt(23)));
    }

    @Test
    void unsupportedAndNullExtraValuesFailExplicitly() {
        var unsupported = new StringBuilder("not data");
        var failure = assertThrows(IllegalArgumentException.class, () -> ZenImportData.extras(List.of(List.of(unsupported))));
        assertTrue(failure.getMessage().contains(StringBuilder.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> ZenImportData.extras(Collections.singletonList(null)));
    }
}
