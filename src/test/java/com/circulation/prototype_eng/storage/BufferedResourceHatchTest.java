package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.storage.HatchMode;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BufferedResourceHatchTest {
    private static final KeyType FIRST = new KeyType("first");
    private static final KeyType SECOND = new KeyType("second");
    private static final IActionSource SOURCE = IActionSource.empty();

    private BufferedResourceHatch mixed() {
        return new BufferedResourceHatch(HatchMode.MIXED, 2,
                Map.of(FIRST, 10_000L, SECOND, 20_000L), () -> {});
    }

    @Test
    void automationAndMachineUseIndependentMultiTypeBuffers() {
        var hatch = mixed();
        var first = new Key(FIRST, "material");
        var second = new Key(SECOND, "material");
        var outside = hatch.automation();

        assertEquals(70, outside.insert(first, 70, Actionable.SIMULATE, SOURCE));
        assertEquals(0, hatch.input().getAvailableStacks().get(first));
        outside.insert(first, 70, Actionable.MODULATE, SOURCE);
        outside.insert(second, 9_000, Actionable.MODULATE, SOURCE);
        hatch.output().insert(first, 5, Actionable.MODULATE, SOURCE);
        hatch.output().insert(second, 300, Actionable.MODULATE, SOURCE);

        assertEquals(5, outside.getAvailableStacks().get(first));
        assertEquals(5, outside.extract(first, 80, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(first, 80, Actionable.MODULATE, SOURCE));
        assertEquals(70, hatch.input().getAvailableStacks().get(first));
        assertEquals(20, hatch.input().extract(first, 20, Actionable.MODULATE, SOURCE));
        assertEquals(9_000, hatch.input().getAvailableStacks().get(second));
        assertEquals(300, hatch.output().getAvailableStacks().get(second));
    }

    @Test
    void retainedAccessCannotTransferAfterHostInvalidation() {
        var hatch = mixed();
        var key = new Key(FIRST, "material");
        var outside = hatch.automation();
        var machineInput = hatch.input();
        var machineOutput = hatch.output();
        outside.insert(key, 50, Actionable.MODULATE, SOURCE);
        machineOutput.insert(key, 30, Actionable.MODULATE, SOURCE);
        hatch.invalidate();

        assertEquals(0, outside.insert(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, outside.extract(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, machineInput.extract(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(0, machineOutput.insert(key, 10, Actionable.MODULATE, SOURCE));
        assertEquals(50, machineInput.getAmount(0));
        assertEquals(30, machineOutput.getAmount(0));
    }

    // Real AEKey implementations exercise multi-type routing without a game world.
    private static final class KeyType extends AEKeyType {
        KeyType(String name) {
            super(new ResourceLocation("prototype_eng_test", name), Key.class, new TextComponentString(name));
        }

        @Override
        public AEKey readFromPacket(PacketBuffer input) {
            return new Key(this, input.readString(128));
        }

        @Override
        public AEKey loadKeyFromTag(NBTTagCompound tag) {
            return new Key(this, tag.getString("name"));
        }
    }

    private static final class Key extends AEKey {
        private final KeyType type;
        private final String name;

        Key(KeyType type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public AEKeyType getType() { return type; }
        @Override
        public AEKey dropSecondary() { return this; }
        @Override
        public Object getPrimaryKey() { return name; }
        @Override
        public ResourceLocation getId() { return new ResourceLocation("prototype_eng_test", name); }
        @Override
        public Object getReadOnlyStack() { return this; }
        @Override
        protected ITextComponent computeDisplayName() { return new TextComponentString(name); }
        @Override
        public NBTBase get(String componentId) { return null; }
        @Override
        public void writeToPacket(PacketBuffer data) { data.writeString(name); }

        @Override
        public NBTTagCompound toTag() {
            var tag = new NBTTagCompound();
            tag.setString("name", name);
            return tag;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Key other && type == other.type && name.equals(other.name);
        }

        @Override
        public int hashCode() { return 31 * type.hashCode() + name.hashCode(); }
    }
}
