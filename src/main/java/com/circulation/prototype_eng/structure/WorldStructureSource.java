package com.circulation.prototype_eng.structure;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Real world adapter; only this capture phase calls world/TE/capability methods. */
@SuppressWarnings("unused") // Public adapter for structure hosts; actual controller scheduling is separate.
public final class WorldStructureSource implements StructureReadSource {
    private final MachineLifecycleHost host;

    public WorldStructureSource(MachineLifecycleHost host) { this.host = Objects.requireNonNull(host); }

    @Override public void requireServerThread() { host.requireServerThread(); }

    @Override public boolean isAreaLoaded(StructureBounds bounds) {
        requireServerThread();
        return host.world().isAreaLoaded(bounds.min(), bounds.max(), false);
    }

    @Override @Nullable public <T> T read(StructureQuery<T> query, BlockPos position) {
        requireServerThread();
        return query.capture(host.world(), position);
    }
}
