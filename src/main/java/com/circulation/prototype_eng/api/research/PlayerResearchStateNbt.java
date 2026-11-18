package com.circulation.prototype_eng.api.research;


import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragment;
import com.circulation.prototype_eng.api.research.delivery.ItemDeliveryFragmentNbt;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardClaimer;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.UUID;

/**
 * Player progress, permissions and reward receipts.
 */
@SuppressWarnings("unused") // Public codec used by save owners.
public final class PlayerResearchStateNbt implements NbtCodec<PlayerResearchState.Snapshot> {
    private final ResearchRegistry research;
    private final ItemDeliveryFragmentNbt fragments;

    public PlayerResearchStateNbt(ResearchRegistry research, ResourceKeyNbt resources) {
        this.research = research;
        fragments = new ItemDeliveryFragmentNbt(resources);
    }

    private static long timestamp(NBTTagCompound tag, String name) {
        return tag.hasKey(name) ? tag.getLong(name) : -1;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static <E extends Enum<E>> E enumValue(E[] values, String name) {
        for (var value : values) if (value.name().equals(name)) return value;
        throw new IllegalArgumentException("Unknown saved state: " + name);
    }

    @Override
    public NBTTagCompound encode(PlayerResearchState.Snapshot value) {
        var tag = new NBTTagCompound();
        tag.setString("Player", value.playerUuid().toString());
        var levels = new NBTTagList();
        for (var entry : value.completedLevels().object2IntEntrySet()) {
            var level = new NBTTagCompound();
            level.setString("Research", entry.getKey().toString());
            level.setInteger("Level", entry.getIntValue());
            levels.appendTag(level);
        }
        tag.setTag("Completed", levels);
        var claims = new NBTTagList();
        var elements = value.rewardClaims();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var claim = elements.get(index1);
            claims.appendTag(encodeClaim(claim));
        }
        tag.setTag("Claims", claims);
        return tag;
    }

    @Override
    public PlayerResearchState.Snapshot decode(NBTTagCompound tag) {
        var player = uuid(tag.getString("Player"));
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        var savedLevels = tag.getTagList("Completed", Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < savedLevels.tagCount(); index++) {
            var entry = savedLevels.getCompoundTagAt(index);
            var id = new ResourceLocation(entry.getString("Research"));
            int level = entry.getInteger("Level");
            validateLevel(id, level);
            levels.put(id, level);
        }
        var claims = new ObjectArrayList<RewardClaim>();
        var savedClaims = tag.getTagList("Claims", Constants.NBT.TAG_COMPOUND);
        var keys = new ObjectOpenHashSet<RewardClaim.Key>(savedClaims.tagCount());
        for (int index = 0; index < savedClaims.tagCount(); index++) {
            var claim = decodeClaim(savedClaims.getCompoundTagAt(index));
            if (!claim.key().playerUuid().equals(player) || !keys.add(claim.key()))
                throw new IllegalArgumentException("Invalid or duplicate player reward claim");
            claims.add(claim);
        }
        return new PlayerResearchState.Snapshot(player, levels, claims);
    }

    public NBTTagCompound encodeClaim(RewardClaim claim) {
        var tag = new NBTTagCompound();
        tag.setString("Player", claim.key().playerUuid().toString());
        tag.setString("Research", claim.key().researchId().toString());
        tag.setInteger("Level", claim.key().level());
        tag.setString("Status", claim.status().name());
        tag.setLong("Eligible", claim.eligibleAtWorldTime());
        if ((claim.selectedGroupId() != null))
            tag.setString("Group", claim.selectedGroupId().toString());
        if (claim.claimingAtWorldTime() >= 0) tag.setLong("Claiming", claim.claimingAtWorldTime());
        if (claim.claimedAtWorldTime() >= 0) tag.setLong("Claimed", claim.claimedAtWorldTime());
        var children = new NBTTagList();
        var elements3 = claim.children();
        for (int index3 = 0; index3 < elements3.size(); index3++) {
            var child = elements3.get(index3);
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
        var key = new RewardClaim.Key(uuid(tag.getString("Player")), new ResourceLocation(tag.getString("Research")), tag.getInteger("Level"));
        var status = enumValue(RewardClaim.Status.VALUES, tag.getString("Status"));
        var group = tag.hasKey("Group") ? new ResourceLocation(tag.getString("Group")) : null;
        var children = new ObjectArrayList<RewardClaim.Child>();
        var savedChildren = tag.getTagList("Children", Constants.NBT.TAG_COMPOUND);
        if (savedChildren.tagCount() != 0 && group == null)
            throw new IllegalArgumentException("Selected reward children require a group");
        for (int index = 0; index < savedChildren.tagCount(); index++) {
            var saved = savedChildren.getCompoundTagAt(index);
            var parent = new RewardEventKey(key.playerUuid(), key.researchId(), key.level(), group, new ResourceLocation(saved.getString("Child")));
            var childStatus = enumValue(RewardClaim.ChildStatus.VALUES, saved.getString("Status"));
            var fragments = new Object2ObjectLinkedOpenHashMap<UUID, ItemDeliveryFragment>();
            var savedFragments = saved.getTagList("Fragments", Constants.NBT.TAG_COMPOUND);
            for (int fragmentIndex = 0; fragmentIndex < savedFragments.tagCount(); fragmentIndex++) {
                var fragment = this.fragments.decodePayload(savedFragments.getCompoundTagAt(fragmentIndex), parent);
                if (fragments.putIfAbsent(fragment.id(), fragment) != null)
                    throw new IllegalArgumentException("Duplicate reward fragment");
                if (childStatus == RewardClaim.ChildStatus.COMPLETED && fragment.status() != ItemDeliveryFragment.Status.CONFIRMED)
                    throw new IllegalArgumentException("Completed reward child retains an unconfirmed fragment");
            }
            children.add(new RewardClaim.Child(parent, enumValue(RewardClaim.Kind.VALUES, saved.getString("Kind")),
                childStatus, fragments));
        }
        var claim = new RewardClaim(key, status, tag.getLong("Eligible"), group, timestamp(tag, "Claiming"), children, timestamp(tag, "Claimed"));
        validateClaim(claim);
        return claim;
    }

    private void validateLevel(ResourceLocation id, int level) {
        var definition = research.require(id);
        if (level < 1 || level > definition.maxLevel() || definition.specialOnly()) {
            throw new IllegalArgumentException("Research level is invalid for player source/claim: " + id);
        }
    }

    private void validateClaim(RewardClaim claim) {
        validateLevel(claim.key().researchId(), claim.key().level());
        var choices = research.require(claim.key().researchId()).rewardGroupsForLevel(claim.key().level());
        if (choices.isEmpty()) throw new IllegalArgumentException("Reward claim has no registered reward level");
        if (claim.status() == RewardClaim.Status.UNCLAIMED) return;
        for (int index2 = 0; index2 < choices.size(); index2++) {
            var group = choices.get(index2);
            if (group.id().equals(claim.selectedGroupId()) && ResearchRewardClaimer.validRecovery(claim, group))
                return;
        }
        throw new IllegalArgumentException("Reward selection/children differ from registered definitions");
    }
}
