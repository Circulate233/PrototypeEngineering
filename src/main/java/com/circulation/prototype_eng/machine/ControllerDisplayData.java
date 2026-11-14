package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Public display snapshot only; generation and sequence are not machine or owner permissions.
 */
public record ControllerDisplayData(ResourceLocation type, BlockOrientation orientation, boolean formed,
                                    long generation, long sequence) {
    private static final VarHandle GENERATIONS;
    @SuppressWarnings({"unused", "FieldMayBeFinal"}) // Shared mutable counter is accessed only through GENERATIONS.
    private static long nextGeneration = 1;

    static {
        try {
            GENERATIONS = MethodHandles.lookup().findStaticVarHandle(ControllerDisplayData.class, "nextGeneration", long.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public ControllerDisplayData {
        Objects.requireNonNull(type);
        Objects.requireNonNull(orientation);
        if (generation <= 0 || sequence < 0) {
            throw new IllegalArgumentException("Invalid controller display generation/sequence");
        }
    }

    static long newGeneration() {
        long result = (long) GENERATIONS.getAndAdd(1L);
        if (result <= 0) {
            throw new IllegalStateException("Controller display generations exhausted");
        }
        return result;
    }

    public static ControllerDisplayData decode(NBTTagCompound tag) {
        if (tag.getInteger("Version") != 1) {
            throw new IllegalArgumentException("Unsupported controller display version");
        }
        return new ControllerDisplayData(new ResourceLocation(tag.getString("Type")), BlockOrientation.valueOf(tag.getString("Orientation")),
            tag.getBoolean("Formed"), tag.getLong("Generation"), tag.getLong("Sequence"));
    }

    public boolean follows(@Nullable ControllerDisplayData previous) {
        return previous == null || type.equals(previous.type) && (generation > previous.generation
            || generation == previous.generation && sequence > previous.sequence);
    }

    public NBTTagCompound encode() {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setString("Type", type.toString());
        tag.setString("Orientation", orientation.name());
        tag.setBoolean("Formed", formed);
        tag.setLong("Generation", generation);
        tag.setLong("Sequence", sequence);
        return tag;
    }
}
