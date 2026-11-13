package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.BuilderConfig;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.structure.StructureMaterials;
import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.AmountFormat;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Enchantments;
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
import java.util.Objects;
import java.util.Map;
import java.util.UUID;

/** Server-owned builder tasks: one per player, configurable batches and inventory-safe payments. */
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
            for (var cell : plan.cells()) {
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
            if (!dismantle) {
                var ready = prepare(player, tool, targets);
                if (ready == null) return;
                targets = ready;
            }
            var task = new Task(player, slot, tool, host, dismantle, List.copyOf(targets));
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
            TASKS.remove(player.getUniqueID());
            BuilderState.state(tag, BuilderState.State.IDLE);
            return;
        }
        if (BuilderState.state(tag).paused() || task.world.getTotalWorldTime() % BuilderConfig.interval() != 0) return;
        for (int visits = 0, attempts = 0; visits < task.targets.size() && attempts < BuilderConfig.batch() && task.done < task.targets.size(); visits++) {
            int index = task.cursor++;
            if (task.cursor == task.targets.size()) task.cursor = 0;
            if (task.complete[index]) continue;
            attempts++;
            var target = task.targets.get(index);
            if (operate(player, task, target)) {
                task.complete[index] = true;
                task.done++;
            }
        }
        BuilderState.progress(tag, task.done, task.targets.size());
        player.inventory.markDirty();
        if (task.done == task.targets.size()) {
            TASKS.remove(player.getUniqueID());
            BuilderState.state(tag, BuilderState.State.IDLE);
            player.sendStatusMessage(StructureToolText.Complete.text(), true);
        } else if (task.world.getTotalWorldTime() % 40 == 0)
            player.sendStatusMessage(StructureToolText.Waiting.text(), true);
    }

    private static @Nullable ObjectArrayList<Target> prepare(EntityPlayer player, ItemStack tool, List<Target> targets) {
        var tag = BuilderState.data(tool);
        var candidates = new ObjectArrayList<Target>(targets.size());
        var resources = new ObjectArrayList<GenericStack>(targets.size());
        int blocked = 0;
        for (var target : targets) {
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
            var resource = StructureMaterials.resource(player.world, target.position(), Objects.requireNonNull(target.state()), player, target.tileNbt());
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
        if (player.isCreative()) return blocked > 0 && BuilderState.Setting.REQUIRE_SPACE.enabled(tag) ? null : candidates;
        var inventory = new ConstructionInventory(player);
        var me = BuilderState.meMode(tag) == 0 ? null : ConstructionMeAccess.find((EntityPlayerMP) player);
        if (BuilderState.meMode(tag) != 0 && me == null) player.sendMessage(StructureToolText.BuilderMeUnavailable.text());
        var result = ConstructionPreflight.allocate(resources, required -> {
            long local = inventory.take(required);
            return local + (me == null ? 0 : me.extract(required.what(), required.amount() - local, Actionable.SIMULATE));
        });
        reportMissing(player, result.missing());
        var admitted = new ObjectArrayList<Target>(result.positions().size());
        for (int index : result.positions()) admitted.add(candidates.get(index));
        boolean requireAll = BuilderState.Setting.REQUIRE_MATERIALS.enabled(tag);
        if (!result.missing().isEmpty() && BuilderState.meMode(tag) == 2 && me != null) {
            var craft = new ObjectArrayList<GenericStack>(result.missing().size());
            result.missing().forEachEntry((key, amount) -> {
                if (me.craftable(key)) craft.add(new GenericStack(key, amount));
            });
            if (!craft.isEmpty() && !me.openCrafting(craft, tool)) player.sendMessage(StructureToolText.BuilderMeUnavailable.text());
        }
        if (requireAll && !result.missing().isEmpty() || blocked > 0 && BuilderState.Setting.REQUIRE_SPACE.enabled(tag)) return null;
        if (admitted.size() < candidates.size()) player.sendMessage(StructureToolText.BuilderSkipped.text(candidates.size() - admitted.size()));
        return admitted;
    }

    private static void reportMissing(EntityPlayer player, AEKey2LongMap missing) {
        if (missing.isEmpty()) return;
        player.sendMessage(StructureToolText.BuilderMissing.text());
        missing.forEachEntry((key, amount) -> player.sendMessage(StructureToolText.BuilderMissingEntry.text(
            key.getDisplayName(), key.formatAmount(amount, AmountFormat.FULL))));
    }

    private static boolean placeable(EntityPlayer player, ItemStack tool, BlockPos pos, IBlockState current) {
        if (!player.world.isBlockModifiable(player, pos) || !player.canPlayerEdit(pos, EnumFacing.UP, tool)) return false;
        if (current.getMaterial().isLiquid()) return BuilderState.Setting.REPLACE_FLUIDS.enabled(BuilderState.data(tool));
        return current.getBlock().isAir(current, player.world, pos) || current.getBlock().isReplaceable(player.world, pos);
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

    private static boolean operate(EntityPlayer player, Task task, Target target) {
        var world = task.world;
        var tool = task.tool;
        var pos = target.position();
        if (world.isOutsideBuildHeight(pos) || !world.isBlockLoaded(pos, false) || !world.isBlockModifiable(player, pos)
            || !player.canPlayerEdit(pos, EnumFacing.UP, tool)) {
            if (target.state() != null && !BuilderState.Setting.REQUIRE_SPACE.enabled(BuilderState.data(tool))) {
                player.sendMessage(StructureToolText.BuilderBlocked.text(1));
                return true;
            }
            return false;
        }
        var current = world.getBlockState(pos);
        var desired = target.state();
        if (desired == null && world.isAirBlock(pos) || current.equals(desired)) return true;
        if (desired != null && !placeable(player, tool, pos, current)) {
            if (!BuilderState.Setting.REQUIRE_SPACE.enabled(BuilderState.data(tool))) {
                player.sendMessage(StructureToolText.BuilderBlocked.text(1));
                return true;
            }
            return false;
        }
        var resource = desired == null || player.isCreative() ? null : StructureMaterials.resource(world, pos, desired, player, target.tileNbt());
        var payment = desired == null || player.isCreative() ? ConstructionPayment.FREE
            : resource == null ? null : ConstructionPayment.prepare((EntityPlayerMP) player, resource, BuilderState.meMode(BuilderState.data(tool)) > 0);
        if (payment == null) {
            if (resource != null && !BuilderState.Setting.REQUIRE_MATERIALS.enabled(BuilderState.data(tool))) {
                var missing = new AEKey2LongMap.OpenHashMap(1);
                missing.addTo(resource.what(), resource.amount());
                reportMissing(player, missing);
                player.sendMessage(StructureToolText.BuilderSkipped.text(1));
                return true;
            }
            return false;
        }
        if (desired == null) return remove(player, world, pos, current, tool);
        if (!world.isAirBlock(pos)) {
            // Replacing grass/liquids is placement, without harvesting the old block into inventory.
            var event = new BlockEvent.BreakEvent(world, pos, current, player);
            event.setExpToDrop(0);
            if (MinecraftForge.EVENT_BUS.post(event) || !world.getBlockState(pos).equals(current) || !task.valid(player)) return false;
        }
        var snapshot = BlockSnapshot.getBlockSnapshot(world, pos);
        boolean placed = false;
        boolean finished = false;
        try {
            if (!payment.commit() || !task.valid(player) || !world.getBlockState(pos).equals(current)) return false;
            placed = true;
            if (!world.setBlockState(pos, desired, 2)) return false;
            if (ForgeEventFactory.onPlayerBlockPlace(player, snapshot, EnumFacing.UP, EnumHand.MAIN_HAND).isCanceled()
                || !task.valid(player) || !world.getBlockState(pos).equals(desired)) return false;
            world.notifyNeighborsOfStateChange(pos, desired.getBlock(), true);
            // Placement is committed before delivering returned containers to the player.
            finished = true;
            payment.finish();
            return true;
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Construction failed at {}", pos, failure);
            return finished;
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

    private static boolean remove(EntityPlayer player, World world, BlockPos pos, IBlockState state, ItemStack tool) {
        var block = state.getBlock();
        boolean fluid = block instanceof IFluidBlock || block instanceof BlockLiquid || state.getMaterial().isLiquid();
        if (state.getBlockHardness(world, pos) < 0) return false;
        @SuppressWarnings("deprecation") // Forge's harvest query uses the actual state on this Minecraft version.
        var actual = block.getActualState(state, world, pos);
        if (!player.isCreative() && !fluid && !BuilderMiningTool.canHarvest(tool, actual, player))
            return false;
        var harvestedWith = BuilderMiningTool.profile(tool);
        var event = new BlockEvent.BreakEvent(world, pos, state, player);
        // The task's builder may be in the offhand or another slot. Do not use an unrelated main-hand enchantment.
        event.setExpToDrop(player.isCreative() || fluid
            || block.canSilkHarvest(world, pos, state, player) && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, harvestedWith) > 0
            ? 0 : block.getExpDrop(state, world, pos, EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, harvestedWith)));
        if (MinecraftForge.EVENT_BUS.post(event) || !world.getBlockState(pos).equals(state)) return false;
        if (fluid && !player.isCreative()) {
            var source = FluidUtil.getFluidHandler(world, pos, EnumFacing.UP);
            if (source == null) return false;
            var available = source.drain(Integer.MAX_VALUE, false);
            // Flowing fluid has no independently drainable source; never manufacture a bucket from it.
            if (available == null || available.amount <= 0) return world.setBlockToAir(pos);
            var drained = source.drain(available, true);
            if (drained == null || drained.amount <= 0) return false;
            BuilderFluidRecovery.deliver(player, pos, drained);
            // A successful source drain owns removal; a neighbor refill must not farm this target again.
            return true;
        }
        var tile = world.getTileEntity(pos);
        if (!block.removedByPlayer(state, world, pos, player, !player.isCreative())) return false;
        block.onPlayerDestroy(world, pos, state);
        world.playEvent(player, 2001, pos, Block.getStateId(state));
        if (!player.isCreative()) {
            block.harvestBlock(world, player, pos, state, tile, harvestedWith);
            if (event.getExpToDrop() > 0) block.dropXpOnBlockBreak(world, pos, event.getExpToDrop());
        }
        return true;
    }

    private record Target(BlockPos position, @Nullable IBlockState state, @Nullable String tileNbt) {
        Target(BlockPos position, @Nullable IBlockState state) { this(position, state, null); }
    }

    private static final class Task {
        final World world;
        final int slot;
        final ItemStack tool;
        final ConstructionTarget controller;
        final BlockOrientation orientation;
        final boolean dismantle;
        final List<Target> targets;
        final boolean[] complete;
        int cursor, done;

        Task(EntityPlayer player, int slot, ItemStack tool, ConstructionTarget controller, boolean dismantle, List<Target> targets) {
            world = player.world;
            this.slot = slot;
            this.tool = tool;
            this.controller = controller;
            orientation = controller.orientation();
            this.dismantle = dismantle;
            this.targets = targets;
            complete = new boolean[targets.size()];
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
