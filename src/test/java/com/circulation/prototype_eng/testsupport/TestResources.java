package com.circulation.prototype_eng.testsupport;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

public final class TestResources {
    private TestResources() {
    }

    // Real AEKey implementations exercise multi-type routing without a game world.
    public static final class KeyType extends AEKeyType {
        public KeyType(String name) {
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

    public static final class Key extends AEKey {
        private final KeyType type;
        private final String name;

        public Key(KeyType type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public AEKeyType getType() {
            return type;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public Object getPrimaryKey() {
            return name;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("prototype_eng_test", name);
        }

        @Override
        public Object getReadOnlyStack() {
            return this;
        }

        @Override
        protected ITextComponent computeDisplayName() {
            return new TextComponentString(name);
        }

        @Override
        public NBTBase get(String componentId) {
            return null;
        }

        @Override
        public void writeToPacket(PacketBuffer data) {
            data.writeString(name);
        }

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
        public int hashCode() {
            return 31 * type.hashCode() + name.hashCode();
        }
    }
}
