package com.circulation.prototype_eng.structure.tool;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import com.circulation.prototype_eng.structure.StructurePickWorld;
import net.minecraft.init.Blocks;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
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
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.PlayerInvWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-owned PM builder tasks: one per player, eight attempts per tick, inventory-safe payments. */
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
        if (!tag.getBoolean("Bound") || tag.getInteger("BoundDim") != player.world.provider.getDimension()
            || !player.world.isBlockLoaded(pos) || !(player.world.getTileEntity(pos) instanceof MachineControllerHost host)) {
            player.sendStatusMessage(StructureToolText.Unavailable.text(), true);
            return;
        }
        var open = player.openContainer;
        host.readGuiAtBoundary(() -> {
            if (player.openContainer != open || player.inventory.getStackInSlot(slot) != tool || !open.canInteractWith(player)
                || player.world.getTileEntity(pos) != host || !BuilderState.position(BuilderState.data(tool)).equals(pos))
                return;
            var server = player.getServer();
            if (server == null) return;
            var view = ServerStructureReloads.view(server, MachineTypeRegistry.INSTANCE.structureCatalog());
            if (view == null || !view.entries().containsKey(host.type().structureId())) {
                player.sendStatusMessage(StructureToolText.Unavailable.text(), true);
                return;
            }
            var old = TASKS.remove(player.getUniqueID());
            if (old != null) BuilderState.state(BuilderState.data(old.tool), BuilderState.State.IDLE);
            var plan = StructureBuildPlan.expand(view, host.type().structureId(), host.orientation(), BuilderState.sliceCounts(BuilderState.data(tool)));
            var rotation = StructureTransform.between(view.nodes().get(view.entries().get(host.type().structureId()).node()).orientation(), host.orientation());
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
                }
            }
            if (unresolved) {
                player.sendStatusMessage(StructureToolText.BuildInstrumentReqUnknown.text(), true);
                if (targets.isEmpty()) {
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
        if (BuilderState.state(tag).paused()) return;
        for (int attempts = 0; attempts < 8 && task.done < task.targets.size(); attempts++) {
            int index = task.cursor++;
            if (task.cursor == task.targets.size()) task.cursor = 0;
            if (task.complete[index]) continue;
            var target = task.targets.get(index);
            if (operate(player, task.world, target, task.tool)) {
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

    private static boolean operate(EntityPlayer player, World world, Target target, ItemStack tool) {
        var pos = target.position();
        if (!world.isBlockLoaded(pos) || !world.isBlockModifiable(player, pos)
            || !player.canPlayerEdit(pos, EnumFacing.UP, tool)) return false;
        var current = world.getBlockState(pos);
        var desired = target.state();
        if (desired == null && world.isAirBlock(pos) || current.equals(desired)) return true;
        var payment = desired == null || player.isCreative() ? Payment.FREE : payment(player, world, pos, desired, target.tileNbt());
        if (payment == null) return false;
        if (!world.isAirBlock(pos)) {
            if (!remove(player, world, pos, current, tool, desired == null)) return false;
        }
        if (desired == null) return true;
        if (!payment.commit()) return false;
        var snapshot = BlockSnapshot.getBlockSnapshot(world, pos);
        if (!world.setBlockState(pos, desired, 2)) {
            payment.refund(player);
            return false;
        }
        if (ForgeEventFactory.onPlayerBlockPlace(player, snapshot, EnumFacing.UP, EnumHand.MAIN_HAND).isCanceled()) {
            snapshot.restore(true, false);
            payment.refund(player);
            return false;
        }
        world.notifyNeighborsOfStateChange(pos, desired.getBlock(), true);
        return true;
    }

    private static boolean remove(EntityPlayer player, World world, BlockPos pos, IBlockState state, ItemStack tool, boolean dismantle) {
        var block = state.getBlock();
        boolean fluid = block instanceof IFluidBlock || block instanceof BlockLiquid || state.getMaterial().isLiquid();
        if (state.getBlockHardness(world, pos) < 0) return false;
        @SuppressWarnings("deprecation") // Forge's harvest query uses the actual state on this Minecraft version.
        var actual = block.getActualState(state, world, pos);
        if (!player.isCreative() && !fluid && !(dismantle ? BuilderMiningTool.canHarvest(tool, actual, player) : block.getHarvestLevel(actual) <= 3))
            return false;
        var harvestedWith = dismantle ? BuilderMiningTool.profile(tool) : ItemStack.EMPTY;
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

    private static boolean itemMatches(ItemStack a, ItemStack b) {
        return !a.isEmpty() && ItemStack.areItemsEqual(a, b) && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static @Nullable Payment payment(EntityPlayer player, World world, BlockPos pos, IBlockState desired, @Nullable String tileNbt) {
        var block = desired.getBlock();
        var fluid = FluidRegistry.lookupFluidForBlock(block);
        if (fluid == null && desired.getMaterial() == Material.WATER) fluid = FluidRegistry.WATER;
        if (fluid == null && desired.getMaterial() == Material.LAVA) fluid = FluidRegistry.LAVA;
        if (fluid != null) return fluidPayment(player, fluid);
        ItemStack required;
        try (var picks = new StructurePickWorld(world, position -> world.isBlockLoaded(position, false)
            ? world.getBlockState(position) : Blocks.AIR.getDefaultState())) {
            required = picks.pick(desired, pos, player, tileNbt);
        }
        if (required.isEmpty()) return null;
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            int slot = i;
            var stack = player.inventory.getStackInSlot(slot);
            if (itemMatches(stack, required)) return new Payment() {
                ItemStack taken = ItemStack.EMPTY;

                public boolean commit() {
                    if (player.inventory.getStackInSlot(slot) != stack || stack.isEmpty()) return false;
                    taken = stack.splitStack(1);
                    return true;
                }

                public void refund(EntityPlayer who) {
                    give(who, taken);
                }
            };
            var handler = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (handler == null) continue;
            for (int j = 0; j < handler.getSlots(); j++) {
                int inner = j;
                if (!itemMatches(handler.extractItem(inner, 1, true), required)) continue;
                return new Payment() {
                    ItemStack taken = ItemStack.EMPTY;

                    public boolean commit() {
                        taken = handler.extractItem(inner, 1, false);
                        if (itemMatches(taken, required) && taken.getCount() == 1) return true;
                        refund(player);
                        return false;
                    }

                    public void refund(EntityPlayer who) {
                        give(who, handler.insertItem(inner, taken, false));
                    }
                };
            }
        }
        return null;
    }

    private static @Nullable Payment fluidPayment(EntityPlayer player, Fluid fluid) {
        var edits = new ObjectArrayList<FluidEdit>();
        var direct = new PlayerInvWrapper(player.inventory);
        int remaining = 1000;
        for (int i = 0; i < direct.getSlots() && remaining > 0; i++) {
            var stack = direct.getStackInSlot(i);
            int amount = prepareFluid(direct, i, fluid, remaining, edits);
            remaining -= amount;
            if (amount > 0) continue;
            var nested = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            if (nested instanceof IItemHandlerModifiable modifiable)
                for (int j = 0; j < nested.getSlots() && remaining > 0; j++)
                    remaining -= prepareFluid(modifiable, j, fluid, remaining, edits);
        }
        if (remaining != 0) return null;
        return new Payment() {
            public boolean commit() {
                for (var edit : edits)
                    if (!ItemStack.areItemStacksEqual(edit.handler().getStackInSlot(edit.slot()), edit.before()))
                        return false;
                for (var edit : edits) edit.handler().setStackInSlot(edit.slot(), edit.after());
                return true;
            }

            public void refund(EntityPlayer who) {
                for (var edit : edits) edit.handler().setStackInSlot(edit.slot(), edit.before());
            }
        };
    }

    private static int prepareFluid(IItemHandlerModifiable inventory, int slot, Fluid fluid, int limit, List<FluidEdit> edits) {
        var original = inventory.getStackInSlot(slot);
        if (original.isEmpty() || original.getCount() != 1) return 0;
        var copy = original.copy();
        var handler = copy.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null);
        if (handler == null) return 0;
        var drained = handler.drain(new FluidStack(fluid, limit), true);
        if (drained == null || drained.getFluid() != fluid || drained.amount <= 0 || drained.amount > limit) return 0;
        edits.add(new FluidEdit(inventory, slot, original.copy(), handler.getContainer()));
        return drained.amount;
    }

    private static void give(EntityPlayer player, ItemStack stack) {
        if (!stack.isEmpty() && !player.inventory.addItemStackToInventory(stack)) player.dropItem(stack, false);
    }

    private interface Payment {
        Payment FREE = new Payment() {
            public boolean commit() {
                return true;
            }

            public void refund(EntityPlayer player) {
            }
        };

        boolean commit();

        void refund(EntityPlayer player);
    }

    private record Target(BlockPos position, @Nullable IBlockState state, @Nullable String tileNbt) {
        Target(BlockPos position, @Nullable IBlockState state) { this(position, state, null); }
    }

    private static final class Task {
        final World world;
        final int slot;
        final ItemStack tool;
        final MachineControllerHost controller;
        final BlockOrientation orientation;
        final boolean dismantle;
        final List<Target> targets;
        final boolean[] complete;
        int cursor, done;

        Task(EntityPlayer player, int slot, ItemStack tool, MachineControllerHost controller, boolean dismantle, List<Target> targets) {
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
            var pos = controller.blockEntity().getPos();
            return player.isEntityAlive() && player.world == world && player.inventory.getStackInSlot(slot) == tool
                && !tool.isEmpty() && world.isBlockLoaded(pos) && world.getTileEntity(pos) == controller
                && controller.orientation().equals(orientation) && tag.getBoolean("Bound")
                && tag.getInteger("BoundDim") == world.provider.getDimension() && BuilderState.position(tag).equals(pos)
                && tag.getString("BoundMachineId").equals(controller.type().id().toString())
                && tag.getString("BoundStructureId").equals(controller.type().structureId().toString())
                && BuilderState.state(tag) != BuilderState.State.IDLE;
        }
    }

    private record FluidEdit(IItemHandlerModifiable handler, int slot, ItemStack before, ItemStack after) {
    }
}
