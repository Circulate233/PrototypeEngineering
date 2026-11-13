package com.circulation.prototype_eng.util;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"UnnecessaryLocalVariable", "ExcessiveLambdaUsage"}) // Exercise each interface and the primitive callback overload.
class BlockPos2ObjectMapTest {
    @Test
    void mixedObjectAndPrimitiveOperationsMatchFastutilIncludingNullAndDefaultValues() {
        var actual = new BlockPos2ObjectMap<String>();
        var expected = new Object2ObjectOpenHashMap<BlockPos, String>();
        actual.defaultReturnValue("missing");
        expected.defaultReturnValue("missing");
        var random = new Random(713);
        for (int i = 0; i < 3000; i++) {
            var key = new BlockPos(random.nextInt(9) - 4, random.nextInt(9) - 4, random.nextInt(9) - 4);
            String value = i % 4 == 0 ? null : i % 7 == 0 ? "missing" : "v" + i % 5;
            switch (random.nextInt(7)) {
                case 0 -> assertEquals(expected.put(key, value), actual.put(key.toLong(), value));
                case 1 -> assertEquals(expected.putIfAbsent(key, value), actual.putIfAbsent(key, value));
                case 2 -> assertEquals(expected.remove(key), actual.remove(key.toLong()));
                case 3 -> assertEquals(expected.remove(key, value), actual.remove(key, value));
                case 4 -> assertEquals(expected.replace(key, value), actual.replace(key.toLong(), value));
                case 5 -> assertEquals(expected.compute(key, (_, old) -> old == null ? value : null),
                    actual.compute(key, (_, old) -> old == null ? value : null));
                default ->
                    assertEquals(expected.getOrDefault(key, "fallback"), actual.getOrDefault(key.toLong(), "fallback"));
            }
            assertEquals(actual, expected);
            assertEquals(expected.hashCode(), actual.hashCode());
        }
    }

    @Test
    void mutableKeysAreSnapshotsAndBothEntryPointsUseNativePacking() {
        var map = new BlockPos2ObjectMap<String>();
        var mutable = new BlockPos.MutableBlockPos(-33_554_432, -2048, 33_554_431);
        long packed = mutable.toLong();
        map.put(mutable, "edge");
        mutable.setPos(0, 0, 0);
        assertEquals("edge", map.get(packed));
        assertEquals("edge", map.get(BlockPos.fromLong(packed)));
        assertNull(map.get(mutable));
        map.put(0L, "origin");
        var alias = new BlockPos(0, 4096, 0);
        assertEquals(0L, alias.toLong());
        assertEquals(alias.toLong(), BlockPos2ObjectMap.toLong(0, 4096, 0));
        assertEquals("origin", map.put(alias, "masked"));
        assertEquals("masked", map.get(0L));
        assertEquals("masked", map.remove(alias));
        assertFalse(map.containsKey(0L));
    }

    @Test
    void objectViewsFailThroughBothMapInterfacesAndPrimitiveViewsStayLive() {
        var map = new BlockPos2ObjectMap<String>();
        Map<BlockPos, String> jdk = map;
        Object2ObjectMap<BlockPos, String> fastutil = map;
        assertThrows(UnsupportedOperationException.class, jdk::entrySet);
        assertThrows(UnsupportedOperationException.class, jdk::keySet);
        assertThrows(UnsupportedOperationException.class, fastutil::object2ObjectEntrySet);
        map.put(0L, "first");
        map.put(new BlockPos(1, 2, 3), "second");
        var iterator = map.long2ObjectEntrySet().iterator();
        assertEquals("first", iterator.next().setValue("updated"));
        assertEquals("updated", map.get(BlockPos.ORIGIN));
        iterator.remove();
        assertFalse(map.containsKey(0L));
        assertTrue(map.values().remove("second"));
        assertTrue(map.isEmpty());
        map.computeIfAbsent(0L, _ -> "computed");
        assertTrue(map.longKeySet().remove(0L));
    }

    @Test
    void copiesRetainAllMappingsAndRemainIndependentOfTheSource() {
        var origin = BlockPos.ORIGIN;
        var neighbor = new BlockPos(-8, 17, 32);
        var source = new BlockPos2ObjectMap<>(Map.of(origin, "origin", neighbor, "neighbor"));
        var copy = new BlockPos2ObjectMap<>(source);
        source.put(0L, "changed");
        source.remove(neighbor);
        assertEquals(2, copy.size());
        assertEquals("origin", copy.get(0L));
        assertEquals("neighbor", copy.get(neighbor));
    }
}
