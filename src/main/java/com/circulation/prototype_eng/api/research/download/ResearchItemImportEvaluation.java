package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Completed additive research and its full native return item, borrowed read-only by the transaction.
 */
public record ResearchItemImportEvaluation(ResearchDiskData data, NBTTagCompound output, int amount) {
    public ResearchItemImportEvaluation(ResearchDiskData data, ItemStack output) {
        this(data, output.serializeNBT(), output.getCount());
        this.output.setByte("Count", (byte) 1);
    }
}
