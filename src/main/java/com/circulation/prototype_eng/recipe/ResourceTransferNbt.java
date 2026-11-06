package com.circulation.prototype_eng.recipe;

import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/** The owning machine stores this tag alongside its structure identity and enabled state. */
public final class ResourceTransferNbt {
    private ResourceTransferNbt() {}

    public static NBTTagCompound write(ResourceTransferRun.Snapshot state) {
        var tag = new NBTTagCompound();
        tag.setInteger("Format", 1);
        tag.setString("Recipe", state.recipe().id().toString());
        tag.setInteger("Duration", state.recipe().durationTicks());
        tag.setLong("FEPerTick", state.recipe().energyPerTick());
        tag.setInteger("Progress", state.progress());
        tag.setLong("EnergyCredit", state.energyCredit());
        tag.setTag("Inputs", writeResources(state.recipe().inputs(), state.acquired()));
        tag.setTag("Outputs", writeResources(state.recipe().outputs(), state.remainingOutputs()));
        return tag;
    }

    private static NBTTagList writeResources(List<GenericStack> definitions, long[] amounts) {
        var list = new NBTTagList();
        for (int i = 0; i < definitions.size(); i++) {
            var tag = GenericStack.writeTag(definitions.get(i));
            tag.setLong("RunAmount", amounts[i]);
            list.appendTag(tag);
        }
        return list;
    }

    public static ResourceTransferRun.Snapshot read(NBTTagCompound tag) {
        if (tag.getInteger("Format") != 1 || !tag.hasKey("Inputs", 9) || !tag.hasKey("Outputs", 9)
                || !tag.hasKey("Recipe", 8) || !tag.hasKey("Duration", 3) || !tag.hasKey("FEPerTick", 4)
                || !tag.hasKey("Progress", 3) || !tag.hasKey("EnergyCredit", 4)) {
            throw new IllegalArgumentException("Unsupported or incomplete saved recipe work state");
        }
        for (String name : new String[]{"Inputs", "Outputs"}) {
            var list = (NBTTagList) tag.getTag(name);
            if (list.tagCount() != 0 && list.getTagType() != 10) {
                throw new IllegalArgumentException("Invalid saved recipe resource list: " + name);
            }
        }
        var inputs = tag.getTagList("Inputs", 10);
        var outputs = tag.getTagList("Outputs", 10);
        var inputDefinitions = new ObjectArrayList<GenericStack>(inputs.tagCount());
        var outputDefinitions = new ObjectArrayList<GenericStack>(outputs.tagCount());
        long[] acquired = readResources(inputs, inputDefinitions);
        long[] remaining = readResources(outputs, outputDefinitions);
        var recipe = new ResourceTransferPlan(new ResourceLocation(tag.getString("Recipe")), inputDefinitions, outputDefinitions,
                tag.getInteger("Duration"), tag.getLong("FEPerTick"));
        return new ResourceTransferRun.Snapshot(recipe, acquired, remaining, tag.getInteger("Progress"), tag.getLong("EnergyCredit"));
    }

    private static long[] readResources(NBTTagList list, List<GenericStack> definitions) {
        var amounts = new long[list.tagCount()];
        for (int i = 0; i < list.tagCount(); i++) {
            var tag = list.getCompoundTagAt(i);
            GenericStack stack = GenericStack.readTag(tag);
            if (stack == null || stack.amount() <= 0 || !tag.hasKey("RunAmount", 4)) {
                throw new IllegalArgumentException("Missing or invalid resource in saved recipe work");
            }
            definitions.add(stack);
            amounts[i] = tag.getLong("RunAmount");
        }
        return amounts;
    }
}
