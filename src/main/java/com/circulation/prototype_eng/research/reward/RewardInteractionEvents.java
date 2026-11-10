package com.circulation.prototype_eng.research.reward;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.research.reward.RewardItemProtection;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerPickupXpEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Server player entry points that precede item consumption or pickup. Any protected inventory or
 * cursor stack blocks the complete action, including indirect consumption such as bow ammunition.
 * Native death and hopper paths have separate guards. Addons that mutate items directly must use
 * the shared protection query rather than relying on these player events.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
@SuppressWarnings("unused") // Forge discovers these static event subscribers.
public final class RewardInteractionEvents {
    private RewardInteractionEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (!event.getEntityPlayer().world.isRemote && RewardItemProtection.isProtected(event.getItem().getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExperiencePickup(PlayerPickupXpEvent event) {
        // This cancelable event precedes Mending's direct setItemDamage, XP grants and orb removal.
        if (blocksInventoryAction(event.getEntityPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        // The empty-space notifications are client-only and cannot be canceled.
        if (event.isCancelable() && blocksInventoryAction(event.getEntityPlayer())) {
            event.setCancellationResult(EnumActionResult.FAIL);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (blocksInventoryAction(event.getEntityPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (blocksInventoryAction(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onItemUse(LivingEntityUseItemEvent event) {
        // Finish is not cancelable and runs after onItemUseFinish has already applied its effects.
        if (!(event instanceof LivingEntityUseItemEvent.Start || event instanceof LivingEntityUseItemEvent.Tick
            || event instanceof LivingEntityUseItemEvent.Stop)) {
            return;
        }
        if (!(event.getEntityLiving() instanceof EntityPlayer player) || player.world.isRemote
            || !(RewardItemProtection.isProtected(event.getItem()) || RewardItemProtection.hasProtectedInventory(player))) {
            return;
        }
        if (event instanceof LivingEntityUseItemEvent.Tick) {
            // Cleanroom converts canceled Tick to duration -1, then calls onItemUseFinish for <= 0.
            // Clear the active stack first so that continuation cannot finish or release a bow shot.
            player.resetActiveHand();
        }
        event.setCanceled(true);
    }

    private static boolean blocksInventoryAction(EntityPlayer player) {
        return !player.world.isRemote && RewardItemProtection.hasProtectedInventory(player);
    }
}
