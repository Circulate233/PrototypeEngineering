package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.client.gui.ClientHatchConfigurations;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HatchConfigurationSyncTest {
    private static HatchConfigurationSnapshot snapshot(UUID session, long revision, long itemEquivalent) {
        var profiles = new ObjectArrayList<HatchConfiguration>();
        for (var tier : HatchTier.values())
            for (var mode : HatchMode.values()) {
                var input = ResourceHatchConfiguration.Side.fromItemEquivalent(96, itemEquivalent, List.of(AEKeyType.items(), AEKeyType.fluids()), Map.of(AEKeyType.fluids(), 2000L));
                var output = ResourceHatchConfiguration.Side.fromItemEquivalent(128, itemEquivalent + tier.level(), List.of(AEKeyType.items(), AEKeyType.fluids()));
                profiles.add(new ResourceHatchConfiguration(mode.hasInput() ? input : null, mode.hasOutput() ? output : null));
                profiles.add(EnergyHatchConfiguration.symmetric(mode, 900_000 + itemEquivalent, 1234 + tier.level()));
            }
        return new HatchConfigurationSnapshot(session, revision, profiles);
    }

    private static boolean deliver(ClientHatchConfigurations client, Object connection, HatchConfigurationSnapshot snapshot) {
        byte[] payload = snapshot.encode();
        boolean accepted = false;
        int count = (payload.length + HatchConfigurationPacket.CHUNK_BYTES - 1) / HatchConfigurationPacket.CHUNK_BYTES;
        for (int index = count - 1; index >= 0; index--) {
            var packet = HatchConfigurationPacket.part(snapshot, payload, index);
            var bytes = Unpooled.buffer();
            try {
                packet.toBytes(bytes);
                var decoded = new HatchConfigurationPacket();
                decoded.fromBytes(bytes);
                accepted |= client.receive(connection, decoded);
            } finally {
                bytes.release();
            }
        }
        return accepted;
    }

    @Test
    void authoritativeLoginAndHotUpdateDriveBareItemsWhileSavedEffectiveProfilesRemainTheirOwn() {
        var session = UUID.randomUUID();
        var client = new ClientHatchConfigurations();
        var connection = new Object();
        client.connected(connection);
        assertNull(client.view());
        var initial = snapshot(session, 1, 777);
        assertEquals(initial, HatchConfigurationSnapshot.decode(initial.encode()));
        assertTrue(deliver(client, connection, initial));
        var current = Objects.requireNonNull(client.view());
        assertEquals(777, Objects.requireNonNull(Objects.requireNonNull(HatchItemProfiles.resource(null, HatchTier.LV1, HatchMode.INPUT, current).input()).basis()).itemEquivalent());
        assertNotEquals(HatchConfig.resourceConfiguration(HatchTier.LV1, HatchMode.INPUT), current.resource(HatchTier.LV1, HatchMode.INPUT));
        assertEquals(901_777, Objects.requireNonNull(HatchItemProfiles.energy(null, HatchTier.LV1, HatchMode.INPUT, snapshot(session, 2, 1777)).input()).capacity());
        var saved = new NBTTagCompound();
        saved.setString("Mode", "INPUT");
        saved.setInteger("Tier", 1);
        var own = new ResourceHatchConfiguration(ResourceHatchConfiguration.Side.fromItemEquivalent(7, 33, List.of(AEKeyType.fluids()), Map.of(AEKeyType.fluids(), 17L)), null);
        saved.setTag("EffectiveProfile", HatchConfigurationNbt.resource(own));
        assertTrue(deliver(client, connection, snapshot(session, 3, 1777)));
        assertEquals(1777, Objects.requireNonNull(Objects.requireNonNull(HatchItemProfiles.resource(null, HatchTier.LV1, HatchMode.INPUT, client.view()).input()).basis()).itemEquivalent());
        assertEquals(own, HatchItemProfiles.resource(saved, HatchTier.LV1, HatchMode.INPUT, client.view()));
        assertEquals(561L, Objects.requireNonNull(own.input()).capacities().get(AEKeyType.fluids()));
    }

    @Test
    void oldConnectionSessionGenerationAndMalformedPayloadCannotReplaceCompletedDisplay() {
        var client = new ClientHatchConfigurations();
        var connection = new Object();
        var session = UUID.randomUUID();
        client.connected(connection);
        var current = snapshot(session, 3, 100);
        assertTrue(deliver(client, connection, current));
        var installed = client.view();
        assertFalse(deliver(client, connection, snapshot(session, 2, 200)));
        assertFalse(deliver(client, connection, snapshot(UUID.randomUUID(), 4, 200)));
        var damaged = current.encode();
        assertThrows(IllegalArgumentException.class, () -> HatchConfigurationSnapshot.decode(Arrays.copyOf(damaged, damaged.length - 1)));
        assertSame(installed, client.view());
        var next = new Object();
        client.connected(next);
        assertNull(client.view());
        assertFalse(deliver(client, connection, snapshot(session, 5, 300)));
        assertTrue(deliver(client, next, snapshot(UUID.randomUUID(), 0, 400)));
        var newView = client.view();
        client.disconnected(connection);
        assertSame(newView, client.view());
        client.disconnected(next);
        assertNull(client.view());
        assertFalse(deliver(client, next, snapshot(UUID.randomUUID(), 1, 500)));
    }

    @Test
    void completeDisplayWaitsForEveryOutOfOrderChunkBeforePublishing() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        for (int index = 0; index < 18; index++) {
            var type = new KeyType("configuration_chunk_" + index);
            AEKeyTypes.register(type);
            capacities.put(type, 123L + index);
        }
        var session = UUID.randomUUID();
        var profiles = new ObjectArrayList<HatchConfiguration>();
        for (var ignored : HatchTier.values())
            for (var mode : HatchMode.values()) {
                profiles.add(ResourceHatchConfiguration.symmetric(mode, 3, capacities));
                profiles.add(EnergyHatchConfiguration.symmetric(mode, 1000, 32));
            }
        var snapshot = new HatchConfigurationSnapshot(session, 1, profiles);
        var payload = snapshot.encode();
        int count = (payload.length + HatchConfigurationPacket.CHUNK_BYTES - 1) / HatchConfigurationPacket.CHUNK_BYTES;
        assertTrue(count > 1);
        var client = new ClientHatchConfigurations();
        var connection = new Object();
        client.connected(connection);
        for (int index = count - 1; index > 0; index--) {
            assertFalse(client.receive(connection, HatchConfigurationPacket.part(snapshot, payload, index)));
            assertNull(client.view());
        }
        assertTrue(client.receive(connection, HatchConfigurationPacket.part(snapshot, payload, 0)));
        assertEquals(snapshot, client.view());
    }

    @Test
    void malformedSavedMixedSlotSumBecomesAReadableSavedDataFailureBeforeAnyAllocation() {
        var saved = new NBTTagCompound();
        saved.setString("Mode", "MIXED");
        saved.setInteger("Tier", 1);
        var profile = HatchConfigurationNbt.resource(ResourceHatchConfiguration.symmetric(HatchMode.MIXED, 1, Map.of(AEKeyType.items(), 64L)));
        profile.getCompoundTag("Input").setInteger("Slots", Integer.MAX_VALUE);
        saved.setTag("EffectiveProfile", profile);
        assertThrows(IllegalArgumentException.class, () -> HatchItemProfiles.resource(saved, HatchTier.LV1, HatchMode.MIXED));
        var declared = HatchConfigurationNbt.resource(new ResourceHatchConfiguration(
            ResourceHatchConfiguration.Side.fromItemEquivalent(1, 64, List.of(AEKeyType.fluids())), null));
        var item = new NBTTagCompound();
        item.setString("Mode", "INPUT");
        item.setInteger("Tier", 1);
        item.setTag("EffectiveProfile", declared);
        declared.getCompoundTag("Input").setLong("ItemEquivalent", Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> HatchItemProfiles.resource(item, HatchTier.LV1, HatchMode.INPUT));
        declared.getCompoundTag("Input").setLong("ItemEquivalent", 64);
        declared.getCompoundTag("Input").getCompoundTag("CapacityRatios").setLong("prototype_eng_test:missing_ratio_type", 1);
        assertThrows(IllegalArgumentException.class, () -> HatchItemProfiles.resource(item, HatchTier.LV1, HatchMode.INPUT));
        assertThrows(ArithmeticException.class, () -> new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(Integer.MAX_VALUE, Map.of(AEKeyType.items(), 64L)),
            new ResourceHatchConfiguration.Side(1, Map.of(AEKeyType.items(), 64L))));
    }
}
