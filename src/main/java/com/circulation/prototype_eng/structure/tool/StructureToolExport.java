package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exports the selected world region using the library's existing structure JSON schema.
 */
public final class StructureToolExport {
    public static final int MAX_VOLUME = 64 * 64 * 64;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private StructureToolExport() {
    }

    public static Cell cell(World world, BlockPos pos, BlockPos origin, StructureTransform rotation, boolean includeNbt) {
        var state = rotateState(world.getBlockState(pos), rotation);
        var nbt = new Object2ObjectLinkedOpenHashMap<String, String>();
        var tile = includeNbt ? world.getTileEntity(pos) : null;
        if (tile != null) {
            var tag = tile.writeToNBT(new NBTTagCompound());
            for (var key : tag.getKeySet()) {
                // Coordinates are placement identity, not reusable matching constraints.
                if (key.equals("x") || key.equals("y") || key.equals("z")) continue;
                nbt.put(key, tag.getTag(key) instanceof NBTTagString ? tag.getString(key) : tag.getTag(key).toString());
            }
        }
        var relative = new BlockPos(Math.subtractExact(pos.getX(), origin.getX()), Math.subtractExact(pos.getY(), origin.getY()), Math.subtractExact(pos.getZ(), origin.getZ()));
        return new Cell(rotation.apply(relative), Objects.requireNonNull(state.getBlock().getRegistryName()),
            state.getBlock().getMetaFromState(state), nbt);
    }

    public static IBlockState rotateState(IBlockState state, StructureTransform rotation) {
        var result = state;
        for (var property : state.getPropertyKeys()) {
            if (property.getValueClass() == EnumFacing.class) result = rotateFacing(result, property, rotation);
            if (property.getValueClass() == EnumFacing.Axis.class) result = rotateAxis(result, property, rotation);
        }
        return result;
    }

    @SuppressWarnings("unchecked") // Property value classes are checked before these exact casts.
    private static IBlockState rotateFacing(IBlockState state, IProperty<?> property, StructureTransform rotation) {
        var direction = (IProperty<EnumFacing>) property;
        var value = rotation.apply(state.getValue(direction));
        return direction.getAllowedValues().contains(value) ? state.withProperty(direction, value) : state;
    }

    @SuppressWarnings("unchecked")
    private static IBlockState rotateAxis(IBlockState state, IProperty<?> property, StructureTransform rotation) {
        var axis = (IProperty<EnumFacing.Axis>) property;
        var facing = switch (state.getValue(axis)) {
            case X -> EnumFacing.EAST;
            case Y -> EnumFacing.UP;
            case Z -> EnumFacing.SOUTH;
        };
        var value = rotation.apply(facing).getAxis();
        return axis.getAllowedValues().contains(value) ? state.withProperty(axis, value) : state;
    }

    public static String json(String name, String title, List<Cell> cells) {
        var root = new JsonObject();
        root.addProperty("id", new ResourceLocation(Tags.MOD_ID, ScannerState.sanitize(name)).toString());
        if (!title.isBlank()) root.addProperty("translationKey", title);
        root.addProperty("type", "template");
        var pattern = new JsonArray();
        for (int index = 0; index < cells.size(); index++) {
            var cell = cells.get(index);
            var element = new JsonObject();
            var pos = new JsonObject();
            pos.addProperty("x", cell.position().getX());
            pos.addProperty("y", cell.position().getY());
            pos.addProperty("z", cell.position().getZ());
            element.add("pos", pos);
            element.addProperty("blockId", cell.block().toString());
            element.addProperty("meta", cell.meta());
            if (!cell.nbt().isEmpty()) {
                var nbt = new JsonObject();
                cell.nbt().forEach(nbt::addProperty);
                element.add("nbt", nbt);
            }
            pattern.add(element);
        }
        root.add("pattern", pattern);
        return JSON.toJson(root);
    }

    public static Path save(Path directory, String id, String content) throws IOException {
        Files.createDirectories(directory);
        String base = ScannerState.sanitize(id);
        var json = JSON.fromJson(content, JsonObject.class);
        for (int suffix = 1; ; suffix++) {
            Path file = directory.resolve(base + (suffix == 1 ? "" : "_" + suffix) + ".json");
            try {
                json.addProperty("id", new ResourceLocation(Tags.MOD_ID, base + (suffix == 1 ? "" : "_" + suffix)).toString());
                Files.writeString(file, JSON.toJson(json), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return file;
            } catch (FileAlreadyExistsException occupied) {
                // A concurrent export cannot replace an existing structure file.
            }
        }
    }

    /**
     * Captured NBT values are borrowed read-only.
     */
    public record Cell(BlockPos position, ResourceLocation block, int meta, Map<String, String> nbt) {
    }

    /**
     * Incremental selection traversal: large exports do not monopolize a server tick.
     */
    public static final class Capture {
        private final World world;
        private final BlockPos origin;
        private final StructureTransform rotation;
        private final boolean includeNbt, includeOrigin;
        private final Iterator<BlockPos.MutableBlockPos> cursor;
        private final ObjectArrayList<Cell> cells = new ObjectArrayList<>();

        public Capture(World world, ScannerState.Box box, BlockPos origin, StructureTransform rotation, boolean includeNbt, boolean includeOrigin) {
            this.world = world;
            this.origin = origin;
            this.rotation = rotation;
            this.includeNbt = includeNbt;
            this.includeOrigin = includeOrigin;
            cursor = BlockPos.getAllInBoxMutable(box.min(), box.max()).iterator();
        }

        public boolean advance(int budget) {
            for (int i = 0; i < budget && cursor.hasNext(); i++) {
                var pos = cursor.next();
                if (!world.isBlockLoaded(pos))
                    throw new IllegalArgumentException("Selection contains an unloaded block");
                if ((!includeOrigin && pos.equals(origin)) || world.isAirBlock(pos)) continue;
                cells.add(cell(world, pos, origin, rotation, includeNbt));
            }
            return !cursor.hasNext();
        }

        /**
         * Call after capture completes; the returned sorted cells are borrowed read-only.
         */
        public List<Cell> cells() {
            cells.sort(Comparator.comparingInt((Cell cell) -> cell.position().getY()).thenComparingInt(cell -> cell.position().getZ())
                                 .thenComparingInt(cell -> cell.position().getX()));
            return cells;
        }
    }
}
