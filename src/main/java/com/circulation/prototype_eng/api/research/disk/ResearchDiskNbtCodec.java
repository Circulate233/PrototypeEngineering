package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Objects;

/**
 * Strict disk namespace codec, derived from PN's validated id/level payload. Item integrations must
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
        Objects.requireNonNull(data, "Research disk data");
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
    public static ResearchDiskData decode(NBTTagCompound payload, ResearchRegistry registry) {
        var stable = Objects.requireNonNull(payload, "Research disk payload").copy();
        StrictNbt.requireKeys(stable, "version", "tier", "entries");
        if (StrictNbt.requireInt(stable, "version") != VERSION) {
            throw new IllegalArgumentException("Unsupported research disk payload version");
        }
        int tier = StrictNbt.requireInt(stable, "tier");
        var entries = StrictNbt.requireList(stable, "entries", Constants.NBT.TAG_COMPOUND);
        if (entries.tagCount() < 1 || entries.tagCount() > ResearchDiskData.MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid research disk entry count");
        }
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "researchId", "level");
            var id = StrictNbt.requireId(entry, "researchId");
            int level = StrictNbt.requireInt(entry, "level");
            if (levels.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate research disk id: " + id);
            }
            levels.put(id, level);
        }
        return new ResearchDiskData(registry, tier, levels);
    }

    public static ResearchDiskData read(ItemStack stack, ResearchRegistry registry) {
        var stable = copyStack(stack);
        return decode(StrictNbt.requireCompound(Objects.requireNonNull(stable.getTagCompound(), "Research disk item tag"),
            PAYLOAD_TAG), registry);
    }

    /**
     * Copy the native stack, including ForgeCaps, then replace only this codec's namespace.
     */
    public static ItemStack writeCopy(ItemStack stack, ResearchDiskData data) {
        var encoded = encode(data);
        var result = copyStack(stack);
        var existing = result.getTagCompound();
        var tag = existing == null ? new NBTTagCompound() : existing.copy();
        tag.setTag(PAYLOAD_TAG, encoded);
        result.setTagCompound(tag);
        return result;
    }

    static ItemStack copyStack(ItemStack stack) {
        validateStack(stack);
        var copy = stack.copy();
        validateStack(copy);
        return copy;
    }

    private static void validateStack(ItemStack stack) {
        Objects.requireNonNull(stack, "Research disk item");
        if (stack.isEmpty() || stack.getCount() < 1 || stack.getMetadata() < 0) {
            throw new IllegalArgumentException("Research disk item must be non-empty with positive count and non-negative metadata");
        }
        var item = stack.getItem();
        var id = item.getRegistryName();
        if (id == null || ForgeRegistries.ITEMS.getValue(id) != item) {
            throw new IllegalArgumentException("Research disk item requires the exact registered item identity");
        }
    }
}
