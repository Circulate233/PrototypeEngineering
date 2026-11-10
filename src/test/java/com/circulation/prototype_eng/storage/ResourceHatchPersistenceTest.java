package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceHatchPersistenceTest {
    private static final KeyType FIRST = new KeyType("hatch_persistence_first");
    private static final KeyType SECOND = new KeyType("hatch_persistence_second");
    private static final Key A = new Key(FIRST, "a");
    private static final Key B = new Key(SECOND, "b");

    static {
        AEKeyTypes.register(FIRST);
        AEKeyTypes.register(SECOND);
    }

    private static NBTTagCompound base() {
        var tag = new NBTTagCompound();
        tag.setString("Mode", "MIXED");
        tag.setInteger("Tier", 1);
        tag.setBoolean("AutoInput", false);
        tag.setBoolean("AutoOutput", false);
        tag.setLong("MaxFluidInRate", 0);
        tag.setLong("MaxFluidOutRate", 0);
        return tag;
    }

    private static NBTTagList entries(int slot, Key key, long amount) {
        var entry = new NBTTagCompound();
        entry.setInteger("Slot", slot);
        entry.setTag("Key", new ResourceKeyNbt().encode(key));
        entry.setLong("Quantity", amount);
        var list = new NBTTagList();
        list.appendTag(entry);
        return list;
    }

    private static NBTTagList legacyEntries(int slot, Key key, long amount) {
        var entry = key.toTag();
        entry.setInteger("Slot", slot);
        entry.setString(AEKey.TYPE_FIELD, key.getType().getId().toString());
        entry.setLong("#", amount);
        var list = new NBTTagList();
        list.appendTag(entry);
        return list;
    }

    @Test
    void asymmetricEffectiveProfilesAndLastSlotsRoundTripWithoutRevivingOldCapabilities() {
        var profile = new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(96, Map.of(FIRST, 64L)),
            new ResourceHatchConfiguration.Side(128, Map.of(SECOND, 1024L)));
        var tag = base();
        tag.setTag("EffectiveProfile", HatchConfigurationNbt.resource(profile));
        tag.setTag("Input", entries(95, A, 57));
        tag.setTag("Output", entries(127, B, 999));
        var tile = new ResourceHatchBlockEntity();
        tile.loadTag(tag);
        var input = Objects.requireNonNull(tile.storage().input());
        var output = Objects.requireNonNull(tile.storage().output());
        assertEquals(new GenericStack(A, 57), input.getStack(95));
        assertEquals(new GenericStack(B, 999), output.getStack(127));
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        var restored = new ResourceHatchBlockEntity();
        restored.loadTag(saved);
        assertEquals(new GenericStack(A, 57), Objects.requireNonNull(restored.storage().input()).getStack(95));
        assertEquals(new GenericStack(B, 999), Objects.requireNonNull(restored.storage().output()).getStack(127));
        tile.invalidate();
        tile.validate();
        assertEquals(0, input.insert(95, A, 1, Actionable.MODULATE));
        assertEquals(0, output.extract(127, B, 1, Actionable.MODULATE));
        assertEquals(57, Objects.requireNonNull(tile.storage().input()).amount(A));
        assertEquals(999, Objects.requireNonNull(tile.storage().output()).amount(B));

        var before = tile.storage();
        var invalid = entries(127, B, 1025);
        saved.setTag("Output", invalid);
        assertThrows(IllegalArgumentException.class, () -> tile.loadTag(saved));
        assertSame(before, tile.storage());
        assertEquals(57, Objects.requireNonNull(before.input()).amount(A));
        assertEquals(999, Objects.requireNonNull(before.output()).amount(B));
        saved.removeTag("Output");
        assertThrows(IllegalArgumentException.class, () -> tile.loadTag(saved));
        assertSame(before, tile.storage());
    }

    @Test
    void droppedHatchRetainsItsConfigurationButCannotRestoreEjectedMaterialsAgain() {
        var profile = new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(3, Map.of(FIRST, 64L)),
            new ResourceHatchConfiguration.Side(5, Map.of(SECOND, 1024L)));
        var tag = base();
        tag.setBoolean("AutoInput", true);
        tag.setLong("MaxFluidOutRate", 777);
        tag.setTag("EffectiveProfile", HatchConfigurationNbt.resource(profile));
        tag.setTag("Input", entries(2, A, 57));
        tag.setTag("Output", entries(4, B, 999));
        var tile = new ResourceHatchBlockEntity();
        tile.loadTag(tag);
        var drop = tile.saveForItemDrop();
        assertEquals(0, drop.getTagList("Input", 10).tagCount());
        assertEquals(0, drop.getTagList("Output", 10).tagCount());
        assertEquals(tag.getCompoundTag("EffectiveProfile"), drop.getCompoundTag("EffectiveProfile"));
        assertTrue(drop.getBoolean("AutoInput"));
        assertEquals(777, drop.getLong("MaxFluidOutRate"));
        assertEquals(57, Objects.requireNonNull(tile.storage().input()).getAmount(2));
        assertEquals(999, Objects.requireNonNull(tile.storage().output()).getAmount(4));
        var placed = new ResourceHatchBlockEntity();
        placed.loadTag(drop);
        assertEquals(3, Objects.requireNonNull(placed.storage().input()).size());
        assertEquals(5, Objects.requireNonNull(placed.storage().output()).size());
        assertEquals(0, Objects.requireNonNull(placed.storage().input()).getAmount(2));
        assertEquals(0, Objects.requireNonNull(placed.storage().output()).getAmount(4));
    }

    @Test
    void legacySavedLayoutAndGenericStackEntriesRestoreBeforeAnyCurrentTarget() {
        var tag = base();
        tag.setInteger("Slots", 96);
        var limits = new NBTTagCompound();
        limits.setLong(FIRST.getId().toString(), 64);
        limits.setLong(SECOND.getId().toString(), 1024);
        tag.setTag("Capacities", limits);
        tag.setTag("Input", legacyEntries(95, A, 61));
        tag.setTag("Output", legacyEntries(94, B, 1001));
        var tile = new ResourceHatchBlockEntity();
        tile.loadTag(tag);
        assertEquals(61, Objects.requireNonNull(tile.storage().input()).getAmount(95));
        assertEquals(1001, Objects.requireNonNull(tile.storage().output()).getAmount(94));
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        var restored = new ResourceHatchBlockEntity();
        restored.loadTag(saved);
        assertEquals(61, Objects.requireNonNull(restored.storage().input()).getAmount(95));
        assertEquals(1001, Objects.requireNonNull(restored.storage().output()).getAmount(94));
    }

    @Test
    void modernIdentityAndSettingsAreRequiredBeforeChangingTheExistingHost() {
        var legacy = base();
        legacy.setInteger("Tier", 10);
        legacy.setInteger("Slots", 2);
        var capacities = new NBTTagCompound();
        capacities.setLong(FIRST.getId().toString(), 100);
        capacities.setLong(SECOND.getId().toString(), 200);
        legacy.setTag("Capacities", capacities);
        legacy.setTag("Input", entries(0, A, 31));
        legacy.setTag("Output", entries(1, B, 47));
        var tile = new ResourceHatchBlockEntity();
        tile.loadTag(legacy);
        tile.setAutoInput(true);
        tile.setMaxFluidOutputRate(17);
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved); // Corrupt the real complete modern format, never a synthetic partial modern tag.
        var original = tile.storage();
        var profile = tile.effectiveConfiguration();
        tile.requestConfiguration(new ResourceHatchConfiguration(new ResourceHatchConfiguration.Side(1, Map.of(FIRST, 10L)),
            new ResourceHatchConfiguration.Side(1, Map.of(SECOND, 10L))));
        var status = tile.configurationStatus();
        for (String field : List.of("Mode", "Tier", "AutoInput", "AutoOutput", "MaxFluidInRate", "MaxFluidOutRate")) {
            var missing = saved.copy();
            missing.removeTag(field);
            assertThrows(IllegalArgumentException.class, () -> tile.loadTag(missing), field);
            var wrongType = saved.copy();
            if (field.equals("Mode")) wrongType.setInteger(field, 1);
            else wrongType.setString(field, "wrong");
            assertThrows(IllegalArgumentException.class, () -> tile.loadTag(wrongType), field);
            assertSame(original, tile.storage());
            assertSame(profile, tile.effectiveConfiguration());
            assertEquals(status, tile.configurationStatus());
            assertEquals(31, Objects.requireNonNull(original.input()).amount(A));
            assertEquals(47, Objects.requireNonNull(original.output()).amount(B));
            assertEquals(17, tile.maxFluidOutputRate());
            assertTrue(tile.autoInput());
        }
    }

    @Test
    void modernSingleDirectionRejectsEvenAnEmptyOppositeSide() {
        var legacy = base();
        legacy.setString("Mode", "INPUT");
        legacy.setInteger("Slots", 2);
        var capacities = new NBTTagCompound();
        capacities.setLong(FIRST.getId().toString(), 100);
        legacy.setTag("Capacities", capacities);
        legacy.setTag("Input", entries(1, A, 29));
        var tile = new ResourceHatchBlockEntity();
        tile.loadTag(legacy);
        var original = tile.storage();
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        saved.setTag("Output", new NBTTagList());
        assertThrows(IllegalArgumentException.class, () -> tile.loadTag(saved));
        assertSame(original, tile.storage());
        assertEquals(29, Objects.requireNonNull(original.input()).amount(A));
    }
}
