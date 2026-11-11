package com.circulation.prototype_eng.machine.data;

import com.circulation.prototype_eng.api.data.ComponentData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Complete ordinary data fact. Identities are attachment-local observations, never execution rights. */
public record MachineDataSnapshot(long generation, long revision, @Nullable ComponentData.Snapshot machine,
                                  List<Host> hosts) {
    public MachineDataSnapshot {
        if (generation <= 0 || revision < 0) throw new IllegalArgumentException("Invalid machine data identity/revision");
        hosts = List.copyOf(hosts);
        var hostIds = new LongOpenHashSet();
        var processIds = new LongOpenHashSet();
        for (var host : hosts) {
            if (!hostIds.add(host.identity())) throw new IllegalArgumentException("Duplicate data host identity");
            for (var slot : host.slots())
                if (slot.process() != null && !processIds.add(slot.process().identity()))
                    throw new IllegalArgumentException("Duplicate data process identity");
        }
    }

    public record Host(long identity, List<Slot> slots) {
        public Host {
            if (identity <= 0) throw new IllegalArgumentException("Invalid data host identity");
            slots = List.copyOf(slots);
            var names = new ObjectOpenHashSet<String>();
            for (var slot : slots)
                if (!names.add(slot.name())) throw new IllegalArgumentException("Duplicate data slot name");
        }
    }

    public record Slot(String name, @Nullable Process process) {
        public Slot {
            Objects.requireNonNull(name);
        }
    }

    public record Process(long identity, ResourceLocation recipe, @Nullable ComponentData.Snapshot data) {
        public Process {
            if (identity <= 0) throw new IllegalArgumentException("Invalid data process identity");
            Objects.requireNonNull(recipe);
        }
    }
}
