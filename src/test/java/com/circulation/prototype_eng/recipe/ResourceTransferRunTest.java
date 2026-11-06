package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import ae2.api.storage.MEStorage;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
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
    private static final IActionSource SOURCE = IActionSource.empty();

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
        backingInput.insert(FEED, 6, Actionable.MODULATE, SOURCE);
        var first = new ExternalInput(backing, 6);
        var alias = new ExternalInput(backing, 6);
        var sources = ObjectArrayList.of(first, alias);
        ResourceTransferPlan chosen = ResourceTransferRun.findPlan(ObjectArrayList.of(expensive, candidate), sources, SOURCE);
        assertSame(candidate, chosen);
        assertEquals(6, backingInput.getAmount(0));
        var run = new ResourceTransferRun(chosen, () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(sources, ObjectLists.emptyList(), SOURCE));
        assertEquals(0, backingInput.getAmount(0));
        assertEquals(1, run.progress());
    }

    @Test
    void partialExternalReceiptsSurviveCheckpointsAndHostInvalidation() {
        var backing = hatch(100);
        var backingInput = Objects.requireNonNull(backing.input());
        backingInput.insert(FEED, 6, Actionable.MODULATE, SOURCE);
        backingInput.insert(AUXILIARY, 4, Actionable.MODULATE, SOURCE);
        var input = new ExternalInput(backing, 2);
        var recipe = recipe(ObjectArrayList.of(new GenericStack(FEED, 6), new GenericStack(AUXILIARY, 4)), 1, 0);
        var run = new ResourceTransferRun(recipe, () -> {
        });
        assertEquals(ResourceTransferRun.Status.COLLECTING_INPUTS, run.tick(ObjectArrayList.of(input), ObjectLists.emptyList(), SOURCE));
        assertEquals(4, backingInput.getAvailableStacks().get(FEED));
        assertEquals(2, backingInput.getAvailableStacks().get(AUXILIARY));
        var checkpoint = run.snapshot();
        input.valid = false;
        run = new ResourceTransferRun(checkpoint, () -> {
        });
        assertEquals(ResourceTransferRun.Status.COLLECTING_INPUTS, run.tick(ObjectArrayList.of(input), ObjectLists.emptyList(), SOURCE));
        assertEquals(4, backingInput.getAvailableStacks().get(FEED));

        var replacement = new ExternalInput(backing, 2);
        assertEquals(ResourceTransferRun.Status.COLLECTING_INPUTS, run.tick(ObjectArrayList.of(replacement), ObjectLists.emptyList(), SOURCE));
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(ObjectArrayList.of(replacement), ObjectLists.emptyList(), SOURCE));
        assertEquals(0, backingInput.getAvailableStacks().get(FEED));
        assertEquals(0, backingInput.getAvailableStacks().get(AUXILIARY));
        var destination = hatch(100);
        var destinationOutput = Objects.requireNonNull(destination.output());
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(ObjectArrayList.of(destination), ObjectLists.emptyList(), SOURCE));
        assertEquals(6, destinationOutput.getAvailableStacks().get(PRODUCT));
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
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_ENERGY, run.tick(hatches, energy, SOURCE));
        assertEquals(6, run.energyCredit());
        assertEquals(0, run.progress());
        assertEquals(14, bank.storedEnergy());
        run = new ResourceTransferRun(run.snapshot(), () -> {
        });
        assertEquals(ResourceTransferRun.Status.WORKING, run.tick(hatches, energy, SOURCE));
        assertEquals(10, bank.storedEnergy());
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_ENERGY, run.tick(hatches, energy, SOURCE));
        run = new ResourceTransferRun(run.snapshot(), () -> {
        });
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(hatches, energy, SOURCE));
        assertEquals(0, bank.storedEnergy());
        assertEquals(6, outputStorage.getAvailableStacks().get(PRODUCT));
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(hatches, energy, SOURCE));
        assertEquals(6, outputStorage.getAvailableStacks().get(PRODUCT));
    }

    @Test
    void blockedOutputsResumeOnlyUndeliveredProductsWithoutAnotherInputOrEnergyCharge() {
        var input = hatch(100);
        var inputStorage = Objects.requireNonNull(input.input());
        inputStorage.insert(FEED, 10, Actionable.MODULATE, SOURCE);
        var output = hatch(2);
        var outputStorage = Objects.requireNonNull(output.output());
        var bank = new EnergyBuffer(10, () -> {
        });
        bank.receiveEnergy(10, Actionable.MODULATE);
        var hatches = ObjectArrayList.of(new ExternalInput(input, 100), output);
        var run = new ResourceTransferRun(recipe(ObjectArrayList.of(new GenericStack(FEED, 4)), 1, 3), () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(hatches, ObjectArrayList.of(bank), SOURCE));
        assertEquals(4, outputStorage.getAvailableStacks().get(PRODUCT));
        assertEquals(6, inputStorage.getAvailableStacks().get(FEED));
        assertEquals(7, bank.storedEnergy());
        var saved = run.snapshot();
        run = new ResourceTransferRun(saved, () -> {
        });
        assertEquals(ResourceTransferRun.Status.WAITING_FOR_OUTPUT, run.tick(hatches, ObjectArrayList.of(bank), SOURCE));
        long delivered = outputStorage.extract(PRODUCT, 100, Actionable.MODULATE, SOURCE);
        assertEquals(ResourceTransferRun.Status.COMPLETE, run.tick(hatches, ObjectArrayList.of(bank), SOURCE));
        delivered += outputStorage.extract(PRODUCT, 100, Actionable.MODULATE, SOURCE);
        assertEquals(6, delivered);
        assertEquals(6, inputStorage.getAvailableStacks().get(FEED));
        assertEquals(7, bank.storedEnergy());
    }

    /**
     * A separate hatch implementation; the executor sees only the public contract.
     */
    private static final class ExternalInput implements ResourceHatch {
        private final @NotNull MEStorage backing;
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
        public MEStorage output() {
            return null;
        }

        @Override
        public Object inputStorageIdentity() {
            return backing;
        }

        @Override
        public MEStorage input() {
            // Deliberately return a fresh adapter to verify underlying storage identity handling.
            return new LimitedInputStorage(this);
        }
    }

    private record LimitedInputStorage(ExternalInput owner) implements MEStorage {
        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return owner.valid ? owner.backing.extract(key,
                mode == Actionable.MODULATE ? Math.min(owner.perCall, amount) : amount, mode, source) : 0;
        }

        @Override
        public ITextComponent getDescription() {
            return new TextComponentString("External input");
        }
    }

    private record LimitedEnergy(EnergyBuffer bank) implements EnergyInput {

        @Override
            public long storedEnergy() {
                return bank.storedEnergy();
            }

            @Override
            public boolean isValid() {
                return bank.isValid();
            }

            @Override
            public Object storageIdentity() {
                return bank;
            }

            @Override
            public long extractEnergy(long amount, Actionable mode) {
                return bank.extractEnergy(Math.min(6, amount), mode);
            }
        }
}
