package pe.visual.examples.run;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import ae2.api.config.Actionable;
import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionPublicationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.research.ResearchDataCenters;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskCarrier;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskData;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskNbtCodec;
import com.circulation.prototype_eng.api.research.disk.ResearchDiskOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadOperation;
import com.circulation.prototype_eng.api.research.download.ResearchDownloadResult;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadContext;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemImportContext;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.integration.crafttweaker.ZenMachineComponent;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeUpdates;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import com.circulation.prototype_eng.integration.crafttweaker.research.ZenResearchRewardEvent;
import com.circulation.prototype_eng.integration.ftb.FtbOwnerTeamResolver;
import com.circulation.prototype_eng.machine.ServerMachineIdentities;
import com.circulation.prototype_eng.recipe.ServerRecipeManifests;
import com.circulation.prototype_eng.research.PlayerDataPersistence;
import com.circulation.prototype_eng.research.ServerResearch;
import com.circulation.prototype_eng.research.download.MinecraftResearchDownloadInventory;
import com.circulation.prototype_eng.research.reward.MinecraftItemDeliveryAdapter;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.feed_the_beast.ftblib.lib.data.Universe;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.minecraft.CraftTweakerMC;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import pe.visual.examples.VisualExamples;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Run-only native adapters. Startup declares items; world and persistence changes require commands.
 */
@ZenRegister
@ZenClass("mods.pe_run_tests.NetworkRender")
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
@SuppressWarnings("unused")
public final class RunNetworkRender {
    private static final ComponentType<MachineInstance, Boolean> MARKER =
        new ComponentType<>(id("render_marker"), _ -> Boolean.TRUE, List.of());
    private static Item diskItem;
    private static Item lowDiskItem;
    private static final String EVIDENCE = "pe_run_tests_evidence";
    private static ZenRecipe[] clientV2;
    private static ZenRecipeUpdates retainedUpdate;
    private static MinecraftServer failNoticeServer;

    private RunNetworkRender() {
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation("pe_run_tests", path);
    }

    public static void registerUserCommands(MinecraftServer server) {
        ((net.minecraft.command.ServerCommandManager) server.getCommandManager()).registerCommand(new ResearchUserCommand());
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        diskItem = new RunDisk(1).setRegistryName(id("research_disk")).setTranslationKey("pe_run_tests.research_disk").setMaxStackSize(1);
        lowDiskItem = new RunDisk(0).setRegistryName(id("research_low_disk")).setTranslationKey("pe_run_tests.research_low_disk").setMaxStackSize(1);
        event.getRegistry().registerAll(diskItem, lowDiskItem);
    }

    @ZenMethod
    public static ZenMachineComponent markerComponent() {
        return ZenMachineComponent.recreated(MARKER);
    }

    @ZenMethod
    public static ZenMachineComponent typedAnimationComponent() {
        return ZenMachineComponent.persistent(com.circulation.prototype_eng.api.machine.render.MachineAnimationState.TYPE,
            com.circulation.prototype_eng.api.machine.render.MachineAnimationState.CODEC);
    }

    /**
     * Exact concrete future definitions are shared by the script client declaration and actual server request.
     * The shared array remains read-only for the declaration lifetime.
     */
    @ZenMethod
    public static synchronized ZenRecipe[] clientV2Recipes() {
        if (clientV2 == null) {
            var iron = ZenResource.item(CraftTweakerMC.getIItemStack(new ItemStack(Items.IRON_INGOT)), 1);
            var gold = ZenResource.item(CraftTweakerMC.getIItemStack(new ItemStack(Items.GOLD_INGOT)), 1);
            var output = ZenResource.item(CraftTweakerMC.getIItemStack(new ItemStack(Items.DIAMOND)), 2);
            var quartz = ZenResource.item(CraftTweakerMC.getIItemStack(new ItemStack(Items.QUARTZ)), 1);
            clientV2 = new ZenRecipe[]{ZenRecipe.create("pe_run_tests:client_update", "v2", 40,
                new ZenRequirement[]{ZenRequirement.fuzzyInput("pe_run_tests:client_input", new ZenResource[]{iron, gold}, 1),
                    ZenRequirement.resources("pe_run_tests:client_output", new ZenResource[0], new ZenResource[]{output}).chance(75, null)}),
                ZenRecipe.create("pe_run_tests:client_migration", "v2", 40,
                    new ZenRequirement[]{ZenRequirement.resources("pe_run_tests:client_migration_io", new ZenResource[]{quartz}, new ZenResource[]{output})})};
        }
        return clientV2;
    }

    /**
     * The stable reward child key is durably written and read back before APPLIED.
     */
    @ZenMethod
    public static String rewardEvent(ZenResearchRewardEvent event) {
        var server = Objects.requireNonNull(FMLCommonHandler.instance().getMinecraftServerInstance());
        var player = server.getPlayerList().getPlayerByUUID(event.key().playerUuid());
        if (player == null) return "RETRY_LATER";
        requireThread(server);
        var evidence = evidence(player);
        if (evidence.getBoolean("reward_wait")) return "RETRY_LATER";
        String key = event.researchId() + "/" + event.level() + "/" + event.groupId() + "/" + event.childId();
        var events = evidence.getCompoundTag("reward_events");
        if (!events.getBoolean(key)) {
            events.setBoolean(key, true);
            evidence.setTag("reward_events", events);
            evidence.setInteger("reward_event_effects", Math.incrementExact(evidence.getInteger("reward_event_effects")));
            persistEvidence(player);
        }
        return "APPLIED";
    }

    /**
     * Invoked only by client-notify-failure: it exercises the actual Forge observer after publication.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void failNotification(RecipeDefinitionPublicationEvent event) {
        if (event.previous() != null && failNoticeServer == event.server()) {
            failNoticeServer = null;
            throw new IllegalStateException("PE_RUN_TESTS deliberate post-publication notification failure");
        }
    }

    public static BlockPos2ObjectMap<Block> placement(String machine) {
        if (machine.startsWith("pe_run_tests:")) machine = machine.substring("pe_run_tests:".length());
        if (!machine.startsWith("net_") && !machine.startsWith("render_")) return null;
        var controller = ForgeRegistries.BLOCKS.getValue(id(machine + "_controller"));
        if (controller == null || controller == Blocks.AIR) return null;
        var result = new BlockPos2ObjectMap<Block>();
        result.put(BlockPos.ORIGIN, controller);
        if (machine.equals("net_computer") || machine.equals("net_demo_computer")) {
            result.put(new BlockPos(-1, 0, 0), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:computation_hatch_4"))));
            if (machine.equals("net_demo_computer"))
                result.put(new BlockPos(1, 0, 0), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:input_hatch_4"))));
            result.put(new BlockPos(0, 0, 1), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:energy_input_hatch_4"))));
            return result;
        }
        if (machine.equals("net_center") || machine.equals("net_demo_center")) {
            result.put(new BlockPos(0, 1, 0), Blocks.IRON_BLOCK);
            result.put(new BlockPos(0, 0, 1), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:energy_input_hatch_4"))));
            return result;
        }
        if (machine.equals("net_demo_database")) {
            result.put(new BlockPos(0, 1, 0), Blocks.IRON_BLOCK);
            return result;
        }
        result.put(new BlockPos(1, 0, 0), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:input_hatch_10"))));
        result.put(new BlockPos(-1, 0, 0), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:output_hatch_10"))));
        if (machine.startsWith("net_"))
            result.put(new BlockPos(0, 0, 1), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng:energy_input_hatch_4"))));
        else {
            result.put(new BlockPos(0, 1, 0), Blocks.GLASS);
            result.put(new BlockPos(0, 2, 0), Blocks.GLASS);
        }
        return result;
    }

    public static boolean worldAction(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0 || !(args[0].startsWith("net-") || args[0].startsWith("research-")
            || args[0].startsWith("render-") || args[0].startsWith("client-"))) return false;
        var player = CommandBase.getCommandSenderAsPlayer(sender);
        var server = Objects.requireNonNull(player.getServer());
        switch (args[0]) {
            case "net-registered" -> {
                requireCount(args, 1);
                var research = ResearchRegistry.INSTANCE.require(id("research_station"));
                var compute = pe.visual.examples.ExampleResources.NET_COMPUTE;
                NativeChecks.check("research_actual_stage_formula", research.totalResourceDemandForLevel(1).getDouble(compute) == 1200
                    && research.totalResourceDemandForLevel(2).getDouble(compute) == 1300);
                NativeChecks.check("research_actual_prerequisite_mutex", research.prerequisitesForLevel(1).getInt(id("research_manual")) == 1
                    && !ResearchRegistry.INSTANCE.require(id("research_mutex")).mutexRulesForLevel(1).isEmpty());
                var canonical = Objects.requireNonNull(RecipeRegistry.INSTANCE.catalog().recipes().stream().filter(r -> r.id().equals(id("net_recipe"))).findFirst().orElse(null));
                var requirements = com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions.INSTANCE.require(canonical);
                NativeChecks.check("net_actual_exact_recipe_extensions", requirements.resources().getDouble(compute) == 2
                    && requirements.resources().size() == 1
                    && requirements.researchRequirements().getInt(id("research_station")) == 1);
                NativeChecks.check("research_real_disk_items", diskItem != null && lowDiskItem != null
                    && ForgeRegistries.ITEMS.getValue(id("research_disk")) == diskItem && ForgeRegistries.ITEMS.getValue(id("research_low_disk")) == lowDiskItem);
                pass(player, "net_actual_registered_definitions", "canonical stage/formula/prerequisite/mutex/native gate/disk items");
            }
            case "net-stock" -> {
                requireCount(args, 6);
                var pos = CommandBase.parseBlockPos(sender, args, 1, false);
                long amount = CommandBase.parseLong(args[5], 1, 1_000_000);
                var key = args[4].equals("water") || args[4].equals("lava")
                    ? AEFluidKey.of(new FluidStack(Objects.requireNonNull(FluidRegistry.getFluid(args[4])), 1))
                    : Objects.requireNonNull(AEItemKey.of(new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation(args[4]))))));
                var hatch = HatchCapabilities.findResource(player.world.getTileEntity(pos), null);
                var input = hatch == null ? null : hatch.input();
                NativeChecks.check("net_native_resource_input", input != null);
                var amounts = new AEKey2LongMap.OpenHashMap();
                amounts.addTo(key, amount);
                NativeChecks.check("net_supply_preflight", input.insert(key, amounts.getLong(key), Actionable.SIMULATE) == amount);
                NativeChecks.check("net_supply_native_insert", input.insert(key, amount, Actionable.MODULATE) == amount);
                pass(player, "net_supply_native_insert", "resource=" + key.getDisplayName() + " amount=" + amount);
            }
            case "net-status" -> {
                requireCount(args, 6);
                var host = host(sender, args, 1);
                String role = args[4], state = args[5];
                host.readGuiAtBoundary(() -> {
                    var owner = Objects.requireNonNull(host.machine());
                    MachineNetworkDevice found = null;
                    for (var entry : owner.components().assemblyBindings())
                        if (entry.component() instanceof MachineNetworkDevice device) {
                            NativeChecks.check("net_single_actual_role", found == null);
                            found = device;
                        }
                    var device = Objects.requireNonNull(found);
                    NativeChecks.check("net_actual_role_state", Objects.toString(device.state()).equals(state));
                    var permit = device.workPermit();
                    NativeChecks.check("net_actual_permit", permit != null && permit.current() && permit.deviceSnapshot().role().name().equals(role));
                    pass(player, "net_actual_role_state", role + " " + state + " supply=" + permit.deviceSnapshot().resourcesPerTick());
                });
            }
            case "net-supply" -> {
                requireCount(args, 6);
                var host = host(sender, args, 1);
                long compute = CommandBase.parseLong(args[4], 0, Long.MAX_VALUE), threads = CommandBase.parseLong(args[5], 0, Long.MAX_VALUE);
                host.readGuiAtBoundary(() -> {
                    var owner = Objects.requireNonNull(host.machine());
                    for (var entry : owner.components().assemblyBindings())
                        if (entry.component() instanceof MachineNetworkDevice device) {
                            var permit = device.workPermit();
                            NativeChecks.check("net_physical_item_supply", permit != null && permit.current()
                                && permit.deviceSnapshot().resourcesPerTick().getDouble(pe.visual.examples.ExampleResources.NET_COMPUTE) == compute
                                && permit.deviceSnapshot().concurrencyPerTick() == threads);
                            pass(player, "net_physical_item_supply", "raw per-device compute=" + compute + " threads=" + threads + " actual team remaining=" + permit.availableResources());
                            return;
                        }
                    NativeChecks.check("net_physical_supply_attachment", false);
                });
            }
            case "net-denied" -> {
                requireCount(args, 5);
                var host = host(sender, args, 1);
                String expected = args[4];
                host.readGuiAtBoundary(() -> {
                    for (var entry : Objects.requireNonNull(host.machine()).components().assemblyBindings())
                        if (entry.component() instanceof MachineNetworkDevice device) {
                            var permit = device.workPermit();
                            NativeChecks.check("net_actual_denied_state", expected.equals(Objects.toString(device.state())) && !device.allowsNetworkWork()
                                && (permit == null || !permit.current()));
                            pass(player, "net_actual_denied_state", expected);
                            return;
                        }
                    NativeChecks.check("net_denied_actual_attachment", false);
                });
            }
            case "net-work-proof" -> {
                if (args.length != 5 && args.length != 6)
                    throw new WrongUsageException("net-work-proof x y z parallelism [actual-process-count]");
                var host = host(sender, args, 1);
                int expected = CommandBase.parseInt(args[4], 1), expectedCount = args.length == 6 ? CommandBase.parseInt(args[5], 1, 4) : 1;
                host.readGuiAtBoundary(() -> {
                    var owner = Objects.requireNonNull(host.machine());
                    var processes = new it.unimi.dsi.fastutil.objects.ObjectArrayList<com.circulation.prototype_eng.api.recipe.process.RecipeProcess>();
                    for (var recipeHost : owner.recipeHosts())
                        for (var process : recipeHost.activeProcesses()) {
                            NativeChecks.check("net_actual_process_ownership", process.owner() == owner && process.host() == recipeHost && process.recipe().id().equals(id("net_recipe")));
                            NativeChecks.check("net_final_parallelism_actual", com.circulation.prototype_eng.api.recipe.process.RecipeParallelism.effective(process) == expected);
                            processes.add(process);
                        }
                    NativeChecks.check("net_expected_actual_processes", processes.size() == expectedCount);
                    for (var entry : owner.components().assemblyBindings())
                        if (entry.component() instanceof MachineNetworkDevice device) {
                            var permit = Objects.requireNonNull(device.workPermit());
                            var compute = pe.visual.examples.ExampleResources.NET_COMPUTE;
                            var required = com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions.INSTANCE.require(
                                processes.getFirst().recipe()).scaledResources(expected);
                            NativeChecks.check("net_final_numeric_vector", required.size() == 1 && required.getDouble(compute) == 2L * expected
                            );
                            double before = permit.availableResources().getDouble(compute);
                            var receipts = new it.unimi.dsi.fastutil.objects.ObjectArrayList<com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.Reservation>();
                            boolean committed = true;
                            try {
                                for (var actualProcess : processes) {
                                    var lease = permit.requestLease();
                                    NativeChecks.check("net_actual_machine_thread_lease", lease == com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED
                                        || receipts.isEmpty() && lease == com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.LeaseStatus.GRANTED);
                                    var vector = com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions.INSTANCE.require(actualProcess.recipe()).scaledResources(expected);
                                    var receipt = permit.reserve(id("net_budget_probe"), vector);
                                    NativeChecks.check("net_native_budget_receipt", receipt != null && receipt.operation().machineId().equals(Objects.requireNonNull(host.savedIdentity()).machineId())
                                        && receipt.resources().getDouble(compute) == 2L * expected);
                                    receipts.add(receipt);
                                }
                                NativeChecks.check("net_same_machine_lease_shared", permit.requestLease() == com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED);
                                NativeChecks.check("net_native_budget_actual_total_delta", permit.availableResources().getDouble(compute) == before - 2L * expected * expectedCount);
                                for (var receipt : receipts) committed &= receipt.commit();
                            } finally {
                                for (var receipt : receipts) receipt.rollback();
                            }
                            NativeChecks.check("net_native_budget_commit_and_compensation", committed && receipts.stream().allMatch(r -> r.state() == com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget.Reservation.State.ROLLED_BACK)
                                && permit.availableResources().getDouble(compute) == before);
                            pass(player, "net_final_parallelism_actual", "actual processes=" + expectedCount + " each effective=" + expected + " suggested=1; THREAD same-machine lease=1; numeric compute total="
                                + (2L * expected * expectedCount) + " actual issuing-book delta/commit/refund verified");
                            return;
                        }
                    NativeChecks.check("net_work_actual_device", false);
                });
            }
            case "research-kit" -> {
                requireCount(args, 1);
                NativeChecks.check("research_kit_operator", sender.canUseCommand(2, "pe_run_tests"));
                give(player, new ItemStack(Items.IRON_INGOT, 4));
                give(player, new ItemStack(Items.PAPER, 4));
                give(player, new ItemStack(Items.GOLD_INGOT, 2));
                give(player, new ItemStack(Items.REDSTONE, 240));
                give(player, new ItemStack(Items.DIAMOND, 8));
                give(player, new ItemStack(Objects.requireNonNull(diskItem)));
                give(player, new ItemStack(Objects.requireNonNull(lowDiskItem)));
                pass(player, "research_native_kit", "4 iron,4 paper,2 gold,240 redstone,8 diamonds, tier1/tier0 native carriers");
            }
            case "research-start" -> {
                requireCount(args, 4);
                var research = location(args[1]);
                int level = CommandBase.parseInt(args[2], 1);
                ServerResearch.requestStart(player, research, level, result -> expect(player, "research_admission", result.status().name(), args[3]));
            }
            case "research-takeover" -> {
                requireCount(args, 4);
                var research = location(args[1]);
                int level = CommandBase.parseInt(args[2], 1);
                ServerResearch.requestTakeover(player, research, level, () -> true, result -> expect(player, "research_manual_takeover", result.name(), args[3]));
            }
            case "research-level" -> {
                requireCount(args, 4);
                var research = location(args[1]);
                int shared = CommandBase.parseInt(args[2], 0), personal = CommandBase.parseInt(args[3], 0);
                ServerResearch.requestView(player, () -> true, view -> {
                    NativeChecks.check("research_completed_levels", view != null && view.team().sharedLevels().getInt(research) == shared && view.personalLevels().getInt(research) == personal);
                    pass(player, "research_completed_levels", research + " shared=" + shared + " personal=" + personal);
                });
            }
            case "research-queue" -> {
                requireCount(args, 2);
                int count = CommandBase.parseInt(args[1], 0);
                ServerResearch.requestView(player, () -> true, view -> {
                    NativeChecks.check("research_queue_exact", view != null && view.team().queueRows().size() == count);
                    pass(player, "research_queue_exact", "rows=" + view.team().queueRows() + " manual=" + view.team().manualRows() + " progress=" + view.team().numericProgress());
                });
            }
            case "research-manual-paid", "research-manual-checkpoint", "research-manual-resume" -> {
                requireCount(args, args[0].equals("research-manual-paid") ? 2 : 1);
                long expected = args[0].equals("research-manual-paid") ? CommandBase.parseLong(args[1], 0, Long.MAX_VALUE) : 0;
                ServerTaskSchedulers.forServer(server).submit(() -> {
                    var membership = new FtbOwnerTeamResolver(server).resolve(player.getUniqueID());
                    NativeChecks.check("research_manual_actual_team", membership.accepted());
                    var team = ServerResearch.forServer(server).snapshot().teams().stream().filter(t -> t.teamId().equals(membership.teamId())).findFirst().orElseThrow();
                    NativeChecks.check("research_manual_actual_session", team.manualSessions().size() == 1);
                    var manual = team.manualSessions().getFirst();
                    long paid = 0;
                    for (var entry : manual.work().object2LongEntrySet())
                        paid = Math.addExact(paid, entry.getLongValue());
                    if (args[0].equals("research-manual-paid"))
                        NativeChecks.check("research_manual_exact_paid", paid == expected);
                    else if (args[0].equals("research-manual-checkpoint")) {
                        evidence(player).setLong("manual_paid", paid);
                        evidence(player).setUniqueId("manual_session", manual.id());
                        evidence(player).setLong("manual_queue_entry", manual.queueKey().queueEntryId());
                        persistEvidence(player);
                    } else
                        NativeChecks.check("research_manual_exact_resume", evidence(player).hasUniqueId("manual_session")
                            && evidence(player).getUniqueId("manual_session").equals(manual.id()) && evidence(player).getLong("manual_paid") == paid
                            && evidence(player).getLong("manual_queue_entry") == manual.queueKey().queueEntryId());
                    pass(player, args[0].equals("research-manual-resume") ? "research_manual_exact_resume" : "research_manual_exact_paid",
                        "session=" + manual.id() + " queueEntry=" + manual.queueKey().queueEntryId() + " actualPaid=" + paid);
                }, ExecutionMode.MAIN_THREAD);
            }
            case "research-blacklist" -> {
                requireCount(args, 4);
                var research = location(args[1]);
                boolean value = CommandBase.parseBoolean(args[2]);
                ServerResearch.requestBlacklist(player, research, value, () -> true, result -> expect(player, "research_permission_blacklist", result.name(), args[3]));
            }
            case "research-station-progress", "research-station-checkpoint", "research-station-resume" -> {
                requireCount(args, args[0].equals("research-station-progress") ? 2 : 1);
                double expected = args[0].equals("research-station-progress") ? CommandBase.parseDouble(args[1], 0) : 0;
                ServerTaskSchedulers.forServer(server).submit(() -> {
                    var membership = new FtbOwnerTeamResolver(server).resolve(player.getUniqueID());
                    NativeChecks.check("research_station_actual_team", membership.accepted());
                    var team = ServerResearch.forServer(server).snapshot().teams().stream().filter(t -> t.teamId().equals(membership.teamId())).findFirst().orElseThrow();
                    var head = Objects.requireNonNull(team.queue().activeHead(), "No active research");
                    var state = team.queue().progress().stream().filter(value -> value.target().equals(head.target())).findFirst().orElseThrow();
                    double progress = state.resources().getDouble(pe.visual.examples.ExampleResources.NET_COMPUTE);
                    var image = new NBTTagCompound();
                    image.setString("research", head.researchId().toString());
                    image.setInteger("level", head.targetLevel());
                    image.setLong("elapsed", state.elapsedTicks());
                    image.setDouble("numeric_progress", progress);
                    var materials = new NBTTagList();
                    var keys = com.circulation.prototype_eng.api.persistence.ResourceKeyNbt.INSTANCE;
                    for (var key : ResearchRegistry.INSTANCE.require(head.researchId()).stageForLevel(head.targetLevel()).materialTotals().keySet()) {
                        var material = new NBTTagCompound();
                        material.setTag("key", keys.encode(key));
                        material.setLong("remaining", state.remainingMaterials().getLong(key));
                        materials.appendTag(material);
                    }
                    image.setTag("materials", materials);
                    if (args[0].equals("research-station-progress"))
                        NativeChecks.check("research_station_exact_numeric_progress", progress == expected);
                    else if (args[0].equals("research-station-checkpoint")) {
                        evidence(player).setTag("station_checkpoint", image);
                        persistEvidence(player);
                    } else
                        NativeChecks.check("research_station_saved_progress_resume", evidence(player).getCompoundTag("station_checkpoint").equals(image));
                    pass(player, args[0].equals("research-station-resume") ? "research_station_saved_progress_resume" : "research_station_exact_numeric_progress",
                        "progress=" + progress + " elapsed=" + state.elapsedTicks() + " remaining=" + state.remainingMaterials());
                }, ExecutionMode.MAIN_THREAD);
            }
            case "research-admin-player" -> {
                requireCount(args, 5);
                var target = UUID.fromString(args[1]);
                var research = location(args[2]);
                int level = CommandBase.parseInt(args[3], 0);
                ServerResearch.requestAdminPlayerLevel(player, target, research, level, () -> true,
                    result -> expect(player, "research_permission_admin", result.name(), args[4]));
            }
            case "research-reward-mode" -> {
                requireCount(args, 2);
                NativeChecks.check("research_reward_mode", args[1].equals("wait") || args[1].equals("ready"));
                evidence(player).setBoolean("reward_wait", args[1].equals("wait"));
                persistEvidence(player);
                pass(player, "research_reward_mode", args[1]);
            }
            case "research-reward" -> {
                requireCount(args, 5);
                var research = location(args[1]);
                int level = CommandBase.parseInt(args[2], 1);
                var group = location(args[3]);
                ServerResearch.requestReward(player, research, level, group, result -> expect(player, "research_reward_durable", result.status().name(), args[4]));
            }
            case "research-event-count" -> {
                requireCount(args, 2);
                int count = CommandBase.parseInt(args[1], 0);
                NativeChecks.check("research_event_durable_once", evidence(player).getInteger("reward_event_effects") == count);
                persistEvidence(player);
                pass(player, "research_event_durable_once", "actual effects=" + count + " verified native saved file");
            }
            case "research-claim" -> {
                requireCount(args, 4);
                var research = location(args[1]);
                int level = CommandBase.parseInt(args[2], 1);
                ServerResearch.requestView(player, () -> true, view -> {
                    var claim = view.claims().stream().filter(c -> c.researchId().equals(research) && c.level() == level).findFirst();
                    NativeChecks.check("research_reward_state", claim.isPresent() && claim.orElseThrow().status().name().equals(args[3]));
                    pass(player, "research_reward_state", claim.orElseThrow() + " eventEffects=" + evidence(player).getInteger("reward_event_effects"));
                });
            }
            case "research-database" -> {
                requireCount(args, 3);
                var research = location(args[1]);
                int level = CommandBase.parseInt(args[2], 0);
                ServerResearch.requestDataCenterView(player, () -> true, view -> {
                    NativeChecks.check("research_database_exact", view != null && view.dataCenter() != null && view.dataCenter().storedResearch().getInt(research) == level);
                    pass(player, "research_database_exact", view.dataCenter().toString());
                });
            }
            case "research-download", "research-item" -> {
                boolean item = args[0].equals("research-item");
                requireCount(args, item ? 5 : 4);
                var research = location(args[1]);
                int level = CommandBase.parseInt(args[2], 1);
                int slot = item ? CommandBase.parseInt(args[3], 0, 35) : 0;
                ServerResearch.requestDataCenterView(player, () -> true, view -> {
                    NativeChecks.check("research_selected_database", view != null && view.dataCenter() != null);
                    if (item)
                        requestItemDownload(player, view.teamId(), view.dataCenter().dataCenterId(), research, slot, UUID.randomUUID(),
                            result -> expect(player, "research_item_download", result.status().name(), args[4]));
                    else
                        ServerResearch.requestDataCenterDownload(player, view.teamId(), view.dataCenter().dataCenterId(), research, level, () -> true,
                            result -> expect(player, "research_database_download", result.status().name(), args[3]));
                });
            }
            case "research-encoding-blank" -> {
                requireCount(args, 3);
                int slot = CommandBase.parseInt(args[1], 0, 35);
                int tier = CommandBase.parseInt(args[2], 1, 2);
                NativeChecks.check("research_encoding_blank_operator", sender.canUseCommand(2, "pe_run_tests"));
                var stack = player.inventory.getStackInSlot(slot);
                NativeChecks.check("research_actual_blank_disk", stack.getItem() == diskItem && !((ResearchDiskCarrier) stack.getItem()).hasResearchDiskData(stack));
                var tag = stack.getTagCompound() == null ? new NBTTagCompound() : stack.getTagCompound();
                if (tier == 2) tag.setInteger("pe_run_tests_physical_tier", tier);
                else tag.removeTag("pe_run_tests_physical_tier");
                stack.setTagCompound(tag.isEmpty() ? null : tag);
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
                pass(player, "research_actual_blank_disk", "slot=" + slot + " physicalTier=" + tier + " no research payload; setup only");
            }
            case "research-encode" -> {
                requireCount(args, 4);
                var research = location(args[1]);
                int slot = CommandBase.parseInt(args[2], 0, 35);
                ServerResearch.requestDataCenterView(player, () -> true, view -> {
                    NativeChecks.check("research_encoding_actual_database", view != null && view.dataCenter() != null);
                    var nonce = UUID.randomUUID();
                    recordEncodingIntent(player, nonce, research, 1, slot);
                    requestItemDownload(player, view.teamId(), view.dataCenter().dataCenterId(), research, slot, nonce,
                        result -> expect(player, "research_actual_disk_encoding", result.status().name(), args[3]));
                });
            }
            case "research-encoded", "research-encoding-rejected" -> {
                requireCount(args, args[0].equals("research-encoded") ? 3 : 2);
                int outputSlot = args[0].equals("research-encoded") ? CommandBase.parseInt(args[1], 0, 35) : -1;
                int after = CommandBase.parseInt(args[args.length - 1], 0);
                ServerTaskSchedulers.forServer(server).submit(() -> verifyEncoding(player, outputSlot, after), ExecutionMode.MAIN_THREAD);
            }
            case "research-disk-write", "research-disk-mismatch" -> {
                requireCount(args, 4);
                int slot = CommandBase.parseInt(args[1], 0, 35);
                var research = location(args[2]);
                int level = CommandBase.parseInt(args[3], 1);
                NativeChecks.check("research_disk_fixture_operator", sender.canUseCommand(2, "pe_run_tests"));
                var stack = player.inventory.getStackInSlot(slot);
                NativeChecks.check("research_native_disk_carrier", stack.getItem() instanceof RunDisk);
                var levels = new Object2IntOpenHashMap<ResourceLocation>();
                levels.put(research, level);
                var data = new ResearchDiskData(ResearchRegistry.INSTANCE, args[0].equals("research-disk-mismatch") ? 1
                    : ((ResearchDiskCarrier) stack.getItem()).researchDiskTier(stack), levels);
                player.inventory.setInventorySlotContents(slot, ResearchDiskNbtCodec.writeCopy(stack, data));
                player.inventoryContainer.detectAndSendChanges();
                pass(player, "research_native_disk_write", "slot=" + slot + " tier=" + data.tier() + " entries=" + data.entries());
            }
            case "research-disk-import" -> {
                requireCount(args, 3);
                int slot = CommandBase.parseInt(args[1], 0, 35);
                ServerResearch.requestDataCenterView(player, () -> true, view -> {
                    NativeChecks.check("research_selected_database", view != null && view.dataCenter() != null);
                    ServerResearch.requestDiskImport(player, view.teamId(), view.dataCenter().dataCenterId(), slot, UUID.randomUUID(), () -> true,
                        result -> expect(player, "research_native_disk_import", result.status().name(), args[2]));
                });
            }
            case "research-prepare-exit" -> {
                requireCount(args, 4);
                boolean disk = args[1].equals("disk"), encode = args[1].equals("encode");
                NativeChecks.check("research_prepare_kind", disk || encode || args[1].equals("item"));
                var research = location(args[2]);
                int slot = CommandBase.parseInt(args[3], 0, 35);
                ServerTaskSchedulers.forServer(server).submit(() -> prepareAndExit(player, disk, encode, false, research, slot), ExecutionMode.MAIN_THREAD);
            }
            case "research-encode-output-exit" -> {
                requireCount(args, 3);
                var research = location(args[1]);
                int slot = CommandBase.parseInt(args[2], 0, 35);
                ServerTaskSchedulers.forServer(server).submit(() -> prepareAndExit(player, false, true, true, research, slot), ExecutionMode.MAIN_THREAD);
            }
            case "research-pending" -> {
                requireCount(args, 3);
                String kind = args[1];
                int count = CommandBase.parseInt(args[2], 0);
                ServerTaskSchedulers.forServer(server).submit(() -> {
                    var world = ServerResearch.forServer(server);
                    int actual;
                    if (kind.equals("disk")) {
                        actual = 0;
                        var operations = world.pendingDiskImports(player.getUniqueID());
                        for (int index = 0; index < operations.size(); index++)
                            if (!operations.get(index).terminal()) actual++;
                    } else actual = world.pendingDownloadOperations(player.getUniqueID()).size();
                    NativeChecks.check("research_pending_recovery", actual == count);
                    pass(player, "research_pending_recovery", kind + " count=" + actual + " receipts=" + world.downloadReceipts(player.getUniqueID()));
                }, ExecutionMode.MAIN_THREAD);
            }
            case "research-recovered" -> {
                requireCount(args, 2);
                String kind = args[1];
                ServerTaskSchedulers.forServer(server).submit(() -> {
                    var saved = evidence(player);
                    var world = ServerResearch.forServer(server);
                    NativeChecks.check("research_recovery_original_nonce", saved.getString("prepared_kind").equals(kind) && saved.hasUniqueId("prepared_nonce"));
                    var nonce = saved.getUniqueId("prepared_nonce");
                    boolean complete = kind.equals("disk") ? world.snapshot().diskImports().stream().anyMatch(op -> op.intent().playerId().equals(player.getUniqueID())
                        && op.intent().nonce().equals(nonce) && op.status() == ResearchDiskOperation.Status.RELEASED)
                        : world.downloadReceipts(player.getUniqueID()).stream().anyMatch(receipt -> receipt.intent().nonce().equals(nonce)
                        && receipt.outcome() == com.circulation.prototype_eng.api.research.download.ResearchDownloadReceipt.Outcome.COMPLETED);
                    NativeChecks.check("research_cross_session_exact_recovery", complete && !MinecraftResearchDownloadInventory.hasPending(player));
                    pass(player, "research_cross_session_exact_recovery", "kind=" + kind + " originalNonce=" + nonce + " materialEvidenceReleased");
                }, ExecutionMode.MAIN_THREAD);
            }
            case "render-data" -> {
                requireCount(args, 7);
                var host = host(sender, args, 1);
                String state = args[4], layers = args[5];
                int value = CommandBase.parseInt(args[6]);
                host.readGuiAtBoundary(() -> {
                    var owner = Objects.requireNonNull(host.machine());
                    var data = Objects.requireNonNull(owner.components().get(DataComponents.MACHINE));
                    data.set("animation", new NBTTagString(state.equals("empty") ? "" : state));
                    data.set("animation_layers", new NBTTagString(layers.equals("empty") ? "" : layers));
                    data.set("run_owner_value", new NBTTagInt(value));
                    pass(player, "render_owner_data_write", "pos=" + host.blockEntity().getPos() + " revision=" + data.snapshot().revision() + " value=" + value);
                });
            }
            case "render-data-check" -> {
                requireCount(args, 5);
                var host = host(sender, args, 1);
                int expected = CommandBase.parseInt(args[4]);
                host.readGuiAtBoundary(() -> {
                    var data = Objects.requireNonNull(Objects.requireNonNull(host.machine()).components().get(DataComponents.MACHINE));
                    NativeChecks.check("render_owner_data_resume", data.snapshot().data().hasKey("run_owner_value") && data.snapshot().data().getInteger("run_owner_value") == expected);
                    pass(player, "render_owner_data_resume", "savedValue=" + expected);
                });
            }
            case "render-orient" -> {
                requireCount(args, 5);
                var host = host(sender, args, 1);
                var orientation = BlockOrientation.valueOf(args[4]);
                host.transformOrientation(_ -> orientation);
                pass(player, "render_actual_orientation", orientation.name());
            }
            case "render-typed" -> {
                requireCount(args, 5);
                var host = host(sender, args, 1);
                String layers = args[4];
                host.readGuiAtBoundary(() -> {
                    var typed = Objects.requireNonNull(Objects.requireNonNull(host.machine()).components().get(com.circulation.prototype_eng.api.machine.render.MachineAnimationState.TYPE));
                    typed.setLayers(layers.equals("empty") ? List.of() : List.of(layers.split(",")));
                    pass(player, "render_actual_typed_layers", typed.snapshot().toString());
                });
            }
            case "client-update", "client-notify-failure" -> {
                requireCount(args, 1);
                retainedUpdate = new ZenRecipeUpdates(com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates.forServer(server));
                if (args[0].equals("client-notify-failure")) failNoticeServer = server;
                retainedUpdate.replace(clientV2Recipes(), "pe_run_tests:directory", "v2").onComplete(result -> {
                    NativeChecks.check("client_actual_publication", result.published());
                    NativeChecks.check("client_notification_result", result.status().equals(args[0].equals("client-update") ? "PUBLISHED" : "NOTIFICATION_FAILED"));
                    pass(player, "client_actual_publication", result.status() + " session=" + result.session() + " generation=" + result.generation());
                });
            }
            case "client-capture-old", "client-old-process", "client-old-finished" -> {
                requireCount(args, 5);
                var host = host(sender, args, 1);
                String nonce = args[4];
                host.readGuiAtBoundary(() -> migrationCheck(player, host, args[0], nonce));
            }
            case "client-old-handle" -> {
                requireCount(args, 2);
                NativeChecks.check("client_retained_old_handle", retainedUpdate != null);
                retainedUpdate.replace(clientV2Recipes(), "pe_run_tests:directory", "v2").onComplete(result -> {
                    NativeChecks.check("client_old_handle_rejected", !result.published() && result.status().equals(args[1]));
                    pass(player, "client_old_handle_rejected", result.status());
                });
            }
            case "client-server-manifest" -> {
                requireCount(args, 2);
                var status = ServerRecipeManifests.status();
                NativeChecks.check("client_actual_server_manifest", status != null && status.manifestSent() && status.generation() == CommandBase.parseLong(args[1]));
                pass(player, "client_actual_server_manifest", status.toString());
            }
            default -> throw new WrongUsageException("Unknown PE run network/render action: " + args[0]);
        }
        return true;
    }

    private static void prepareAndExit(EntityPlayerMP player, boolean disk, boolean encoding, boolean holdOutput, ResourceLocation research, int slot) {
        var server = Objects.requireNonNull(player.getServer());
        var world = ServerResearch.forServer(server);
        var membership = new FtbOwnerTeamResolver(server).resolve(player.getUniqueID());
        NativeChecks.check("research_prepared_real_team", membership.accepted());
        var forgePlayer = Objects.requireNonNull(Universe.get().getPlayer(player.getUniqueID()));
        var permission = new ResearchPermissionContext(player.getUniqueID(), membership.teamId(), null, membership.teamId(),
            forgePlayer.team.isOwner(forgePlayer), forgePlayer.team.isModerator(forgePlayer), server.getPlayerList().canSendCommands(player.getGameProfile()));
        var plan = com.circulation.prototype_eng.machine.ServerNetworkRuntime.forServer(server).plan();
        var teamPlan = Objects.requireNonNull(Objects.requireNonNull(plan).teams().get(membership.teamId()));
        var databaseId = Objects.requireNonNull(teamPlan.dataCenterId());
        var selected = new ResearchDataCenters(ServerMachineIdentities.forServer(server), () -> requireThread(server)).capture(membership.teamId(), databaseId);
        NativeChecks.check("research_prepared_real_database", selected != null && selected.current());
        var inventory = new MinecraftResearchDownloadInventory(player);
        int downloadLevel = disk || encoding ? 1 : ResearchItemDownloadRegistry.INSTANCE.find(
            AEItemKey.of(player.inventory.getStackInSlot(slot)), research).researchLevel();
        var nonce = UUID.randomUUID();
        if (encoding) recordEncodingIntent(player, nonce, research, downloadLevel, slot);
        long time = Math.max(world.serverTime(), server.getWorld(0).getTotalWorldTime());
        if (disk) {
            var result = world.requestDiskImport(new ResearchDiskOperation.Intent(player.getUniqueID(), nonce, databaseId, membership.teamId(), slot),
                membership, permission, selected.target(), selected.storage(), inventory, material -> {
                    var stack = new ItemStack(material.material());
                    NativeChecks.check("research_prepared_native_carrier", stack.getItem() instanceof ResearchDiskCarrier);
                    return ResearchItemImportRegistry.INSTANCE.evaluate(
                        new ResearchItemImportContext(ResearchRegistry.INSTANCE,
                            stack, player, databaseId, membership.teamId(), material.operationId()));
                }, null, new MinecraftItemDeliveryAdapter(player), time, selected::current, selected::physicalCurrent,
                () -> world.pendingDiskImports(player.getUniqueID()).stream().noneMatch(op -> op.intent().nonce().equals(nonce)));
            NativeChecks.check("research_disk_saved_prepared", result.operationId() != null && world.pendingDiskImports(player.getUniqueID()).stream()
                                                                                                    .anyMatch(op -> op.id().equals(result.operationId()) && op.status() == ResearchDiskOperation.Status.PREPARED));
        } else {
            var intent = new ResearchDownloadOperation.Intent(player.getUniqueID(), nonce, databaseId, membership.teamId(), research, downloadLevel, slot, false, null);
            var result = world.requestItemDownload(intent, membership, permission, selected.snapshot(), inventory,
                material -> ResearchItemDownloadRegistry.INSTANCE.evaluate(new ResearchItemDownloadContext(downloadLevel,
                    new ItemStack(material.material()), ResearchRegistry.INSTANCE.require(research), player, server, player.getServerWorld(),
                    databaseId, membership.teamId(), player.world.getTotalWorldTime(), material.operationId())), null, null, () -> true, time,
                new MinecraftItemDeliveryAdapter(player), selected::current, () -> holdOutput && world.pendingDownloadOperations(player.getUniqueID()).stream()
                                                                                                      .noneMatch(op -> op.intent().nonce().equals(nonce) && op.status() == ResearchDownloadOperation.Status.DELIVERING));
            NativeChecks.check("research_item_saved_prepared", result.operationId() != null && world.pendingDownloadOperations(player.getUniqueID()).stream()
                                                                                                    .anyMatch(op -> op.id().equals(result.operationId()) && op.status() == (holdOutput ? ResearchDownloadOperation.Status.DELIVERING : ResearchDownloadOperation.Status.PREPARED)));
        }
        String kind = disk ? "disk" : encoding ? (holdOutput ? "encode_output" : "encode") : "item";
        evidence(player).setString("prepared_kind", kind);
        evidence(player).setUniqueId("prepared_nonce", nonce);
        persistEvidence(player);
        player.connection.disconnect(new TextComponentString("PE_RUN_TESTS saved real " + (holdOutput ? "DELIVERING" : "PREPARED") + " " + kind + "; rejoin and check recovery"));
    }

    private static void migrationCheck(EntityPlayerMP player, MachineControllerHost host, String action, String nonce) {
        var owner = Objects.requireNonNull(host.machine());
        var saved = evidence(player);
        com.circulation.prototype_eng.api.recipe.process.RecipeProcess process = null;
        for (var recipeHost : owner.recipeHosts())
            for (var candidate : recipeHost.activeProcesses())
                if (candidate.recipe().id().equals(id("client_migration"))) {
                    NativeChecks.check("client_single_actual_migration_process", process == null);
                    process = candidate;
                }
        if (action.equals("client-old-finished")) {
            NativeChecks.check("client_old_completion_owner", saved.getCompoundTag("migration_machine").equals(Objects.requireNonNull(host.savedIdentity()).encode())
                && nonce.equals(saved.getString("migration_nonce")) && process == null);
            var amounts = new AEKey2LongMap.OpenHashMap();
            var inputs = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<com.circulation.prototype_eng.api.storage.ResourceStorage>();
            var outputs = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<com.circulation.prototype_eng.api.storage.ResourceStorage>();
            for (var hatch : com.circulation.prototype_eng.api.machine.component.MachineHatches.get(owner).resources()) {
                if (hatch.input() != null) inputs.add(hatch.input());
                if (hatch.output() != null) outputs.add(hatch.output());
            }
            var quartz = AEItemKey.of(new ItemStack(Items.QUARTZ));
            var redstone = AEItemKey.of(new ItemStack(Items.REDSTONE));
            for (var input : inputs) {
                amounts.addTo(quartz, input.amount(quartz));
                amounts.addTo(redstone, input.amount(redstone));
            }
            NativeChecks.check("client_old_duration_tick_material_payment", amounts.getLong(quartz) == 0 && amounts.getLong(redstone) == 0);
            var emerald = AEItemKey.of(new ItemStack(Items.EMERALD));
            var diamond = AEItemKey.of(new ItemStack(Items.DIAMOND));
            for (var output : outputs) {
                amounts.addTo(emerald, output.amount(emerald));
                amounts.addTo(diamond, output.amount(diamond));
            }
            NativeChecks.check("client_old_definition_actual_completion", amounts.getLong(emerald) == 1 && amounts.getLong(diamond) == 0);
            pass(player, "client_old_definition_actual_completion", "old v1 2400 ticks paid/redstone2400, emerald1, diamond0; nonce=" + nonce);
            return;
        }
        var active = Objects.requireNonNull(process, "No actual active client_migration process");
        var codec = Objects.requireNonNull(Objects.requireNonNull(RecipeRegistry.INSTANCE.processCodecs()).snapshot().get(active.recipe()));
        NativeChecks.check("client_old_actual_owner_codec", active.owner() == owner && active.host().activeProcesses().contains(active) && active.state().started()
            && active.recipe().durationTicks() == 2400 && codec.revision().equals("v1") && active.components().get(DataComponents.PROCESS) != null);
        var data = Objects.requireNonNull(active.components().get(DataComponents.PROCESS));
        if (action.equals("client-capture-old")) {
            data.set("migration_nonce", new NBTTagString(nonce));
            data.set("migration_seed", new NBTTagLong(active.seed()));
            var tag = codec.encode(active);
            saved.setTag("migration_machine", Objects.requireNonNull(host.savedIdentity()).encode());
            saved.setLong("migration_seed", active.seed());
            saved.setString("migration_nonce", nonce);
            saved.setTag("migration_graph", tag.getTag("Graph"));
            persistEvidence(player);
            pass(player, "client_captured_actual_v1_process", "recipe=" + active.recipe().id() + " revision=v1 duration=2400 seed=" + active.seed() + " nonce=" + nonce);
            return;
        }
        var current = RecipeRegistry.INSTANCE.snapshot();
        var replacement = current.catalog().recipes().stream().filter(r -> r.id().equals(id("client_migration"))).findFirst().orElseThrow();
        NativeChecks.check("client_old_process_survives_publication", current.revision(replacement).equals("v2") && replacement != active.recipe() && replacement.durationTicks() == 40
            && current.historicalRecipe(new com.circulation.prototype_eng.api.recipe.RecipeDefinitionRevision(id("client_migration"), "v1")) == active.recipe()
            && saved.getCompoundTag("migration_machine").equals(Objects.requireNonNull(host.savedIdentity()).encode()) && active.seed() == saved.getLong("migration_seed")
            && nonce.equals(saved.getString("migration_nonce")) && data.snapshot().data().getString("migration_nonce").equals(nonce)
            && Objects.requireNonNull(codec.encode(active).getTag("Graph")).equals(saved.getTag("migration_graph")));
        pass(player, "client_old_process_survives_publication", "active actual v1 duration2400/codec graph/seed/data retained; current v2 duration40; successfulTicks=" + active.state().successfulTicks());
    }

    private static void requestItemDownload(EntityPlayerMP player, String team, UUID database,
                                            ResourceLocation research, int slot, UUID nonce,
                                            Consumer<ResearchDownloadResult> result) {
        var inventory = new MinecraftResearchDownloadInventory(player);
        var delivery = new MinecraftItemDeliveryAdapter(player);
        ServerResearch.requestItemDownloadPreview(player, team, database, research, slot, inventory, delivery, () -> true, preview -> {
            if (preview == null) {
                result.accept(new ResearchDownloadResult(ResearchDownloadResult.Status.NO_EVENT, null, null));
                return;
            }
            if (!preview.ready()) {
                result.accept(new ResearchDownloadResult(ResearchDownloadResult.Status.SOURCE_REJECTED, null, preview.sources().status()));
                return;
            }
            ServerResearch.requestItemDownload(player, team, database, preview, nonce, inventory,
                delivery, () -> true, result);
        });
    }

    private static void recordEncodingIntent(EntityPlayerMP player, UUID nonce, ResourceLocation research, int level, int slot) {
        var material = player.inventory.getStackInSlot(slot);
        NativeChecks.check("research_encoding_native_material", material.getItem() instanceof ResearchDiskCarrier
            && !((ResearchDiskCarrier) material.getItem()).hasResearchDiskData(material));
        int tier = ((ResearchDiskCarrier) material.getItem()).researchDiskTier(material);
        var saved = evidence(player);
        saved.setUniqueId("encoding_nonce", nonce);
        saved.setString("encoding_research", research.toString());
        saved.setInteger("encoding_level", level);
        saved.setInteger("encoding_tier", tier);
        saved.setInteger("encoding_slot", slot);
        saved.setTag("encoding_material", material.serializeNBT());
        saved.setLong("encoding_blank_total", inventoryAmount(player, AEItemKey.of(material)));
        saved.setLong("encoding_output_before", encodedAmount(player, material.getItem(), research, level, tier));
        saved.setInteger("encoding_source_before", sourceLevel(player, research));
        persistEvidence(player);
    }

    private static void verifyEncoding(EntityPlayerMP player, int outputSlot, int expectedSourceAfter) {
        var saved = evidence(player);
        NativeChecks.check("research_encoding_original_nonce", saved.hasUniqueId("encoding_nonce"));
        var nonce = saved.getUniqueId("encoding_nonce");
        var research = new ResourceLocation(saved.getString("encoding_research"));
        int level = saved.getInteger("encoding_level"), tier = saved.getInteger("encoding_tier");
        var material = new ItemStack(saved.getCompoundTag("encoding_material"));
        var key = Objects.requireNonNull(AEItemKey.of(material));
        var world = ServerResearch.forServer(Objects.requireNonNull(player.getServer()));
        var receipt = world.downloadReceipts(player.getUniqueID()).stream().filter(r -> r.intent().nonce().equals(nonce)).findFirst();
        if (outputSlot < 0) {
            NativeChecks.check("research_encoding_rejected_no_effect", receipt.isEmpty() && world.pendingDownloadOperations(player.getUniqueID()).stream().noneMatch(op -> op.intent().nonce().equals(nonce))
                && player.inventory.getStackInSlot(saved.getInteger("encoding_slot")).serializeNBT().equals(saved.getCompoundTag("encoding_material"))
                && inventoryAmount(player, key) == saved.getLong("encoding_blank_total") && sourceLevel(player, research) == expectedSourceAfter
                && expectedSourceAfter == saved.getInteger("encoding_source_before"));
            pass(player, "research_encoding_rejected_no_effect", "original slot/material/source untouched; nonce=" + nonce);
            return;
        }
        NativeChecks.check("research_encoding_completed_exact_nonce", receipt.isPresent() && receipt.orElseThrow().outcome() == ResearchDownloadReceipt.Outcome.COMPLETED
            && receipt.orElseThrow().intent().researchId().equals(research) && receipt.orElseThrow().intent().level() == level
            && receipt.orElseThrow().intent().slot() == saved.getInteger("encoding_slot") && receipt.orElseThrow().amount() == 1);
        var completed = receipt.orElseThrow();
        var output = player.inventory.getStackInSlot(outputSlot);
        NativeChecks.check("research_encoding_actual_native_output", output.getItem() instanceof ResearchDiskCarrier && output.getCount() == 1);
        var disk = ((ResearchDiskCarrier) output.getItem()).readResearchDisk(output, ResearchRegistry.INSTANCE);
        NativeChecks.check("research_encoding_payload_and_native_item", disk != null && disk.tier() == tier && disk.entries().size() == 1 && disk.entries().getInt(research) == level
            && output.serializeNBT().equals(completed.output())
            && ((ResearchDiskCarrier) output.getItem()).emptyResearchDisk(output).serializeNBT().equals(material.serializeNBT()));
        var energy = output.getCapability(net.minecraftforge.energy.CapabilityEnergy.ENERGY, null);
        NativeChecks.check("research_encoding_actual_native_forge_caps", completed.output().hasKey("ForgeCaps") && energy != null && energy.getEnergyStored() == 37);
        NativeChecks.check("research_encoding_single_material_and_output", inventoryAmount(player, key) == saved.getLong("encoding_blank_total") - 1
            && encodedAmount(player, output.getItem(), research, level, tier) == saved.getLong("encoding_output_before") + 1
            && !MinecraftResearchDownloadInventory.hasPending(player));
        var sources = completed.sources();
        int before = saved.getInteger("encoding_source_before"), cost = sources.cost();
        NativeChecks.check("research_encoding_declared_cost", sources.cost() == cost && before - cost == expectedSourceAfter);
        if (sources.policy() == com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM) {
            NativeChecks.check("research_encoding_exact_personal_source_plan", sources.memberChanges().stream().anyMatch(change -> change.memberId().equals(player.getUniqueID())
                && change.beforeLevel() == before && change.afterLevel() == expectedSourceAfter));
            NativeChecks.check("research_encoding_all_real_member_source_levels", sources.memberChanges().stream().allMatch(change -> change.beforeLevel() - cost == change.afterLevel()
                && world.player(change.memberId()).completedLevel(research) == change.afterLevel()));
        } else
            NativeChecks.check("research_encoding_exact_team_source_plan", sources.teamBeforeLevel() == before && sources.teamAfterLevel() == expectedSourceAfter);
        NativeChecks.check("research_encoding_actual_source_after", sourceLevel(player, research) == expectedSourceAfter);
        persistEvidence(player);
        pass(player, "research_actual_disk_encoding_verified", "nonce=" + nonce + " materialSlot=" + saved.getInteger("encoding_slot") + " outputSlot=" + outputSlot
            + " tier=" + tier + " completedLevel=" + level + " source=" + before + "->" + expectedSourceAfter + " full native output and exactly one leaf");
    }

    private static int sourceLevel(EntityPlayerMP player, ResourceLocation research) {
        var server = Objects.requireNonNull(player.getServer());
        var world = ServerResearch.forServer(server);
        if (ResearchRegistry.INSTANCE.require(research).deliveryPolicy() == com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
            return world.player(player.getUniqueID()).completedLevel(research);
        var membership = new FtbOwnerTeamResolver(server).resolve(player.getUniqueID());
        NativeChecks.check("research_encoding_current_source_team", membership.accepted());
        return world.snapshot().teams().stream().filter(t -> t.teamId().equals(membership.teamId())).findFirst().orElseThrow().research().levels().getInt(research);
    }

    private static long inventoryAmount(EntityPlayerMP player, AEItemKey key) {
        var amounts = new AEKey2LongMap.OpenHashMap();
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            var stack = player.inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) amounts.addTo(AEItemKey.of(stack), stack.getCount());
        }
        return amounts.getLong(key);
    }

    private static long encodedAmount(EntityPlayerMP player, Item item, ResourceLocation research, int level, int tier) {
        long amount = 0;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            var stack = player.inventory.getStackInSlot(slot);
            if (stack.getItem() != item || !(item instanceof ResearchDiskCarrier carrier) || !carrier.hasResearchDiskData(stack))
                continue;
            var data = carrier.readResearchDisk(stack, ResearchRegistry.INSTANCE);
            if (data != null && data.tier() == tier && data.entries().size() == 1 && data.entries().getInt(research) == level)
                amount = Math.addExact(amount, stack.getCount());
        }
        return amount;
    }

    private static MachineControllerHost host(ICommandSender sender, String[] args, int start) throws CommandException {
        var pos = CommandBase.parseBlockPos(sender, args, start, false);
        var world = sender.getEntityWorld();
        NativeChecks.check("native_loaded_host", world.isBlockLoaded(pos) && world.getTileEntity(pos) instanceof MachineControllerHost);
        return (MachineControllerHost) world.getTileEntity(pos);
    }

    private static ResourceLocation location(String text) {
        return text.contains(":") ? new ResourceLocation(text) : id(text);
    }

    private static void requireCount(String[] args, int count) throws WrongUsageException {
        if (args.length != count)
            throw new WrongUsageException("Expected " + (count - 1) + " arguments for " + args[0]);
    }

    private static void expect(EntityPlayerMP player, String id, String actual, String expected) {
        NativeChecks.check(id + " expected=" + expected + " actual=" + actual, expected.equals(actual));
        pass(player, id, actual);
    }

    private static void pass(EntityPlayerMP player, String id, String detail) {
        player.sendMessage(new TextComponentString("PE_RUN_TESTS PASS " + id + " " + detail));
    }

    private static void give(EntityPlayerMP player, ItemStack stack) {
        if (!player.inventory.addItemStackToInventory(stack) && !stack.isEmpty()) player.dropItem(stack, false);
        player.inventoryContainer.detectAndSendChanges();
    }

    private static NBTTagCompound evidence(EntityPlayerMP player) {
        var root = player.getEntityData();
        if (!root.hasKey(EntityPlayer.PERSISTED_NBT_TAG))
            root.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        var persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (!persisted.hasKey(EVIDENCE)) persisted.setTag(EVIDENCE, new NBTTagCompound());
        return persisted.getCompoundTag(EVIDENCE);
    }

    private static void persistEvidence(EntityPlayerMP player) {
        var expected = evidence(player).copy();
        var server = Objects.requireNonNull(player.getServer());
        PlayerDataPersistence.persist(player, () -> requireThread(server), saved -> {
            var actual = saved.getCompoundTag("ForgeData").getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG).getCompoundTag(EVIDENCE);
            if (!expected.equals(actual))
                throw new IOException("PE run evidence did not reach the actual vanilla player file");
        });
    }

    private static void requireThread(MinecraftServer server) {
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("PE run action requires actual server thread");
    }

    private static final class RunDisk extends Item implements ResearchDiskCarrier {
        private final int tier;

        private RunDisk(int tier) {
            this.tier = tier;
        }

        @Override
        public int researchDiskTier(ItemStack stack) {
            return stack.hasTagCompound() && Objects.requireNonNull(stack.getTagCompound()).hasKey("pe_run_tests_physical_tier")
                ? Objects.requireNonNull(stack.getTagCompound()).getInteger("pe_run_tests_physical_tier") : tier;
        }

        @Override
        public net.minecraftforge.common.capabilities.ICapabilityProvider initCapabilities(ItemStack stack, NBTTagCompound nbt) {
            return new DiskEnergy();
        }
    }

    /**
     * An actual Forge capability on this addon Item; no hand-written ForgeCaps test payload.
     */
    private static final class DiskEnergy implements net.minecraftforge.common.capabilities.ICapabilitySerializable<NBTTagCompound> {
        private net.minecraftforge.energy.EnergyStorage storage = new net.minecraftforge.energy.EnergyStorage(64, 64, 64, 37);

        @Override
        public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability, net.minecraft.util.EnumFacing facing) {
            return capability == net.minecraftforge.energy.CapabilityEnergy.ENERGY;
        }

        @Override
        public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.util.EnumFacing facing) {
            return hasCapability(capability, facing) ? net.minecraftforge.energy.CapabilityEnergy.ENERGY.cast(storage) : null;
        }

        @Override
        public NBTTagCompound serializeNBT() {
            var data = new NBTTagCompound();
            data.setInteger("energy", storage.getEnergyStored());
            return data;
        }

        @Override
        public void deserializeNBT(NBTTagCompound nbt) {
            storage = new net.minecraftforge.energy.EnergyStorage(64, 64, 64, nbt.getInteger("energy"));
        }
    }

    private static final class ResearchUserCommand extends CommandBase {
        @Override
        public String getName() {
            return "pe_run_tests_research";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/pe_run_tests_research research-start/level/queue/reward/claim/blacklist/admin-player/...";
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length == 0 || !args[0].startsWith("research-") || !worldAction(sender, args))
                throw new WrongUsageException(getUsage(sender));
        }
    }
}
