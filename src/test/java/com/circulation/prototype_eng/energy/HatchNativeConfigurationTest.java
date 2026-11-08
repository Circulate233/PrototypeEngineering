package com.circulation.prototype_eng.energy;

import ae2.api.config.Actionable;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.storage.ResourceHatchBlockEntity;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.machine.ControllerData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class HatchNativeConfigurationTest {
    @Test
    void nativeEnergyConfigurationInvalidatesTheInitialViewAndAppliesVariantTransfers() {
        var tile = new NativeEnergy();
        var old = tile.storage();
        tile.configure(HatchMode.OUTPUT, HatchTier.LV3);
        assertNotSame(old, tile.storage());
        assertEquals(0, old.receiveEnergy(100, Actionable.MODULATE));
        assertEquals(0, old.extractEnergy(100, Actionable.MODULATE));
        assertEquals(HatchConfig.energyTransfer(HatchTier.LV3),
            tile.storage().receiveEnergy(Long.MAX_VALUE, Actionable.MODULATE));
        assertEquals(HatchConfig.energyCapacity(HatchTier.LV3), tile.storage().capacity());
        tile.configure(HatchMode.INPUT, HatchTier.LV1);
        assertEquals(HatchMode.OUTPUT, tile.mode());
        assertEquals(HatchTier.LV3, tile.tier());
    }

    @Test
    void explicitEnergyAndSavedEnergyIgnoreNativeConfigurationIncludingPartialNbt() {
        var explicit = new NativeEnergy(HatchMode.MIXED, HatchTier.LV2);
        explicit.configure(HatchMode.OUTPUT, HatchTier.LV3);
        assertEquals(HatchMode.MIXED, explicit.mode());
        assertEquals(HatchTier.LV2, explicit.tier());
        var saved = new NBTTagCompound();
        saved.setString("Mode", HatchMode.MIXED.name());
        saved.setInteger("Tier", 2);
        saved.setLong("Energy", 13);
        var restored = new NativeEnergy();
        restored.loadTag(saved);
        restored.configure(HatchMode.OUTPUT, HatchTier.LV3);
        assertEquals(13, restored.storage().savedEnergy());
        assertEquals(HatchMode.MIXED, restored.mode());
        assertEquals(HatchTier.LV2, restored.tier());
        var partial = new NativeEnergy();
        partial.loadTag(new NBTTagCompound());
        partial.configure(HatchMode.OUTPUT, HatchTier.LV3);
        assertEquals(HatchMode.INPUT, partial.mode());
        assertEquals(HatchTier.LV1, partial.tier());
    }

    @Test
    void nativeResourceConfigurationAppliesTheActualVariantLayoutOnlyOnce() {
        var tile = new NativeResource();
        tile.configure(HatchMode.MIXED, HatchTier.LV4);
        assertEquals(HatchConfig.slotsPerSide(HatchTier.LV4, HatchMode.MIXED), tile.slotsPerSide());
        tile.configure(HatchMode.INPUT, HatchTier.LV1);
        assertEquals(HatchMode.MIXED, tile.mode());
        assertEquals(HatchTier.LV4, tile.tier());
        var explicit = new NativeResource(HatchMode.OUTPUT, HatchTier.LV2);
        explicit.configure(HatchMode.MIXED, HatchTier.LV4);
        assertEquals(HatchMode.OUTPUT, explicit.mode());
        assertEquals(HatchTier.LV2, explicit.tier());
    }

    @Test
    void nativeControllerConfigurationAllowsSavingBeforeAssemblyAndPreservesRestoredData() {
        var type = new ResourceLocation("native_configuration_test", "machine");
        var tile = new NativeController();
        tile.configure(type, BlockOrientation.UP_WEST);
        tile.configure(new ResourceLocation("native_configuration_test", "other"), BlockOrientation.NORTH_UP);
        var saved = new NBTTagCompound();
        tile.saveAdditional(saved);
        var decoded = ControllerData.decode(saved.getCompoundTag("Machine"));
        assertEquals(type, decoded.type());
        assertEquals(BlockOrientation.UP_WEST, decoded.orientation());
        assertNull(decoded.runtime());
        assertNull(tile.machine());
        var runtime = new NBTTagCompound();
        runtime.setLong("Progress", 13);
        var restored = new NativeController();
        saved.setTag("Machine", new ControllerData(type, BlockOrientation.DOWN_EAST, runtime).encode());
        restored.loadTag(saved);
        restored.configure(type, BlockOrientation.NORTH_UP);
        var output = new NBTTagCompound();
        restored.saveAdditional(output);
        assertEquals(BlockOrientation.DOWN_EAST, ControllerData.decode(output.getCompoundTag("Machine")).orientation());
        assertEquals(runtime, ControllerData.decode(output.getCompoundTag("Machine")).runtime());
        assertNull(restored.machine());
    }

    private static final class NativeController extends MachineControllerBlockEntity {
        private void configure(ResourceLocation type, BlockOrientation orientation) {
            initializeControllerConfiguration(type, orientation);
        }
    }

    private static final class NativeEnergy extends EnergyHatchBlockEntity {
        private NativeEnergy() {
        }

        private NativeEnergy(HatchMode mode, HatchTier tier) {
            super(mode, tier);
        }

        private void configure(HatchMode mode, HatchTier tier) {
            initializeConfiguration(mode, tier);
        }
    }

    private static final class NativeResource extends ResourceHatchBlockEntity {
        private NativeResource() {
        }

        private NativeResource(HatchMode mode, HatchTier tier) {
            super(mode, tier);
        }

        private void configure(HatchMode mode, HatchTier tier) {
            initializeConfiguration(mode, tier);
        }
    }
}
