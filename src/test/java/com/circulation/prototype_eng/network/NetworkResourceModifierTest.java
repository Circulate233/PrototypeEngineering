package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkResourceModifierTest {
    private static final NetworkResourceType COMPUTE = type("compute", false, 0L);
    private static final NetworkResourceType QUBIT = type("qubit", false, 0L);
    private static final NetworkResourceType THREAD = type("thread", true, 1L);

    @Test void combinesFlatAndPpmWithFlooringAndIncludesAdjustmentOnlyResources() {
        var base = amounts(COMPUTE, 10L);
        base.put(THREAD, 12L);
        var modifier = NetworkResourceModifier.builder().flat(COMPUTE, 5L)
            .multiplierPpm(COMPUTE, 500_000L).flat(QUBIT, 3L)
            .multiplierPpm(QUBIT, -500_000L).build();
        var adjusted = modifier.apply(base);
        assertEquals(22L, adjusted.getLong(COMPUTE));
        assertEquals(1L, adjusted.getLong(QUBIT));
        assertEquals(12L, adjusted.getLong(THREAD));
        assertEquals(0L, adjusted.getLong(type("absent", false, 0L)));
        assertEquals(10L, base.getLong(COMPUTE));
    }

    @Test void rejectsEachIntermediateOverflowEvenWhenFinalQuotientWouldFit() {
        assertThrows(ArithmeticException.class, () -> NetworkResourceModifier.builder()
            .flat(COMPUTE, 1L).build().apply(amounts(COMPUTE, Long.MAX_VALUE)));
        assertThrows(ArithmeticException.class, () -> NetworkResourceModifier.builder()
            .multiplierPpm(COMPUTE, Long.MAX_VALUE).build());
        assertThrows(ArithmeticException.class, () -> NetworkResourceModifier.EMPTY
            .apply(amounts(COMPUTE, Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE / 1_000_000L,
            NetworkResourceModifier.EMPTY.apply(amounts(COMPUTE, Long.MAX_VALUE / 1_000_000L)).getLong(COMPUTE));
    }

    @Test void permitsNegativeAdjustmentsAndZeroFactorsButRejectsNegativeOutcomes() {
        assertEquals(4L, NetworkResourceModifier.builder().flat(COMPUTE, -2L)
            .multiplierPpm(COMPUTE, -500_000L).build().apply(amounts(COMPUTE, 10L)).getLong(COMPUTE));
        assertEquals(0L, NetworkResourceModifier.builder().multiplierPpm(COMPUTE, -1_000_000L)
            .build().apply(amounts(COMPUTE, Long.MAX_VALUE)).getLong(COMPUTE));
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .multiplierPpm(COMPUTE, -1_000_001L).build());
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .flat(COMPUTE, -2L).build().apply(amounts(COMPUTE, 1L)));
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .flat(COMPUTE, -2L).multiplierPpm(COMPUTE, -1_000_000L).build().apply(amounts(COMPUTE, 1L)));
    }

    @Test void rejectsConflictingMetadataWithinCopiesAndAcrossModifierTablesAndBase() {
        var logicalCompute = type("compute", true, 0L);
        var limitedCompute = type("compute", false, 5L);
        var conflicting = amounts(COMPUTE, 1L);
        conflicting.put(logicalCompute, 2L);
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.copyResources(conflicting));
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .flat(COMPUTE, 1L).multiplierPpm(logicalCompute, 1L));
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .flat(COMPUTE, 1L).build().apply(amounts(limitedCompute, 1L)));
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .multiplierPpm(COMPUTE, 1L).build().apply(amounts(logicalCompute, 1L)));
        assertThrows(IllegalArgumentException.class, () -> NetworkResourceModifier.builder()
            .flat(COMPUTE, 1L).flat(type("compute", false, 0L), 2L));
    }

    @Test void quantityCopiesAndBuiltModifiersRemainImmutableIncludingEntryMutation() {
        var source = amounts(COMPUTE, 7L);
        source.defaultReturnValue(99L);
        var frozen = NetworkResourceModifier.copyResources(source);
        source.clear();
        assertEquals(7L, frozen.getLong(COMPUTE));
        assertEquals(0L, frozen.getLong(QUBIT));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put(COMPUTE, 8L));
        assertThrows(UnsupportedOperationException.class, () -> frozen.defaultReturnValue(9L));
        assertThrows(UnsupportedOperationException.class, () -> frozen.object2LongEntrySet().iterator().next().setValue(9L));
        var iterator = frozen.object2LongEntrySet().iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> frozen.values().removeIf(_ -> true));
        var builder = NetworkResourceModifier.builder().flat(COMPUTE, 2L);
        var modifier = builder.build();
        builder.flat(QUBIT, 5L);
        assertEquals(0L, modifier.flatAdjustments().getLong(QUBIT));
        assertThrows(UnsupportedOperationException.class,
            () -> modifier.flatAdjustments().object2LongEntrySet().iterator().next().setValue(3L));
        assertThrows(UnsupportedOperationException.class,
            () -> modifier.apply(amounts(COMPUTE, 2L)).object2LongEntrySet().iterator().next().setValue(3L));
    }

    private static NetworkResourceType type(String path, boolean logical, long limit) {
        return new NetworkResourceType(new ResourceLocation("prototype_eng_test", path), logical, limit);
    }

    private static Object2LongOpenHashMap<NetworkResourceType> amounts(NetworkResourceType type, long amount) {
        var values = new Object2LongOpenHashMap<NetworkResourceType>();
        values.put(type, amount);
        return values;
    }
}
