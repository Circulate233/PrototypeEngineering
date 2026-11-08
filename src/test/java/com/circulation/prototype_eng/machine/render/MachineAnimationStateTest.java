package com.circulation.prototype_eng.machine.render;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Collections;
import java.util.Objects;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MachineAnimationStateTest {
    @Test
    void immutableLayerUpdateKeepsOrderAndNoopDoesNotAdvanceRevision() {
        var state = new MachineAnimationState();
        var caller = new ObjectArrayList<>(List.of("base", "overlay"));
        state.setLayers(caller);
        var captured = state.snapshot();
        caller.clear();
        assertEquals(List.of("base", "overlay"), captured.layers());
        state.setLayers(List.of("base", "overlay"));
        assertSame(captured, state.snapshot());
        state.setLayers(List.of("overlay", "base"));
        assertEquals(2, state.snapshot().revision());
        assertEquals(List.of("base", "overlay"), captured.layers());
        state.clear();
        assertEquals(3, state.snapshot().revision());
        state.setAnimation(null);
        assertEquals(3, state.snapshot().revision());
    }

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

    @Test
    void illegalSavedStateIsRejectedAndRevisionOverflowDoesNotMutateState() {
        var state = new MachineAnimationState();
        state.setAnimation("idle");
        var tag = MachineAnimationState.CODEC.encode(null, state);
        tag.setLong("Revision", Long.MAX_VALUE);
        MachineAnimationState.CODEC.restore(null, state, tag);
        var captured = state.snapshot();
        assertThrows(ArithmeticException.class, () -> state.setAnimation("active"));
        assertSame(captured, state.snapshot());
        tag.setLong("Revision", -1);
        assertThrows(IllegalArgumentException.class, () -> MachineAnimationState.CODEC.restore(null, state, tag));
        assertSame(captured, state.snapshot());
        assertThrows(IllegalArgumentException.class, () -> state.setLayers(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> state.setLayers(Collections.nCopies(257, "a")));
    }
}
