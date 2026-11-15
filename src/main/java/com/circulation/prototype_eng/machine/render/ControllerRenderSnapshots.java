package com.circulation.prototype_eng.machine.render;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.ReloadableStructureHost;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureMatch;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Captures the installed structure at the joined server boundary. Preview callbacks must not mutate the machine or host.
 */
public final class ControllerRenderSnapshots {
    private ControllerRenderSnapshots() {
    }

    @SuppressWarnings("DataFlowIssue")
    public static @Nullable MachineRenderSnapshot capture(MachineInstance machine, StructureConfiguration configuration,
                                                          StructureCatalogView view, ControllerDisplayData display,
                                                          BlockPos controller, BooleanSupplier current) {
        var type = machine.type();
        var lifecycle = machine.lifecycle();
        lifecycle.host().requireServerThread();
        machine.requirePersistenceBoundary();
        if (!(lifecycle.host() instanceof ReloadableStructureHost host) || !current.getAsBoolean() ||
            !host.structureLifecycleCurrent(lifecycle) || host.structureConfiguration() != configuration ||
            lifecycle.state() != MachineLifecycle.State.LOADED || lifecycle.retired()) return null;
        var match = lifecycle.structureMatch();
        if (lifecycle.formed() != display.formed() || display.orientation() != configuration.orientation() ||
            !display.type().equals(type.id())) return null;
        var occurrences = new ObjectArrayList<MachineRenderSnapshot.Occurrence>();
        var positions = new ObjectLinkedOpenHashSet<BlockPos>();
        if (display.formed()) {
            if (match == null) throw new IllegalStateException("Formed lifecycle has no accepted structure match");
            var stack = new ObjectArrayList<Frame>();
            stack.add(new Frame(match, -1, -1));
            long memberships = 0;
            while (!stack.isEmpty()) {
                var frame = stack.removeLast();
                var accepted = frame.match();
                var shape = accepted.structure();
                var geometry = shape instanceof StructurePreviewProvider provider ? provider.previewGeometry() : null;
                if (occurrences.size() == MachineRenderSnapshot.MAX_OCCURRENCES ||
                    (memberships += accepted.blocks().size()) > MachineRenderSnapshot.MAX_POSITIONS)
                    throw new IllegalArgumentException("Accepted machine exceeds render occurrence/position scale");
                int index = occurrences.size();
                occurrences.add(new MachineRenderSnapshot.Occurrence(frame.parent(), frame.edge(), shape.id(), accepted.origin(), accepted.repetitions(),
                    geometry == null ? StructurePreviewProvider.Kind.OPAQUE : geometry.kind(),
                    geometry == null ? BlockPos.ORIGIN : geometry.sliceOffset(), shape.hideWorldBlocks(), accepted.blocks()));
                positions.addAll(accepted.blocks());
                for (int child = accepted.children().size() - 1; child >= 0; child--)
                    stack.add(new Frame(accepted.children().get(child), index, child));
            }
        }
        var ids = new ObjectArrayList<ResourceLocation>();
        var bindings = machine.components().assemblyBindings();
        for (int index1 = 0; index1 < bindings.size(); index1++) {
            var binding = bindings.get(index1);
            ids.add(binding.type().id());
        }
        var animation = machine.components().get(MachineAnimationState.TYPE);
        return new MachineRenderSnapshot(display, new MachineRenderSnapshot.CatalogToken(view.session(), view.sequence()), controller,
            configuration.definition().id(), occurrences, new ObjectArrayList<>(positions), ids, animation == null ? null : animation.snapshot());
    }

    private record Frame(StructureMatch match, int parent, int edge) {
    }
}
