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
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 只捕获当前挖掘调用产生的物品，结束捕获后按 AE、背包、玩家脚下的顺序交付。
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class BuilderDropRecovery implements AutoCloseable {
    private static final ThreadLocal<BuilderDropRecovery> ACTIVE = new ThreadLocal<>();
    private final @Nullable BuilderDropRecovery previous;
    private final EntityPlayerMP player;
    private final World world;
    private final BlockPos feet;
    private final boolean useAe;
    private final ObjectArrayList<EntityItem> drops = new ObjectArrayList<>();

    private BuilderDropRecovery(EntityPlayerMP player, boolean useAe) {
        this.player = player;
        world = player.world;
        feet = player.getPosition();
        this.useAe = useAe;
        previous = ACTIVE.get();
        ACTIVE.set(this);
    }

    static BuilderDropRecovery capture(EntityPlayerMP player, boolean useAe) {
        return new BuilderDropRecovery(player, useAe);
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
     * 在仓室开始移除时取得交付函数，延迟归还的库存继续交给此次挖掘的玩家。
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
        if (!playerAvailable()) {
            Platform.spawnDrops(world, feet, stacks);
            return;
        }
        var ae = useAe ? ConstructionMEAccess.find(player) : null;
        for (var stack : stacks) give(player, insert(ae, stack));
        player.inventory.markDirty();
    }

    private static ItemStack insert(@Nullable ConstructionMEAccess ae, ItemStack stack) {
        if (ae == null || stack.isEmpty()) return stack;
        if (GenericResourcePackageItem.isPackage(stack)) {
            var resource = Objects.requireNonNull(GenericResourcePackageItem.unwrap(stack), "Invalid harvested resource package");
            long inserted = ae.insert(resource.what(), resource.amount());
            return inserted == 0 ? stack : inserted == resource.amount() ? ItemStack.EMPTY
                : GenericResourcePackageItem.wrap(resource.what(), resource.amount() - inserted);
        }
        stack.shrink((int) ae.insert(Objects.requireNonNull(AEItemKey.of(stack)), stack.getCount()));
        return stack;
    }

    static void give(EntityPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
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
            var ae = useAe && available ? ConstructionMEAccess.find(player) : null;
            for (var entity : drops) {
                var remaining = insert(ae, entity.getItem());
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
