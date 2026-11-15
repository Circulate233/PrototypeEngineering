package com.circulation.prototype_eng.structure.tool;

import ae2.api.config.Actionable;
import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AmountFormat;
import ae2.api.stacks.GenericStack;
import ae2.core.gui.locator.GuiHostLocators;
import com.circulation.prototype_eng.item.BuilderInstrumentItem;
import com.circulation.prototype_eng.BuilderConfig;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.StructureMaterials;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import com.circulation.prototype_eng.structure.tool.ConstructionProgress.Step;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-owned builder tasks: one per player, configurable batches and inventory-safe payments.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class StructureBuildTasks {
    private static final Map<UUID, Task> TASKS = new Object2ObjectOpenHashMap<>();

    private StructureBuildTasks() {
    }

    public static void toggle(EntityPlayer player, int slot, ItemStack tool) {
        if (!(player instanceof EntityPlayerMP) || player.world.isRemote) return;
        var previous = TASKS.get(player.getUniqueID());
        var tag = BuilderState.data(tool);
        if (previous != null && previous.tool == tool && previous.valid(player)) {
            var state = BuilderState.state(tag);
            BuilderState.state(tag, previous.dismantle
                ? (state.paused() ? BuilderState.State.DISASSEMBLING : BuilderState.State.PAUSED_DISASSEMBLING)
                : (state.paused() ? BuilderState.State.BUILDING : BuilderState.State.PAUSED_BUILDING));
            player.inventory.markDirty();
            return;
        }
        var pos = BuilderState.position(tag);
        var anchor = ConstructionTarget.resolve(player.world, pos);
        if (!tag.getBoolean("Bound") || tag.getInteger("BoundDim") != player.world.provider.getDimension() || anchor == null) {
            player.sendStatusMessage(StructureToolText.Unavailable.text(), true);
            return;
        }
        var open = player.openContainer;
        anchor.atBoundary(() -> {
            var host = ConstructionTarget.resolve(player.world, pos);
            if (host == null || !anchor.current() || !host.binding().toString().equals(tag.getString("BoundMachineId"))
                || !host.structure().toString().equals(tag.getString("BoundStructureId"))) return;
            if (player.openContainer != open || player.inventory.getStackInSlot(slot) != tool || !open.canInteractWith(player)
                || !BuilderState.position(BuilderState.data(tool)).equals(pos))
                return;
            var server = player.getServer();
            if (server == null) return;
            var view = ServerStructureReloads.view(server, MachineTypeRegistry.INSTANCE.structureCatalog());
            if (view == null || !view.entries().containsKey(host.structure())) {
                player.sendStatusMessage(StructureToolText.Unavailable.text(), true);
                return;
            }
            var old = TASKS.remove(player.getUniqueID());
            if (old != null) BuilderState.state(BuilderState.data(old.tool), BuilderState.State.IDLE);
            var plan = StructureBuildPlan.expand(view, host.structure(), host.orientation(), BuilderState.sliceCounts(BuilderState.data(tool)));
            var rotation = StructureTransform.between(view.nodes().get(view.entries().get(host.structure()).node()).orientation(), host.orientation());
            boolean dismantle = host.formed();
            var targets = new ObjectArrayList<Target>();
            boolean unresolved = false;
            var elements3 = plan.cells();
            for (int index3 = 0; index3 < elements3.size(); index3++) {
                var cell = elements3.get(index3);
                var position = StructureBuildPlan.add(pos, cell.position());
                if (dismantle) targets.add(new Target(position, null));
                else if (cell.display() != null) {
                    if (!cell.display().complete()) {
                        unresolved = true;
                        continue;
                    }
                    var choice = BuilderState.choice(tag, cell.display(), cell.position(), host.orientation());
                    if (!Block.REGISTRY.containsKey(choice.blockId())) {
                        unresolved = true;
                        continue;
                    }
                    var block = Block.REGISTRY.getObject(choice.blockId());
                    @SuppressWarnings("deprecation") var resolved = choice.resolveProperties(block.getStateFromMeta(choice.meta()));
                    if (resolved == null) {
                        unresolved = true;
                        continue;
                    }
                    targets.add(new Target(position, StructureToolExport.rotateState(resolved, rotation), cell.display().tileNbt()));
                } else unresolved = true;
            }
            if (unresolved) {
                player.sendStatusMessage(StructureToolText.BuildInstrumentReqUnknown.text(), true);
                if (targets.isEmpty() || BuilderState.Setting.REQUIRE_MATERIALS.enabled(tag)) {
                    BuilderState.state(tag, BuilderState.State.IDLE);
                    BuilderState.progress(tag, 0, 0);
                    player.inventory.markDirty();
                    return;
                }
            }
            Comparator<Target> order = Comparator.comparingInt((Target target) -> target.position().getY())
                                                 .thenComparingInt(target -> target.position().getX()).thenComparingInt(target -> target.position().getZ());
            targets.sort(dismantle ? order.reversed() : Comparator.comparing((Target target) -> target.state() != null && (target.state().getMaterial().isLiquid()
                || target.state().getBlock() instanceof BlockFalling)).thenComparing(order));
            var me = BuilderState.meMode(tag) == 0 ? null
                : ((BuilderInstrumentItem) tool.getItem()).getGuiHost(player, GuiHostLocators.forInventorySlot(slot), null);
            if (!dismantle) {
                var ready = prepare(player, tool, targets, me);
                if (ready == null) return;
                targets = ready;
            }
            var task = new Task(player, slot, tool, host, dismantle, targets, me);
            TASKS.put(player.getUniqueID(), task);
            BuilderState.state(tag, dismantle ? BuilderState.State.DISASSEMBLING : BuilderState.State.BUILDING);
            BuilderState.progress(tag, 0, targets.size());
            player.inventory.markDirty();
        });
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) return;
        var player = event.player;
        var task = TASKS.get(player.getUniqueID());
        if (task == null) return;
        var tag = BuilderState.data(task.tool);
        if (!task.valid(player)) {
            if (TASKS.remove(player.getUniqueID(), task)) BuilderState.state(tag, BuilderState.State.IDLE);
            return;
        }
        if (BuilderState.state(tag).paused() || task.world.getTotalWorldTime() % BuilderConfig.interval() != 0) return;
        task.waitingMaterial = null;
        task.progress.advance(BuilderConfig.batch(), index -> task.valid(player) ? operate(player, task, task.targets.get(index)) : Step.WAIT);
        if (!task.valid(player)) {
            if (TASKS.remove(player.getUniqueID(), task)) BuilderState.state(tag, BuilderState.State.IDLE);
            return;
        }
        BuilderState.progress(tag, task.progress.done(), task.targets.size());
        player.inventory.markDirty();
        if (task.progress.done() == task.targets.size()) {
            TASKS.remove(player.getUniqueID());
            BuilderState.state(tag, BuilderState.State.IDLE);
            player.sendStatusMessage(StructureToolText.Complete.text(), true);
        } else if (task.world.getTotalWorldTime() % 40 == 0) {
            if (task.progress.waiting() && task.waitingMaterial != null)
                player.sendStatusMessage(StructureToolText.BuilderWaitingMaterial.text(task.waitingMaterial.what().getDisplayName()), true);
            else player.sendStatusMessage(StructureToolText.Waiting.text(), true);
        }
    }

    private static @Nullable ObjectArrayList<Target> prepare(EntityPlayer player, ItemStack tool, List<Target> targets, @Nullable ConstructionMEAccess me) {
        var tag = BuilderState.data(tool);
        var candidates = new ObjectArrayList<Target>(targets.size());
        var resources = new ObjectArrayList<GenericStack>(targets.size());
        int blocked = 0;
        for (int index2 = 0; index2 < targets.size(); index2++) {
            var target = targets.get(index2);
            if (player.world.isOutsideBuildHeight(target.position()) || !player.world.isBlockLoaded(target.position(), false)) {
                blocked++;
                continue;
            }
            var current = player.world.getBlockState(target.position());
            if (current.equals(target.state())) continue;
            if (!placeable(player, tool, target.position(), current)) {
                blocked++;
                continue;
            }
            var resource = StructureMaterials.resource(player.world, target.position(), target.state(), player, target.tileNbt());
            if (resource == null) {
                player.sendMessage(StructureToolText.BuildInstrumentReqUnknown.text());
                return null;
            }
            candidates.add(target);
            resources.add(resource);
        }
        if (blocked > 0) {
            player.sendMessage(StructureToolText.BuilderBlocked.text(blocked));
        }
        if (player.isCreative())
            return blocked > 0 && BuilderState.Setting.REQUIRE_SPACE.enabled(tag) ? null : candidates;
        var inventory = new ConstructionInventory(player);
        if (me != null && me.connect() == null) player.sendMessage(me.unavailableReason());
        var result = ConstructionPreflight.allocate(resources, required -> {
            long local = inventory.take(required);
            return local + (me == null ? 0 : me.extract(required.what(), required.amount() - local, Actionable.SIMULATE));
        });
        reportMissing(player, result.missing());
        var admitted = new ObjectArrayList<Target>(result.positions().size());
        var elements = result.positions();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            int index = elements.getInt(index1);
            admitted.add(candidates.get(index));
        }
        boolean requireAll = BuilderState.Setting.REQUIRE_MATERIALS.enabled(tag);
        if (!result.missing().isEmpty() && BuilderState.meMode(tag) == 2 && me != null) {
            var craft = new ObjectArrayList<GenericStack>(result.missing().size());
            result.missing().forEachEntry((key, amount) -> {
                if (me.craftable(key)) craft.add(new GenericStack(key, amount));
            });
            if (!craft.isEmpty() && !me.openCrafting(craft))
                player.sendMessage(StructureToolText.BuilderMeUnavailable.text());
        }
        if (requireAll && !result.missing().isEmpty() || blocked > 0 && BuilderState.Setting.REQUIRE_SPACE.enabled(tag))
            return null;
        if (BuilderState.Setting.WAIT_MATERIALS.enabled(tag)) return candidates;
        if (admitted.size() < candidates.size())
            player.sendMessage(StructureToolText.BuilderSkipped.text(candidates.size() - admitted.size()));
        return admitted;
    }

    private static void reportMissing(EntityPlayer player, AEKey2LongMap missing) {
        if (missing.isEmpty()) return;
        player.sendMessage(StructureToolText.BuilderMissing.text());
        missing.forEachEntry((key, amount) -> player.sendMessage(StructureToolText.BuilderMissingEntry.text(
            key.getDisplayName(), key.formatAmount(amount, AmountFormat.FULL))));
    }

    private static boolean placeable(EntityPlayer player, ItemStack tool, BlockPos pos, IBlockState current) {
        if (!player.world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos, EnumFacing.UP, tool))
            return false;
        if (current.getBlock().isAir(current, player.world, pos)) return true;
        var tag = BuilderState.data(tool);
        if (BuilderState.Setting.REQUIRE_SPACE.enabled(tag))
            return BuilderState.Setting.REPLACE_FLUIDS.enabled(tag) && fluid(current);
        return canRemove(player, player.world, pos, current, tool);
    }

    private static boolean fluid(IBlockState state) {
        return state.getBlock() instanceof IFluidBlock || state.getBlock() instanceof BlockLiquid || state.getMaterial().isLiquid();
    }

    private static boolean canRemove(EntityPlayer player, World world, BlockPos pos, IBlockState state, ItemStack tool) {
        if (state.getBlockHardness(world, pos) < 0) return false;
        if (player.isCreative() || fluid(state)) return true;
        @SuppressWarnings("deprecation") // Forge's harvest query uses the actual state on this Minecraft version.
        var actual = state.getBlock().getActualState(state, world, pos);
        return BuilderMiningTool.canHarvest(tool, actual, player);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        var task = TASKS.remove(event.player.getUniqueID());
        if (task != null) BuilderState.state(BuilderState.data(task.tool), BuilderState.State.IDLE);
    }

    @SubscribeEvent
    public static void unload(WorldEvent.Unload event) {
        TASKS.values().removeIf(task -> {
            if (task.world != event.getWorld()) return false;
            BuilderState.state(BuilderState.data(task.tool), BuilderState.State.IDLE);
            return true;
        });
    }

    private static Step operate(EntityPlayer player, Task task, Target target) {
        var world = task.world;
        var tool = task.tool;
        var tag = BuilderState.data(tool);
        var pos = target.position();
        if (world.isOutsideBuildHeight(pos) || !world.isBlockLoaded(pos, false)) {
            if (target.state() != null && !BuilderState.Setting.REQUIRE_SPACE.enabled(tag)) {
                player.sendMessage(StructureToolText.BuilderBlocked.text(1));
                return Step.COMPLETE;
            }
            return Step.RETRY;
        }
        var current = world.getBlockState(pos);
        if (task.progress.firstPass() && !fluid(current)) return Step.DEFER;
        if (!world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos, EnumFacing.UP, tool)) {
            if (target.state() != null && !BuilderState.Setting.REQUIRE_SPACE.enabled(tag)) {
                player.sendMessage(StructureToolText.BuilderBlocked.text(1));
                return Step.COMPLETE;
            }
            return Step.RETRY;
        }
        var desired = target.state();
        if (desired == null && world.isAirBlock(pos) || current.equals(desired)) return Step.COMPLETE;
        if (desired == null || task.progress.firstPass())
            return remove(player, task, pos, current) ? desired == null ? Step.COMPLETE : Step.DEFER : Step.RETRY;
        if (!placeable(player, tool, pos, current)) {
            if (!BuilderState.Setting.REQUIRE_SPACE.enabled(tag)) {
                player.sendMessage(StructureToolText.BuilderBlocked.text(1));
                return Step.COMPLETE;
            }
            return Step.RETRY;
        }
        var resource = player.isCreative() ? null : StructureMaterials.resource(world, pos, desired, player, target.tileNbt());
        var payment = player.isCreative() ? ConstructionPayment.FREE
            : resource == null ? null : ConstructionPayment.prepare((EntityPlayerMP) player, resource, task.me);
        if (payment == null) {
            if (resource == null) return Step.RETRY;
            if (BuilderState.Setting.REQUIRE_MATERIALS.enabled(tag) || BuilderState.Setting.WAIT_MATERIALS.enabled(tag)) {
                task.waitingMaterial = resource;
                return Step.WAIT;
            }
            long amount = ConstructionPayment.missingAmount((EntityPlayerMP) player, resource, task.me);
            if (amount <= 0) return Step.RETRY;
            var missing = new AEKey2LongMap.OpenHashMap(1);
            missing.addTo(resource.what(), amount);
            reportMissing(player, missing);
            player.sendMessage(StructureToolText.BuilderSkipped.text(1));
            return Step.COMPLETE;
        }
        var snapshot = BlockSnapshot.getBlockSnapshot(world, pos);
        boolean placed = false;
        boolean finished = false;
        try {
            // Escrow the material before breaking an obstruction; callbacks can still invalidate the task.
            if (!payment.commit() || !task.valid(player) || !world.getBlockState(pos).equals(current))
                return Step.RETRY;
            if (!world.isAirBlock(pos)) {
                if (!BuilderState.Setting.REQUIRE_SPACE.enabled(tag)) {
                    if (!remove(player, task, pos, current)) {
                        player.sendMessage(StructureToolText.BuilderBlocked.text(1));
                        return Step.COMPLETE;
                    }
                    if (!task.valid(player) || !world.isAirBlock(pos)) return Step.RETRY;
                    // A mined block has already produced drops. Roll back placement only to the cleared position.
                    snapshot = BlockSnapshot.getBlockSnapshot(world, pos);
                } else {
                    // Ignored world fluid is replaced without harvesting it.
                    var event = new BlockEvent.BreakEvent(world, pos, current, player);
                    event.setExpToDrop(0);
                    if (MinecraftForge.EVENT_BUS.post(event) || !world.getBlockState(pos).equals(current) || !task.valid(player))
                        return Step.RETRY;
                }
            }
            placed = true;
            if (!world.setBlockState(pos, desired, 2)) return Step.RETRY;
            if (ForgeEventFactory.onPlayerBlockPlace(player, snapshot, EnumFacing.UP, EnumHand.MAIN_HAND).isCanceled()
                || !task.valid(player) || !world.getBlockState(pos).equals(desired)) return Step.RETRY;
            world.notifyNeighborsOfStateChange(pos, desired.getBlock(), true);
            finished = true;
            payment.finish();
            return Step.COMPLETE;
        } finally {
            if (!finished) {
                try {
                    if (placed) snapshot.restore(true, false);
                } finally {
                    payment.refund(player);
                }
            }
        }
    }

    private static boolean remove(EntityPlayer player, Task task, BlockPos pos, IBlockState state) {
        var world = task.world;
        var tool = task.tool;
        var block = state.getBlock();
        boolean fluid = fluid(state);
        if (!canRemove(player, world, pos, state, tool)) return false;
        var harvestedWith = BuilderMiningTool.profile(tool);
        var event = new BlockEvent.BreakEvent(world, pos, state, player);
        // The task's builder may be in the offhand or another slot. Do not use an unrelated main-hand enchantment.
        event.setExpToDrop(player.isCreative() || fluid
            || block.canSilkHarvest(world, pos, state, player) && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, harvestedWith) > 0
            ? 0 : block.getExpDrop(state, world, pos, EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, harvestedWith)));
        if (MinecraftForge.EVENT_BUS.post(event) || !world.getBlockState(pos).equals(state) || !task.valid(player))
            return false;
        if (fluid && !player.isCreative()) {
            var source = FluidUtil.getFluidHandler(world, pos, EnumFacing.UP);
            if (source == null) return false;
            var available = source.drain(Integer.MAX_VALUE, false);
            // Flowing fluid has no independently drainable source.
            if (available == null || available.amount <= 0) return world.setBlockToAir(pos);
            var drained = source.drain(available, true);
            if (drained == null || drained.amount <= 0) return false;
            BuilderFluidRecovery.deliver((EntityPlayerMP) player, drained, task.me);
            // Successful extraction completes this position so neighbor refill cannot produce another recovery.
            return true;
        }
        try (var _ = BuilderDropRecovery.capture((EntityPlayerMP) player, task.me)) {
            var tile = world.getTileEntity(pos);
            if (!block.removedByPlayer(state, world, pos, player, !player.isCreative())) return false;
            block.onPlayerDestroy(world, pos, state);
            world.playEvent(player, 2001, pos, Block.getStateId(state));
            if (!player.isCreative()) {
                block.harvestBlock(world, player, pos, state, tile, harvestedWith);
                if (event.getExpToDrop() > 0) block.dropXpOnBlockBreak(world, pos, event.getExpToDrop());
            }
        }
        return true;
    }

    private record Target(BlockPos position, @Nullable IBlockState state, @Nullable String tileNbt) {
        Target(BlockPos position, @Nullable IBlockState state) {
            this(position, state, null);
        }
    }

    private static final class Task {
        final World world;
        final int slot;
        final ItemStack tool;
        final ConstructionTarget controller;
        final BlockOrientation orientation;
        final boolean dismantle;
        final List<Target> targets;
        final ConstructionProgress progress;
        final @Nullable ConstructionMEAccess me;
        @Nullable GenericStack waitingMaterial;

        Task(EntityPlayer player, int slot, ItemStack tool, ConstructionTarget controller, boolean dismantle, List<Target> targets, @Nullable ConstructionMEAccess me) {
            world = player.world;
            this.slot = slot;
            this.tool = tool;
            this.controller = controller;
            orientation = controller.orientation();
            this.dismantle = dismantle;
            this.targets = targets;
            this.me = me;
            progress = dismantle || !BuilderState.Setting.REQUIRE_SPACE.enabled(BuilderState.data(tool))
                ? new ConstructionProgress(targets.size(), index -> {
                var pos = targets.get(index).position();
                return !world.isBlockLoaded(pos, false) || fluid(world.getBlockState(pos));
            }) : new ConstructionProgress(targets.size());
        }

        boolean valid(EntityPlayer player) {
            var tag = BuilderState.data(tool);
            var pos = controller.position();
            return player.isEntityAlive() && player.world == world && player.inventory.getStackInSlot(slot) == tool
                && !tool.isEmpty() && controller.current() && tag.getBoolean("Bound")
                && tag.getInteger("BoundDim") == world.provider.getDimension() && BuilderState.position(tag).equals(pos)
                && tag.getString("BoundMachineId").equals(controller.binding().toString())
                && tag.getString("BoundStructureId").equals(controller.structure().toString())
                && BuilderState.state(tag) != BuilderState.State.IDLE;
        }
    }

}
