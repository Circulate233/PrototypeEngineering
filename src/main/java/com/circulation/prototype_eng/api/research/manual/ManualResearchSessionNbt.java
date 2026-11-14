package com.circulation.prototype_eng.api.research.manual;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.UUID;

/**
 * Versioned checkpoints. Decoding preserves the exact state; world loading calls restored().
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
        tag.setLong("Elapsed", value.elapsedTicks());
        tag.setLong("AdvancedAt", value.advancedAt());
        tag.setTag("Pending", encodeWork(value.pendingWork()));
        tag.setTag("Expected", encodeWork(value.expectedWork()));
        return tag;
    }

    public static ManualResearchSession.MaterialIntent decodeMaterialIntent(NBTTagCompound tag) {
        return new ManualResearchSession.MaterialIntent(uuid(tag.getString("Operation")),
            uuid(tag.getString("Contributor")), tag.getLong("Sequence"),
            decodeWork(tag.getTagList("Pending", Constants.NBT.TAG_COMPOUND)),
            decodeWork(tag.getTagList("Expected", Constants.NBT.TAG_COMPOUND)),
            tag.getLong("Elapsed"), tag.getLong("AdvancedAt"));
    }

    public static NBTTagList encodeWork(Object2LongMap<ManualMaterialKey> work) {
        var list = new NBTTagList();
        for (var entry : work.object2LongEntrySet()) {
            var tag = new NBTTagCompound();
            tag.setString("Requirement", entry.getKey().requirementId().toString());
            tag.setInteger("Input", entry.getKey().inputIndex());
            tag.setLong("Amount", entry.getLongValue());
            list.appendTag(tag);
        }
        return list;
    }

    public static Object2LongMap<ManualMaterialKey> decodeWork(NBTTagList list) {
        var amounts = new Object2LongOpenHashMap<ManualMaterialKey>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var tag = list.getCompoundTagAt(index);
            var key = new ManualMaterialKey(new ResourceLocation(tag.getString("Requirement")), tag.getInteger("Input"));
            if (amounts.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate saved manual material key");
            }
            amounts.put(key, tag.getLong("Amount"));
        }
        ManualResearchSession.validateWork(amounts);
        return amounts;
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
        var saved = tag.getTagList("Baselines", Constants.NBT.TAG_COMPOUND);
        var baselines = new Object2IntOpenHashMap<UUID>(saved.tagCount());
        for (int index = 0; index < saved.tagCount(); index++) {
            var baseline = saved.getCompoundTagAt(index);
            var player = uuid(baseline.getString("Player"));
            if (baselines.containsKey(player)) {
                throw new IllegalArgumentException("Duplicate saved manual completion member");
            }
            baselines.put(player, baseline.getInteger("Level"));
        }
        return new ManualResearchSession.CompletionIntent(tag.getLong("WorldTime"), baselines);
    }

    private static void validateWork(Object2LongMap<ManualMaterialKey> work, Object2LongMap<ManualMaterialKey> required) {
        for (var entry : work.object2LongEntrySet()) {
            if (!required.containsKey(entry.getKey()) || entry.getLongValue() > required.getLong(entry.getKey())) {
                throw new IllegalArgumentException("Saved manual work differs from research material definitions");
            }
        }
    }

    private static UUID uuid(String text) {
        return UUID.fromString(text);
    }

    @Override
    public NBTTagCompound encode(ManualResearchSession value) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 2);
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
        if (tag.getInteger("Version") != 2) {
            throw new IllegalArgumentException("Unknown manual research save version");
        }
        var key = tag.getCompoundTag("QueueKey");
        var saved = new ManualResearchSession(uuid(tag.getString("Id")), tag.getString("Team"),
            new TeamResearchQueue.Key(new ResourceLocation(key.getString("Research")), key.getInteger("Level"), key.getLong("EntryId")),
            tag.hasKey("Contributor") ? uuid(tag.getString("Contributor")) : null,
            tag.getLong("LastActivity"), ManualResearchSession.Status.valueOf(tag.getString("Status")),
            tag.hasKey("PauseReason") ? ManualResearchSession.PauseReason.valueOf(tag.getString("PauseReason")) : null,
            decodeWork(tag.getTagList("Work", Constants.NBT.TAG_COMPOUND)),
            tag.hasKey("Material") ? decodeMaterialIntent(tag.getCompoundTag("Material")) : null,
            tag.hasKey("Completion") ? decodeCompletion(tag.getCompoundTag("Completion")) : null);
        validateDefinition(saved);
        return saved;
    }

    private static Object2LongOpenHashMap<ManualMaterialKey> requiredMaterials(ResearchLevelStage stage) {
        var required = new Object2LongOpenHashMap<ManualMaterialKey>();
        var elements = stage.materialRequirements();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var requirement = elements.get(index1);
            var payload = (ResourceRequirement) requirement.payload();
            for (int index = 0; index < payload.inputs().size(); index++) {
                required.put(new ManualMaterialKey(requirement.id(), index), payload.inputs().get(index).amount());
            }
        }
        return required;
    }

    private void validateDefinition(ManualResearchSession value) {
        var stage = registry.require(value.queueKey().researchId()).stageForLevel(value.queueKey().targetLevel());
        if (stage.researchTier() != 0) {
            throw new IllegalArgumentException("Manual session requires tier-zero research");
        }
        var required = requiredMaterials(stage);
        validateWork(value.work(), required);
        var material = value.materialIntent();
        if (material != null) {
            if (material.elapsedTicks() > stage.durationTicks() || material.advancedAt() > value.lastActivity())
                throw new IllegalArgumentException("Manual material intent exceeds research time");
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
