package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadContext;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.player.IPlayer;
import crafttweaker.api.world.IWorld;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;


/**
 * Authoritative same-tick download context; callbacks must not mutate inventories or the world.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchDownloadContext")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public record ZenResearchDownloadContext(ResearchItemDownloadContext value) {

    @ZenGetter("research")
    public ZenResearch research() {
        return new ZenResearch(value.research());
    }

    @ZenGetter("level")
    public int level() {
        return value.downloadLevel();
    }

    @ZenGetter("material")
    public IItemStack material() {
        return CraftTweakerMC.getIItemStack(value.material());
    }

    @ZenGetter("actor")
    public IPlayer actor() {
        return CraftTweakerMC.getIPlayer(value.actor());
    }

    @ZenGetter("world")
    public IWorld world() {
        return CraftTweakerMC.getIWorld(value.world());
    }

    @ZenGetter("dataCenterId")
    public String dataCenterId() {
        return value.activeDataCenterMachineId().toString();
    }

    @ZenGetter("teamId")
    public String teamId() {
        return value.teamKey();
    }

    @ZenGetter("worldTick")
    public long worldTick() {
        return value.worldTick();
    }

    @ZenGetter("operationId")
    public String operationId() {
        return value.operationId().toString();
    }
}
