package com.circulation.prototype_eng.api.research.download;

import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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
        StrictNbt.requireKeys(tag, "Player", "Nonce", "Database", "Team", "Research", "Level", "Slot");
        return new ResearchDownloadOperation.Intent(uuid(StrictNbt.requireString(tag, "Player")), uuid(StrictNbt.requireString(tag, "Nonce")),
            uuid(StrictNbt.requireString(tag, "Database")), StrictNbt.requireString(tag, "Team"), StrictNbt.requireId(tag, "Research"),
            StrictNbt.requireInt(tag, "Level"), StrictNbt.requireInt(tag, "Slot"));
    }

    private static void requireVersion(NBTTagCompound tag) {
        if (StrictNbt.requireInt(tag, "Version") != 1)
            throw new IllegalArgumentException("Unknown download envelope version");
    }

    private static UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical download UUID");
        return id;
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
        StrictNbt.requireKeys(tag, "Version", "Id", "Intent", "Sources", "Output", "Amount", "Material", "CreatedAt", "Status", "MaterialArmed", "Fragments");
        requireVersion(tag);
        return new ResearchDownloadOperation(uuid(StrictNbt.requireString(tag, "Id")), intent(StrictNbt.requireCompound(tag, "Intent")),
            sources.codec().decode(StrictNbt.requireCompound(tag, "Sources")), ResearchDownloadMaterial.CODEC.decode(StrictNbt.requireCompound(tag, "Material")),
            StrictNbt.requireCompound(tag, "Output"), StrictNbt.requireLong(tag, "Amount"), StrictNbt.requireLong(tag, "CreatedAt"),
            ResearchDownloadOperation.Status.valueOf(StrictNbt.requireString(tag, "Status")), StrictNbt.requireBoolean(tag, "MaterialArmed"), decodeFragments(tag));
    }

    public NBTTagCompound encode(ResearchDownloadReceipt receipt) {
        var tag = common(receipt.operationId(), receipt.intent(), receipt.sources(), receipt.output(), receipt.amount());
        tag.setString("Outcome", receipt.outcome().name());
        tag.setTag("Fragments", encodeFragments(receipt.fragments()));
        return tag;
    }

    public ResearchDownloadReceipt decodeReceipt(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Id", "Intent", "Sources", "Output", "Amount", "Outcome", "Fragments");
        requireVersion(tag);
        return new ResearchDownloadReceipt(uuid(StrictNbt.requireString(tag, "Id")), intent(StrictNbt.requireCompound(tag, "Intent")),
            sources.codec().decode(StrictNbt.requireCompound(tag, "Sources")), StrictNbt.requireCompound(tag, "Output"), StrictNbt.requireLong(tag, "Amount"),
            ResearchDownloadReceipt.Outcome.valueOf(StrictNbt.requireString(tag, "Outcome")), decodeFragments(tag));
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
        for (var fragment : values) tags.appendTag(fragments.encode(fragment));
        return tags;
    }

    private ObjectList<ItemDeliveryFragment> decodeFragments(NBTTagCompound tag) {
        var tags = StrictNbt.requireList(tag, "Fragments", Constants.NBT.TAG_COMPOUND);
        var values = new ObjectArrayList<ItemDeliveryFragment>();
        for (int index = 0; index < tags.tagCount(); index++)
            values.add(fragments.decode(tags.getCompoundTagAt(index)));
        return values;
    }
}
