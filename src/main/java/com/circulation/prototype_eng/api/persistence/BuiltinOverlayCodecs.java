package com.circulation.prototype_eng.api.persistence;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Built-in patch data only; overlay type registration remains the host's responsibility.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class BuiltinOverlayCodecs {
    public static final NbtCodec<EnergyRequirementPatch> ENERGY_RATES = new NbtCodec<>() {
        @Override
        public NBTTagCompound encode(EnergyRequirementPatch value) {
            var tag = new NBTTagCompound();
            if (value.inputsPerTick() != -1) {
                tag.setLong("InputsPerTick", value.inputsPerTick());
            }
            if (value.outputsPerTick() != -1) {
                tag.setLong("OutputsPerTick", value.outputsPerTick());
            }
            if (value.voidExcessOutputs() != -1) {
                tag.setBoolean("VoidExcessOutputs", value.voidExcessOutputs() == 1);
            }
            return tag;
        }

        @Override
        public EnergyRequirementPatch decode(NBTTagCompound tag) {
            return new EnergyRequirementPatch(tag.hasKey("InputsPerTick") ? tag.getLong("InputsPerTick") : -1,
                tag.hasKey("OutputsPerTick") ? tag.getLong("OutputsPerTick") : -1,
                tag.hasKey("VoidExcessOutputs") ? tag.getBoolean("VoidExcessOutputs") ? 1 : 0 : -1);
        }
    };

    private BuiltinOverlayCodecs() {
    }

    public static NbtCodec<ResourceRequirementPatch> resourceIo(ResourceKeyNbt resources) {
        return new NbtCodec<>() {
            @Override
            public NBTTagCompound encode(ResourceRequirementPatch value) {
                var tag = new NBTTagCompound();
                write(tag, "Inputs", value.inputs());
                write(tag, "InputsPerTick", value.inputsPerTick());
                write(tag, "OutputsPerTick", value.outputsPerTick());
                write(tag, "Outputs", value.outputs());
                if (value.voidExcessOutputs() != -1) {
                    tag.setBoolean("VoidExcessOutputs", value.voidExcessOutputs() == 1);
                }
                return tag;
            }

            @Override
            public ResourceRequirementPatch decode(NBTTagCompound tag) {
                return new ResourceRequirementPatch(read(tag, "Inputs"), read(tag, "InputsPerTick"), read(tag, "OutputsPerTick"),
                    read(tag, "Outputs"), tag.hasKey("VoidExcessOutputs") ? tag.getBoolean("VoidExcessOutputs") ? 1 : 0 : -1);
            }

            private void write(NBTTagCompound tag, String field, @Nullable List<GenericStack> stacks) {
                if (stacks != null) {
                    tag.setTag(field, resources.writeStacks(stacks));
                }
            }

            private @Nullable List<GenericStack> read(NBTTagCompound tag, String field) {
                return tag.hasKey(field) ? resources.readStacks(tag.getTagList(field, Constants.NBT.TAG_COMPOUND)) : null;
            }
        };
    }
}
