package com.circulation.prototype_eng.api.persistence;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Encodes one persistent value. Encoding returns an independent tag, and decoding must neither
 * modify nor retain mutable input tags. Invalid or incompatible saved data must be rejected.
 */
public interface NbtCodec<T> {
    NBTTagCompound encode(T value);

    T decode(NBTTagCompound tag);
}
