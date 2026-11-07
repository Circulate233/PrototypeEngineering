package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.recipe.requirement.NbtLongArrayAccess;
import net.minecraft.nbt.NBTTagLongArray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Only reads independent serialized-key tags; never exposes or mutates the underlying array.
 */
@Mixin(NBTTagLongArray.class)
public abstract class NBTTagLongArrayMixin implements NbtLongArrayAccess {
    @Shadow
    private long[] data;

    @Override
    public int prototype_eng$length() {
        return data.length;
    }

    @Override
    public long prototype_eng$value(int index) {
        return data[index];
    }
}
