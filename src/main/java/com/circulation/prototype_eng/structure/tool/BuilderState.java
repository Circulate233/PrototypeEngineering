package com.circulation.prototype_eng.structure.tool;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectRBTreeMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/** Original builder binding, task states and per-material choice persistence. */
public final class BuilderState {
    public enum Setting {
        REQUIRE_MATERIALS("BuilderRequireMaterials", true), REQUIRE_SPACE("BuilderRequireSpace", true),
        REPLACE_FLUIDS("BuilderReplaceFluids", false), WAIT_MATERIALS("BuilderWaitMaterials", false);
        public static final Setting[] VALUES = values();
        public final String key;
        private final boolean fallback;

        Setting(String key, boolean fallback) { this.key = key; this.fallback = fallback; }

        public boolean available(NBTTagCompound tag) {
            return switch (this) {
                case WAIT_MATERIALS -> !REQUIRE_MATERIALS.enabled(tag);
                case REPLACE_FLUIDS -> REQUIRE_SPACE.enabled(tag);
                default -> true;
            };
        }

        public boolean enabled(NBTTagCompound tag) {
            return available(tag) && (tag.hasKey(key) ? tag.getBoolean(key) : fallback);
        }

        public void toggle(NBTTagCompound tag) {
            if (!available(tag)) return;
            tag.setBoolean(key, !enabled(tag));
            if (this == REQUIRE_MATERIALS && enabled(tag)) tag.setBoolean(WAIT_MATERIALS.key, false);
            if (this == REQUIRE_SPACE && !enabled(tag)) tag.setBoolean(REPLACE_FLUIDS.key, false);
        }
    }

    public static int meMode(NBTTagCompound tag) { return Math.clamp(tag.getInteger("BuilderMeMode"), 0, 2); }

    public static void cycleMeMode(NBTTagCompound tag) { tag.setInteger("BuilderMeMode", (meMode(tag) + 1) % 3); }
    public static void toggleMeUse(NBTTagCompound tag) {
        tag.setInteger("BuilderMeMode", meMode(tag) > 0 ? 0 : 1);
    }

    public static void toggleMeCraft(NBTTagCompound tag) {
        int mode = meMode(tag);
        if (mode > 0) tag.setInteger("BuilderMeMode", mode == 2 ? 1 : 2);
    }

    private BuilderState() {
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
        bind(stack, dimension, pos, machine.id().toString(), machine.structureId().toString());
    }

    public static void bind(ItemStack stack, int dimension, BlockPos pos, String binding, String structure) {
        var tag = data(stack);
        ensureIdentity(tag);
        if (!tag.getBoolean("Bound") || tag.getInteger("BoundDim") != dimension || !position(tag).equals(pos)
            || !tag.getString("BoundMachineId").equals(binding) || !tag.getString("BoundStructureId").equals(structure)) {
            tag.removeTag("CellMaterialSelections");
            tag.removeTag("SliceSelections");
        }
        tag.setBoolean("Bound", true);
        tag.setInteger("BoundDim", dimension);
        tag.setInteger("BoundX", pos.getX());
        tag.setInteger("BoundY", pos.getY());
        tag.setInteger("BoundZ", pos.getZ());
        tag.setString("BoundMachineId", binding);
        tag.setString("BoundStructureId", structure);
        state(tag, State.IDLE);
        progress(tag, 0, 0);
    }

    public static void ensureIdentity(NBTTagCompound tag) {
        var identity = tag.getString("BuilderIdentity");
        if (!identity.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            identity = UUID.randomUUID().toString();
            tag.setString("BuilderIdentity", identity);
        }
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
        return state >= 0 && state < State.VALUES.length ? State.VALUES[state] : State.IDLE;
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
        new Object2ObjectRBTreeMap<>(option.properties()).forEach((name, value) -> key.append('/').append('p').append(name.length()).append(':').append(name).append(value.length()).append(':').append(value));
        new Object2ObjectRBTreeMap<>(option.nbt()).forEach((name, value) -> key.append('/').append('n').append(name.length()).append(':').append(name).append(value.length()).append(':').append(value));
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
        var cells = tag.getCompoundTag("CellMaterialSelections");
        for (var key : new ObjectArrayList<>(cells.getKeySet()))
            if (cells.getCompoundTag(key).getString("Material").equals(material)) cells.removeTag(key);
    }

    public static String cellKey(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    public static @Nullable BlockPos cellPosition(String key) {
        if (key.length() > 35 || !key.matches("-?(0|[1-9][0-9]{0,9}),-?(0|[1-9][0-9]{0,9}),-?(0|[1-9][0-9]{0,9})")) return null;
        var parts = key.split(",");
        long x = Long.parseLong(parts[0]), y = Long.parseLong(parts[1]), z = Long.parseLong(parts[2]);
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return null;
        var result = new BlockPos((int) x, (int) y, (int) z);
        return cellKey(result).equals(key) ? result : null;
    }

    /** Length-prefixed material identity cannot collide with newlines or option-map contents. */
    public static String cellValue(StructureDisplay display, int option, BlockOrientation orientation) {
        return orientation.name() + ":" + display.key().length() + ":" + display.key() + optionKey(display.options().get(option));
    }

    public static void chooseCell(NBTTagCompound tag, BlockPos position, String material, String option, BlockOrientation orientation) {
        if (!tag.hasKey("CellMaterialSelections", 10)) tag.setTag("CellMaterialSelections", new NBTTagCompound());
        var choice = new NBTTagCompound();
        choice.setString("Material", material);
        choice.setString("Option", option);
        choice.setString("Orientation", orientation.name());
        tag.getCompoundTag("CellMaterialSelections").setTag(cellKey(position), choice);
    }

    private static int cellOption(NBTTagCompound tag, StructureDisplay display, BlockPos position, BlockOrientation orientation) {
        var saved = tag.getCompoundTag("CellMaterialSelections").getCompoundTag(cellKey(position));
        if (!saved.getString("Material").equals(display.key()) || !saved.getString("Orientation").equals(orientation.name())) return -1;
        var option = saved.getString("Option");
        for (int index = 0; index < display.options().size(); index++)
            if (optionKey(display.options().get(index)).equals(option)) return index;
        return -1;
    }

    public static StructureDisplay.BlockOption choice(NBTTagCompound tag, StructureDisplay display, BlockPos position, BlockOrientation orientation) {
        int local = cellOption(tag, display, position, orientation);
        return local < 0 ? choice(tag, display) : display.options().get(local);
    }

    public static Map<BlockPos, StructurePreviewSettings.CellChoice> cellChoices(NBTTagCompound tag, Map<BlockPos, StructureDisplay> displays,
                                                                            BlockOrientation orientation) {
        var choices = new Object2ObjectOpenHashMap<BlockPos, StructurePreviewSettings.CellChoice>();
        displays.forEach((position, display) -> {
            if (display == null) return;
            int option = cellOption(tag, display, position, orientation);
            if (option >= 0) choices.put(position.toImmutable(), new StructurePreviewSettings.CellChoice(display, option));
        });
        return choices;
    }

    /** Construction keys identify occurrences, so two uses of one shared child remain independent. */
    public static @Nullable StructurePreviewPath slicePath(String key) {
        if (key.length() > 8192 || !key.matches("0(?:/(?:0|[1-9][0-9]{0,4}))*")) return null;
        var path = StructurePreviewPath.ROOT;
        var edges = key.split("/");
        for (int index = 1; index < edges.length; index++) path = path.child(Integer.parseInt(edges[index]));
        return path;
    }

    public static String sliceKey(StructurePreviewPath path) {
        var edges = new IntArrayList();
        for (var cursor = path; cursor.parent() != null; cursor = cursor.parent()) edges.add(cursor.childIndex());
        var key = new StringBuilder("0");
        for (int index = edges.size() - 1; index >= 0; index--) key.append('/').append(edges.getInt(index));
        return key.toString();
    }

    public static Object2IntMap<String> sliceCounts(NBTTagCompound tag) {
        var result = new Object2IntOpenHashMap<String>();
        var saved = tag.getCompoundTag("SliceSelections");
        for (var key : saved.getKeySet())
            if (saved.hasKey(key, 3) && saved.getInteger(key) >= 0 && slicePath(key) != null) result.put(key, saved.getInteger(key));
        return result;
    }

    public static Object2IntMap<StructurePreviewPath> previewSlices(NBTTagCompound tag) {
        var result = new Object2IntOpenHashMap<StructurePreviewPath>();
        sliceCounts(tag).forEach((key, count) -> result.put(slicePath(key), count));
        return result;
    }

    /** The container validates this occurrence's min/max against the current server catalog first. */
    public static void chooseSlice(NBTTagCompound tag, String key, int count) {
        if (slicePath(key) == null || count < 0) throw new IllegalArgumentException("Invalid slice selection");
        if (!tag.hasKey("SliceSelections", 10)) tag.setTag("SliceSelections", new NBTTagCompound());
        tag.getCompoundTag("SliceSelections").setInteger(key, count);
    }

    public enum State {
        IDLE, BUILDING, PAUSED_BUILDING, DISASSEMBLING, PAUSED_DISASSEMBLING;

        /** Shared declaration order; callers must not modify this array. */
        public static final State[] VALUES = values();

        public boolean paused() {
            return this == PAUSED_BUILDING || this == PAUSED_DISASSEMBLING;
        }
    }
}
