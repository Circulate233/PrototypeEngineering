package com.circulation.prototype_eng.api.registration;

import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefinitionRegistrationPhaseTest {
    @Test
    void closesBeforeCallbacksAndKeepsDeclarationOrder() {
        var phase = new DefinitionRegistrations.Phase<>("test");
        var event = new Object();
        var calls = new ObjectArrayList<String>();
        phase.add(received -> {
            assertSame(event, received);
            calls.add("first");
            assertThrows(IllegalStateException.class, () -> phase.add(_ -> calls.add("late")));
            assertThrows(IllegalStateException.class, () -> phase.deliver(event));
        });
        phase.add(_ -> calls.add("second"));
        phase.deliver(event);
        assertEquals(List.of("first", "second"), calls);
        assertThrows(IllegalStateException.class, () -> phase.deliver(event));
    }

    @Test
    void failedStartupCallbackCannotReplayPartiallyAppliedDefinitions() {
        var phase = new DefinitionRegistrations.Phase<>("test");
        var calls = new ObjectArrayList<String>();
        phase.add(_ -> {
            calls.add("applied");
            throw new IllegalArgumentException("invalid definition");
        });
        phase.add(_ -> calls.add("unreached"));
        assertThrows(IllegalArgumentException.class, () -> phase.deliver(new Object()));
        assertEquals(List.of("applied"), calls);
        assertThrows(IllegalStateException.class, () -> phase.deliver(new Object()));
        assertThrows(IllegalStateException.class, () -> phase.add(_ -> calls.add("reload")));
    }
}
