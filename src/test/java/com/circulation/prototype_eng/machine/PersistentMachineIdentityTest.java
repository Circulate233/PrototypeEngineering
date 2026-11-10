package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistentMachineIdentityTest {
    private static void assertRejectedWithoutMutation(NBTTagCompound tag) {
        var before = tag.copy();
        assertThrows(IllegalArgumentException.class, () -> PersistentMachineIdentity.decode(tag));
        assertEquals(before, tag);
    }

    private static PersistentMachineIdentity identity() {
        return new PersistentMachineIdentity(new UUID(1, 2), new UUID(3, 4));
    }

    @Test
    void fullUuidBitsRoundTripWithoutBorrowingOrMutatingEncodedCompounds() {
        var identity = new PersistentMachineIdentity(new UUID(Long.MIN_VALUE, Long.MAX_VALUE), new UUID(-1, 0));
        var encoded = identity.encode();
        var before = encoded.copy();
        assertEquals(identity, PersistentMachineIdentity.decode(encoded));
        assertEquals(before, encoded);
        encoded.setLong("ownerLeast", 99);
        assertEquals(identity, PersistentMachineIdentity.decode(identity.encode()));
        var zeros = new PersistentMachineIdentity(new UUID(0, 0), new UUID(0, 0));
        assertEquals(zeros, PersistentMachineIdentity.decode(zeros.encode()));
    }

    @Test
    void missingAndCoercedUuidFieldsFailAtomicallyIncludingTheLastOwnerField() {
        var identity = identity();
        for (var key : List.of("machineMost", "machineLeast", "ownerMost", "ownerLeast")) {
            var missing = identity.encode();
            missing.removeTag(key);
            assertRejectedWithoutMutation(missing);
            var integer = identity.encode();
            integer.setInteger(key, 1);
            assertRejectedWithoutMutation(integer);
            var string = identity.encode();
            string.setString(key, "1");
            assertRejectedWithoutMutation(string);
            var decimal = identity.encode();
            decimal.setDouble(key, 1);
            assertRejectedWithoutMutation(decimal);
        }
        assertEquals(identity, PersistentMachineIdentity.decode(identity.encode()));
    }

    @Test
    void strictVersionAndUnknownFieldsRejectRatherThanRestoringSavedPermission() {
        var identity = identity();
        assertRejectedWithoutMutation(new NBTTagCompound());
        var missingVersion = identity.encode();
        missingVersion.removeTag("version");
        assertRejectedWithoutMutation(missingVersion);
        var longVersion = identity.encode();
        longVersion.setLong("version", 1);
        assertRejectedWithoutMutation(longVersion);
        var byteVersion = identity.encode();
        byteVersion.setByte("version", (byte) 1);
        assertRejectedWithoutMutation(byteVersion);
        for (int version : new int[]{-1, 0, 2, Integer.MAX_VALUE}) {
            var unsupported = identity.encode();
            unsupported.setInteger("version", version);
            assertRejectedWithoutMutation(unsupported);
        }
        for (var key : List.of("team", "epoch", "lease", "ready")) {
            var extra = identity.encode();
            extra.setLong(key, 1);
            assertRejectedWithoutMutation(extra);
        }
        assertThrows(NullPointerException.class, () -> PersistentMachineIdentity.decode(null));
    }
}
