package com.circulation.prototype_eng.api.machine.render;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Owner-context state only. No World callback, synchronization permission or transient TE owner.
 */
public class MachineAnimationState {
    public static final int MAX_LAYERS = 256;
    public static final ComponentType<MachineInstance, MachineAnimationState> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "machine_animation"), _ -> new MachineAnimationState(), List.of());
    public static final MachineComponentCodec<MachineAnimationState> CODEC = new MachineComponentCodec<>() {
        @Override
        public NBTTagCompound encode(MachineInstance owner, MachineAnimationState component) {
            var state = component.snapshot();
            var tag = new NBTTagCompound();
            tag.setInteger("Version", 1);
            tag.setLong("Revision", state.revision());
            var layers = new NBTTagList();
            var elements = state.layers();
            for (int index = 0; index < elements.size(); index++) {
                var layer = elements.get(index);
                layers.appendTag(new NBTTagString(layer));
            }
            tag.setTag("Layers", layers);
            return tag;
        }

        @Override
        public MachineAnimationState restore(MachineInstance owner, MachineAnimationState component, NBTTagCompound tag) {
            if (tag.getInteger("Version") != 1)
                throw new IllegalArgumentException("Unsupported machine animation state version");
            var values = tag.getTagList("Layers", Constants.NBT.TAG_STRING);
            if (values.tagCount() > MAX_LAYERS) throw new IllegalArgumentException("Too many animation layers");
            var layers = new ObjectArrayList<String>(values.tagCount());
            for (int i = 0; i < values.tagCount(); i++) layers.add(values.getStringTagAt(i));
            component.state = new Snapshot(tag.getLong("Revision"), layers);
            return component;
        }
    };

    private Snapshot state = new Snapshot(0, List.of());

    public Snapshot snapshot() {
        return state;
    }

    /**
     * Called in the machine's current execution context; changing state never calls TE/World.
     * The supplied layer list is borrowed and must stay unchanged until replaced; snapshot readers
     * must not modify it. The list must fit MAX_LAYERS.
     */
    public void setLayers(List<String> layers) {
        if (!layers.equals(state.layers()))
            state = new Snapshot(Math.incrementExact(state.revision()), layers);
    }

    public void setAnimation(@Nullable String name) {
        setLayers(name == null ? List.of() : List.of(name));
    }

    public void clear() {
        setLayers(List.of());
    }

    public record Snapshot(long revision, List<String> layers) {
    }
}
