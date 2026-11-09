package com.circulation.prototype_eng.structure.tool;

import java.util.TreeMap;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

/** Original builder binding, task states and per-material choice persistence. */
public final class BuilderState {
    private BuilderState() {
    }

    public enum State {
        IDLE, BUILDING, PAUSED_BUILDING, DISASSEMBLING, PAUSED_DISASSEMBLING;

        public boolean paused() {
            return this == PAUSED_BUILDING || this == PAUSED_DISASSEMBLING;
        }
    }

    public static NBTTagCompound data(ItemStack stack) {
        var tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        return tag;
    }

    public static void bind(ItemStack stack, int dimension, BlockPos pos, MachineType machine) {
        var tag = data(stack);
        tag.setBoolean("Bound", true);
        tag.setInteger("BoundDim", dimension);
        tag.setInteger("BoundX", pos.getX());
        tag.setInteger("BoundY", pos.getY());
        tag.setInteger("BoundZ", pos.getZ());
        tag.setString("BoundMachineId", machine.id().toString());
        tag.setString("BoundStructureId", machine.structureId().toString());
        state(tag, State.IDLE);
        progress(tag, 0, 0);
    }

    public static BlockPos position(NBTTagCompound tag) {
        return new BlockPos(tag.getInteger("BoundX"), tag.getInteger("BoundY"), tag.getInteger("BoundZ"));
    }

    private static NBTTagCompound task(NBTTagCompound tag) {
        if (!tag.hasKey("Task", 10)) tag.setTag("Task", new NBTTagCompound());
        return tag.getCompoundTag("Task");
    }

    public static State state(NBTTagCompound tag) {
        int state = tag.getCompoundTag("Task").getInteger("State");
        return state >= 0 && state < State.values().length ? State.values()[state] : State.IDLE;
    }

    public static void state(NBTTagCompound tag, State state) {
        task(tag).setInteger("State", state.ordinal());
    }

    public static void progress(NBTTagCompound tag, int done, int total) {
        var task = task(tag);
        task.setInteger("Done", done);
        task.setInteger("Total", total);
    }

    public static int done(NBTTagCompound tag) {
        return tag.getCompoundTag("Task").getInteger("Done");
    }

    public static int total(NBTTagCompound tag) {
        return tag.getCompoundTag("Task").getInteger("Total");
    }

    public static String optionKey(StructureDisplay.BlockOption option) {
        var key = new StringBuilder(option.blockId() + "@" + option.meta());
        new TreeMap<>(option.nbt()).forEach((name, value) -> key.append('/').append(name.length()).append(':').append(name).append(value.length()).append(':').append(value));
        return key.toString();
    }

    public static StructureDisplay.BlockOption choice(NBTTagCompound tag, StructureDisplay display) {
        var key = tag.getCompoundTag("MaterialSelections").getString(display.key());
        for (var option : display.options()) if (optionKey(option).equals(key)) return option;
        return display.options().getFirst();
    }

    public static void choose(NBTTagCompound tag, String material, String option) {
        if (!tag.hasKey("MaterialSelections", 10)) tag.setTag("MaterialSelections", new NBTTagCompound());
        tag.getCompoundTag("MaterialSelections").setString(material, option);
    }
}
