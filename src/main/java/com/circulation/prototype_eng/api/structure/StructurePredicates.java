package com.circulation.prototype_eng.api.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Built-in PM predicate behavior composed over declared, detached queries.
 */
@SuppressWarnings({"unused", "ReturnOfInnerClass"})
// Public factories; private retained cursors own their detached predicate data.
public final class StructurePredicates {
    private StructurePredicates() {
    }

    /**
     * Query descriptors must be reused by identity when the same fact is referenced repeatedly.
     */
    public static <T> StructurePredicate test(StructureQuery<T> query, Predicate<? super T> condition) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(condition);
        return new StructurePredicate() {
            @Override
            public boolean planReusable() {
                return true;
            }

            @Override
            public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
                plan.read(position, query);
            }

            @Override
            public boolean matches(StructureSnapshot snapshot, BlockPos position) {
                return snapshot.isAreaLoaded(StructureBounds.at(position)) && condition.test(snapshot.read(query, position));
            }
        };
    }

    /**
     * Convenience for a rotation-invariant custom query and its display in one declaration.
     */
    public static <T> StructurePredicate test(StructureQuery<T> query, Predicate<? super T> condition, StructureDisplay display) {
        return withDisplay(test(query, condition), display);
    }

    /**
     * The supplied display is rotation-invariant; directional custom predicates should override display/transform together.
     */
    public static StructurePredicate withDisplay(StructurePredicate predicate, StructureDisplay display) {
        return new Displayed(Objects.requireNonNull(predicate), Objects.requireNonNull(display));
    }

    public static StructurePredicate all(Collection<? extends StructurePredicate> predicates) {
        return composite(predicates, true);
    }

    public static StructurePredicate any(Collection<? extends StructurePredicate> predicates) {
        return composite(predicates, false);
    }

    private static StructurePredicate composite(Collection<? extends StructurePredicate> predicates, boolean all) {
        var copied = new ObjectArrayList<StructurePredicate>(predicates.size());
        boolean reusable = true;
        for (var predicate : predicates) {
            copied.add(Objects.requireNonNull(predicate));
            reusable = reusable && predicate.planReusable();
        }
        return new Composite(Collections.unmodifiableList(copied), all, reusable);
    }

    /**
     * Local validator offsets rotate together with both capture declarations and matching.
     */
    public static StructurePredicate at(BlockPos offset, StructurePredicate predicate) {
        return new Offset(offset, predicate);
    }

    /**
     * Custom detached validator. The immutable read declarations are potential reads; a callback
     * may short-circuit optional positions. Queries and callbacks obey the same purity and
     * concurrent-use contract as {@link #test}. No callback runs while freezing or previewing.
     */
    public static StructurePredicate custom(Collection<StructureQuery<?>> queries, Collection<BlockPos> offsets,
                                            Collection<BlockPos> controllerOffsets, Predicate<StructureFacts> validator) {
        return new Custom(List.copyOf(queries), positions(offsets), positions(controllerOffsets),
            StructureTransform.of(BlockOrientation.NORTH_UP), Objects.requireNonNull(validator));
    }

    private static List<BlockPos> positions(Collection<BlockPos> source) {
        var positions = new ObjectArrayList<BlockPos>(source.size());
        for (var position : source) positions.add(Objects.requireNonNull(position).toImmutable());
        return List.copyOf(positions);
    }

    public static StructurePredicate notAir() {
        return test(StructureQueries.NON_AIR, Boolean.TRUE::equals);
    }

    public static StructurePredicate hasTileEntity() {
        return test(StructureQueries.HAS_TILE_ENTITY, Boolean.TRUE::equals);
    }

    /**
     * Block regex expansion belongs to definition loading; matching uses this frozen membership.
     */
    public static StructurePredicate blocks(Collection<Block> blocks) {
        var accepted = new ReferenceLinkedOpenHashSet<Block>(blocks.size());
        for (var block : blocks) {
            accepted.add(Objects.requireNonNull(block));
        }
        return new Blocks(ReferenceSets.unmodifiable(accepted));
    }

    public static StructurePredicate state(IBlockState state) {
        return new State(Objects.requireNonNull(state));
    }

    public static StructurePredicate stateWithNbt(IBlockState state, Map<String, String> expected) {
        return expected.isEmpty() ? state(state) : all(List.of(state(state), nbt(expected)));
    }

    /**
     * PM's shallow root fields: strings use their value, other tags use their SNBT representation.
     * The supplied map must remain immutable while the predicate is in use.
     */
    public static StructurePredicate nbt(Map<String, String> expected) {
        return new Nbt(expected);
    }

    @SuppressWarnings("unchecked") // Minecraft's property valueClass validates each typed access.
    private static IBlockState rotateState(IBlockState state, StructureTransform rotation) {
        var result = state;
        for (var property : state.getPropertyKeys()) {
            if (property.getValueClass() == EnumFacing.class) {
                var facing = (IProperty<EnumFacing>) property;
                var transformed = rotation.apply(state.getValue(facing));
                if (facing.getAllowedValues().contains(transformed)) {
                    result = result.withProperty(facing, transformed);
                }
            } else if (property.getValueClass() == EnumFacing.Axis.class) {
                var axis = (IProperty<EnumFacing.Axis>) property;
                var representative = switch (state.getValue(axis)) {
                    case X -> EnumFacing.EAST;
                    case Y -> EnumFacing.UP;
                    case Z -> EnumFacing.NORTH;
                };
                var transformed = rotation.apply(representative).getAxis();
                if (axis.getAllowedValues().contains(transformed)) {
                    result = result.withProperty(axis, transformed);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked") // Minecraft valueClass binds the same property to its Enum type.
    private static IBlockState rotateProperty(IBlockState state, IProperty<?> property, StructureTransform rotation) {
        if (property.getValueClass() == EnumFacing.class) {
            var facing = (IProperty<EnumFacing>) property;
            var transformed = rotation.apply(state.getValue(facing));
            return facing.getAllowedValues().contains(transformed) ? state.withProperty(facing, transformed) : state;
        }
        if (property.getValueClass() == EnumFacing.Axis.class) {
            var axis = (IProperty<EnumFacing.Axis>) property;
            var representative = switch (state.getValue(axis)) {
                case X -> EnumFacing.EAST;
                case Y -> EnumFacing.UP;
                case Z -> EnumFacing.NORTH;
            };
            var transformed = rotation.apply(representative).getAxis();
            return axis.getAllowedValues().contains(transformed) ? state.withProperty(axis, transformed) : state;
        }
        return state;
    }

    static StructurePredicateDisplays.Fact displayFact(StructurePredicate predicate) {
        return switch (predicate) {
            case State state -> new StructurePredicateDisplays.Material(state.expected, null, null);
            case Blocks blocks -> new StructurePredicateDisplays.Material(null, blocks.accepted, null);
            case Nbt nbt -> new StructurePredicateDisplays.Nbt(nbt.expected);
            case Composite composite -> new StructurePredicateDisplays.Branch(composite.children, composite.all);
            case Offset offset when offset.offset.equals(BlockPos.ORIGIN) ->
                new StructurePredicateDisplays.Branch(List.of(offset.predicate), true);
            case Offset ignored -> StructurePredicateDisplays.Unknown.INSTANCE;
            default -> {
                var display = predicate.display();
                yield display == null ? StructurePredicateDisplays.Unknown.INSTANCE : new StructurePredicateDisplays.Material(null, null, display);
            }
        };
    }

    private record Custom(List<StructureQuery<?>> queries, List<BlockPos> offsets, List<BlockPos> controllerOffsets,
                          StructureTransform rotation,
                          Predicate<StructureFacts> validator) implements StructurePredicate.ControllerAware {
        @Override
        public boolean planReusable() {
            return true;
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            beginCollectReads(plan, position, controller).run();
        }

        @Override
        public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            Objects.requireNonNull(plan);
            return declarations(plan, Objects.requireNonNull(position).toImmutable(), Objects.requireNonNull(controller).toImmutable());
        }

        private StructureOperation<Void> declarations(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            return new StructureOperation<>() {
                private int offsetIndex, queryIndex;
                private boolean controllers, baseDeclared, areaDeclared;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (!baseDeclared) {
                        if (!work.take()) return false;
                        plan.area(StructureBounds.at(position));
                        baseDeclared = true;
                    }
                    while (true) {
                        var locations = controllers ? controllerOffsets : offsets;
                        if (offsetIndex == locations.size()) {
                            if (controllers) break;
                            controllers = true;
                            offsetIndex = 0;
                            continue;
                        }
                        var origin = controllers ? controller : position;
                        var actual = StructurePattern.add(origin, rotation.apply(locations.get(offsetIndex)));
                        if (!areaDeclared) {
                            if (!work.take()) return false;
                            plan.area(StructureBounds.at(actual));
                            areaDeclared = true;
                        }
                        while (queryIndex < queries.size()) {
                            if (!work.take()) return false;
                            plan.read(actual, queries.get(queryIndex++));
                        }
                        queryIndex = 0;
                        areaDeclared = false;
                        offsetIndex++;
                    }
                    finish(null);
                    return true;
                }
            };
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            var facts = new StructureFacts(snapshot, position, controller, rotation);
            return facts.available() && validator.test(facts);
        }

        @Override
        public StructurePredicate transform(StructureTransform transform) {
            var composed = StructureTransform.of(BlockOrientation.get(transform.apply(rotation.apply(EnumFacing.NORTH)),
                transform.apply(rotation.apply(EnumFacing.UP))));
            return new Custom(queries, offsets, controllerOffsets, composed, validator);
        }
    }

    private record Blocks(ReferenceSet<Block> accepted,
                          StructurePredicateDisplays.Cache presentation) implements StructurePredicate {
        private Blocks(ReferenceSet<Block> accepted) {
            this(accepted, new StructurePredicateDisplays.Cache());
        }

        @Override
        public boolean planReusable() {
            return true;
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
            plan.read(position, StructureQueries.BLOCK_STATE);
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position) {
            if (!snapshot.isAreaLoaded(StructureBounds.at(position))) return false;
            var state = snapshot.read(StructureQueries.BLOCK_STATE, position);
            return state != null && accepted.contains(state.getBlock());
        }

        @Override
        public @Nullable StructureDisplay display() {
            return presentation.get(this);
        }
    }

    private record Displayed(StructurePredicate predicate,
                             StructureDisplay display) implements StructurePredicate.ControllerAware {
        @Override
        public boolean planReusable() {
            return predicate.planReusable();
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            predicate.collectReads(plan, position, controller);
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            return predicate.matches(snapshot, position, controller);
        }

        @Override
        public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            return predicate.beginCollectReads(plan, position, controller);
        }

        @Override
        public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            return predicate.beginMatch(snapshot, position, controller);
        }

        @Override
        public StructurePredicate transform(StructureTransform rotation) {
            return new Displayed(predicate.transform(rotation), display);
        }

        @Override
        public StructureOperation<StructurePredicate> beginTransform(StructureTransform rotation) {
            var child = predicate.beginTransform(rotation);
            return new StructureOperation<>() {
                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (!child.advance(work) || !work.take()) return false;
                    finish(new Displayed(Objects.requireNonNull(child.result()), display));
                    return true;
                }
            };
        }
    }

    private record Composite(List<StructurePredicate> children, boolean all,
                             boolean planReusable,
                             StructurePredicateDisplays.Cache presentation) implements StructurePredicate.ControllerAware {
        private Composite(List<StructurePredicate> children, boolean all, boolean planReusable) {
            this(children, all, planReusable, new StructurePredicateDisplays.Cache());
        }

        @Override
        public @Nullable StructureDisplay display() {
            return presentation.get(this);
        }

        @Override
        public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            return visit(child -> child.beginCollectReads(plan, position, controller));
        }

        private StructureOperation<Void> visit(Function<StructurePredicate, StructureOperation<Void>> factory) {
            return new StructureOperation<>() {
                private int index;
                @Nullable
                private StructureOperation<Void> current;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    while (index < children.size()) {
                        if (current == null) {
                            if (!work.take()) return false;
                            current = factory.apply(children.get(index));
                        }
                        if (!current.advance(work)) return false;
                        current = null;
                        index++;
                    }
                    finish(null);
                    return true;
                }
            };
        }

        @Override
        public StructureOperation<StructurePredicate> beginTransform(StructureTransform rotation) {
            return new StructureOperation<>() {
                private final ObjectArrayList<StructurePredicate> transformed = new ObjectArrayList<>();
                private int index;
                @Nullable
                private StructureOperation<StructurePredicate> current;
                private boolean reusable = planReusable;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    while (index < children.size()) {
                        if (current == null) {
                            if (!work.take()) return false;
                            current = children.get(index).beginTransform(rotation);
                        }
                        if (!current.advance(work)) return false;
                        transformed.add(Objects.requireNonNull(current.result()));
                        reusable = reusable && Objects.requireNonNull(current.result()).planReusable();
                        current = null;
                        index++;
                    }
                    if (!work.take()) return false;
                    finish(new Composite(Collections.unmodifiableList(transformed), all, reusable));
                    return true;
                }
            };
        }

        @Override
        public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            return new StructureOperation<>() {
                private int index;
                private boolean unavailable;
                @Nullable
                private StructureOperation<StructurePredicateResult> current;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    while (index < children.size()) {
                        if (current == null) {
                            if (!work.take()) return false;
                            current = children.get(index).beginMatch(snapshot, position, controller);
                        }
                        if (!current.advance(work)) return false;
                        var result = Objects.requireNonNull(current.result());
                        current = null;
                        index++;
                        if (all && result != StructurePredicateResult.MATCHED || !all && result == StructurePredicateResult.MATCHED) {
                            finish(result);
                            return true;
                        }
                        unavailable |= result == StructurePredicateResult.UNAVAILABLE;
                    }
                    finish(unavailable ? StructurePredicateResult.UNAVAILABLE : all ? StructurePredicateResult.MATCHED : StructurePredicateResult.MISMATCH);
                    return true;
                }
            };
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            for (int index1 = 0; index1 < children.size(); index1++) {
                var child = children.get(index1);
                child.collectReads(plan, position, controller);
            }
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            for (int index2 = 0; index2 < children.size(); index2++) {
                var child = children.get(index2);
                if (child.matches(snapshot, position, controller) != all) {
                    return !all;
                }
            }
            return all;
        }

        @Override
        public StructurePredicate transform(StructureTransform rotation) {
            var transformed = new ObjectArrayList<StructurePredicate>(children.size());
            boolean reusable = planReusable;
            for (int index3 = 0; index3 < children.size(); index3++) {
                var child = children.get(index3);
                var result = Objects.requireNonNull(child.transform(rotation));
                transformed.add(result);
                reusable = reusable && result.planReusable();
            }
            return new Composite(Collections.unmodifiableList(transformed), all, reusable);
        }
    }

    private record Offset(BlockPos offset, StructurePredicate predicate,
                          boolean planReusable) implements StructurePredicate.ControllerAware {
        private Offset(BlockPos offset, StructurePredicate predicate) {
            this(offset, predicate, Objects.requireNonNull(predicate).planReusable());
        }

        private Offset {
            offset = Objects.requireNonNull(offset).toImmutable();
            Objects.requireNonNull(predicate);
        }

        @Override
        public @Nullable StructureDisplay display() {
            return offset.equals(BlockPos.ORIGIN) ? predicate.display() : null;
        }

        @Override
        public StructureOperation<Void> beginCollectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            return new StructureOperation<>() {
                @Nullable
                private StructureOperation<Void> child;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (child == null) {
                        if (!work.take()) return false;
                        child = predicate.beginCollectReads(plan, StructurePattern.add(position, offset), controller);
                    }
                    if (!child.advance(work)) return false;
                    finish(null);
                    return true;
                }
            };
        }

        @Override
        public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            return new StructureOperation<>() {
                @Nullable
                private StructureOperation<StructurePredicateResult> child;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (child == null) {
                        if (!work.take()) return false;
                        child = predicate.beginMatch(snapshot, StructurePattern.add(position, offset), controller);
                    }
                    if (!child.advance(work)) return false;
                    finish(child.result());
                    return true;
                }
            };
        }

        @Override
        public StructureOperation<StructurePredicate> beginTransform(StructureTransform rotation) {
            return new StructureOperation<>() {
                @Nullable
                private StructureOperation<StructurePredicate> child;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (child == null) {
                        if (!work.take()) return false;
                        child = predicate.beginTransform(rotation);
                    }
                    if (!child.advance(work) || !work.take()) return false;
                    var result = Objects.requireNonNull(child.result());
                    finish(new Offset(rotation.apply(offset), result, planReusable && result.planReusable()));
                    return true;
                }
            };
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position, BlockPos controller) {
            predicate.collectReads(plan, StructurePattern.add(position, offset), controller);
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position, BlockPos controller) {
            return predicate.matches(snapshot, StructurePattern.add(position, offset), controller);
        }

        @Override
        public StructurePredicate transform(StructureTransform rotation) {
            var result = Objects.requireNonNull(predicate.transform(rotation));
            return new Offset(rotation.apply(offset), result, planReusable && result.planReusable());
        }
    }

    private record State(IBlockState expected,
                         StructurePredicateDisplays.Cache presentation) implements StructurePredicate {
        private State(IBlockState expected) {
            this(expected, new StructurePredicateDisplays.Cache());
        }

        @Override
        public @Nullable StructureDisplay display() {
            return presentation.get(this);
        }

        @Override
        public boolean planReusable() {
            return true;
        }

        @Override
        public StructureOperation<StructurePredicate> beginTransform(StructureTransform rotation) {
            var properties = expected.getPropertyKeys().iterator();
            return new StructureOperation<>() {
                private IBlockState transformed = expected;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    while (properties.hasNext()) {
                        if (!work.take()) return false;
                        transformed = rotateProperty(transformed, properties.next(), rotation);
                    }
                    if (!work.take()) return false;
                    finish(new State(transformed));
                    return true;
                }
            };
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
            plan.read(position, StructureQueries.BLOCK_STATE);
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position) {
            return snapshot.isAreaLoaded(StructureBounds.at(position)) && snapshot.read(StructureQueries.BLOCK_STATE, position) == expected;
        }

        @Override
        public StructurePredicate transform(StructureTransform rotation) {
            return new State(rotateState(expected, rotation));
        }
    }

    private record Nbt(Map<String, String> expected) implements StructurePredicate {
        @Override
        public boolean planReusable() {
            return true;
        }

        @Override
        public void collectReads(StructureReadPlan.Builder plan, BlockPos position) {
            plan.read(position, StructureQueries.TILE_NBT);
        }

        @Override
        public boolean matches(StructureSnapshot snapshot, BlockPos position) {
            return beginMatch(snapshot, position).run() == StructurePredicateResult.MATCHED;
        }

        @Override
        public StructureOperation<StructurePredicateResult> beginMatch(StructureSnapshot snapshot, BlockPos position) {
            return new StructureOperation<>() {
                private final Iterator<Map.Entry<String, String>> entries = expected.entrySet().iterator();
                @Nullable
                private NBTTagCompound tag;
                private boolean captured;

                @Override
                public boolean advance(RecipeWorkBudget work) {
                    if (complete()) return true;
                    if (!captured) {
                        if (!work.take()) return false;
                        if (!snapshot.isAreaLoaded(StructureBounds.at(position))) {
                            finish(StructurePredicateResult.UNAVAILABLE);
                            return true;
                        }
                        tag = snapshot.read(StructureQueries.TILE_NBT, position);
                        captured = true;
                        if (tag == null) {
                            finish(StructurePredicateResult.MISMATCH);
                            return true;
                        }
                    }
                    while (entries.hasNext()) {
                        if (!work.take()) return false;
                        var entry = entries.next();
                        var value = Objects.requireNonNull(tag).getTag(entry.getKey());
                        if (value == null || !entry.getValue().equals(value instanceof NBTTagString string ? string.getString() : value.toString())) {
                            finish(StructurePredicateResult.MISMATCH);
                            return true;
                        }
                    }
                    finish(StructurePredicateResult.MATCHED);
                    return true;
                }
            };
        }
    }
}
