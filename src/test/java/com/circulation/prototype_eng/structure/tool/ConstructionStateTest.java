package com.circulation.prototype_eng.structure.tool;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionStateTest {
    @Test
    void oldSavesUseConservativeDefaultsAndNewSettingsSurviveRoundTrip() {
        var tag = new NBTTagCompound();
        assertTrue(BuilderState.Setting.REQUIRE_MATERIALS.enabled(tag));
        assertTrue(BuilderState.Setting.REQUIRE_SPACE.enabled(tag));
        assertFalse(BuilderState.Setting.REPLACE_FLUIDS.enabled(tag));
        assertEquals(0, BuilderState.meMode(tag));
        for (var setting : BuilderState.Setting.VALUES) tag.setBoolean(setting.key, !setting.enabled(tag));
        BuilderState.cycleMeMode(tag);
        BuilderState.cycleMeMode(tag);
        var copy = tag.copy();
        assertFalse(BuilderState.Setting.REQUIRE_MATERIALS.enabled(copy));
        assertFalse(BuilderState.Setting.REQUIRE_SPACE.enabled(copy));
        assertFalse(BuilderState.Setting.REPLACE_FLUIDS.enabled(copy));
        assertTrue(BuilderState.Setting.WAIT_MATERIALS.enabled(copy));
        assertEquals(2, BuilderState.meMode(copy));
        BuilderState.cycleMeMode(copy);
        assertEquals(0, BuilderState.meMode(copy));
    }
}
