package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.MEStorage;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageFence;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.energy.IEnergyStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchAutoTransferTest {
    private static final KeyType TYPE = new KeyType("auto_transfer");
    private static final Key KEY = new Key(TYPE, "material");

    private static BufferedResourceHatch hatch(HatchMode mode, int slots) {
        return new BufferedResourceHatch(mode, slots, Map.of(TYPE, 100L), () -> {});
    }

    private static HatchAutoTransfer.ResourceEndpoint endpoint(MEStorage remote, StorageContext... contexts) {
        return new HatchAutoTransfer.ResourceEndpoint(List.of(new HatchAutoTransfer.ResourcePort(null, remote)), null, List.of(contexts));
    }

    @Test
    void partialExportReservesBeforeReentrantExtractionAndRefundsOnlyTheRemainder() {
        var hatch = hatch(HatchMode.OUTPUT, 1);
        var output = Objects.requireNonNull(hatch.output());
        output.insert(KEY, 100, Actionable.MODULATE);
        var running = new AtomicBoolean(true);
        var remote = new Resources();
        remote.insertLimit = 7;
        remote.onInsert = () -> {
            assertEquals(0, output.extract(KEY, 100, Actionable.MODULATE));
            running.set(false);
        };
        new HatchAutoTransfer().resources(hatch, endpoint(remote), 0, false, true, 0, 0, running::get);
        assertEquals(93, output.amount(KEY));
        assertEquals(7, remote.amounts.getLong(KEY));
        assertEquals(1, remote.insertCalls);
        assertEquals(93, output.extract(KEY, 100, Actionable.MODULATE));
    }

    @Test
    void partialImportHoldsCapacityWithoutPublishingFakeResourcesDuringCallback() {
        var hatch = hatch(HatchMode.INPUT, 1);
        var input = Objects.requireNonNull(hatch.input());
        input.insert(KEY, 60, Actionable.MODULATE);
        var running = new AtomicBoolean(true);
        var remote = new Resources();
        remote.amounts.addTo(KEY, 100);
        remote.extractLimit = 6;
        remote.onExtract = () -> {
            assertEquals(60, input.amount(KEY));
            assertEquals(0, input.insert(KEY, 100, Actionable.MODULATE));
            running.set(false);
        };
        new HatchAutoTransfer().resources(hatch, endpoint(remote), 0, true, false, 0, 0, running::get);
        assertEquals(66, input.amount(KEY));
        assertEquals(94, remote.amounts.getLong(KEY));
        assertEquals(34, input.insert(KEY, 100, Actionable.MODULATE));
    }

    @Test
    void existingReservationsAndRetainedFencesRemainUnavailableToAutomation() {
        var hatch = hatch(HatchMode.OUTPUT, 1);
        var output = Objects.requireNonNull(hatch.output());
        output.insert(KEY, 100, Actionable.MODULATE);
        var hold = output.reserveExtract(output.snapshot(), KEY, 70).reservation();
        assertNotNull(hold);
        var remote = new Resources();
        var transfer = new HatchAutoTransfer();
        var fence = StorageFence.tryAcquire(List.of(output.context()));
        assertNotNull(fence);
        try {
            transfer.resources(hatch, endpoint(remote), 0, false, true, 0, 0, () -> true);
            assertEquals(0, remote.insertCalls);
        } finally {
            fence.release();
        }
        transfer.resources(hatch, endpoint(remote), 0, false, true, 0, 0, () -> true);
        assertEquals(30, remote.amounts.getLong(KEY));
        assertEquals(70, output.amount(KEY));
        output.context().lock();
        try {
            hold.rollback();
        } finally {
            output.context().unlock();
        }
        assertEquals(70, output.availableAmount(KEY));
    }

    @Test
    void busyPeerSkipsWithoutHoldingTheLocalContext() throws Exception {
        var hatch = hatch(HatchMode.OUTPUT, 1);
        var output = Objects.requireNonNull(hatch.output());
        output.insert(KEY, 100, Actionable.MODULATE);
        var peer = new StorageContext(() -> {});
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var remote = new Resources();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var worker = executor.submit(() -> {
                peer.lock();
                try {
                    locked.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owner did not release test peer");
                } finally {
                    peer.unlock();
                }
                return null;
            });
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                new HatchAutoTransfer().resources(hatch, endpoint(remote, peer), 0, false, true, 0, 0, () -> true);
                assertEquals(0, remote.insertCalls);
                assertTrue(output.context().tryOrdinaryLock());
                output.context().unlock();
            } finally {
                release.countDown();
            }
            worker.get(5, TimeUnit.SECONDS);
        }
        assertEquals(100, output.amount(KEY));
    }

    @Test
    void inputSearchDoesNotPermanentlyPairOppositeKeySlots() {
        var first = new Key(TYPE, "first");
        var second = new Key(TYPE, "second");
        var hatch = hatch(HatchMode.MIXED, 2);
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        input.insert(0, second, 1, Actionable.MODULATE);
        input.insert(1, first, 1, Actionable.MODULATE);
        output.insert(first, 50, Actionable.MODULATE);
        var source = hatch(HatchMode.OUTPUT, 2);
        var sourceOutput = Objects.requireNonNull(source.output());
        sourceOutput.insert(0, first, 99, Actionable.MODULATE);
        sourceOutput.insert(1, second, 99, Actionable.MODULATE);
        var peer = new HatchAutoTransfer.ResourceEndpoint(List.of(new HatchAutoTransfer.ResourcePort(null, source.automation())),
            sourceOutput, List.of(sourceOutput.context()));
        new HatchAutoTransfer().resources(hatch, peer, 0, true, false, 0, 0, () -> true);
        assertEquals(100, input.amount(first));
        assertEquals(100, input.amount(second));
        assertEquals(0, sourceOutput.amount(first));
        assertEquals(0, sourceOutput.amount(second));
        assertEquals(50, output.amount(first));
    }

    @Test
    void boundedOutputAndGenericInputContinuePastTheFirstProbeWindow() {
        var hatch = hatch(HatchMode.MIXED, 12);
        var output = Objects.requireNonNull(hatch.output());
        output.insert(11, KEY, 25, Actionable.MODULATE);
        var source = new Resources();
        for (int i = 0; i < 10; i++) source.amounts.addTo(new Key(TYPE, "key_" + i), 1);
        var transfer = new HatchAutoTransfer();
        var peer = endpoint(source);
        transfer.beginTick();
        transfer.resources(hatch, peer, 0, true, true, 0, 0, () -> true);
        assertEquals(25, output.amount(KEY));
        for (int i = 0; i < 4; i++) {
            transfer.beginTick();
            transfer.resources(hatch, peer, 0, true, true, 0, 0, () -> true);
        }
        var input = Objects.requireNonNull(hatch.input());
        for (int i = 0; i < 10; i++) assertEquals(1, input.amount(new Key(TYPE, "key_" + i)));
        assertEquals(0, output.amount(KEY));
        assertEquals(25, source.amounts.getLong(KEY));
        assertEquals(1, source.enumerations);
    }

    @Test
    void replacingNeighborDropsItsUnconsumedGenericSnapshot() {
        var hatch = hatch(HatchMode.INPUT, 128);
        var input = Objects.requireNonNull(hatch.input());
        var original = new Resources();
        for (int i = 0; i < 100; i++) original.amounts.addTo(new Key(TYPE, "old_" + i), 1);
        var replacement = new Resources();
        replacement.amounts.addTo(KEY, 3);
        var firstNeighbor = new TileEntity() {};
        var nextNeighbor = new TileEntity() {};
        var transfer = new HatchAutoTransfer();
        transfer.observeNeighbor(0, firstNeighbor);
        transfer.beginTick();
        transfer.resources(hatch, endpoint(original), 0, true, false, 0, 0, () -> true);
        assertEquals(1, original.enumerations);
        for (int slot = 0; slot < 4; slot++) assertEquals(1, input.getAmount(slot));
        assertEquals(0, input.getAmount(4));
        transfer.observeNeighbor(0, nextNeighbor);
        transfer.beginTick();
        transfer.resources(hatch, endpoint(replacement), 0, true, false, 0, 0, () -> true);
        assertEquals(1, replacement.enumerations);
        assertEquals(0, input.amount(KEY));
        assertEquals(0, replacement.extractAttempts.size());
        // The first four probes visit the occupied prefix; the next tick reaches empty slot 4.
        transfer.beginTick();
        transfer.resources(hatch, endpoint(replacement), 0, true, false, 0, 0, () -> true);
        assertEquals(1, replacement.enumerations);
        assertEquals(1, replacement.extractAttempts.getLong(KEY));
        assertEquals(3, input.amount(KEY));
        assertEquals(3, input.getAmount(4));
    }

    @Test
    void genericRefreshCancelsVanishedPendingKeysAndPreservesUnvisitedQueueOrder() {
        var hatch = hatch(HatchMode.INPUT, 128);
        var input = Objects.requireNonNull(hatch.input());
        for (int slot = 0; slot < input.size(); slot++) input.insert(slot, KEY, 1, Actionable.MODULATE);
        var neighbor = new TileEntity() {};
        var remote = new Resources();
        for (int i = 0; i < 100; i++) remote.amounts.addTo(new Key(TYPE, "obsolete_" + i), 1);
        var transfer = new HatchAutoTransfer();
        transfer.observeNeighbor(0, neighbor);
        transfer.beginTick();
        transfer.resources(hatch, endpoint(remote), 0, true, false, 0, 0, () -> true);
        remote.amounts.clear();
        remote.amounts.addTo(KEY, 3);
        for (int i = 0; i < 20; i++) {
            transfer.observeNeighbor(0, neighbor);
            transfer.beginTick();
            transfer.resources(hatch, endpoint(remote), 0, true, false, 0, 0, () -> true);
        }
        assertEquals(2, remote.enumerations);
        assertEquals(131, input.amount(KEY));

        var empty = hatch(HatchMode.INPUT, 128);
        var blocked = new Resources();
        blocked.extractLimit = 0;
        for (int i = 0; i < 100; i++) blocked.amounts.addTo(new Key(TYPE, "reported_" + i), 1);
        var fair = new HatchAutoTransfer();
        for (int i = 0; i < 30; i++) {
            fair.beginTick();
            fair.resources(empty, endpoint(blocked), 0, true, false, 0, 0, () -> true);
        }
        assertEquals(2, blocked.enumerations);
        assertEquals(100, blocked.extractAttempts.size());
    }

    @Test
    void partialEnergySettlesAcrossRateChangesAndKeepsMixedBalancesIndependent() {
        var tile = new EnergyHatchBlockEntity(HatchMode.MIXED, HatchTier.LV1);
        var input = Objects.requireNonNull(tile.inputStorage());
        var output = Objects.requireNonNull(tile.outputStorage());
        assertEquals(-1, tile.maxInputRate());
        assertEquals(-1, tile.maxOutputRate());
        assertEquals(32, input.energySnapshot().maxReceive());
        assertEquals(32, output.energySnapshot().maxExtract());
        output.restore(40);
        var remote = new Energy();
        remote.amount = 100;
        remote.extractLimit = 4;
        remote.receiveLimit = 3;
        remote.onExtract = () -> {
            assertEquals(0, input.storedEnergy());
            tile.setMaxInputRate(0);
        };
        remote.onReceive = () -> {
            assertEquals(8, output.storedEnergy());
            tile.setMaxOutputRate(0);
        };
        var peerContexts = List.of(new StorageContext(() -> {}));
        HatchAutoTransfer.energy(input, output, remote, peerContexts, true, true, () -> true);
        assertEquals(4, input.storedEnergy());
        assertEquals(37, output.storedEnergy());
        assertEquals(99, remote.amount);
        assertEquals(2, remote.calls);
        HatchAutoTransfer.energy(input, output, remote, peerContexts, true, true, () -> true);
        assertEquals(2, remote.calls);
    }

    @Test
    void unknownExternalEffectStopsTheLocalLifetimeWithoutGuessingARefund() {
        var hatch = hatch(HatchMode.OUTPUT, 1);
        var output = Objects.requireNonNull(hatch.output());
        output.insert(KEY, 100, Actionable.MODULATE);
        var failure = new IllegalStateException("External result is unknown");
        var remote = new Resources();
        remote.onInsert = () -> {
            remote.amounts.addTo(KEY, 7);
            throw failure;
        };
        var transfer = new HatchAutoTransfer();
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> transfer.resources(hatch, endpoint(remote), 0, false, true, 0, 0, () -> true)));
        assertFalse(output.isValid());
        assertEquals(0, output.getAmount(0));
        assertEquals(7, remote.amounts.getLong(KEY));
        transfer.resources(hatch, endpoint(remote), 0, false, true, 0, 0, () -> true);
        assertEquals(1, remote.insertCalls);
    }

    private static final class Resources implements MEStorage {
        private final AEKey2LongMap amounts = new AEKey2LongMap.LinkdOpenHashMap();
        private final AEKey2LongMap extractAttempts = new AEKey2LongMap.LinkdOpenHashMap();
        private long insertLimit = Long.MAX_VALUE, extractLimit = Long.MAX_VALUE;
        private int insertCalls, enumerations;
        private Runnable onInsert = () -> {}, onExtract = () -> {};

        @Override
        public long insert(AEKey key, long requested, Actionable mode, IActionSource source) {
            long amount = Math.min(requested, insertLimit);
            if (mode == Actionable.MODULATE) {
                insertCalls++;
                onInsert.run();
                amounts.addTo(key, amount);
            }
            return amount;
        }

        @Override
        public long extract(AEKey key, long requested, Actionable mode, IActionSource source) {
            long amount = Math.min(requested, Math.min(extractLimit, amounts.getLong(key)));
            if (mode == Actionable.MODULATE) {
                extractAttempts.addTo(key, 1);
                onExtract.run();
                amounts.addTo(key, -amount);
            }
            return amount;
        }

        @Override
        public void getAvailableStacks(KeyCounter result) {
            enumerations++;
            amounts.forEachEntry(result::add);
        }

        @Override
        public ITextComponent getDescription() {
            return new TextComponentString("Test resource neighbor");
        }
    }

    private static final class Energy implements IEnergyStorage {
        private int amount, calls;
        private int extractLimit, receiveLimit;
        private Runnable onExtract = () -> {}, onReceive = () -> {};

        @Override
        public int receiveEnergy(int requested, boolean simulate) {
            int accepted = Math.min(requested, receiveLimit);
            if (!simulate) {
                calls++;
                onReceive.run();
                amount += accepted;
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int requested, boolean simulate) {
            int extracted = Math.min(amount, Math.min(requested, extractLimit));
            if (!simulate) {
                calls++;
                onExtract.run();
                amount -= extracted;
            }
            return extracted;
        }

        @Override
        public int getEnergyStored() { return amount; }

        @Override
        public int getMaxEnergyStored() { return Integer.MAX_VALUE; }

        @Override
        public boolean canExtract() { return true; }

        @Override
        public boolean canReceive() { return true; }
    }
}
