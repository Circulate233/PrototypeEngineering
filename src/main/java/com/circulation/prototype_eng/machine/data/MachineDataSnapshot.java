package com.circulation.prototype_eng.machine.data;

import com.circulation.prototype_eng.api.data.ComponentData;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Complete ordinary data fact. Identities are attachment-local observations, never execution rights.
 * Producers supply unique host/process IDs and slot names. Borrowed lists must remain unchanged;
 * consumers must not modify them. List access must support constant-time indexing.
 */
public record MachineDataSnapshot(long generation, long revision, @Nullable ComponentData.Snapshot machine,
                                  List<Host> hosts) {
    public MachineDataSnapshot {
        if (generation <= 0 || revision < 0)
            throw new IllegalArgumentException("Invalid machine data identity/revision");

    }

    public record Host(long identity, List<Slot> slots) {
        public Host {
            if (identity <= 0) throw new IllegalArgumentException("Invalid data host identity");

        }
    }

    public record Slot(String name, @Nullable Process process) {
    }

    public record Process(long identity, ResourceLocation recipe, @Nullable ComponentData.Snapshot data) {
        public Process {
            if (identity <= 0) throw new IllegalArgumentException("Invalid data process identity");
        }
    }
}
