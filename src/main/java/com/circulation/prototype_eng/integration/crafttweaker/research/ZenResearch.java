package com.circulation.prototype_eng.integration.crafttweaker.research;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Locale;

/**
 * A Java definition handle, including complete stage, unlock and eligibility behavior.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.research.Research")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearch {
    private final ResearchDefinition.Builder builder;
    private final ResourceLocation id;
    private final int maxLevel;
    private ResearchDefinition value;

    public ZenResearch(ResearchDefinition value) {
        this.value = value;
        builder = null;
        id = value.id();
        maxLevel = value.maxLevel();
    }

    private ZenResearch(ResearchDefinition.Builder builder, ResourceLocation id, int maxLevel) {
        this.builder = builder;
        this.id = id;
        this.maxLevel = maxLevel;
    }

    @ZenMethod
    public static ZenResearch create(String id, int maxLevel, boolean specialOnly, String deliveryPolicy, int itemDownloadLevelCost, ZenResearchStage[] stages, ZenResearchStartCondition condition) {
        var location = new ResourceLocation(id);
        var builder = ResearchDefinition.builder(location).maxLevel(maxLevel).specialOnly(specialOnly)
                                        .deliveryPolicy(ResearchDownloadDeliveryPolicy.valueOf(deliveryPolicy.toUpperCase(Locale.ROOT))).itemDownloadLevelCost(itemDownloadLevelCost);
        for (var stage : stages) builder.stage(stage.value());
        builder.startCondition(context -> condition.canStart(new ZenResearchStartContext(context)));
        return new ZenResearch(builder, location, maxLevel);
    }

    @ZenMethod
    public static ZenResearchStartCondition alwaysAllowed() {
        return _ -> true;
    }

    @ZenMethod
    public ZenResearch unlock(String effectId, String targetId, int level) {
        if (value != null) throw new IllegalStateException("Research definition is already captured");
        builder.unlock(new ResourceLocation(effectId), new ResourceLocation(targetId), level);
        return this;
    }

    @ZenMethod
    public ZenResearch display(String translationKey, IItemStack icon) {
        if (value != null) throw new IllegalStateException("Research definition is already captured");
        builder.displayName(() -> translationKey).icon(AEItemKey.of(CraftTweakerMC.getItemStack(icon)));
        return this;
    }

    @ZenMethod
    public void register() {
        var definition = value();
        DefinitionRegistrations.research(event -> event.registry().register(definition));
    }

    @ZenGetter("id")
    public String id() {
        return id.toString();
    }

    @ZenGetter("maxLevel")
    public int maxLevel() {
        return maxLevel;
    }

    public ResearchDefinition value() {
        if (value == null) value = builder.build();
        return value;
    }
}
