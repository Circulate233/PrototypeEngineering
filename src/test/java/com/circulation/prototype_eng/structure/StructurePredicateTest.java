package com.circulation.prototype_eng.structure;

import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructurePredicateTest {
    @Test
    void rootNbtUsesBareStringsAndTypedSnbtAndFreezesExternalConstraints() {
        BlockPos position = new BlockPos(3, 4, 5);
        var tag = new NBTTagCompound();
        tag.setString("name", "plain text");
        tag.setInteger("amount", 7);
        tag.setByte("enabled", (byte) 1);
        var nested = new NBTTagCompound();
        nested.setInteger("amount", 7);
        tag.setTag("storage", nested);
        var source = new FakeSource();
        source.put(StructureQueries.TILE_NBT, position, tag);
        var expected = new Object2ObjectOpenHashMap<String, String>();
        expected.put("name", "plain text");
        expected.put("amount", "7");
        expected.put("enabled", "1b");
        StructurePredicate frozen = StructurePredicates.nbt(expected);
        expected.clear();
        expected.put("missing", "changed after construction");

        assertTrue(matches(frozen, source, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of("name", "\"plain text\"")), source, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of("amount", "7b")), source, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of("enabled", "1")), source, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of("missing", "7")), source, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of("storage.amount", "7")), source, position));
        assertTrue(matches(StructurePredicates.nbt(Map.of("storage", "{amount:7}")), source, position));
        assertTrue(matches(StructurePredicates.nbt(Map.of()), source, position));

        var noTile = new FakeSource();
        assertFalse(matches(StructurePredicates.nbt(Map.of("amount", "7")), noTile, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of()), noTile, position));
        noTile.put(StructureQueries.TILE_NBT, position, new NBTTagCompound());
        assertTrue(matches(StructurePredicates.nbt(Map.of()), noTile, position));
        assertFalse(matches(StructurePredicates.nbt(Map.of("amount", "7")), noTile, position));
    }

    @Test
    void compositesShortCircuitInDeclaredOrderAndThrowOnlyWhenCapturedFailuresAreUsed() {
        StructureQuery<String> ordinary = stringQuery("ordinary");
        StructureQuery<String> poisoned = stringQuery("poisoned");
        BlockPos position = new BlockPos(1, 2, 3);
        var visits = new ObjectArrayList<String>();
        StructurePredicate falsePredicate = StructurePredicates.test(ordinary, _ -> {
            visits.add("false");
            return false;
        });
        StructurePredicate truePredicate = StructurePredicates.test(ordinary, _ -> {
            visits.add("true");
            return true;
        });
        StructurePredicate failedPredicate = StructurePredicates.test(poisoned,
                _ -> fail("The failed fact must throw before testing its condition"));
        StructurePredicate all = StructurePredicates.all(List.of(truePredicate, falsePredicate, failedPredicate));
        StructurePredicate any = StructurePredicates.any(List.of(falsePredicate, truePredicate, failedPredicate));
        var failure = new IllegalArgumentException("optional provider failed");
        var source = new FakeSource();
        source.put(ordinary, position, "available");
        source.supply(poisoned, position, () -> { throw failure; });
        var plan = new StructureReadPlan.Builder();
        all.collectReads(plan, position);
        any.collectReads(plan, position);
        var snapshot = StructureSnapshot.capture(plan.build(), source);

        assertEquals(1, snapshot.failures().size());
        assertFalse(all.matches(snapshot, position));
        assertEquals(List.of("true", "false"), visits);
        visits.clear();
        assertTrue(any.matches(snapshot, position));
        assertEquals(List.of("false", "true"), visits);

        visits.clear();
        StructurePredicate allReachesFailure = StructurePredicates.all(List.of(truePredicate, failedPredicate));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> allReachesFailure.matches(snapshot, position)).getCause());
        assertEquals(List.of("true"), visits);
        visits.clear();
        StructurePredicate anyReachesFailure = StructurePredicates.any(List.of(falsePredicate, failedPredicate));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> anyReachesFailure.matches(snapshot, position)).getCause());
        assertEquals(List.of("false"), visits);
        assertEquals(1, source.readCount(ordinary, position));
        assertEquals(1, source.readCount(poisoned, position));
    }

    @Test
    void unloadedCellAndOffsetValidatorPositionsAreOrdinaryMismatchesWithoutQueryReads() {
        StructureQuery<String> query = stringQuery("unloaded");
        BlockPos origin = new BlockPos(3, 4, 5);
        BlockPos offset = new BlockPos(32, -1, 2);
        BlockPos validatorPosition = origin.add(offset);
        StructurePredicate cell = StructurePredicates.test(query,
                _ -> fail("An unloaded cell must short circuit before its condition"));
        StructurePredicate validator = StructurePredicates.at(offset, StructurePredicates.nbt(Map.of("amount", "7")));
        var source = new FakeSource();
        source.unloaded.add(StructureBounds.at(origin));
        source.unloaded.add(StructureBounds.at(validatorPosition));
        source.supply(query, origin, () -> fail("An unloaded cell must not be captured"));
        source.supply(StructureQueries.TILE_NBT, validatorPosition,
                () -> fail("An unloaded validator position must not be captured"));
        var plan = new StructureReadPlan.Builder();
        cell.collectReads(plan, origin);
        validator.collectReads(plan, origin);
        var snapshot = StructureSnapshot.capture(plan.build(), source);

        assertFalse(cell.matches(snapshot, origin));
        assertFalse(validator.matches(snapshot, origin));
        assertTrue(snapshot.failures().isEmpty());
        assertEquals(0, source.readCount(query, origin));
        assertEquals(0, source.readCount(StructureQueries.TILE_NBT, validatorPosition));
    }

    private static boolean matches(StructurePredicate predicate, FakeSource source, BlockPos position) {
        var plan = new StructureReadPlan.Builder();
        predicate.collectReads(plan, position);
        return predicate.matches(StructureSnapshot.capture(plan.build(), source), position);
    }

    private static StructureQuery<String> stringQuery(String path) {
        return new StructureQuery<>(new ResourceLocation("test", path),
                (_, _) -> fail("No World reader may run in this test"), UnaryOperator.identity(), String::equals);
    }

    private static final class FakeSource implements StructureReadSource {
        private final Thread serverThread = Thread.currentThread();
        private final ObjectOpenHashSet<StructureBounds> unloaded = new ObjectOpenHashSet<>();
        private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Supplier<?>> values = new Object2ObjectOpenHashMap<>();
        private final Object2IntOpenHashMap<StructureReadPlan.Read<?>> readCounts = new Object2IntOpenHashMap<>();

        private <T> void put(StructureQuery<T> query, BlockPos position, T value) {
            supply(query, position, () -> value);
        }

        private <T> void supply(StructureQuery<T> query, BlockPos position, Supplier<T> value) {
            values.put(new StructureReadPlan.Read<>(position, query), value);
        }

        private <T> int readCount(StructureQuery<T> query, BlockPos position) {
            return readCounts.getInt(new StructureReadPlan.Read<>(position, query));
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != serverThread) {
                throw new IllegalStateException("World facts belong to the server thread");
            }
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            requireServerThread();
            return !unloaded.contains(bounds);
        }

        @Override
        @Nullable
        @SuppressWarnings("unchecked") // supply binds a descriptor's T to its stored supplier.
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            requireServerThread();
            assertTrue(isAreaLoaded(StructureBounds.at(position)), "Do not capture unloaded positions");
            var request = new StructureReadPlan.Read<>(position, query);
            readCounts.addTo(request, 1);
            var value = values.get(request);
            return value == null ? null : (T) value.get();
        }
    }
}
