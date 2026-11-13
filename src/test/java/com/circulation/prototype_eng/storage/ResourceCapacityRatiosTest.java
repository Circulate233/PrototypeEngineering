package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceCapacityRatiosTest {
    private static final KeyType SAVED = new KeyType("ratio_legacy_saved");

    static {
        AEKeyTypes.register(SAVED);
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
        assertEquals(HatchReconfigurationStatus.BLOCKED_CAPACITY, old.replace(target, () -> {
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

}
