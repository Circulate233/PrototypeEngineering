package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.util.ExternalEnumValues;

import ae2.api.orientation.BlockOrientation;
import ae2.api.orientation.RelativeSide;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Rotation;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerDataTest {
    private static final ResourceLocation TYPE = new ResourceLocation("controller_data_test", "machine");

    @Test
    void detachedSavesKeepOpaqueRuntimeAcrossRepeatedDecodingWithoutSharingCallerOrReturnedTags() {
        var progress = new NBTTagCompound();
        progress.setInteger("Progress", 17);
        var raw = new NBTTagCompound();
        raw.setTag("Custom", progress);
        var original = new ControllerData(TYPE, BlockOrientation.UP_WEST, raw);
        raw.getCompoundTag("Custom").setInteger("Progress", 99);
        var saved = original.encode();
        var restored = ControllerData.decode(saved);
        saved.getCompoundTag("Runtime").getCompoundTag("Custom").setInteger("Progress", 88);
        Objects.requireNonNull(restored.runtime()).getCompoundTag("Custom").setInteger("Progress", 77);
        var secondRead = ControllerData.decode(restored.encode());
        assertEquals(17, Objects.requireNonNull(secondRead.runtime()).getCompoundTag("Custom").getInteger("Progress"));
        assertEquals(original.encode(), secondRead.encode());
        var invalid = original.encode();
        invalid.setString("Runtime", "wrong-tag-kind");
        assertThrows(IllegalArgumentException.class, () -> ControllerData.decode(invalid));
        assertEquals(original.encode(), restored.encode());
    }

    @Test
    void fullOrientationTransformsSurviveNbtAndComposeForVerticalAndHorizontalControllers() {
        for (var original : ExternalEnumValues.BLOCK_ORIENTATIONS) {
            var rotated = original;
            for (int turn = 0; turn < 4; turn++) {
                var next = ControllerOrientation.rotate(rotated, Rotation.CLOCKWISE_90);
                assertEquals(Rotation.CLOCKWISE_90.rotate(rotated.getSide(RelativeSide.FRONT)), next.getSide(RelativeSide.FRONT));
                assertEquals(Rotation.CLOCKWISE_90.rotate(rotated.getSide(RelativeSide.TOP)), next.getSide(RelativeSide.TOP));
                rotated = ControllerData.decode(new ControllerData(TYPE, next, null).encode()).orientation();
            }
            assertSame(original, rotated);
            for (var mirror : ExternalEnumValues.MIRRORS) {
                var mirrored = ControllerOrientation.mirror(original, mirror);
                assertSame(original, ControllerOrientation.mirror(mirrored, mirror));
                assertNotEquals(mirrored.getSide(RelativeSide.FRONT).getAxis(), mirrored.getSide(RelativeSide.TOP).getAxis());
            }
        }
    }

    @Test
    void displayGenerationRejectsOldRuntimePacketsAndSequenceReplaysWithoutAcceptingPersistentState() {
        var first = new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, true, 4, 12);
        var next = new ControllerDisplayData(TYPE, BlockOrientation.DOWN_EAST, false, 5, 0);
        assertTrue(next.follows(first));
        assertFalse(first.follows(next));
        assertFalse(new ControllerDisplayData(TYPE, BlockOrientation.NORTH_UP, true, 4, 100).follows(next));
        assertFalse(ControllerDisplayData.decode(next.encode()).follows(next));
        assertTrue(new ControllerDisplayData(TYPE, BlockOrientation.DOWN_EAST, true, 5, 1).follows(next));
        assertFalse(new ControllerDisplayData(new ResourceLocation("controller_data_test", "other"), BlockOrientation.NORTH_UP, true, 6, 0).follows(next));
        var injected = next.encode();
        injected.setTag("Runtime", new NBTTagCompound());
        assertThrows(IllegalArgumentException.class, () -> ControllerDisplayData.decode(injected));
        injected.removeTag("Runtime");
        injected.setInteger("Generation", 5);
        assertThrows(IllegalArgumentException.class, () -> ControllerDisplayData.decode(injected));
    }
}
