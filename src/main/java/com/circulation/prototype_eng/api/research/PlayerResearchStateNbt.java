package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Versioned player persistence; a complete candidate is validated before publication.
 */
@SuppressWarnings("unused") // Public codec used by save owners.
public final class PlayerResearchStateNbt implements NbtCodec<PlayerResearchState.Snapshot> {
    private final ResearchRegistry research;
    private final ItemDeliveryFragmentNbt fragments;

    public PlayerResearchStateNbt(ResearchRegistry research, ResourceKeyNbt resources) {
        this.research = Objects.requireNonNull(research, "research");
        fragments = new ItemDeliveryFragmentNbt(Objects.requireNonNull(resources, "resources"));
    }

    private static OptionalLong optionalLong(NBTTagCompound tag, String name) {
        return tag.hasKey(name) ? OptionalLong.of(StrictNbt.requireLong(tag, name)) : OptionalLong.empty();
    }

    private static UUID uuid(String value) {
        var uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return uuid;
    }

    private static <E extends Enum<E>> E enumValue(E[] values, String name) {
        for (var value : values) if (value.name().equals(name)) return value;
        throw new IllegalArgumentException("Unknown saved state: " + name);
    }

    @Override
    public NBTTagCompound encode(PlayerResearchState.Snapshot value) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setString("Player", value.playerUuid().toString());
        var levels = new NBTTagList();
        for (var entry : value.completedLevels().object2IntEntrySet()) {
            validateLevel(entry.getKey(), entry.getIntValue(), true);
            var level = new NBTTagCompound();
            level.setString("Research", entry.getKey().toString());
            level.setInteger("Level", entry.getIntValue());
            levels.appendTag(level);
        }
        tag.setTag("Completed", levels);
        var claims = new NBTTagList();
        for (var claim : value.rewardClaims()) claims.appendTag(encodeClaim(claim));
        tag.setTag("Claims", claims);
        return tag;
    }

    @Override
    public PlayerResearchState.Snapshot decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Version", "Player", "Completed", "Claims");
        if (StrictNbt.requireInt(tag, "Version") != 1)
            throw new IllegalArgumentException("Unknown player research version");
        var player = uuid(StrictNbt.requireString(tag, "Player"));
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        var savedLevels = StrictNbt.requireList(tag, "Completed", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedLevels.tagCount(); index++) {
            var entry = savedLevels.getCompoundTagAt(index);
            StrictNbt.requireKeys(entry, "Research", "Level");
            var id = StrictNbt.requireId(entry, "Research");
            int level = StrictNbt.requireInt(entry, "Level");
            validateLevel(id, level, true);
            if (levels.containsKey(id)) throw new IllegalArgumentException("Duplicate completed research");
            levels.put(id, level);
        }
        var claims = new ObjectArrayList<RewardClaim>();
        var savedClaims = StrictNbt.requireList(tag, "Claims", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedClaims.tagCount(); index++)
            claims.add(decodeClaim(savedClaims.getCompoundTagAt(index)));
        return new PlayerResearchState.Snapshot(player, levels, claims);
    }

    public NBTTagCompound encodeClaim(RewardClaim claim) {
        validateClaim(claim);
        var tag = new NBTTagCompound();
        tag.setString("Player", claim.key().playerUuid().toString());
        tag.setString("Research", claim.key().researchId().toString());
        tag.setInteger("Level", claim.key().level());
        tag.setString("Status", claim.status().name());
        tag.setLong("Eligible", claim.eligibleAtWorldTime());
        if (claim.selectedGroupId().isPresent())
            tag.setString("Group", claim.selectedGroupId().orElseThrow().toString());
        if (claim.claimingAtWorldTime().isPresent()) tag.setLong("Claiming", claim.claimingAtWorldTime().getAsLong());
        if (claim.claimedAtWorldTime().isPresent()) tag.setLong("Claimed", claim.claimedAtWorldTime().getAsLong());
        var children = new NBTTagList();
        for (var child : claim.children()) {
            var saved = new NBTTagCompound();
            saved.setString("Child", child.key().childId().toString());
            saved.setString("Kind", child.kind().name());
            saved.setString("Status", child.status().name());
            var fragments = new NBTTagList();
            for (var fragment : child.fragments().values()) fragments.appendTag(this.fragments.encodePayload(fragment));
            saved.setTag("Fragments", fragments);
            children.appendTag(saved);
        }
        tag.setTag("Children", children);
        return tag;
    }

    public RewardClaim decodeClaim(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Player", "Research", "Level", "Status", "Eligible", "Group", "Claiming", "Claimed", "Children");
        var key = new RewardClaim.Key(uuid(StrictNbt.requireString(tag, "Player")), StrictNbt.requireId(tag, "Research"), StrictNbt.requireInt(tag, "Level"));
        var status = enumValue(RewardClaim.Status.values(), StrictNbt.requireString(tag, "Status"));
        var group = tag.hasKey("Group") ? Optional.of(StrictNbt.requireId(tag, "Group")) : Optional.<ResourceLocation>empty();
        var children = new ObjectArrayList<RewardClaim.Child>();
        var savedChildren = StrictNbt.requireList(tag, "Children", Constants.NBT.TAG_COMPOUND);
        if (savedChildren.tagCount() != 0 && group.isEmpty())
            throw new IllegalArgumentException("Selected reward children require a group");
        for (int index = 0; index < savedChildren.tagCount(); index++) {
            var saved = savedChildren.getCompoundTagAt(index);
            StrictNbt.requireKeys(saved, "Child", "Kind", "Status", "Fragments");
            var parent = new RewardEventKey(key.playerUuid(), key.researchId(), key.level(), group.orElseThrow(), StrictNbt.requireId(saved, "Child"));
            var fragments = new Object2ObjectLinkedOpenHashMap<UUID, ItemDeliveryFragment>();
            var savedFragments = StrictNbt.requireList(saved, "Fragments", Constants.NBT.TAG_COMPOUND);
            for (int fragmentIndex = 0; fragmentIndex < savedFragments.tagCount(); fragmentIndex++) {
                var fragment = this.fragments.decodePayload(savedFragments.getCompoundTagAt(fragmentIndex), parent);
                if (fragments.putIfAbsent(fragment.id(), fragment) != null)
                    throw new IllegalArgumentException("Duplicate reward fragment");
            }
            children.add(new RewardClaim.Child(parent, enumValue(RewardClaim.Kind.values(), StrictNbt.requireString(saved, "Kind")),
                enumValue(RewardClaim.ChildStatus.values(), StrictNbt.requireString(saved, "Status")), fragments));
        }
        var claim = new RewardClaim(key, status, StrictNbt.requireLong(tag, "Eligible"), group, optionalLong(tag, "Claiming"), children, optionalLong(tag, "Claimed"));
        validateClaim(claim);
        return claim;
    }

    private void validateLevel(ResourceLocation id, int level, boolean personalSource) {
        var definition = research.require(id);
        if (level < 1 || level > definition.maxLevel() || definition.specialOnly()
            || personalSource && definition.deliveryPolicy() != ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            throw new IllegalArgumentException("Research level is invalid for player source/claim: " + id);
        }
    }

    private void validateClaim(RewardClaim claim) {
        validateLevel(claim.key().researchId(), claim.key().level(), false);
        var choices = research.require(claim.key().researchId()).rewardGroupsForLevel(claim.key().level());
        if (choices.isEmpty()) throw new IllegalArgumentException("Reward claim has no registered reward level");
        if (claim.status() == RewardClaim.Status.UNCLAIMED) return;
        for (var group : choices) {
            if (group.id().equals(claim.selectedGroupId().orElseThrow()) && ResearchRewardClaimer.validRecovery(claim, group))
                return;
        }
        throw new IllegalArgumentException("Reward selection/children differ from registered definitions");
    }
}
