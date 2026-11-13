package com.circulation.prototype_eng.machine.render;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class MachineAnimationStateTest {
    @Test
    void actualAssemblyCodecRestoresLayersIntoAFreshOwnerAndCheckedRevision() {
        try (var fixture = new MachineRenderSnapshotTest.Fixture(MachineRenderSnapshotTest.nested(), BlockOrientation.NORTH_UP)) {
            var state = Objects.requireNonNull(fixture.machine.components().get(MachineAnimationState.TYPE));
            state.setLayers(List.of("base", "glow"));
            var saved = fixture.assembly.encode(fixture.machine);
            var host = new MachineRenderSnapshotTest.Host();
            host.configuration = fixture.configuration;
            var restored = fixture.assembly.restore(host, saved, new MachineLifecycleRegistry());
            host.machine = restored;
            try {
                var next = Objects.requireNonNull(restored.components().get(MachineAnimationState.TYPE));
                assertNotSame(state, next);
                assertEquals(state.snapshot(), next.snapshot());
                next.setAnimation("active");
                assertEquals(List.of("base", "glow"), state.snapshot().layers());
                assertEquals(2, next.snapshot().revision());
                assertFalse(Objects.requireNonNull(restored.lifecycle()).formed());
            } finally {
                Objects.requireNonNull(restored.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
        }
    }

}
