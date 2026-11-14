package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The tile's transient save envelope; reading it never creates a machine or touches a world.
 * Runtime NBT is borrowed: do not mutate input, accessor results or encoded runtime while this
 * value is in use. Components capture live mutable data before creating this envelope.
 */
public record ControllerData(ResourceLocation type, BlockOrientation orientation, @Nullable NBTTagCompound runtime) {
    public ControllerData {
        Objects.requireNonNull(type);
        Objects.requireNonNull(orientation);
    }

    public static ControllerData decode(NBTTagCompound saved) {
        if (saved.getInteger("Version") != 1) {
            throw new IllegalArgumentException("Unsupported controller save version");
        }
        return new ControllerData(new ResourceLocation(saved.getString("Type")),
            BlockOrientation.valueOf(saved.getString("Orientation")),
            saved.hasKey("Runtime") ? saved.getCompoundTag("Runtime") : null);
    }

    public NBTTagCompound encode() {
        var saved = new NBTTagCompound();
        saved.setInteger("Version", 1);
        saved.setString("Type", type.toString());
        saved.setString("Orientation", orientation.name());
        if (runtime != null) {
            saved.setTag("Runtime", runtime);
        }
        return saved;
    }
}
