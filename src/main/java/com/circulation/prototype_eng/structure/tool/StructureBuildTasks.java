package com.circulation.prototype_eng.structure.tool;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import ae2.api.stacks.GenericStack;
import ae2.core.gui.locator.GuiHostLocators;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.item.BuilderInstrumentItem;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.StructureMaterials;
import com.circulation.prototype_eng.structure.StructurePickWorld;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import com.circulation.prototype_eng.structure.tool.ConstructionProgress.Step;
import com.circulation.prototype_eng.structure.tool.effect.ServerConstructionEffects;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class StructureBuildTasks {
    private static final Object2ObjectLinkedOpenHashMap<UUID, Task> TASKS = new Object2ObjectLinkedOpenHashMap<>();
    private static final ObjectArrayList<Task> RUNNABLE = new ObjectArrayList<>();
    private static final ObjectArrayList<Task> WAITING = new ObjectArrayList<>();
    private static final ConstructionBudget BUDGET = new ConstructionBudget(System::nanoTime);
    private static final ConstructionLightingBatch<World> LIGHTING = new ConstructionLightingBatch<>(ConstructionPlacement::checkLight, System::nanoTime);
    private static final long WAITING_CHECK_NANOS = 250_000L;
    private static final Comparator<Target> ORDER = Comparator.comparingInt((Target target) -> target.pos.getY())
        .thenComparingInt(target -> target.pos.getX()).thenComparingInt(target -> target.pos.getZ());
    private static final Comparator<Target> BUILD_ORDER = Comparator.<Target>comparingInt(target -> {
        var desired = target.desired;
        return desired != null && (fluid(desired) || desired.getBlock() instanceof BlockFalling) ? 1 : 0;
    }).thenComparing(ORDER);
    private static long earlyLightingNanos;
    private static int nextWaiting;

    private StructureBuildTasks() {
    }

    public static void toggle(EntityPlayer player, int slot, ItemStack tool) {
        if (!(player instanceof EntityPlayerMP owner) || !(owner.world instanceof WorldServer world)) return;
        var previous = TASKS.get(owner.getUniqueID());
        var tag = BuilderState.data(tool);
        if (previous != null && previous.tool == tool && previous.valid()) {
            previous.setPaused(!BuilderState.state(tag).paused());
            return;
        }
        var pos = BuilderState.position(tag);
        var anchor = ConstructionTarget.resolve(world, pos);
        if (!tag.getBoolean("Bound") || tag.getInteger("BoundDim") != world.provider.getDimension() || anchor == null) {
            owner.sendStatusMessage(StructureToolText.Unavailable.text(), true);
            return;
        }
        var open = owner.openContainer;
        anchor.atBoundary(() -> {
            var host = ConstructionTarget.resolve(world, pos);
            if (host == null || !anchor.current() || !host.binding().toString().equals(tag.getString("BoundMachineId"))
                || !host.structure().toString().equals(tag.getString("BoundStructureId"))) return;
            if (owner.world != world || owner.openContainer != open || owner.inventory.getStackInSlot(slot) != tool
                || !open.canInteractWith(owner) || !BuilderState.position(tag).equals(pos) || !ConstructionPlacement.accepts(world)) return;
            var server = owner.getServer();
            if (server == null) return;
            var view = ServerStructureReloads.view(server, MachineTypeRegistry.INSTANCE.structureCatalog());
            if (view == null || !view.entries().containsKey(host.structure())) {
                owner.sendStatusMessage(StructureToolText.Unavailable.text(), true);
                return;
            }
            var active = TASKS.get(owner.getUniqueID());
            if (active != null && active.tool == tool && active.valid()) return;
            if (active != null) cancel(active);
            var task = new Task(owner, world, slot, tool, tag, host, view, open);
            TASKS.put(owner.getUniqueID(), task);
            task.setPaused(false);
            BuilderState.progress(tag, 0, 0);
        });
    }

    public static void beginTick() {
        earlyLightingNanos = 0;
        if (!LIGHTING.hasPending()) return;
        long start = System.nanoTime();
        LIGHTING.flush(() -> System.nanoTime() - start < ConstructionBudget.TICK_NANOS);
        earlyLightingNanos = System.nanoTime() - start;
    }

    public static void tick(MinecraftServer server) {
        long start = System.nanoTime() - earlyLightingNanos;
        long deadline = start + ConstructionBudget.TICK_NANOS;
        try {
            if (System.nanoTime() - deadline >= 0) return;
            ServerConstructionEffects.tick(server, deadline);
            if (TASKS.isEmpty()) return;
            for (var task : TASKS.values()) {
                if (System.nanoTime() - deadline >= 0) return;
                if (task.world.getMinecraftServer() != server) continue;
                (task.runnable() ? RUNNABLE : WAITING).add(task);
            }
            checkWaiting(start);
            BUDGET.run(start, RUNNABLE, LIGHTING::reservedNanos, (task, slice) -> {
                if (TASKS.get(task.owner.getUniqueID()) != task || !task.runnable()) return;
                try {
                    if (!task.valid()) {
                        cancel(task);
                        return;
                    }
                    task.advance(slice);
                    if (slice.available() && !task.valid()) cancel(task);
                } catch (RuntimeException failure) {
                    fail(task, failure);
                }
            });
        } finally {
            RUNNABLE.clear();
            WAITING.clear();
            LIGHTING.flush(() -> System.nanoTime() - deadline < 0);
        }
    }

    private static void checkWaiting(long tickStart) {
        int count = WAITING.size();
        if (count == 0) {
            nextWaiting = 0;
            return;
        }
        long start = System.nanoTime();
        long budget = ConstructionBudget.TICK_NANOS - (start - tickStart);
        if (!RUNNABLE.isEmpty()) budget = Math.min(budget, WAITING_CHECK_NANOS);
        if (budget <= 0L) return;
        nextWaiting %= count;
        for (int visited = 0; visited < count && System.nanoTime() - start < budget; visited++) {
            var task = WAITING.get(nextWaiting);
            if (++nextWaiting == count) nextWaiting = 0;
            if (TASKS.get(task.owner.getUniqueID()) != task) continue;
            try {
                if (!task.valid()) cancel(task);
            } catch (RuntimeException failure) {
                fail(task, failure);
            }
        }
    }

    private static void fail(Task task, RuntimeException failure) {
        PrototypeEngineering.LOGGER.error("Construction at {} for {} failed", task.controller.position(), task.owner.getName(), failure);
        task.owner.sendStatusMessage(StructureToolText.Unavailable.text(), true);
        cancel(task);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        var task = TASKS.get(event.player.getUniqueID());
        if (task != null) cancel(task);
    }

    @SubscribeEvent
    public static void unload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        LIGHTING.clear(event.getWorld());
        TASKS.values().removeIf(task -> {
            if (task.world != event.getWorld()) return false;
            task.stop();
            return true;
        });
    }

    public static void stop() {
        for (var task : TASKS.values()) task.stop();
        TASKS.clear();
        earlyLightingNanos = 0;
        nextWaiting = 0;
        LIGHTING.flush(() -> true);
        LIGHTING.clear();
        ServerConstructionEffects.stop();
    }

    private static void cancel(Task task) {
        if (TASKS.remove(task.owner.getUniqueID(), task)) task.stop();
    }

    private static boolean blockPermission(Task task, BlockPos pos) {
        return task.world.isBlockModifiable(task.owner, pos) && task.owner.canPlayerEdit(pos, EnumFacing.UP, task.tool);
    }

    private static boolean air(World world, BlockPos pos, IBlockState state) {
        return state.getBlock() == Blocks.AIR || state.getBlock().isAir(state, world, pos);
    }

    private static boolean spaceAvailable(Task task, BlockPos pos, IBlockState current) {
        return air(task.world, pos, current) || (task.requireSpace ? task.replaceFluids && fluid(current)
            : canRemove(task.owner, task.world, pos, current, task.tool));
    }

    private static boolean fluid(IBlockState state) {
        return state.getBlock() instanceof IFluidBlock || state.getBlock() instanceof BlockLiquid || state.getMaterial().isLiquid();
    }

    private static boolean canRemove(EntityPlayer player, World world, BlockPos pos, IBlockState state, ItemStack tool) {
        if (state.getBlockHardness(world, pos) < 0) return false;
        if (player.isCreative() || fluid(state)) return true;
        @SuppressWarnings("deprecation")
        var actual = state.getBlock().getActualState(state, world, pos);
        return BuilderMiningTool.canHarvest(tool, actual, player);
    }

    private static Step operate(Task task, Target target) {
        var world = task.world;
        var pos = target.pos;
        var chunk = task.chunk(pos);
        if (chunk == null) return task.outcome(target, State.UNLOADED, !task.dismantle && !task.requireSpace);
        if (!ConstructionPlacement.accepts(world)) return Step.WAIT;
        var current = chunk.getBlockState(pos);
        var desired = target.desired;
        if ((task.dismantle && air(world, pos, current)) || current.equals(desired))
            return task.outcome(target, State.MATCHED, true);
        if (task.progress.firstPass() && !fluid(current)) {
            task.setOutcome(target, State.PENDING);
            return Step.DEFER;
        }
        if (!blockPermission(task, pos))
            return task.outcome(target, State.BLOCKED, !task.dismantle && !task.requireSpace);
        if (desired == null || task.progress.firstPass()) {
            if (!remove(task, pos, chunk, current)) return task.outcome(target, State.BLOCKED, !task.dismantle && !task.requireSpace);
            if (task.dismantle) return task.outcome(target, State.COMMITTED, true);
            task.setOutcome(target, State.PENDING);
            return Step.DEFER;
        }
        if (!spaceAvailable(task, pos, current)) return task.outcome(target, State.BLOCKED, !task.requireSpace);
        ConstructionPayment payment;
        if (task.owner.isCreative()) payment = ConstructionPayment.FREE;
        else {
            var resource = target.resource;
            if (resource == null) resource = target.resource = task.resource(target);
            if (resource == null) return task.outcome(target, State.BLOCKED, !task.requireMaterials && !task.waitMaterials);
            var quote = task.sources.prepare(resource, task.me);
            payment = quote.payment();
            if (payment == null) {
                if (task.requireMaterials || task.waitMaterials) {
                    task.waitingMaterial = resource;
                    task.setOutcome(target, State.MISSING);
                    return Step.WAIT;
                }
                task.reportMissing(resource.what(), quote.missing());
                return task.outcome(target, State.MISSING, true);
            }
        }
        var snapshot = ConstructionPlacement.snapshot(world, pos, current);
        boolean wrote = false;
        boolean finished = false;
        try {
            if (!payment.commit() || !task.authorized(pos, chunk, current)) return Step.RETRY;
            if (!air(world, pos, current)) {
                if (!task.requireSpace) {
                    if (!remove(task, pos, chunk, current)) return task.outcome(target, State.BLOCKED, true);
                    current = chunk.getBlockState(pos);
                    if (!task.authorized(pos, chunk, current) || !air(world, pos, current)) return Step.RETRY;
                    snapshot = ConstructionPlacement.snapshot(world, pos, current);
                } else {
                    var event = new BlockEvent.BreakEvent(world, pos, current, task.owner);
                    event.setExpToDrop(0);
                    if (MinecraftForge.EVENT_BUS.post(event) || !task.authorized(pos, chunk, current))
                        return task.outcome(target, State.BLOCKED, false);
                }
            }
            wrote = true;
            if (!ConstructionPlacement.write(world, pos, chunk, current, desired, LIGHTING)
                || ForgeEventFactory.onPlayerBlockPlace(task.owner, snapshot, EnumFacing.UP, task.hand).isCanceled()
                || !task.authorized(pos, chunk, desired)) return task.outcome(target, State.ROLLED_BACK, !task.requireSpace);
            finished = true;
            try {
                payment.finish();
            } finally {
                ConstructionPlacement.notify(world, pos, chunk, current, desired);
            }
            return task.outcome(target, State.COMMITTED, true);
        } finally {
            if (!finished) {
                try {
                    if (wrote) ConstructionPlacement.restore(world, chunk, snapshot, desired, LIGHTING);
                } finally {
                    payment.refund(task.owner);
                }
            }
        }
    }

    private static boolean remove(Task task, BlockPos pos, Chunk chunk, IBlockState state) {
        var player = task.owner;
        var world = task.world;
        var block = state.getBlock();
        boolean fluid = fluid(state);
        if (!canRemove(player, world, pos, state, task.tool)) return false;
        var harvestedWith = BuilderMiningTool.profile(task.tool);
        var event = new BlockEvent.BreakEvent(world, pos, state, player);
        event.setExpToDrop(player.isCreative() || fluid
            || block.canSilkHarvest(world, pos, state, player) && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, harvestedWith) > 0
            ? 0 : block.getExpDrop(state, world, pos, EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE, harvestedWith)));
        if (MinecraftForge.EVENT_BUS.post(event) || !task.authorized(pos, chunk, state))
            return false;
        if (fluid && !player.isCreative()) {
            var source = FluidUtil.getFluidHandler(world, pos, EnumFacing.UP);
            if (source == null) return false;
            var available = source.drain(Integer.MAX_VALUE, false);
            if (!task.authorized(pos, chunk, state)) return false;
            if (available == null || available.amount <= 0)
                return world.setBlockToAir(pos) && air(world, pos, chunk.getBlockState(pos));
            var drained = source.drain(available, true);
            if (drained == null || drained.amount <= 0) return false;
            var actual = chunk.getBlockState(pos);
            boolean removed = air(world, pos, actual) || !fluid(actual);
            BuilderFluidRecovery.deliver(player, drained, task.me);
            return removed;
        }
        try (var _ = BuilderDropRecovery.capture(player, task.me)) {
            var tile = world.getTileEntity(pos);
            if (!block.removedByPlayer(state, world, pos, player, !player.isCreative()) || chunk.getBlockState(pos).equals(state)) return false;
            block.onPlayerDestroy(world, pos, state);
            world.playEvent(player, 2001, pos, Block.getStateId(state));
            if (!player.isCreative()) {
                block.harvestBlock(world, player, pos, state, tile, harvestedWith);
                if (event.getExpToDrop() > 0) block.dropXpOnBlockBreak(world, pos, event.getExpToDrop());
            }
        }
        return true;
    }

    private enum State {PENDING, MATCHED, COMMITTED, MISSING, BLOCKED, UNLOADED, ROLLED_BACK}

    private static final class Target {
        final BlockPos pos;
        final @Nullable IBlockState desired;
        final @Nullable String tileNbt;
        @Nullable GenericStack resource;
        State outcome = State.PENDING;
        boolean first;

        Target(BlockPos pos, @Nullable IBlockState desired, @Nullable String tileNbt) {
            this.pos = pos;
            this.desired = desired;
            this.tileNbt = tileNbt;
        }
    }

    private static final class Task {
        final EntityPlayerMP owner;
        final WorldServer world;
        final int slot;
        final ItemStack tool;
        final NBTTagCompound tag;
        final EnumHand hand;
        final ConstructionTarget controller;
        final String binding;
        final String structure;
        final boolean dismantle, requireSpace, replaceFluids, requireMaterials, waitMaterials, autoCraft;
        final ConstructionPayment.Sources sources;
        final StructureCatalogView view;
        final int meMode;
        @Nullable ConstructionMEAccess me;
        @Nullable ServerConstructionEffects.Effect projection;
        @Nullable StructurePickWorld pickWorld;
        final ConstructionProgress progress = new ConstructionProgress();
        final ObjectArrayList<Target> targets = new ObjectArrayList<>();
        @Nullable Preparation prepare;
        @Nullable Container builderWindow;
        @Nullable Container craftingWindow;
        @Nullable GenericStack waitingMaterial;
        @Nullable Chunk lastChunk;
        int lastChunkX, lastChunkZ;
        int matched, committed, missing, errors, unloaded, total;
        long nextMissingReport;
        boolean initialized, effectStarted, geometryPublished;

        Task(EntityPlayerMP owner, WorldServer world, int slot, ItemStack tool, NBTTagCompound tag, ConstructionTarget controller,
             StructureCatalogView view, @Nullable Container window) {
            this.owner = owner;
            this.world = world;
            this.slot = slot;
            this.tool = tool;
            this.tag = tag;
            hand = slot == owner.inventory.getSizeInventory() - 1 ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
            this.controller = controller;
            this.view = view;
            binding = controller.binding().toString();
            structure = controller.structure().toString();
            dismantle = controller.formed();
            requireSpace = BuilderState.Setting.REQUIRE_SPACE.enabled(tag);
            replaceFluids = BuilderState.Setting.REPLACE_FLUIDS.enabled(tag);
            requireMaterials = BuilderState.Setting.REQUIRE_MATERIALS.enabled(tag);
            waitMaterials = BuilderState.Setting.WAIT_MATERIALS.enabled(tag);
            meMode = BuilderState.meMode(tag);
            autoCraft = meMode == 2;
            sources = new ConstructionPayment.Sources(owner);
            builderWindow = window;
        }

        boolean valid() {
            return taskItemsCurrent() && controller.current() && tag.getBoolean("Bound")
                && tag.getInteger("BoundDim") == world.provider.getDimension() && BuilderState.position(tag).equals(controller.position())
                && tag.getString("BoundMachineId").equals(binding) && tag.getString("BoundStructureId").equals(structure);
        }

        private boolean taskItemsCurrent() {
            return owner.isEntityAlive() && owner.world == world && owner.inventory.getStackInSlot(slot) == tool
                && !tool.isEmpty() && BuilderState.state(tag) != BuilderState.State.IDLE;
        }

        boolean authorized(BlockPos pos, Chunk chunk, IBlockState expected) {
            return valid() && blockPermission(this, pos) && ConstructionPlacement.accepts(world)
                && world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4) == chunk && chunk.getBlockState(pos).equals(expected);
        }

        boolean runnable() {
            return !BuilderState.state(tag).paused() && (craftingWindow == null || owner.openContainer != craftingWindow)
                && (!effectStarted || geometryPublished || effect().hasConstructionWork());
        }

        void setPaused(boolean paused) {
            BuilderState.state(tag, dismantle
                ? (paused ? BuilderState.State.PAUSED_DISASSEMBLING : BuilderState.State.DISASSEMBLING)
                : (paused ? BuilderState.State.PAUSED_BUILDING : BuilderState.State.BUILDING));
            owner.inventory.markDirty();
            status();
        }

        @Nullable Chunk chunk(BlockPos pos) {
            if (!inBuildBounds(pos)) return null;
            int x = pos.getX() >> 4;
            int z = pos.getZ() >> 4;
            var cached = lastChunk;
            if (cached != null && x == lastChunkX && z == lastChunkZ && cached.isLoaded()) return cached;
            var chunk = world.getChunkProvider().getLoadedChunk(x, z);
            if (chunk == null || chunk.isEmpty()) return null;
            lastChunk = chunk;
            lastChunkX = x;
            lastChunkZ = z;
            return chunk;
        }

        boolean inBuildBounds(BlockPos pos) {
            return !world.isOutsideBuildHeight(pos) && pos.getX() >= -30000000 && pos.getX() < 30000000
                && pos.getZ() >= -30000000 && pos.getZ() < 30000000;
        }

        IBlockState state(BlockPos pos) {
            var chunk = chunk(pos);
            return chunk == null ? Blocks.AIR.getDefaultState() : chunk.getBlockState(pos);
        }

        @Nullable GenericStack resource(Target target) {
            var picks = pickWorld;
            if (picks == null) pickWorld = picks = new StructurePickWorld(world, this::state);
            return StructureMaterials.resource(picks, Objects.requireNonNull(target.desired), target.pos, owner, target.tileNbt);
        }

        ServerConstructionEffects.Effect effect() {
            return Objects.requireNonNull(projection);
        }

        void advance(ConstructionBudget.Slice slice) {
            if (!slice.available()) return;
            lastChunk = null;
            sources.beginSlice();
            if (!initialized) {
                me = meMode == 0 ? null
                    : ((BuilderInstrumentItem) tool.getItem()).getGuiHost(owner, GuiHostLocators.forInventorySlot(slot), null);
                projection = ServerConstructionEffects.create(owner, controller.position(), dismantle);
                prepare = new Preparation(this, view);
                initialized = true;
                return;
            }
            var confirmation = craftingWindow;
            craftingWindow = null;
            if (confirmation != null) {
                start();
                return;
            }
            var planning = prepare;
            if (planning != null) {
                while (slice.available() && taskItemsCurrent()) {
                    if (!planning.step()) break;
                }
                return;
            }
            if (!geometryPublished) {
                geometryPublished = effect().ready(slice::available);
                if (!geometryPublished || !slice.available()) return;
            }
            waitingMaterial = null;
            progress.advance(slice, index -> taskItemsCurrent() ? operate(this, targets.get(index)) : Step.WAIT);
            int done = progress.done();
            if (BuilderState.done(tag) != done) {
                BuilderState.progress(tag, done, targets.size());
                owner.inventory.markDirty();
            }
            status();
            if (done == targets.size()) {
                if (!TASKS.remove(owner.getUniqueID(), this)) return;
                boolean successful = matched + committed == total;
                effect().finish(successful);
                finish();
            } else if (progress.waiting() && waitingMaterial != null && world.getTotalWorldTime() % 40 == 0 && effect().ownerWithinRenderDistance()) {
                owner.sendStatusMessage(StructureToolText.BuilderWaitingMaterial.text(waitingMaterial.what().getDisplayName()), true);
            }
        }

        void status() {
            if (effectStarted) {
                int stillNeeded = total - matched - committed - errors - unloaded;
                effect().status(Math.max(stillNeeded, missing), errors, unloaded, BuilderState.state(tag).paused());
            }
        }

        void reportMissing(AEKey key, long amount) {
            long now = world.getTotalWorldTime();
            if (now < nextMissingReport) return;
            nextMissingReport = now + 40;
            owner.sendMessage(StructureToolText.BuilderMissingEntry.text(key.getDisplayName(), key.formatAmount(amount, AmountFormat.FULL)));
        }

        Step outcome(Target target, State state, boolean terminal) {
            setOutcome(target, state);
            if (terminal) {
                if (state == State.COMMITTED) effect().committed(target.pos);
                else effect().exclude(target.pos);
            }
            return terminal ? Step.COMPLETE : Step.RETRY;
        }

        void setOutcome(Target target, State state) {
            if (target.outcome == state) return;
            switch (target.outcome) {
                case MISSING -> missing--;
                case BLOCKED, ROLLED_BACK -> errors--;
                case UNLOADED -> unloaded--;
                case PENDING -> {
                }
                case MATCHED, COMMITTED -> throw new IllegalStateException("Construction changed a completed position");
            }
            switch (state) {
                case MATCHED -> matched++;
                case COMMITTED -> committed++;
                case MISSING -> missing++;
                case BLOCKED, ROLLED_BACK -> errors++;
                case UNLOADED -> unloaded++;
                case PENDING -> {
                }
            }
            target.outcome = state;
        }

        void start() {
            var window = builderWindow;
            if (window != null && owner.openContainer == window) owner.closeScreen();
            builderWindow = null;
            prepare = null;
            BuilderState.progress(tag, 0, targets.size());
            owner.inventory.markDirty();
            effect().start();
            effectStarted = true;
            status();
        }

        void stop() {
            var effect = projection;
            if (effect != null) effect.cancel();
            finish();
        }

        private void finish() {
            BuilderState.state(tag, BuilderState.State.IDLE);
            owner.inventory.markDirty();
        }
    }

    private static final class Preparation {
        private final Task task;
        private final StructureBuildPlan.Expansion expansion;
        private final PriorityQueue<Target> ordered;
        private final ObjectArrayList<Target> candidates = new ObjectArrayList<>();
        private final ConstructionInventory inventory = new ConstructionInventory();
        private final ConstructionPreflight.Stock allocations = new ConstructionPreflight.Stock();
        private final ObjectArrayList<GenericStack> craft = new ObjectArrayList<>();
        private @Nullable Iterator<StructureBuildPlan.Cell> cells;
        private @Nullable Iterator<AEKey> materialKeys;
        private @Nullable ConstructionInventory.Request stock;
        private @Nullable GenericStack material;
        private Phase phase = Phase.EXPAND;
        private int scan, slot, source, selected, invalid, skipped;
        private boolean unavailable, missing;

        Preparation(Task task, StructureCatalogView view) {
            this.task = task;
            expansion = new StructureBuildPlan.Expansion(view, task.controller.structure(), task.controller.orientation(), BuilderState.sliceCounts(task.tag));
            ordered = new PriorityQueue<>(task.dismantle ? ORDER.reversed() : BUILD_ORDER);
        }

        boolean step() {
            switch (phase) {
                case EXPAND -> expand();
                case RESOLVE -> resolve();
                case SORT -> inspect();
                case INVENTORY -> inventory();
                case MATERIALS -> materials();
                case ADMIT -> admit();
                case START -> {
                    start();
                    return false;
                }
            }
            return task.prepare == this;
        }

        private void expand() {
            if (!expansion.step()) {
                cells = expansion.cells();
                phase = Phase.RESOLVE;
            }
        }

        private void resolve() {
            var iterator = Objects.requireNonNull(cells);
            if (!iterator.hasNext()) {
                cells = null;
                phase = Phase.SORT;
                if (task.me != null && task.me.connect() == null && !task.owner.isCreative() && !task.dismantle)
                    task.owner.sendMessage(task.me.unavailableReason());
                return;
            }
            var cell = iterator.next();
            var pos = StructureBuildPlan.add(task.controller.position(), cell.position());
            task.total++;
            if (!task.inBuildBounds(pos)) {
                task.errors++;
                return;
            }
            task.effect().includeInArea(pos);
            if (task.dismantle) {
                ordered.add(new Target(pos, null, null));
                return;
            }
            var display = cell.display();
            if (display != null && display.complete()) {
                var choice = BuilderState.choice(task.tag, display, cell.position(), task.controller.orientation());
                if (Block.REGISTRY.containsKey(choice.blockId())) {
                    var block = Block.REGISTRY.getObject(choice.blockId());
                    if (block != null) {
                        @SuppressWarnings("deprecation")
                        var resolved = choice.resolveProperties(block.getStateFromMeta(choice.meta()));
                        if (resolved != null) {
                            ordered.add(new Target(pos, StructureToolExport.rotateState(resolved, expansion.rotation), display.tileNbt()));
                            return;
                        }
                    }
                }
            }
            invalid++;
            task.errors++;
        }

        private void inspect() {
            var target = ordered.poll();
            if (target == null) {
                if (invalid > 0 || unavailable) task.owner.sendMessage(StructureToolText.BuildInstrumentReqUnknown.text());
                int blocked = task.errors + task.unloaded - invalid;
                if (blocked > 0) task.owner.sendMessage(StructureToolText.BuilderBlocked.text(blocked));
                if (unavailable || invalid > 0 && (task.requireMaterials || candidates.isEmpty())
                    || !task.dismantle && task.requireSpace && blocked > 0) {
                    cancel(task);
                    task.prepare = null;
                    return;
                }
                phase = allocations.isEmpty() ? Phase.ADMIT : Phase.INVENTORY;
                return;
            }
            var chunk = task.chunk(target.pos);
            if (chunk == null) {
                task.setOutcome(target, State.UNLOADED);
                if (task.dismantle) candidates.add(target);
                return;
            }
            var current = chunk.getBlockState(target.pos);
            if (task.dismantle ? air(task.world, target.pos, current) : current.equals(target.desired)) {
                task.setOutcome(target, State.MATCHED);
                return;
            }
            if (!task.dismantle && (!blockPermission(task, target.pos) || !spaceAvailable(task, target.pos, current))) {
                task.setOutcome(target, State.BLOCKED);
                return;
            }
            target.first = (task.dismantle || !task.requireSpace) && fluid(current);
            if (!task.dismantle && !task.owner.isCreative()) {
                var resource = task.resource(target);
                if (resource == null) {
                    unavailable = true;
                    return;
                }
                target.resource = resource;
                allocations.require(resource);
            }
            candidates.add(target);
        }

        private void inventory() {
            var handlers = task.sources.inventories();
            if (source >= handlers.size()) {
                materialKeys = allocations.requirements();
                phase = Phase.MATERIALS;
                return;
            }
            var handler = handlers.get(source);
            if (slot >= handler.getSlots()) {
                source++;
                slot = 0;
            } else inventory.capture(handler, slot++);
        }

        private void materials() {
            var request = stock;
            var me = task.me;
            if (request != null) {
                if (request.step()) return;
                var resource = Objects.requireNonNull(material);
                long local = request.taken();
                long remaining = resource.amount() - local;
                long network = me == null || remaining == 0 ? 0 : me.extract(resource.what(), remaining, Actionable.SIMULATE);
                if (network < 0 || network > remaining) throw new IllegalStateException("Invalid construction network simulation");
                long absent = allocations.reserve(resource, local + network);
                if (absent > 0) {
                    if (!missing) task.owner.sendMessage(StructureToolText.BuilderMissing.text());
                    missing = true;
                    var key = resource.what();
                    task.owner.sendMessage(StructureToolText.BuilderMissingEntry.text(key.getDisplayName(), key.formatAmount(absent, AmountFormat.FULL)));
                    if (task.autoCraft && me != null && me.craftable(key)) craft.add(new GenericStack(key, absent));
                }
                material = null;
                stock = null;
                return;
            }
            var iterator = Objects.requireNonNull(materialKeys);
            if (iterator.hasNext()) {
                var resource = allocations.requirement(iterator.next());
                material = resource;
                stock = inventory.new Request(resource);
            } else {
                phase = Phase.ADMIT;
                materialKeys = null;
            }
        }

        private void admit() {
            if (scan == candidates.size()) {
                if (skipped > 0) task.owner.sendMessage(StructureToolText.BuilderSkipped.text(skipped));
                phase = Phase.START;
                return;
            }
            var target = candidates.get(scan++);
            var material = target.resource;
            if (material != null && !allocations.admit(material) && !task.waitMaterials) {
                task.setOutcome(target, State.MISSING);
                skipped++;
                return;
            }
            task.effect().add(target.pos);
            task.targets.add(target);
            task.progress.add(selected++, target.first);
        }

        private void start() {
            var me = task.me;
            if (!craft.isEmpty() && me != null && task.owner.openContainer == task.builderWindow) {
                if (me.openCrafting(craft)) task.craftingWindow = task.owner.openContainer;
                else task.owner.sendMessage(StructureToolText.BuilderMeUnavailable.text());
            }
            if (missing && task.requireMaterials) {
                cancel(task);
                return;
            }
            if (task.craftingWindow != null) return;
            task.start();
        }

        private enum Phase {EXPAND, RESOLVE, SORT, INVENTORY, MATERIALS, ADMIT, START}
    }
}
