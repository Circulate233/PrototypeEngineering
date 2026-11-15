package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.download.ResearchItemImportContext;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.player.IPlayer;
import crafttweaker.api.world.IWorld;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

/**
 * Pure same-tick import callback view; player, world and material are borrowed read-only.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchImportContext")
@SuppressWarnings("unused")
public record ZenResearchImportContext(ResearchItemImportContext value) {
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
        return CraftTweakerMC.getIWorld(value.actor().getServerWorld());
    }

    @ZenGetter("dataCenterId")
    public String dataCenterId() {
        return value.dataCenterId().toString();
    }

    @ZenGetter("teamId")
    public String teamId() {
        return value.teamId();
    }

    @ZenGetter("operationId")
    public String operationId() {
        return value.operationId().toString();
    }
}
