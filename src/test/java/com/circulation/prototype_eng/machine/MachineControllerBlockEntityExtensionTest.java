package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MachineControllerBlockEntityExtensionTest {
    @Test
    void externalNbtHooksPreserveRuntimeAndFullDirectionWhileDetachedLifecycleRemainsDormant() {
        var type = MachineType.builder(new ResourceLocation("controller_extension_test", "controller"),
            GuiText.ResourceStorage, new ResourceLocation("controller_extension_test", "shape")).build();
        var runtime = new NBTTagCompound();
        runtime.setLong("WorkProgress", 123);
        var saved = new NBTTagCompound();
        saved.setTag("Machine", new ControllerData(type.id(), BlockOrientation.UP_WEST, runtime).encode());
        saved.setInteger("Extension", 17);
        var tile = new DownstreamController(type);
        tile.readFromNBT(saved);
        assertNull(tile.machine());
        var output = new NBTTagCompound();
        tile.saveAdditional(output);
        var restored = ControllerData.decode(output.getCompoundTag("Machine"));
        assertEquals(BlockOrientation.UP_WEST, restored.orientation());
        assertEquals(runtime, restored.runtime());
        assertEquals(17, output.getInteger("Extension"));
        assertEquals("UP", output.getString("forward"));
        assertEquals("WEST", output.getString("up"));
    }

    private static final class DownstreamController extends MachineControllerBlockEntity {
        private int extension;

        private DownstreamController(MachineType type) {
            super(type, BlockOrientation.NORTH_UP);
        }

        @Override
        public void loadTag(@NotNull NBTTagCompound tag) {
            super.loadTag(tag);
            extension = tag.getInteger("Extension");
        }

        @Override
        public void saveAdditional(@NotNull NBTTagCompound tag) {
            super.saveAdditional(tag);
            tag.setInteger("Extension", extension);
        }
    }
}
