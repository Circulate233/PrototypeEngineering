package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergySnapshot;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResourceTransferRunTest {
    private static final KeyType MATERIAL = new KeyType("material");
    private static final KeyType OTHER = new KeyType("other");
    private static final Key FEED = new Key(MATERIAL, "feed");
    private static final Key AUXILIARY = new Key(OTHER, "auxiliary");
    private static final Key PRODUCT = new Key(MATERIAL, "product");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("prototype_eng_test", name);
    }

    private static BufferedResourceHatch hatch(long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(MATERIAL, capacity);
        capacities.put(OTHER, capacity);
        return new BufferedResourceHatch(HatchMode.MIXED, 2, capacities, () -> {
        });
    }

    private static ResourceTransferPlan recipe(List<GenericStack> inputs, int ticks, long fe) {
        return new ResourceTransferPlan(id("recipe"), inputs, ObjectArrayList.of(new GenericStack(PRODUCT, 6)), ticks, fe);
    }

    @Test
    void transferCandidatesDoNotCountTheSameUnderlyingInputTwice() {
        var expensive = new ResourceTransferPlan(id("too_expensive"),
            ObjectArrayList.of(new GenericStack(FEED, 12)), ObjectLists.emptyList(), 1, 0);
        var candidate = recipe(ObjectArrayList.of(new GenericStack(FEED, 2), new GenericStack(FEED, 4)), 1, 0);

        var backing = hatch(100);
        var backingInput = Objects.requireNonNull(backing.input());
        backingInput.insert(FEED, 6, Actionable.MODULATE);
        var first = new ExternalInput(backing, 6);
        var alias = new ExternalInput(backing, 6);
        var sources = ObjectArrayList.of(first, alias);
        ResourceTransferPlan chosen = ResourceTransferRun.findPlan(ObjectArrayList.of(expensive, candidate), sources);
        assertSame(candidate, chosen);
        assertEquals(6, backingInput.getAmount(0));
        var run = new ResourceTransferRun(chosen, () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(sources, ObjectLists.emptyList()));
        assertEquals(0, backingInput.getAmount(0));
        assertEquals(1, run.progress());
    }

    @Test
    void partialExternalReceiptsSurviveCheckpointsAndHostInvalidation() {
        var backing = hatch(100);
        var backingInput = Objects.requireNonNull(backing.input());
        backingInput.insert(FEED, 6, Actionable.MODULATE);
        backingInput.insert(AUXILIARY, 4, Actionable.MODULATE);
        var input = new ExternalInput(backing, 2);
        var recipe = recipe(ObjectArrayList.of(new GenericStack(FEED, 6), new GenericStack(AUXILIARY, 4)), 1, 0);
        var run = new ResourceTransferRun(recipe, () -> {
        });
        assertEquals(ResourceTransferRun.Status.COLLECTING_INPUTS, run.tick(ObjectArrayList.of(input), ObjectLists.emptyList()));
        assertEquals(4, backingInput.amount(FEED));
        assertEquals(2, backingInput.amount(AUXILIARY));
        var checkpoint = run.snapshot();
        input.valid = false;
        run = new ResourceTransferRun(checkpoint, () -> {
        });
        assertEquals(ResourceTransferRun.Status.COLLECTING_INPUTS, run.tick(ObjectArrayList.of(input), ObjectLists.emptyList()));
        assertEquals(4, backingInput.amount(FEED));

        var replacement = new ExternalInput(backing, 2);
        assertEquals(ResourceTransferRun.Status.COLLECTING_INPUTS, run.tick(ObjectArrayList.of(replacement), ObjectLists.emptyList()));
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(ObjectArrayList.of(replacement), ObjectLists.emptyList()));
        assertEquals(0, backingInput.amount(FEED));
        assertEquals(0, backingInput.amount(AUXILIARY));
        var destination = hatch(100);
        var destinationOutput = Objects.requireNonNull(destination.output());
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(ObjectArrayList.of(destination), ObjectLists.emptyList()));
        assertEquals(6, destinationOutput.amount(PRODUCT));
    }

    @Test
    void sharedEnergyViewsCreditPartialPaymentsOnceAndRestoreTheUnspentCredit() {
        var bank = new EnergyBuffer(20, () -> {
        });
        bank.receiveEnergy(20, Actionable.MODULATE);
        var first = new LimitedEnergy(bank);
        var alias = new LimitedEnergy(bank);
        var energy = ObjectArrayList.of(first, alias);
        var output = hatch(100);
        var outputStorage = Objects.requireNonNull(output.output());
        var hatches = ObjectArrayList.of(output);
        var run = new ResourceTransferRun(recipe(ObjectLists.emptyList(), 2, 10), () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_ENERGY, run.tick(hatches, energy));
        assertEquals(6, run.energyCredit());
        assertEquals(0, run.progress());
        assertEquals(14, bank.storedEnergy());
        run = new ResourceTransferRun(run.snapshot(), () -> {
        });
        assertEquals(ResourceTransferRun.Status.WORKING, run.tick(hatches, energy));
        assertEquals(10, bank.storedEnergy());
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_ENERGY, run.tick(hatches, energy));
        run = new ResourceTransferRun(run.snapshot(), () -> {
        });
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(hatches, energy));
        assertEquals(0, bank.storedEnergy());
        assertEquals(6, outputStorage.amount(PRODUCT));
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(hatches, energy));
        assertEquals(6, outputStorage.amount(PRODUCT));
    }

    @Test
    void blockedOutputsResumeOnlyUndeliveredProductsWithoutAnotherInputOrEnergyCharge() {
        var input = hatch(100);
        var inputStorage = Objects.requireNonNull(input.input());
        inputStorage.insert(FEED, 10, Actionable.MODULATE);
        var output = hatch(2);
        var outputStorage = Objects.requireNonNull(output.output());
        var bank = new EnergyBuffer(10, () -> {
        });
        bank.receiveEnergy(10, Actionable.MODULATE);
        var hatches = ObjectArrayList.of(new ExternalInput(input, 100), output);
        var run = new ResourceTransferRun(recipe(ObjectArrayList.of(new GenericStack(FEED, 4)), 1, 3), () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(hatches, ObjectArrayList.of(bank)));
        assertEquals(4, outputStorage.amount(PRODUCT));
        assertEquals(6, inputStorage.amount(FEED));
        assertEquals(7, bank.storedEnergy());
        var saved = run.snapshot();
        run = new ResourceTransferRun(saved, () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(hatches, ObjectArrayList.of(bank)));
        long delivered = outputStorage.extract(PRODUCT, 100, Actionable.MODULATE);
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(hatches, ObjectArrayList.of(bank)));
        delivered += outputStorage.extract(PRODUCT, 100, Actionable.MODULATE);
        assertEquals(6, delivered);
        assertEquals(6, inputStorage.amount(FEED));
        assertEquals(7, bank.storedEnergy());
    }

    /**
     * A separate hatch implementation; the executor sees only the public contract.
     */
    private static final class ExternalInput implements ResourceHatch {
        private final @NotNull ResourceStorage backing;
        private final long perCall;
        boolean valid = true;

        ExternalInput(BufferedResourceHatch backing, long perCall) {
            this.backing = Objects.requireNonNull(backing.input());
            this.perCall = perCall;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public ResourceStorage output() {
            return null;
        }

        @Override
        public ResourceStorage input() {
            // Deliberately return a fresh adapter to verify underlying storage identity handling.
            return new LimitedInputStorage(this);
        }
    }

    private record LimitedInputStorage(ExternalInput owner) implements ResourceStorage {
        @Override
        public StorageContext context() {
            return owner.backing.context();
        }

        @Override
        public StorageSnapshot snapshot() {
            return owner.backing.snapshot();
        }

        @Override
        public long amount(AEKey key) {
            return owner.backing.amount(key);
        }

        @Override
        public long availableAmount(AEKey key) {
            return owner.valid ? owner.backing.availableAmount(key) : 0;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode) {
            return owner.valid ? owner.backing.insert(key, amount, mode) : 0;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode) {
            return owner.valid ? owner.backing.extract(key,
                mode == Actionable.MODULATE ? Math.min(owner.perCall, amount) : amount, mode) : 0;
        }

        @Override
        public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            return owner.valid ? owner.backing.reserveExtract(snapshot, key, amount)
                : new ReservationResult(ReservationResult.Status.INVALID_STORAGE, null);
        }

        @Override
        public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return owner.valid ? owner.backing.reserveInsert(snapshot, key, amount)
                : new ReservationResult(ReservationResult.Status.INVALID_STORAGE, null);
        }
    }

    private record LimitedEnergy(EnergyBuffer bank) implements EnergyInput {

        @Override
        public StorageContext context() {
            return bank.context();
        }

        @Override
        public EnergySnapshot energySnapshot() {
            var snapshot = bank.energySnapshot();
            return new EnergySnapshot(context(), snapshot.revision(), snapshot.availableEnergy(),
                snapshot.availableCapacity(), snapshot.maxReceive(), Math.min(6, snapshot.maxExtract()));
        }

        @Override
        public ReservationResult reserveExtractEnergy(EnergySnapshot snapshot, long amount) {
            return amount > 6 ? new ReservationResult(ReservationResult.Status.INSUFFICIENT, null)
                : bank.reserveExtractEnergy(snapshot, amount);
        }

        @Override
        public long storedEnergy() {
            return bank.storedEnergy();
        }

        @Override
        public boolean isValid() {
            return bank.isValid();
        }

        @Override
        public long extractEnergy(long amount, Actionable mode) {
            return bank.extractEnergy(Math.min(6, amount), mode);
        }
    }
}
