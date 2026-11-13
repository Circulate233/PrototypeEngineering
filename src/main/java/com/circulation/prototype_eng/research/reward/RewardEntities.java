package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.EntityEntryBuilder;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class RewardEntities {
    public static final ResourceLocation ID = new ResourceLocation(Tags.MOD_ID, "reward_item");

    private RewardEntities() {
    }

    @SubscribeEvent
    public static void register(RegistryEvent.Register<EntityEntry> event) {
        event.getRegistry().register(EntityEntryBuilder.create()
                                                       .entity(RewardItemEntity.class).id(ID, 0).name("reward_item").tracker(64, 20, true).build());
    }
}
