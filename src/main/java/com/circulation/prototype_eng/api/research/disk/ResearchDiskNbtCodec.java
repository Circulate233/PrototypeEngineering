package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;


/**
 * Disk namespace codec for research IDs and levels. Item integrations must
 * establish that the actual item is a supported disk and that its declared tier matches this data.
 * Other item tags and native capability payloads remain owned by that item.
 */
@SuppressWarnings("unused") // Public item persistence boundary; no item-registration policy here.
public final class ResearchDiskNbtCodec {
    public static final String PAYLOAD_TAG = "prototype_eng_research_disk";
    private static final int VERSION = 1;

    private ResearchDiskNbtCodec() {
    }

    /**
     * Fresh namespace payload. It does not contain or replace the item's other tags.
     */
    public static NBTTagCompound encode(ResearchDiskData data) {
        var result = new NBTTagCompound();
        result.setInteger("version", VERSION);
        result.setInteger("tier", data.tier());
        var entries = new NBTTagList();
        for (var entry : data.entries().object2IntEntrySet()) {
            var encoded = new NBTTagCompound();
            encoded.setString("researchId", entry.getKey().toString());
            encoded.setInteger("level", entry.getIntValue());
            entries.appendTag(encoded);
        }
        result.setTag("entries", entries);
        return result;
    }

    /**
     * Freeze the complete parsed batch only after every id, level and research tier validates.
     */
    public static ResearchDiskData decode(NBTTagCompound stable, ResearchRegistry registry) {
        if (stable.getInteger("version") != VERSION) {
            throw new IllegalArgumentException("Unsupported research disk payload version");
        }
        int tier = stable.getInteger("tier");
        var entries = stable.getTagList("entries", Constants.NBT.TAG_COMPOUND);
        if (entries.tagCount() < 1 || entries.tagCount() > ResearchDiskData.MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid research disk entry count");
        }
        var levels = new Object2IntOpenHashMap<ResourceLocation>(entries.tagCount());
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            var id = new ResourceLocation(entry.getString("researchId"));
            int level = entry.getInteger("level");
            if (levels.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate research disk id: " + id);
            }
            levels.put(id, level);
        }
        return new ResearchDiskData(registry, tier, levels);
    }

    @SuppressWarnings("DataFlowIssue")
    public static ResearchDiskData read(ItemStack stack, ResearchRegistry registry) {
        return decode(stack.getTagCompound().getCompoundTag(PAYLOAD_TAG), registry);
    }

    /**
     * Copy the native stack, including ForgeCaps, then replace only this codec's namespace.
     */
    public static ItemStack writeCopy(ItemStack stack, ResearchDiskData data) {
        var encoded = encode(data);
        var result = stack.copy();
        var existing = result.getTagCompound();
        var tag = existing == null ? new NBTTagCompound() : existing;
        tag.setTag(PAYLOAD_TAG, encoded);
        result.setTagCompound(tag);
        return result;
    }

}
