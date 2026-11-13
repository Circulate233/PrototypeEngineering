package com.circulation.prototype_eng.network;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.resource.ComputerSupplyFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentInventory;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the production resolved-key boundary without initializing native ItemStack or a game world.
 */
class NetworkComputerComponentsTest {
    private static final AEKey CARD = ComputerSupplyFixtures.key("card");
    private static final NetworkResourceType CUSTOM = ComputingResourceFixtures.network(id("custom"));

    private static NetworkComputerComponentInventory capture(NetworkComputerComponentRegistry registry, SlottedResourceStorage... storage) {
        return NetworkComputerComponentInventory.capture(registry, new MachineInstance(), List.of(storage));
    }

    private static SlottedResourceBuffer buffer(int slots) {
        return new SlottedResourceBuffer(slots, Reference2LongMaps.singleton(ComputerSupplyFixtures.TYPE, Long.MAX_VALUE), () -> {
        });
    }

    private static void put(SlottedResourceStorage storage, int slot, AEKey key, long count) {
        assertTrue(storage.tryReplaceSlot(slot, storage.getStack(slot), new GenericStack(key, count)));
    }

    private static NetworkComputerComponentRegistry registry(Reference2DoubleMap<NetworkResourceType> resources) {
        var registry = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.fixed(registry, CARD, resources);
        registry.freeze();
        return registry;
    }

    private static Reference2DoubleMap<NetworkResourceType> vector(NetworkResourceType type, double amount) {
        var result = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        result.put(type, amount);
        return result;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("computer_supply_test", path);
    }

    @Test
    void longQuantitiesMultiplyPerItemSupplyAcrossSourcesWithoutCountingAliases() {
        var registry = registry(vector(CUSTOM, 5));
        var first = buffer(2);
        var second = buffer(2);
        put(first, 0, CARD, (long) Integer.MAX_VALUE + 1);
        put(first, 1, CARD, 2);
        put(second, 0, CARD, 3);
        put(second, 1, ComputerSupplyFixtures.key("ordinary_unregistered"), Long.MAX_VALUE);
        var captured = capture(registry, first, second, first);
        assertEquals(((long) Integer.MAX_VALUE + 6) * 5, captured.resourcesPerTick().getDouble(CUSTOM));
        assertTrue(captured.current());
        put(first, 0, CARD, 1);
        assertFalse(captured.current());
    }

    @Test
    void extractionHoldsStillSupplyUntilAppliedAndPendingOutputsDoNotSupply() {
        var registry = registry(vector(ComputingResourceFixtures.COMPUTE, 10));
        var storage = buffer(2);
        put(storage, 0, CARD, 4);
        var extract = Objects.requireNonNull(storage.reserveExtract(storage.snapshot(), CARD, 2).reservation());
        var insert = Objects.requireNonNull(storage.reserveInsert(storage.snapshot(), CARD, 3).reservation());
        var captured = capture(registry, storage);
        assertEquals(40, captured.resourcesPerTick().getDouble(ComputingResourceFixtures.COMPUTE));
        storage.context().lock();
        try {
            extract.apply();
        } finally {
            storage.context().unlock();
        }
        assertFalse(captured.current());
        assertEquals(20, capture(registry, storage).resourcesPerTick().getDouble(ComputingResourceFixtures.COMPUTE));
        ResourceTransactions.rollback(List.of(extract, insert));
        assertEquals(40, capture(registry, storage).resourcesPerTick().getDouble(ComputingResourceFixtures.COMPUTE));
    }

    @Test
    void developerDefinedSupplyRejectsNonFiniteAccumulationBeforePublication() {
        var storage = buffer(2);
        put(storage, 0, CARD, 17);
        var supply = vector(ComputingResourceFixtures.COMPUTE, 2_048);
        supply.put(CUSTOM, 10_000);
        var components = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.fixed(components, CARD, 64, supply);
        components.freeze();
        var installed = capture(components, storage);
        var captured = installed.resourcesPerTick();
        assertEquals(1_088, installed.concurrencyPerTick());
        assertEquals(34_816, captured.getDouble(ComputingResourceFixtures.COMPUTE));
        assertEquals(170_000, captured.getDouble(CUSTOM));
        assertThrows(ArithmeticException.class, () -> capture(registry(vector(ComputingResourceFixtures.COMPUTE, Double.MAX_VALUE)), storage));
        var registry = new NetworkComputerComponentRegistry();
        var other = ComputerSupplyFixtures.key("second_component");
        ComputerSupplyFixtures.fixed(registry, CARD, vector(ComputingResourceFixtures.COMPUTE, Double.MAX_VALUE));
        ComputerSupplyFixtures.fixed(registry, other, vector(ComputingResourceFixtures.COMPUTE, Double.MAX_VALUE));
        registry.freeze();
        put(storage, 0, CARD, 1);
        put(storage, 1, other, 1);
        assertThrows(ArithmeticException.class, () -> capture(registry, storage));
    }

    @Test
    void dynamicCaptureNeverRecomputesInsideForeignLocksAndVersionChangesInvalidateIt() {
        long[] value = {2};
        int[] calls = {0};
        var registry = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.register(registry, CARD, _ -> {
            calls[0]++;
            long captured = value[0];
            return new NetworkComputerComponent.Capture(vector(ComputingResourceFixtures.COMPUTE, captured), () -> captured == value[0]);
        });
        registry.freeze();
        var storage = buffer(1);
        put(storage, 0, CARD, 3);
        var captured = capture(registry, storage);
        var foreign = buffer(1);
        foreign.context().lock();
        try {
            assertTrue(captured.current());
        } finally {
            foreign.context().unlock();
        }
        assertEquals(1, calls[0]);
        value[0] = 3;
        assertFalse(captured.current());
        assertEquals(9, capture(registry, storage).resourcesPerTick().getDouble(ComputingResourceFixtures.COMPUTE));
        storage.context().invalidate();
        assertFalse(captured.current());
        assertFalse(assertDoesNotThrow(() -> capture(registry, storage)).current());
    }

    @Test
    void contextRevisionIsRecheckedAfterExternalProofAndCaptureInvalidationIsNormal() {
        var storage = buffer(1);
        put(storage, 0, CARD, 2);
        boolean[] mutate = {false};
        var registry = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.register(registry, CARD, _ -> new NetworkComputerComponent.Capture(vector(ComputingResourceFixtures.COMPUTE, 5), () -> {
            if (mutate[0]) {
                put(storage, 0, CARD, 3);
            }
            return true;
        }));
        registry.freeze();
        var captured = capture(registry, storage);
        mutate[0] = true;
        assertFalse(captured.current());
        var invalidating = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.register(invalidating, CARD, _ -> {
            storage.context().invalidate();
            return new NetworkComputerComponent.Capture(vector(ComputingResourceFixtures.COMPUTE, 5), () -> true);
        });
        invalidating.freeze();
        assertFalse(capture(invalidating, storage).current());
    }

    @Test
    void restoredExistingBufferFeedsRecreatedComponentAndOldProofNeverRevives() {
        var registry = registry(vector(ComputingResourceFixtures.COMPUTE, 4));
        var source = buffer(1);
        put(source, 0, CARD, 7);
        var sourceHolder = new SlottedResourceStorage[]{source};
        try (var identities = new MachineIdentityRegistry(() -> {
        })) {
            var ownership = new ComponentType<MachineInstance, MachineOwnership>(id("ownership"), owner -> new MachineOwnership(owner, _ -> identities), List.of());
            var supply = MachineComputerComponents.component(id("supply"), registry, _ -> {
                var physical = sourceHolder[0];
                return new MachineComputerComponents.Sources(List.of(physical), () -> sourceHolder[0] == physical);
            });
            var type = MachineType.builder(id("machine"), GuiText.ResourceStorage, id("structure")).component(ownership).component(supply).build();
            var assembly = MachineAssembly.builder(type, "v1").component(ownership, MachineOwnership.CODEC).recreated(supply).build();
            var original = assembly.create(new Host(), new MachineLifecycleRegistry());
            Objects.requireNonNull(original.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
            var identity = Objects.requireNonNull(Objects.requireNonNull(original.components().get(ownership)).registration());
            var previous = Objects.requireNonNull(original.components().get(supply)).networkCapture(identity, 1, true, Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY);
            var saved = assembly.encode(original);
            var keys = new ResourceKeyNbt(_ -> ComputerSupplyFixtures.TYPE);
            var decoded = keys.readStacks(keys.writeStacks(List.of(new GenericStack(CARD, 7))));
            Objects.requireNonNull(original.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            var restoredBuffer = buffer(1);
            restoredBuffer.restore(decoded);
            sourceHolder[0] = restoredBuffer;
            var restored = assembly.restore(new Host(), saved, new MachineLifecycleRegistry());
            Objects.requireNonNull(restored.lifecycle()).form(new MachineHatches(List.of(), List.of(), List.of()));
            var restoredIdentity = Objects.requireNonNull(Objects.requireNonNull(restored.components().get(ownership)).registration());
            var current = Objects.requireNonNull(restored.components().get(supply)).networkCapture(restoredIdentity, 1, true, Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY);
            assertFalse(previous.current().getAsBoolean());
            assertEquals(28, current.snapshot().resourcesPerTick().getDouble(ComputingResourceFixtures.COMPUTE));
            assertTrue(current.current().getAsBoolean());
            restored.components().remove(supply);
            assertFalse(current.current().getAsBoolean());
            Objects.requireNonNull(restored.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    private record Host() implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No game world in supply tests");
        }

        @Override
        public Object worldIdentity() {
            return NetworkComputerComponentsTest.class;
        }

        @Override
        public UUID placementPlayer() {
            return new UUID(0, 1);
        }

        @Override
        public void requireServerThread() {
        }
    }
}
