package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.UUID;

/**
 * Strict world-owned download envelopes. No live player, event or inventory adapter is serialized.
 */
public final class ResearchDownloadNbt {
    private final ResearchDownloadSources sources;
    private final ItemDeliveryFragmentNbt fragments;

    public ResearchDownloadNbt(ResearchRegistry registry, ResourceKeyNbt keys) {
        sources = new ResearchDownloadSources(registry);
        fragments = new ItemDeliveryFragmentNbt(keys);
    }

    private static NBTTagCompound intent(ResearchDownloadOperation.Intent value) {
        var tag = new NBTTagCompound();
        tag.setString("Player", value.playerId().toString());
        tag.setString("Nonce", value.nonce().toString());
        tag.setString("Database", value.databaseId().toString());
        tag.setString("Team", value.teamId());
        tag.setString("Research", value.researchId().toString());
        tag.setInteger("Level", value.level());
        tag.setInteger("Slot", value.slot());
        return tag;
    }

    private static ResearchDownloadOperation.Intent intent(NBTTagCompound tag) {
        return new ResearchDownloadOperation.Intent(uuid(tag.getString("Player")), uuid(tag.getString("Nonce")),
            uuid(tag.getString("Database")), tag.getString("Team"), new ResourceLocation(tag.getString("Research")),
            tag.getInteger("Level"), tag.getInteger("Slot"));
    }

    private static void requireVersion(NBTTagCompound tag) {
        if (tag.getInteger("Version") != 1)
            throw new IllegalArgumentException("Unknown download envelope version");
    }

    private static UUID uuid(String text) {
        return UUID.fromString(text);
    }

    public NBTTagCompound encode(ResearchDownloadOperation operation) {
        var tag = common(operation.id(), operation.intent(), operation.sources(), operation.output(), operation.amount());
        tag.setTag("Material", ResearchDownloadMaterial.CODEC.encode(operation.material()));
        tag.setLong("CreatedAt", operation.createdAt());
        tag.setString("Status", operation.status().name());
        tag.setBoolean("MaterialArmed", operation.materialArmed());
        tag.setTag("Fragments", encodeFragments(operation.fragments()));
        return tag;
    }

    public ResearchDownloadOperation decodeOperation(NBTTagCompound tag) {
        requireVersion(tag);
        return new ResearchDownloadOperation(uuid(tag.getString("Id")), intent(tag.getCompoundTag("Intent")),
            sources.codec().decode(tag.getCompoundTag("Sources")), ResearchDownloadMaterial.CODEC.decode(tag.getCompoundTag("Material")),
            tag.getCompoundTag("Output"), tag.getLong("Amount"), tag.getLong("CreatedAt"),
            ResearchDownloadOperation.Status.valueOf(tag.getString("Status")), tag.getBoolean("MaterialArmed"), decodeFragments(tag));
    }

    public NBTTagCompound encode(ResearchDownloadReceipt receipt) {
        var tag = common(receipt.operationId(), receipt.intent(), receipt.sources(), receipt.output(), receipt.amount());
        tag.setString("Outcome", receipt.outcome().name());
        tag.setTag("Fragments", encodeFragments(receipt.fragments()));
        return tag;
    }

    public ResearchDownloadReceipt decodeReceipt(NBTTagCompound tag) {
        requireVersion(tag);
        return new ResearchDownloadReceipt(uuid(tag.getString("Id")), intent(tag.getCompoundTag("Intent")),
            sources.codec().decode(tag.getCompoundTag("Sources")), tag.getCompoundTag("Output"), tag.getLong("Amount"),
            ResearchDownloadReceipt.Outcome.valueOf(tag.getString("Outcome")), decodeFragments(tag));
    }

    private NBTTagCompound common(UUID id, ResearchDownloadOperation.Intent intent, ResearchDownloadSources.Plan plan, NBTTagCompound output, long amount) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setString("Id", id.toString());
        tag.setTag("Intent", intent(intent));
        tag.setTag("Sources", sources.codec().encode(plan));
        tag.setTag("Output", output);
        tag.setLong("Amount", amount);
        return tag;
    }

    private NBTTagList encodeFragments(ObjectList<ItemDeliveryFragment> values) {
        var tags = new NBTTagList();
        for (int index1 = 0; index1 < values.size(); index1++) {
            var fragment = values.get(index1);
            tags.appendTag(fragments.encode(fragment));
        }
        return tags;
    }

    private ObjectList<ItemDeliveryFragment> decodeFragments(NBTTagCompound tag) {
        var tags = tag.getTagList("Fragments", Constants.NBT.TAG_COMPOUND);
        var values = new ObjectArrayList<ItemDeliveryFragment>(tags.tagCount());
        for (int index = 0; index < tags.tagCount(); index++)
            values.add(fragments.decode(tags.getCompoundTagAt(index)));
        return values;
    }
}
