package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.reward.ResearchRewardContext;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;


/**
 * Stable child identity survives retries and restarts; no persistent state is exposed.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchRewardEvent")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public record ZenResearchRewardEvent(RewardEventKey key, ResearchRewardContext context) {

    @ZenGetter("playerId")
    public String playerId() {
        return key.playerUuid().toString();
    }

    @ZenGetter("researchId")
    public String researchId() {
        return key.researchId().toString();
    }

    @ZenGetter("level")
    public int level() {
        return key.level();
    }

    @ZenGetter("groupId")
    public String groupId() {
        return key.groupId().toString();
    }

    @ZenGetter("childId")
    public String childId() {
        return key.childId().toString();
    }

    @ZenGetter("worldTime")
    public long worldTime() {
        return context.currentWorldTime();
    }
}
