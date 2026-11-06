package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Built-in PM predicate behavior composed over declared, detached queries. */
@SuppressWarnings("unused") // Public Java definition factories, also consumed by the future JSON loader.
public final class StructurePredicates {
    private StructurePredicates() {}

    /** Query descriptors must be reused by identity when the same fact is referenced repeatedly. */
    public static <T> StructurePredicate test(StructureQuery<T> query, Predicate<? super T> condition) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(condition);
        return new StructurePredicate() {
            @Override public void collectReads(StructureReadPlan.Builder plan, BlockPos position) { plan.read(position, query); }
            @Override public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                return snapshot.isAreaLoaded(StructureBounds.at(position)) && condition.test(snapshot.read(query, position));
            }
        };
    }

    public static StructurePredicate all(Collection<? extends StructurePredicate> predicates) { return new Composite(List.copyOf(predicates), true); }
    public static StructurePredicate any(Collection<? extends StructurePredicate> predicates) { return new Composite(List.copyOf(predicates), false); }

    /** Local validator offsets rotate together with both capture declarations and matching. */
    public static StructurePredicate at(BlockPos offset, StructurePredicate predicate) { return new Offset(offset, predicate); }

    public static StructurePredicate notAir() { return test(StructureQueries.NON_AIR, Boolean.TRUE::equals); }
    public static StructurePredicate hasTileEntity() { return test(StructureQueries.HAS_TILE_ENTITY, Boolean.TRUE::equals); }

    /** Block regex expansion belongs to definition loading; matching uses this frozen membership. */
    public static StructurePredicate blocks(Collection<Block> blocks) {
        var accepted = new ReferenceOpenHashSet<Block>();
        for (var block : blocks) { accepted.add(Objects.requireNonNull(block)); }
        return test(StructureQueries.BLOCK_STATE, state -> state != null && accepted.contains(state.getBlock()));
    }

    public static StructurePredicate state(IBlockState state) { return new State(Objects.requireNonNull(state)); }

    public static StructurePredicate stateWithNbt(IBlockState state, Map<String, String> expected) {
        return expected.isEmpty() ? state(state) : all(List.of(state(state), nbt(expected)));
    }

    /** PM's shallow root fields: strings use their value, other tags use their SNBT representation. */
    public static StructurePredicate nbt(Map<String, String> expected) {
        var constraints = Map.copyOf(expected);
        return test(StructureQueries.TILE_NBT, tag -> {
            if (tag == null) { return false; }
            for (var entry : constraints.entrySet()) {
                var value = tag.getTag(entry.getKey());
                if (value == null) { return false; }
                String actual = value instanceof NBTTagString string ? string.getString() : value.toString();
                if (!actual.equals(entry.getValue())) { return false; }
            }
            return true;
        });
    }

    private record Composite(List<StructurePredicate> children, boolean all) implements StructurePredicate {
        @Override public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
            for (var child : children) { child.collectReads(plan, position); }
        }
        @Override public boolean matches(StructureSnapshot snapshot, BlockPos position) {
            for (var child : children) {
                if (child.matches(snapshot, position) != all) { return !all; }
            }
            return all;
        }
        @Override public StructurePredicate transform(StructureTransform rotation) {
            return new Composite(children.stream().map(child -> child.transform(rotation)).toList(), all);
        }
    }

    private record Offset(BlockPos offset, StructurePredicate predicate) implements StructurePredicate {
        private Offset { offset = Objects.requireNonNull(offset).toImmutable(); Objects.requireNonNull(predicate); }
        @Override public void collectReads(StructureReadPlan.Builder plan, BlockPos position) { predicate.collectReads(plan, StructurePattern.add(position, offset)); }
        @Override public boolean matches(StructureSnapshot snapshot, BlockPos position) { return predicate.matches(snapshot, StructurePattern.add(position, offset)); }
        @Override public StructurePredicate transform(StructureTransform rotation) { return new Offset(rotation.apply(offset), predicate.transform(rotation)); }
    }

    private record State(IBlockState expected) implements StructurePredicate {
        @Override public void collectReads(StructureReadPlan.Builder plan, BlockPos position) { plan.read(position, StructureQueries.BLOCK_STATE); }
        @Override public boolean matches(StructureSnapshot snapshot, BlockPos position) {
            return snapshot.isAreaLoaded(StructureBounds.at(position)) && snapshot.read(StructureQueries.BLOCK_STATE, position) == expected;
        }
        @Override public StructurePredicate transform(StructureTransform rotation) { return new State(rotateState(expected, rotation)); }
    }

    @SuppressWarnings("unchecked") // Minecraft's property valueClass validates each typed access.
    private static IBlockState rotateState(IBlockState state, StructureTransform rotation) {
        var result = state;
        for (var property : state.getPropertyKeys()) {
            if (property.getValueClass() == EnumFacing.class) {
                var facing = (IProperty<EnumFacing>) property;
                var transformed = rotation.apply(state.getValue(facing));
                if (facing.getAllowedValues().contains(transformed)) { result = result.withProperty(facing, transformed); }
            } else if (property.getValueClass() == EnumFacing.Axis.class) {
                var axis = (IProperty<EnumFacing.Axis>) property;
                var representative = switch (state.getValue(axis)) {
                    case X -> EnumFacing.EAST;
                    case Y -> EnumFacing.UP;
                    case Z -> EnumFacing.NORTH;
                };
                var transformed = rotation.apply(representative).getAxis();
                if (axis.getAllowedValues().contains(transformed)) { result = result.withProperty(axis, transformed); }
            }
        }
        return result;
    }
}
