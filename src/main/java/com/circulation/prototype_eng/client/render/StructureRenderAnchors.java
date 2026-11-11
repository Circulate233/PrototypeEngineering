package com.circulation.prototype_eng.client.render;

import it.unimi.dsi.fastutil.ints.IntList;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Accepted world origins/steps are never transformed again. Only model-local offsets rotate. */
public final class StructureRenderAnchors {
    private StructureRenderAnchors() {
    }

    public static List<Anchor> select(MachineRenderSnapshot snapshot, MachineRenderBindings.View bindings) {
        return select(snapshot, bindings, null);
    }

    /** Data has already been captured from the exact client attachment; workers only see chosen names. */
    public static List<Anchor> select(MachineRenderSnapshot snapshot, MachineRenderBindings.View bindings, @Nullable ComponentData.Snapshot data) {
        var result = new ObjectArrayList<Anchor>();
        var type = snapshot.display().type();
        boolean structurePath = snapshot.display().formed() && bindings.structures().keySet().stream().anyMatch(key -> key.machine().equals(type));
        if (structurePath) {
            long anchorCount = 0;
            for (var occurrence : snapshot.occurrences()) {
                var binding = bindings.structures().get(new MachineRenderBindings.StructureKey(type, occurrence.structure()));
                if (binding == null) continue;
                anchorCount += perSlice(occurrence, binding)
                    ? occurrence.repetitions() : 1;
                if (anchorCount > MachineRenderSnapshot.MAX_POSITIONS)
                    throw new IllegalArgumentException("Machine model anchor expansion exceeds supported scale");
            }
            for (int index = 0; index < snapshot.occurrences().size(); index++) {
                var occurrence = snapshot.occurrences().get(index);
                var binding = bindings.structures().get(new MachineRenderBindings.StructureKey(type, occurrence.structure()));
                if (binding == null) continue;
                boolean perSlice = perSlice(occurrence, binding);
                int count = perSlice ? occurrence.repetitions() : 1;
                for (int slice = 0; slice < count; slice++) {
                    var origin = perSlice ? MachineRenderSnapshot.sliceAnchor(occurrence.origin(), occurrence.sliceStep(), slice) : occurrence.origin();
                    result.add(anchor(snapshot, new Part(Kind.STRUCTURE, occurrence.structure(), snapshot.path(index), perSlice ? slice : -1), origin, binding.model(), data));
                }
            }
        } else {
            var machine = bindings.machines().get(type);
            if (machine != null)
                result.add(anchor(snapshot, new Part(Kind.MACHINE, type, IntList.of(), -1), snapshot.controller(), machine, data));
            // Only actual assembled IDs participate; no client component factories or declaration guesses.
            for (var id : snapshot.components()) {
                var binding = bindings.components().get(id);
                if (binding != null)
                    result.add(anchor(snapshot, new Part(Kind.COMPONENT, id, IntList.of(), -1), snapshot.controller(), binding, data));
            }
        }
        return List.copyOf(result);
    }

    private static Anchor anchor(MachineRenderSnapshot snapshot, Part part, BlockPos origin, GeckoModelBinding binding, @Nullable ComponentData.Snapshot data) {
        return new Anchor(part, origin, snapshot.display().orientation(), modelOffset(snapshot.display().orientation(), binding), binding,
            binding.effectiveLayers(snapshot.animation(), data));
    }

    private static boolean perSlice(MachineRenderSnapshot.Occurrence occurrence, GeckoStructureBinding binding) {
        return occurrence.kind() == StructurePreviewProvider.Kind.SLICE && binding.sliceMode() == GeckoStructureBinding.SliceMode.PER_SLICE;
    }

    private static Vec3d modelOffset(BlockOrientation orientation, GeckoModelBinding binding) {
        var east = orientation.rotate(EnumFacing.EAST);
        var up = orientation.rotate(EnumFacing.UP);
        var south = orientation.rotate(EnumFacing.SOUTH);
        return new Vec3d(binding.offsetX() * east.getXOffset() + binding.offsetY() * up.getXOffset() + binding.offsetZ() * south.getXOffset(),
            binding.offsetX() * east.getYOffset() + binding.offsetY() * up.getYOffset() + binding.offsetZ() * south.getYOffset() + binding.yOffset(),
            binding.offsetX() * east.getZOffset() + binding.offsetY() * up.getZOffset() + binding.offsetZ() * south.getZOffset());
    }

    public enum Kind {MACHINE, COMPONENT, STRUCTURE}

    public record Part(Kind kind, ResourceLocation id, IntList occurrencePath, int slice) {
        public Part {
            occurrencePath = IntLists.unmodifiable(new IntArrayList(occurrencePath));
        }
    }

    public record Anchor(Part part, BlockPos origin, BlockOrientation orientation, Vec3d modelOffset,
                         GeckoModelBinding binding, List<String> layers) {
    }
}
