package com.circulation.prototype_eng.structure.tool;

import ae2.api.stacks.AEItemKey;
import ae2.items.misc.GenericResourcePackageItem;
import ae2.util.Platform;
import com.circulation.prototype_eng.Tags;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Captures drops from the current mining call and delivers them to AE and player inventory.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class BuilderDropRecovery implements AutoCloseable {
    private static final ThreadLocal<BuilderDropRecovery> ACTIVE = new ThreadLocal<>();
    private final @Nullable BuilderDropRecovery previous;
    private final EntityPlayerMP player;
    private final World world;
    private final BlockPos feet;
    private final @Nullable ConstructionMEAccess ae;
    private final @Nullable Consumer<ItemStack> overflow;
    private final ObjectArrayList<EntityItem> drops = new ObjectArrayList<>();

    private BuilderDropRecovery(EntityPlayerMP player, @Nullable ConstructionMEAccess ae, @Nullable Consumer<ItemStack> overflow) {
        this.player = player;
        world = player.world;
        feet = player.getPosition();
        this.ae = ae;
        this.overflow = overflow;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    static BuilderDropRecovery capture(EntityPlayerMP player, @Nullable ConstructionMEAccess ae, @Nullable Consumer<ItemStack> overflow) {
        return new BuilderDropRecovery(player, ae, overflow);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrop(EntityJoinWorldEvent event) {
        var capture = ACTIVE.get();
        if (capture == null || event.getWorld() != capture.world || !(event.getEntity() instanceof EntityItem item))
            return;
        capture.drops.add(item);
        event.setCanceled(true);
    }

    /**
     * Captures the recipient when hatch removal begins so delayed inventory returns reach the mining player.
     */
    public static Consumer<List<ItemStack>> destination(World world, BlockPos position) {
        var capture = ACTIVE.get();
        var recipient = capture != null && capture.world == world ? capture : null;
        return stacks -> {
            var current = ACTIVE.get();
            ACTIVE.remove();
            try {
                if (recipient == null) Platform.spawnDrops(world, position, stacks);
                else recipient.deliver(stacks);
            } finally {
                if (current != null) ACTIVE.set(current);
            }
        };
    }

    private boolean playerAvailable() {
        return player.isEntityAlive() && player.world == world && player.connection.netManager.isChannelOpen();
    }

    private void deliver(List<ItemStack> stacks) {
        boolean available = playerAvailable();
        var retain = overflow;
        if (!available && retain == null) {
            Platform.spawnDrops(world, feet, stacks);
            return;
        }
        for (int index1 = 0; index1 < stacks.size(); index1++) {
            var stack = stacks.get(index1);
            if (retain == null) give(player, insert(ae, stack), null);
            else {
                var remaining = available ? recover(player, ae, stack) : stack;
                if (!remaining.isEmpty()) retain.accept(remaining);
            }
        }
        player.inventory.markDirty();
    }

    @SuppressWarnings("DataFlowIssue")
    private static ItemStack insert(@Nullable ConstructionMEAccess ae, ItemStack stack) {
        if (ae == null || stack.isEmpty()) return stack;
        if (GenericResourcePackageItem.isPackage(stack)) {
            var resource = GenericResourcePackageItem.unwrap(stack);
            long inserted = ae.insert(resource.what(), resource.amount());
            return inserted == 0 ? stack : inserted == resource.amount() ? ItemStack.EMPTY
                : GenericResourcePackageItem.wrap(resource.what(), resource.amount() - inserted);
        }
        stack.shrink((int) ae.insert(AEItemKey.of(stack), stack.getCount()));
        return stack;
    }

    static ItemStack recover(EntityPlayer player, @Nullable ConstructionMEAccess ae, ItemStack stack) {
        return recoverInventory(player, insert(ae, stack));
    }

    private static ItemStack recoverInventory(EntityPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return stack;
        if (player.isCreative()) return ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.inventory), stack, false);
        player.inventory.addItemStackToInventory(stack);
        return stack;
    }

    static void give(EntityPlayer player, ItemStack stack, @Nullable Consumer<ItemStack> overflow) {
        if (stack.isEmpty()) return;
        if (overflow != null) {
            var remaining = recoverInventory(player, stack);
            if (!remaining.isEmpty()) overflow.accept(remaining);
            return;
        }
        player.inventory.addItemStackToInventory(stack);
        if (stack.isEmpty()) return;
        var entity = new EntityItem(player.world, player.posX, player.posY, player.posZ, stack);
        entity.motionX = entity.motionY = entity.motionZ = 0;
        entity.setDefaultPickupDelay();
        player.world.spawnEntity(entity);
    }

    @Override
    public void close() {
        ACTIVE.remove();
        try {
            boolean available = playerAvailable();
            var destination = available ? ae : null;
            for (int index = 0; index < drops.size(); index++) {
                var entity = drops.get(index);
                var retain = overflow;
                if (retain != null) {
                    var stack = entity.getItem();
                    var remaining = available ? recover(player, destination, stack) : stack;
                    if (!remaining.isEmpty()) retain.accept(remaining);
                    entity.setDead();
                    continue;
                }
                var remaining = insert(destination, entity.getItem());
                if (available) {
                    player.inventory.addItemStackToInventory(remaining);
                    entity.setPosition(player.posX, player.posY, player.posZ);
                } else entity.setPosition(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                if (remaining.isEmpty()) {
                    entity.setDead();
                    continue;
                }
                entity.setItem(remaining);
                entity.motionX = entity.motionY = entity.motionZ = 0;
                world.spawnEntity(entity);
            }
        } finally {
            drops.clear();
            if (previous != null) ACTIVE.set(previous);
        }
    }
}
