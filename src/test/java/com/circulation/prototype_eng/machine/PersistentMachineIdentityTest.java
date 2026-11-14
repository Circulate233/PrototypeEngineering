package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistentMachineIdentityTest {


    @Test
    void fullUuidBitsRoundTrip() {
        var identity = new PersistentMachineIdentity(new UUID(Long.MIN_VALUE, Long.MAX_VALUE), new UUID(-1, 0));
        var encoded = identity.encode();
        assertEquals(identity, PersistentMachineIdentity.decode(encoded));
        var zeros = new PersistentMachineIdentity(new UUID(0, 0), new UUID(0, 0));
        assertEquals(zeros, PersistentMachineIdentity.decode(zeros.encode()));
    }


}
