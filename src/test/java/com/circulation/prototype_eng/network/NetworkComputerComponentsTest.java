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
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the production resolved-key boundary without initializing native ItemStack or a game world. */
class NetworkComputerComponentsTest {
    private static final AEKey CARD = ComputerSupplyFixtures.key("card");
    private static final NetworkResourceType CAPPED = new NetworkResourceType(id("capped"), false, 5);

    @Test void longQuantitiesMultiplyPerItemCapAcrossSourcesWithoutCountingAliases() {
        var registry = registry(vector(CAPPED, 5));
        var first = buffer(2);
        var second = buffer(2);
        put(first, 0, CARD, (long) Integer.MAX_VALUE + 1);
        put(first, 1, CARD, 2);
        put(second, 0, CARD, 3);
        put(second, 1, ComputerSupplyFixtures.key("ordinary_unregistered"), Long.MAX_VALUE);
        var captured = capture(registry, first, second, first);
        assertEquals(((long) Integer.MAX_VALUE + 6) * 5, captured.resourcesPerTick().getLong(CAPPED));
        assertTrue(captured.current());
        put(first, 0, CARD, 1);
        assertFalse(captured.current());
    }

    @Test void extractionHoldsStillSupplyUntilAppliedAndPendingOutputsDoNotSupply() {
        var registry = registry(vector(NetworkResources.COMPUTE, 10));
        var storage = buffer(2);
        put(storage, 0, CARD, 4);
        var extract = Objects.requireNonNull(storage.reserveExtract(storage.snapshot(), CARD, 2).reservation());
        var insert = Objects.requireNonNull(storage.reserveInsert(storage.snapshot(), CARD, 3).reservation());
        var captured = capture(registry, storage);
        assertEquals(40, captured.resourcesPerTick().getLong(NetworkResources.COMPUTE));
        storage.context().lock();
        try { extract.apply(); }
        finally { storage.context().unlock(); }
        assertFalse(captured.current());
        assertEquals(20, capture(registry, storage).resourcesPerTick().getLong(NetworkResources.COMPUTE));
        ResourceTransactions.rollback(List.of(extract, insert));
        assertEquals(40, capture(registry, storage).resourcesPerTick().getLong(NetworkResources.COMPUTE));
    }

    @Test void canonicalLimitsAndOverflowAreCheckedBeforePublishingSupply() {
        var storage = buffer(2);
        put(storage, 0, CARD, 17);
        assertEquals(17, capture(registry(vector(NetworkResources.THREAD, 1)), storage).resourcesPerTick().getLong(NetworkResources.THREAD));
        assertThrows(IllegalArgumentException.class, () -> capture(registry(vector(NetworkResources.THREAD, 2)), storage));
        var drifted = new NetworkResourceType(CAPPED.id(), false, 0);
        assertThrows(IllegalArgumentException.class, () -> capture(registry(vector(drifted, 6)), storage));
        var unknown = new NetworkResourceType(id("unknown"), false, 0);
        assertThrows(IllegalArgumentException.class, () -> capture(registry(vector(unknown, 0)), storage));
        assertThrows(ArithmeticException.class, () -> capture(registry(vector(NetworkResources.COMPUTE, Long.MAX_VALUE)), storage));
        var registry = new NetworkComputerComponentRegistry();
        var other = ComputerSupplyFixtures.key("second_component");
        ComputerSupplyFixtures.fixed(registry, CARD, vector(NetworkResources.COMPUTE, Long.MAX_VALUE));
        ComputerSupplyFixtures.fixed(registry, other, vector(NetworkResources.COMPUTE, 1));
        registry.freeze(resources());
        put(storage, 0, CARD, 1);
        put(storage, 1, other, 1);
        assertThrows(ArithmeticException.class, () -> capture(registry, storage));
    }

    @Test void dynamicCaptureNeverRecomputesInsideForeignLocksAndVersionChangesInvalidateIt() {
        long[] value = {2};
        int[] calls = {0};
        var registry = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.register(registry, CARD, _ -> {
            calls[0]++;
            long captured = value[0];
            return new NetworkComputerComponent.Capture(vector(NetworkResources.COMPUTE, captured), () -> captured == value[0]);
        });
        registry.freeze(resources());
        var storage = buffer(1);
        put(storage, 0, CARD, 3);
        var captured = capture(registry, storage);
        var foreign = buffer(1);
        foreign.context().lock();
        try { assertTrue(captured.current()); }
        finally { foreign.context().unlock(); }
        assertEquals(1, calls[0]);
        value[0] = 3;
        assertFalse(captured.current());
        assertEquals(9, capture(registry, storage).resourcesPerTick().getLong(NetworkResources.COMPUTE));
        storage.context().invalidate();
        assertFalse(captured.current());
        assertFalse(assertDoesNotThrow(() -> capture(registry, storage)).current());
    }

    @Test void contextRevisionIsRecheckedAfterExternalProofAndCaptureInvalidationIsNormal() {
        var storage = buffer(1);
        put(storage, 0, CARD, 2);
        boolean[] mutate = {false};
        var registry = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.register(registry, CARD, _ -> new NetworkComputerComponent.Capture(vector(NetworkResources.COMPUTE, 5), () -> {
            if (mutate[0]) { put(storage, 0, CARD, 3); }
            return true;
        }));
        registry.freeze(resources());
        var captured = capture(registry, storage);
        mutate[0] = true;
        assertFalse(captured.current());
        var invalidating = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.register(invalidating, CARD, _ -> {
            storage.context().invalidate();
            return new NetworkComputerComponent.Capture(vector(NetworkResources.COMPUTE, 5), () -> true);
        });
        invalidating.freeze(resources());
        assertFalse(capture(invalidating, storage).current());
    }

    @Test void restoredExistingBufferFeedsRecreatedComponentAndOldProofNeverRevives() {
        var registry = registry(vector(NetworkResources.COMPUTE, 4));
        var source = buffer(1);
        put(source, 0, CARD, 7);
        var sourceHolder = new SlottedResourceStorage[]{source};
        try (var identities = new MachineIdentityRegistry(() -> { })) {
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
            var previous = Objects.requireNonNull(original.components().get(supply)).networkCapture(identity, 1, true, Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY);
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
            var current = Objects.requireNonNull(restored.components().get(supply)).networkCapture(restoredIdentity, 1, true, Object2LongMaps.emptyMap(), NetworkResourceModifier.EMPTY);
            assertFalse(previous.current().getAsBoolean());
            assertEquals(28, current.snapshot().resourcesPerTick().getLong(NetworkResources.COMPUTE));
            assertTrue(current.current().getAsBoolean());
            restored.components().remove(supply);
            assertFalse(current.current().getAsBoolean());
            Objects.requireNonNull(restored.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }
    }

    private static NetworkComputerComponentInventory capture(NetworkComputerComponentRegistry registry, SlottedResourceStorage... storage) {
        return NetworkComputerComponentInventory.capture(registry, new MachineInstance(), List.of(storage));
    }
    private static SlottedResourceBuffer buffer(int slots) { return new SlottedResourceBuffer(slots, Map.of(ComputerSupplyFixtures.TYPE, Long.MAX_VALUE), () -> { }); }
    private static void put(SlottedResourceStorage storage, int slot, AEKey key, long count) { assertTrue(storage.tryReplaceSlot(slot, storage.getStack(slot), new GenericStack(key, count))); }
    private static NetworkComputerComponentRegistry registry(Object2LongMap<NetworkResourceType> resources) {
        var registry = new NetworkComputerComponentRegistry();
        ComputerSupplyFixtures.fixed(registry, CARD, resources);
        registry.freeze(resources());
        return registry;
    }
    private static NetworkResourceRegistry resources() { var resources = new NetworkResourceRegistry(); resources.register(CAPPED); resources.freeze(); return resources; }
    private static Object2LongMap<NetworkResourceType> vector(NetworkResourceType type, long amount) { var result = new Object2LongOpenHashMap<NetworkResourceType>(); result.put(type, amount); return result; }
    private static ResourceLocation id(String path) { return new ResourceLocation("computer_supply_test", path); }
    private record Host() implements MachineLifecycleHost {
        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
        @Override public World world() { throw new AssertionError("No game world in supply tests"); }
        @Override public Object worldIdentity() { return NetworkComputerComponentsTest.class; }
        @Override public UUID placementPlayer() { return new UUID(0, 1); }
        @Override public void requireServerThread() { }
    }
}
