package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The tile's detached save envelope; reading it never creates a machine or touches a world.
 */
@SuppressWarnings("ClassCanBeRecord") // Mutable NBT stays private; accessors return detached copies.
public final class ControllerData {
    private final ResourceLocation type;
    private final BlockOrientation orientation;
    @Nullable
    private final NBTTagCompound runtime;

    public ControllerData(ResourceLocation type, BlockOrientation orientation, @Nullable NBTTagCompound runtime) {
        this.type = Objects.requireNonNull(type);
        this.orientation = Objects.requireNonNull(orientation);
        this.runtime = runtime == null ? null : runtime.copy();
    }

    public static ControllerData decode(NBTTagCompound saved) {
        StrictNbt.requireKeys(saved, "Version", "Type", "Orientation", "Runtime");
        if (StrictNbt.requireInt(saved, "Version") != 1) {
            throw new IllegalArgumentException("Unsupported controller save version");
        }
        return new ControllerData(StrictNbt.requireId(saved, "Type"),
            BlockOrientation.valueOf(StrictNbt.requireString(saved, "Orientation")),
            saved.hasKey("Runtime") ? StrictNbt.requireCompound(saved, "Runtime") : null);
    }

    public ResourceLocation type() {
        return type;
    }

    public BlockOrientation orientation() {
        return orientation;
    }

    @Nullable
    public NBTTagCompound runtime() {
        return runtime == null ? null : runtime.copy();
    }

    public NBTTagCompound encode() {
        var saved = new NBTTagCompound();
        saved.setInteger("Version", 1);
        saved.setString("Type", type.toString());
        saved.setString("Orientation", orientation.name());
        if (runtime != null) {
            saved.setTag("Runtime", runtime.copy());
        }
        return saved;
    }
}
