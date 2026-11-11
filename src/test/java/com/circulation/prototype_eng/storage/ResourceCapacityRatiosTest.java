package com.circulation.prototype_eng.storage;

import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCapacityRatiosTest {
    private static final AEKeyType CUSTOM = new ScaledType("ratio_scaled", 250);
    private static final KeyType SAVED = new KeyType("ratio_legacy_saved");

    static {
        AEKeyTypes.register(CUSTOM);
        AEKeyTypes.register(SAVED);
    }

    @Test
    void everyTierScalesItemsFluidsAndCustomTypesFromOneItemEquivalentCapacity() {
        for (var tier : HatchTier.values()) {
            var profile = HatchConfig.resourceConfiguration(tier, HatchMode.MIXED);
            var side = Objects.requireNonNull(profile.input());
            assertEquals(tier.itemsPerSlot(), side.capacities().getLong(AEKeyType.items()));
            assertEquals(Math.multiplyExact(tier.itemsPerSlot(), 1000), side.capacities().getLong(AEKeyType.fluids()));
            assertEquals(Math.multiplyExact(tier.itemsPerSlot(), 250), side.capacities().getLong(CUSTOM));
            assertEquals(side, profile.output());
        }
        assertEquals(64_000L, Objects.requireNonNull(HatchConfig.resourceConfiguration(HatchTier.LV1, HatchMode.INPUT).input()).capacities().getLong(AEKeyType.fluids()));
    }

    @Test
    void explicitRatiosProduceImmutableIndependentSidesAndRejectOverflowBeforeStorageCreation() {
        var types = List.of(AEKeyType.items(), AEKeyType.fluids(), CUSTOM);
        var input = ResourceHatchConfiguration.Side.fromItemEquivalent(3, 64, types, Reference2LongMaps.singleton(CUSTOM, 500L));
        var output = ResourceHatchConfiguration.Side.fromItemEquivalent(7, 128, types);
        assertEquals(32_000L, input.capacities().getLong(CUSTOM));
        assertEquals(32_000L, output.capacities().getLong(CUSTOM));
        assertEquals(64_000L, input.capacities().getLong(AEKeyType.fluids()));
        assertEquals(128_000L, output.capacities().getLong(AEKeyType.fluids()));
        assertEquals(input, HatchConfigurationNbt.resourceSide(HatchConfigurationNbt.resource(new ResourceHatchConfiguration(input, null)).getCompoundTag("Input")));
        assertNull(new ResourceHatchConfiguration.Side(3, new Reference2LongArrayMap<>(new AEKeyType[]{AEKeyType.items(), AEKeyType.fluids()}, new long[]{64L, 64_000L})).basis());
        assertThrows(UnsupportedOperationException.class, () -> input.capacities().reference2LongEntrySet().iterator().next().setValue(1L));
        @SuppressWarnings("deprecation") // Verify fastutil's inherited boxed Map bridge also rejects entry writes.
        var boxedEntry = input.capacities().entrySet().iterator().next();
        assertThrows(UnsupportedOperationException.class, () -> boxedEntry.setValue(1L));
        assertThrows(ArithmeticException.class, () -> ResourceHatchConfiguration.Side.fromItemEquivalent(1, Long.MAX_VALUE, types));
        assertThrows(IllegalArgumentException.class, () -> ResourceHatchConfiguration.Side.fromItemEquivalent(1, 64, types, Reference2LongMaps.singleton(CUSTOM, 0L)));
        assertThrows(IllegalArgumentException.class, () -> ResourceHatchConfiguration.Side.fromItemEquivalent(1, 64, types, Reference2LongMaps.singleton(CUSTOM, -1L)));
        assertThrows(IllegalArgumentException.class, () -> ResourceHatchConfiguration.Side.fromItemEquivalent(1, 64, List.of(AEKeyType.items()), Reference2LongMaps.singleton(CUSTOM, 1L)));
        assertThrows(IllegalArgumentException.class, () -> ResourceHatchConfiguration.Side.fromItemEquivalent(1, 64, List.of(new ScaledType("invalid_ratio", 0))));
        assertThrows(IllegalArgumentException.class, () -> ResourceHatchConfiguration.Side.fromItemEquivalent(1, -1, types));
    }

    @Test
    void legacyEffectiveAmountsSurviveRatioChangeAndUnsafeShrinkIsRejected() {
        var key = new Key(SAVED, "material");
        var legacy = new NBTTagCompound();
        legacy.setString("Mode", "INPUT");
        legacy.setInteger("Tier", 1);
        legacy.setInteger("Slots", 1);
        var limits = new NBTTagCompound();
        limits.setLong(SAVED.getId().toString(), 9000);
        legacy.setTag("Capacities", limits);
        var contents = new NBTTagList();
        var entry = new NBTTagCompound();
        entry.setInteger("Slot", 0);
        entry.setTag("Key", new ResourceKeyNbt().encode(key));
        entry.setLong("Quantity", 8500);
        contents.appendTag(entry);
        legacy.setTag("Input", contents);
        var tile = new ResourceHatchBlockEntity();
        tile.loadTag(legacy);
        var old = tile.storage();
        var target = new ResourceHatchConfiguration(ResourceHatchConfiguration.Side.fromItemEquivalent(1, 64, List.of(SAVED)), null);
        assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, old.replace(target, null, () -> {
            }, () -> true,
            _ -> {
                throw new AssertionError("Unsafe ratio shrink must not publish");
            }).status());
        assertEquals(8500, Objects.requireNonNull(old.input()).amount(key));
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        assertEquals(9000L, Objects.requireNonNull(HatchItemProfiles.resource(saved, HatchTier.LV1, HatchMode.INPUT).input()).capacities().getLong(SAVED));
        var restored = new ResourceHatchBlockEntity();
        restored.loadTag(saved);
        assertEquals(8500, Objects.requireNonNull(restored.storage().input()).amount(key));
        assertEquals(500, Objects.requireNonNull(restored.storage().input()).insert(key, 1000, Actionable.MODULATE));
    }

    private static final class ScaledType extends AEKeyType {
        private final int amount;

        private ScaledType(String id, int amount) {
            super(new ResourceLocation("prototype_eng_test", id), Key.class, new TextComponentString(id));
            this.amount = amount;
        }

        @Override
        public int getAmountPerUnit() {
            return amount;
        }

        @Override
        public AEKey readFromPacket(PacketBuffer input) {
            return null;
        }

        @Override
        public AEKey loadKeyFromTag(NBTTagCompound tag) {
            return null;
        }
    }
}
