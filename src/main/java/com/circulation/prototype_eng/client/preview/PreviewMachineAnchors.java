package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bounded declaration-only preview selection; occurrence origins already carry the C root rotation. */
public final class PreviewMachineAnchors {
    private static final int MAX_ANCHORS = 1_048_576;
    private final StructurePreviewModel model;
    private final MachineType machine;
    private final MachineRenderBindings.View bindings;
    private final StructureTransform rotation;
    private final ObjectArrayList<StructureRenderAnchors.Anchor> anchors = new ObjectArrayList<>();
    private boolean machineVisited;
    private int cursor, slice, count;
    private StructurePreviewModel.Occurrence occurrence;
    private GeckoStructureBinding structureBinding;
    private BlockPos sliceStep;
    private StructurePreviewPath pathCursor;
    private Integer[] pathParts;
    private List<Integer> occurrencePath;
    private boolean perSlice;
    private List<StructureRenderAnchors.Anchor> completed;

    public PreviewMachineAnchors(StructurePreviewModel model, MachineType machine, MachineRenderBindings.View bindings) {
        this.model = Objects.requireNonNull(model);
        this.machine = Objects.requireNonNull(machine);
        this.bindings = Objects.requireNonNull(bindings);
        if (!machine.structureId().equals(model.structureId()))
            throw new IllegalArgumentException("Preview machine does not reference this model");
        var entry = Objects.requireNonNull(model.generation().entries().get(model.structureId()));
        var root = model.generation().nodes().get(entry.node());
        rotation = StructureTransform.between(root.orientation(), model.settings().orientation());
    }

    private static BlockPos sliceOrigin(BlockPos origin, BlockPos step, int slice) {
        return new BlockPos(Math.toIntExact(origin.getX() + (long) step.getX() * slice),
            Math.toIntExact(origin.getY() + (long) step.getY() * slice),
            Math.toIntExact(origin.getZ() + (long) step.getZ() * slice));
    }

    private static Vec3d modelOffset(BlockOrientation orientation, GeckoModelBinding binding) {
        var east = orientation.rotate(EnumFacing.EAST);
        var up = orientation.rotate(EnumFacing.UP);
        var south = orientation.rotate(EnumFacing.SOUTH);
        return new Vec3d(binding.offsetX() * east.getXOffset() + binding.offsetY() * up.getXOffset() + binding.offsetZ() * south.getXOffset(),
            binding.offsetX() * east.getYOffset() + binding.offsetY() * up.getYOffset() + binding.offsetZ() * south.getYOffset() + binding.yOffset(),
            binding.offsetX() * east.getZOffset() + binding.offsetY() * up.getZOffset() + binding.offsetZ() * south.getZOffset());
    }

    /** Each declaration, path edge and emitted slice spends one visit, including absent/zero-slice bindings. */
    public int advance(int visits) {
        if (visits < 0) throw new IllegalArgumentException("Negative model anchor visits");
        int spent = 0;
        while (spent < visits && completed == null) {
            spent++;
            if (!machineVisited) {
                machineVisited = true;
                var binding = bindings.machines().get(machine.id());
                if (binding != null)
                    add(new StructureRenderAnchors.Part(StructureRenderAnchors.Kind.MACHINE, machine.id(), List.of(), -1), BlockPos.ORIGIN, binding);
                continue;
            }
            if (occurrence == null) {
                if (cursor == model.occurrences().size()) {
                    completed = ObjectLists.unmodifiable(anchors);
                    break;
                }
                var next = model.occurrences().get(cursor++);
                var node = model.generation().nodes().get(next.node());
                var binding = bindings.structures().get(new MachineRenderBindings.StructureKey(machine.id(), node.id()));
                if (binding == null) continue;
                perSlice = node.kind() == StructurePreviewProvider.Kind.SLICE
                    && binding.sliceMode() == GeckoStructureBinding.SliceMode.PER_SLICE;
                count = perSlice ? next.selectedSlices() : 1;
                if (count < 0) throw new IllegalArgumentException("Negative selected model slices");
                if (count == 0) continue;
                if (count > MAX_ANCHORS - anchors.size())
                    throw new IllegalArgumentException("Preview model anchor limit exceeded");
                occurrence = next;
                structureBinding = binding;
                slice = 0;
                sliceStep = rotation.apply(node.sliceOffset());
                pathCursor = next.path();
                pathParts = new Integer[pathCursor.depth()];
                occurrencePath = null;
                continue;
            }
            if (pathCursor.depth() != 0) {
                pathParts[pathCursor.depth() - 1] = pathCursor.childIndex();
                pathCursor = Objects.requireNonNull(pathCursor.parent());
                continue;
            }
            if (occurrencePath == null) occurrencePath = List.copyOf(Arrays.asList(pathParts));
            var node = model.generation().nodes().get(occurrence.node());
            var origin = perSlice ? sliceOrigin(occurrence.origin(), sliceStep, slice) : occurrence.origin();
            add(new StructureRenderAnchors.Part(StructureRenderAnchors.Kind.STRUCTURE, node.id(), occurrencePath, perSlice ? slice : -1), origin, structureBinding.model());
            if (++slice == count) occurrence = null;
        }
        return spent;
    }

    public @Nullable List<StructureRenderAnchors.Anchor> completed() {
        return completed;
    }

    private void add(StructureRenderAnchors.Part part, BlockPos origin, GeckoModelBinding binding) {
        if (anchors.size() == MAX_ANCHORS) throw new IllegalArgumentException("Preview model anchor limit exceeded");
        var orientation = model.settings().orientation();
        anchors.add(new StructureRenderAnchors.Anchor(part, origin, orientation, modelOffset(orientation, binding), binding, binding.effectiveLayers(null)));
    }
}
