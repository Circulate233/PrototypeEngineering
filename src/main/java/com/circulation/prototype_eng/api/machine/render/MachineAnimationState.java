package com.circulation.prototype_eng.api.machine.render;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Owner-context state only. No World callback, synchronization permission or transient TE owner. */
public class MachineAnimationState {
    public static final int MAX_LAYERS = 256;
    public static final int MAX_NAME_LENGTH = 1024;
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
            for (var layer : state.layers()) layers.appendTag(new NBTTagString(layer));
            tag.setTag("Layers", layers);
            return tag;
        }

        @Override
        public MachineAnimationState restore(MachineInstance owner, MachineAnimationState component, NBTTagCompound tag) {
            StrictNbt.requireKeys(tag, "Version", "Revision", "Layers");
            if (StrictNbt.requireInt(tag, "Version") != 1)
                throw new IllegalArgumentException("Unsupported machine animation state version");
            var values = StrictNbt.requireList(tag, "Layers", Constants.NBT.TAG_STRING);
            if (values.tagCount() > MAX_LAYERS) throw new IllegalArgumentException("Too many animation layers");
            var layers = new ObjectArrayList<String>(values.tagCount());
            for (int i = 0; i < values.tagCount(); i++) layers.add(values.getStringTagAt(i));
            component.state = new Snapshot(StrictNbt.requireLong(tag, "Revision"), layers);
            return component;
        }
    };

    private Snapshot state = new Snapshot(0, List.of());

    public Snapshot snapshot() {
        return state;
    }

    /** Called in the machine's current execution context; changing state never calls TE/World. */
    public void setLayers(List<String> layers) {
        var next = new Snapshot(state.revision(), layers);
        if (!next.layers().equals(state.layers()))
            state = new Snapshot(Math.incrementExact(state.revision()), next.layers());
    }

    public void setAnimation(@Nullable String name) {
        setLayers(name == null ? List.of() : List.of(name));
    }

    public void clear() {
        setLayers(List.of());
    }

    public record Snapshot(long revision, List<String> layers) {
        public Snapshot {
            if (revision < 0 || layers.size() > MAX_LAYERS)
                throw new IllegalArgumentException("Invalid animation revision/layer count");
            var copy = new ObjectArrayList<String>(layers.size());
            for (var name : layers) {
                Objects.requireNonNull(name, "animation layer");
                if (name.isBlank() || !name.equals(name.trim()) || name.length() > MAX_NAME_LENGTH)
                    throw new IllegalArgumentException("Animation layer must be a nonempty, trimmed bounded name");
                copy.add(name);
            }
            layers = List.copyOf(copy);
        }
    }
}
