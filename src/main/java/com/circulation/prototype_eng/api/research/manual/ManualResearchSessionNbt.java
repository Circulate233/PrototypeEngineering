package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.UUID;

/**
 * Strict versioned checkpoints. Decoding preserves the exact state; world loading calls restored().
 */
@SuppressWarnings("unused")
public final class ManualResearchSessionNbt implements NbtCodec<ManualResearchSession> {
    private final ResearchRegistry registry;

    public ManualResearchSessionNbt(ResearchRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /**
     * Shared with the player's persistent material receipt: the whole intent remains unchanged.
     */
    public static NBTTagCompound encodeMaterialIntent(ManualResearchSession.MaterialIntent value) {
        Objects.requireNonNull(value);
        var tag = new NBTTagCompound();
        tag.setString("Operation", value.operationId().toString());
        tag.setString("Contributor", value.contributor().toString());
        tag.setLong("Sequence", value.sequence());
        tag.setTag("Pending", encodeWork(value.pendingWork()));
        tag.setTag("Expected", encodeWork(value.expectedWork()));
        return tag;
    }

    public static ManualResearchSession.MaterialIntent decodeMaterialIntent(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Operation", "Contributor", "Sequence", "Pending", "Expected");
        return new ManualResearchSession.MaterialIntent(uuid(StrictNbt.requireString(tag, "Operation")),
            uuid(StrictNbt.requireString(tag, "Contributor")), StrictNbt.requireLong(tag, "Sequence"),
            decodeWork(StrictNbt.requireList(tag, "Pending", Constants.NBT.TAG_COMPOUND)),
            decodeWork(StrictNbt.requireList(tag, "Expected", Constants.NBT.TAG_COMPOUND)));
    }

    public static NBTTagList encodeWork(Object2LongMap<ManualMaterialKey> work) {
        var list = new NBTTagList();
        for (var entry : ManualResearchSession.immutableWork(work).object2LongEntrySet()) {
            var tag = new NBTTagCompound();
            tag.setString("Requirement", entry.getKey().requirementId().toString());
            tag.setInteger("Input", entry.getKey().inputIndex());
            tag.setLong("Amount", entry.getLongValue());
            list.appendTag(tag);
        }
        return list;
    }

    public static Object2LongMap<ManualMaterialKey> decodeWork(NBTTagList list) {
        StrictNbt.requireListElements(list, Constants.NBT.TAG_COMPOUND);
        var amounts = new Object2LongOpenHashMap<ManualMaterialKey>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var tag = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(tag, "Requirement", "Input", "Amount");
            var key = new ManualMaterialKey(StrictNbt.requireId(tag, "Requirement"), StrictNbt.requireInt(tag, "Input"));
            if (amounts.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate saved manual material key");
            }
            amounts.put(key, StrictNbt.requireLong(tag, "Amount"));
        }
        return ManualResearchSession.immutableWork(amounts);
    }

    private static NBTTagCompound encodeCompletion(ManualResearchSession.CompletionIntent value) {
        var tag = new NBTTagCompound();
        tag.setLong("WorldTime", value.worldTime());
        var list = new NBTTagList();
        for (var entry : value.baselines().object2IntEntrySet()) {
            var baseline = new NBTTagCompound();
            baseline.setString("Player", entry.getKey().toString());
            baseline.setInteger("Level", entry.getIntValue());
            list.appendTag(baseline);
        }
        tag.setTag("Baselines", list);
        return tag;
    }

    private static ManualResearchSession.CompletionIntent decodeCompletion(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "WorldTime", "Baselines");
        var saved = StrictNbt.requireList(tag, "Baselines", Constants.NBT.TAG_COMPOUND);
        var baselines = new Object2IntOpenHashMap<UUID>(saved.tagCount());
        for (int index = 0; index < saved.tagCount(); index++) {
            var baseline = saved.getCompoundTagAt(index);
            StrictNbt.requireKeys(baseline, "Player", "Level");
            var player = uuid(StrictNbt.requireString(baseline, "Player"));
            if (baselines.containsKey(player)) {
                throw new IllegalArgumentException("Duplicate saved manual completion member");
            }
            baselines.put(player, StrictNbt.requireInt(baseline, "Level"));
        }
        return new ManualResearchSession.CompletionIntent(StrictNbt.requireLong(tag, "WorldTime"), baselines);
    }

    private static void validateWork(Object2LongMap<ManualMaterialKey> work, Object2LongMap<ManualMaterialKey> required) {
        for (var entry : work.object2LongEntrySet()) {
            if (!required.containsKey(entry.getKey()) || entry.getLongValue() > required.getLong(entry.getKey())) {
                throw new IllegalArgumentException("Saved manual work differs from research material definitions");
            }
        }
    }

    private static UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) {
            throw new IllegalArgumentException("Noncanonical manual research UUID");
        }
        return id;
    }

    @Override
    public NBTTagCompound encode(ManualResearchSession value) {
        validateDefinition(value);
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setString("Id", value.id().toString());
        tag.setString("Team", value.teamId());
        var key = new NBTTagCompound();
        key.setString("Research", value.queueKey().researchId().toString());
        key.setInteger("Level", value.queueKey().targetLevel());
        key.setLong("EntryId", value.queueKey().queueEntryId());
        tag.setTag("QueueKey", key);
        tag.setLong("LastActivity", value.lastActivity());
        tag.setString("Status", value.status().name());
        if (value.contributor() != null) {
            tag.setString("Contributor", value.contributor().toString());
        }
        if (value.pauseReason() != null) {
            tag.setString("PauseReason", value.pauseReason().name());
        }
        tag.setTag("Work", encodeWork(value.work()));
        if (value.materialIntent() != null) {
            tag.setTag("Material", encodeMaterialIntent(value.materialIntent()));
        }
        if (value.completionIntent() != null) {
            tag.setTag("Completion", encodeCompletion(value.completionIntent()));
        }
        return tag;
    }

    @Override
    public ManualResearchSession decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Id", "Team", "QueueKey", "LastActivity", "Status", "Contributor", "PauseReason", "Work", "Material", "Completion");
        if (StrictNbt.requireInt(tag, "Version") != 1) {
            throw new IllegalArgumentException("Unknown manual research save version");
        }
        var key = StrictNbt.requireCompound(tag, "QueueKey");
        StrictNbt.requireKeys(key, "Research", "Level", "EntryId");
        var saved = new ManualResearchSession(uuid(StrictNbt.requireString(tag, "Id")), StrictNbt.requireString(tag, "Team"),
            new TeamResearchQueue.Key(StrictNbt.requireId(key, "Research"), StrictNbt.requireInt(key, "Level"), StrictNbt.requireLong(key, "EntryId")),
            tag.hasKey("Contributor") ? uuid(StrictNbt.requireString(tag, "Contributor")) : null,
            StrictNbt.requireLong(tag, "LastActivity"), ManualResearchSession.Status.valueOf(StrictNbt.requireString(tag, "Status")),
            tag.hasKey("PauseReason") ? ManualResearchSession.PauseReason.valueOf(StrictNbt.requireString(tag, "PauseReason")) : null,
            decodeWork(StrictNbt.requireList(tag, "Work", Constants.NBT.TAG_COMPOUND)),
            tag.hasKey("Material") ? decodeMaterialIntent(StrictNbt.requireCompound(tag, "Material")) : null,
            tag.hasKey("Completion") ? decodeCompletion(StrictNbt.requireCompound(tag, "Completion")) : null);
        validateDefinition(saved);
        return saved;
    }

    private void validateDefinition(ManualResearchSession value) {
        var stage = registry.require(value.queueKey().researchId()).stageForLevel(value.queueKey().targetLevel());
        if (stage.researchTier() != 0) {
            throw new IllegalArgumentException("Manual session requires tier-zero research");
        }
        var required = new Object2LongOpenHashMap<ManualMaterialKey>();
        for (var requirement : stage.materialRequirements()) {
            var payload = (ResourceRequirement) requirement.payload();
            for (int index = 0; index < payload.inputs().size(); index++) {
                required.put(new ManualMaterialKey(requirement.id(), index), payload.inputs().get(index).amount());
            }
        }
        validateWork(value.work(), required);
        var material = value.materialIntent();
        if (material != null) {
            validateWork(material.pendingWork(), required);
            validateWork(material.expectedWork(), required);
        }
        if (value.completionIntent() != null) {
            var projected = material == null ? value.work() : material.expectedWork();
            for (var entry : required.object2LongEntrySet()) {
                if (projected.getLong(entry.getKey()) != entry.getLongValue()) {
                    throw new IllegalArgumentException("Manual completion has unfinished materials");
                }
            }
        }
    }
}
