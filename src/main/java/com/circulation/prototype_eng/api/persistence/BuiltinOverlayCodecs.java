package com.circulation.prototype_eng.api.persistence;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Built-in patch data only; overlay type registration remains the host's responsibility.
 */
public final class BuiltinOverlayCodecs {
    public static final NbtCodec<EnergyRequirementPatch> ENERGY_RATES = new NbtCodec<>() {
        @Override
        public NBTTagCompound encode(EnergyRequirementPatch value) {
            Objects.requireNonNull(value, "value");
            var tag = new NBTTagCompound();
            if (value.inputsPerTick() != null) {
                tag.setLong("InputsPerTick", value.inputsPerTick());
            }
            if (value.outputsPerTick() != null) {
                tag.setLong("OutputsPerTick", value.outputsPerTick());
            }
            if (value.voidExcessOutputs() != null) {
                tag.setBoolean("VoidExcessOutputs", value.voidExcessOutputs());
            }
            return tag;
        }

        @Override
        public EnergyRequirementPatch decode(NBTTagCompound tag) {
            StrictNbt.requireKeys(tag, "InputsPerTick", "OutputsPerTick", "VoidExcessOutputs");
            return new EnergyRequirementPatch(tag.hasKey("InputsPerTick") ? StrictNbt.requireLong(tag, "InputsPerTick") : null,
                tag.hasKey("OutputsPerTick") ? StrictNbt.requireLong(tag, "OutputsPerTick") : null,
                tag.hasKey("VoidExcessOutputs") ? StrictNbt.requireBoolean(tag, "VoidExcessOutputs") : null);
        }
    };

    private BuiltinOverlayCodecs() {
    }

    public static NbtCodec<ResourceRequirementPatch> resourceIo(ResourceKeyNbt resources) {
        Objects.requireNonNull(resources, "resources");
        return new NbtCodec<>() {
            @Override
            public NBTTagCompound encode(ResourceRequirementPatch value) {
                Objects.requireNonNull(value, "value");
                var tag = new NBTTagCompound();
                write(tag, "Inputs", value.inputs());
                write(tag, "InputsPerTick", value.inputsPerTick());
                write(tag, "OutputsPerTick", value.outputsPerTick());
                write(tag, "Outputs", value.outputs());
                if (value.voidExcessOutputs() != null) {
                    tag.setBoolean("VoidExcessOutputs", value.voidExcessOutputs());
                }
                return tag;
            }

            @Override
            public ResourceRequirementPatch decode(NBTTagCompound tag) {
                StrictNbt.requireKeys(tag, "Inputs", "InputsPerTick", "OutputsPerTick", "Outputs", "VoidExcessOutputs");
                return new ResourceRequirementPatch(read(tag, "Inputs"), read(tag, "InputsPerTick"), read(tag, "OutputsPerTick"),
                    read(tag, "Outputs"), tag.hasKey("VoidExcessOutputs") ? StrictNbt.requireBoolean(tag, "VoidExcessOutputs") : null);
            }

            private void write(NBTTagCompound tag, String field, @Nullable List<GenericStack> stacks) {
                if (stacks != null) {
                    tag.setTag(field, resources.writeStacks(stacks));
                }
            }

            private @Nullable List<GenericStack> read(NBTTagCompound tag, String field) {
                return tag.hasKey(field) ? resources.readStacks(StrictNbt.requireList(tag, field, Constants.NBT.TAG_COMPOUND)) : null;
            }
        };
    }
}
