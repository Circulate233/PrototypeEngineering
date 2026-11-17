package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.DataCenterController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.ResearchStationController;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistrationEvent;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Real research definitions matching the preview, followed by focused display and interaction examples.
 * Names, descriptions, reward text, icons and page positions are owned by client JSON files.
 */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class ResearchOverviewExamples {
    private static final ResourceLocation STATION = machineId("overview_station_2");
    private static final ResourceLocation DATABASE = machineId("overview_database_2");
    private static final ComponentType<MachineInstance, MachineDataCenterStorage> STORAGE =
        MachineDataCenterStorage.component(machineId("overview_database_2/database"), 2, 64, ResearchRegistry.INSTANCE);

    private ResearchOverviewExamples() {
    }

    private enum Text implements LocalizationEnum {
        STATION("station_2"), DATABASE("database_2");

        private final String path;

        Text(String path) {
            this.path = path;
        }

        @Override
        public String getTranslationKey() {
            return "pe_run_tests.research_showcase." + path;
        }
    }

    @SubscribeEvent
    public static void machines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(STATION, Text.STATION, machineId("overview_station_2_shape"))
                                             .component(MachineOwnership.TYPE).component(ResearchStation.DEVICE).build());
        event.registry().register(MachineType.builder(DATABASE, Text.DATABASE, machineId("overview_database_2_shape"))
                                             .component(MachineOwnership.TYPE).component(STORAGE).component(DataCenter.DEVICE).build());
    }

    @SubscribeEvent
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        for (var name : List.of("overview_station_2", "overview_database_2")) {
            var cells = new BlockPos2ObjectMap<StructurePredicate>();
            var blocks = placement(name);
            for (var entries = Objects.requireNonNull(blocks).fastIterator(); entries.hasNext(); ) {
                var entry = entries.next();
                if (entry.getLongKey() != 0)
                    cells.put(entry.getLongKey(), StructurePredicates.state(entry.getValue().getDefaultState()));
            }
            event.structure(StructureDefinitions.template(machineId(name + "_shape"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
                false, new StructurePattern(cells), List.of(), List.of()));
        }
        var rates = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        rates.addTo(ExampleResources.COMPUTE, 40);
        rates.addTo(ExampleResources.NET_COMPUTE, 40);
        rates.addTo(ExampleResources.QUBIT, 4);
        rates.addTo(ExampleResources.MEMORY, 32);
        rates.addTo(ExampleResources.BANDWIDTH, 64);
        rates.addTo(ExampleResources.STORAGE, 32);
        rates.addTo(ExampleResources.SIGNAL, 8);
        event.assembly(MachineAssembly.builder(Objects.requireNonNull(event.registry().get(STATION)), "overview-station-v1")
                                      .instanceFactory(type -> new ResearchStationController(type, 2, rates, 16))
                                      .recreated(ResearchStation.DEVICE).build());
        event.assembly(MachineAssembly.builder(Objects.requireNonNull(event.registry().get(DATABASE)), "overview-database-v1")
                                      .instanceFactory(type -> new DataCenterController(type, STORAGE))
                                      .component(STORAGE, MachineDataCenterStorage.CODEC).recreated(DataCenter.DEVICE).build());
    }

    /**
     * Concrete placement representatives shared by runtime structure registration and the player build command.
     */
    public static @Nullable BlockPos2ObjectMap<Block> placement(String name) {
        String path = name.startsWith("pe_run_tests:") ? name.substring("pe_run_tests:".length()) : name;
        if (!path.equals("overview_station_2") && !path.equals("overview_database_2")) return null;
        var cells = new BlockPos2ObjectMap<Block>();
        cells.put(BlockPos.ORIGIN, block("pe_run_tests:" + path + "_controller"));
        if (path.equals("overview_station_2")) {
            cells.put(new BlockPos(-1, 0, 0), block("prototype_eng:input_hatch_10"));
            cells.put(new BlockPos(1, 0, 0), block("prototype_eng:output_hatch_10"));
            cells.put(new BlockPos(0, 0, 1), block("prototype_eng:energy_input_hatch_4"));
        } else cells.put(new BlockPos(0, 1, 0), block("minecraft:gold_block"));
        return cells;
    }

    @SubscribeEvent
    public static void research(ResearchRegistrationEvent event) {
        var definitions = definitions();
        for (int index = 0; index < definitions.size(); index++) event.registry().register(definitions.get(index));
    }

    /**
     * Construct on the registry owner after vanilla item/fluid registration. Returned definitions are borrowed read-only.
     */
    public static ObjectList<ResearchDefinition> definitions() {
        var result = new ObjectArrayList<ResearchDefinition>();
        preview(result);
        showcase(result);
        return result;
    }

    private static void preview(ObjectArrayList<ResearchDefinition> result) {
        result.add(research("basic_circuit", 1, ResearchDownloadDeliveryPolicy.ITEM_ONLY,
            stage(1, 1, 0, 200).material(AEItemKey.of(Items.REDSTONE), 16).build()));
        result.add(research("data_storage", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 300).prerequisite(demo("basic_circuit"), 1)
                               .material(AEItemKey.of(Items.PAPER), 32).material(AEItemKey.of(Items.QUARTZ), 4)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 5).build()).build()));
        result.add(research("signal_amp", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 300).prerequisite(demo("basic_circuit"), 1).material(AEItemKey.of(Items.REDSTONE), 24)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.SIGNAL, 0.5).build()).build()));
        result.add(research("high_heat", 1, ResearchDownloadDeliveryPolicy.ITEM_ONLY,
            stage(1, 1, 1, 400).prerequisite(demo("basic_circuit"), 1).material(AEItemKey.of(Items.BLAZE_POWDER), 8).build()));
        result.add(research("cartography", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 300).prerequisite(demo("data_storage"), 1).material(AEItemKey.of(Items.PAPER), 64)
                               .resourceFormula(ResearchResourceFormula.builder().constant(ExampleResources.STORAGE, 800).build()).build()));
        result.add(research("logic_array", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 600).prerequisite(demo("data_storage"), 1).prerequisite(demo("signal_amp"), 1)
                               .material(AEItemKey.of(Items.REPEATER), 16).material(AEItemKey.of(Items.QUARTZ), 8)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 10)
                                                                       .perTick(ExampleResources.MEMORY, 4).build())
                               .rewardGroup(1, group(demo("circuit_kit"), reward(demo("circuit_repeaters"), Items.REPEATER, 8),
                                   reward(demo("circuit_comparators"), Items.COMPARATOR, 4)))
                               .rewardGroup(1, group(demo("research_notes"), reward(demo("notes_paper"), Items.PAPER, 32),
                                   reward(demo("notes_books"), Items.BOOK, 2))).build()));
        result.add(research("crystal_resonance", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 2, 400).prerequisite(demo("signal_amp"), 1).material(AEItemKey.of(Items.QUARTZ), 32)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.SIGNAL, 1).build()).build()));
        result.add(research("refining", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 400).prerequisite(demo("high_heat"), 1).material(AEItemKey.of(Items.GLOWSTONE_DUST), 16)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 5).build()).build()));
        result.add(research("quantum_link", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 2, 1200).prerequisite(demo("logic_array"), 1).prerequisite(machineId("java_research_connection"), 1)
                                .material(AEItemKey.of(Items.ENDER_PEARL), 16).material(AEItemKey.of(Items.DIAMOND), 4)
                                .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.NET_COMPUTE, 20)
                                                                        .constant(ExampleResources.BANDWIDTH, 6000).build())
                                .rewardGroup(1, group(demo("quantum_reward"), reward(demo("quantum_pearls"), Items.ENDER_PEARL, 4))).build()));
        result.add(research("optical_link", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 600).prerequisite(demo("crystal_resonance"), 1).mutex(demo("sticky_seal"), 1)
                               .material(AEItemKey.of(Items.PRISMARINE_CRYSTALS), 24)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.BANDWIDTH, 6).build()).build()));
        result.add(research("sticky_seal", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 600).prerequisite(demo("refining"), 1).mutex(demo("optical_link"), 1)
                               .material(AEItemKey.of(Items.SLIME_BALL), 24)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 5).build()).build()));
        result.add(research("alloy", 3, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            alloyStage(1, 1, 1).rewardGroup(1, group(demo("alloy_1"), reward(demo("alloy_iron"), Items.IRON_INGOT, 16))).build(),
            alloyStage(2, 3, 2).rewardGroup(3, group(demo("alloy_3"), reward(demo("alloy_diamonds"), Items.DIAMOND, 2))).build()));
        result.add(research("enchant_theory", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 2, 900).prerequisite(demo("cartography"), 1).prerequisite(demo("alloy"), 2)
                               .material(AEItemKey.of(Items.BOOK), 8).material(AEItemKey.of(Items.EMERALD), 4)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 10).build()).build()));
        result.add(research("gemology", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 600).prerequisite(demo("alloy"), 1).material(AEItemKey.of(Items.EMERALD), 8)
                               .resourceFormula(ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 5).build()).build()));
    }

    private static ResearchLevelStage.Builder alloyStage(int first, int last, int tier) {
        return stage(first, last, tier, 800).prerequisite(demo("refining"), 1)
                                            .material(AEItemKey.of(Items.IRON_INGOT), 64).material(AEItemKey.of(Items.GOLD_INGOT), 16)
                                            .resourceFormula(ResearchResourceFormula.builder().affinePerTick(ExampleResources.COMPUTE, 5D * first, 5).build());
    }

    private static void showcase(ObjectArrayList<ResearchDefinition> result) {
        var catalog = stage(1, 1, 1, 1200).resourceFormula(ResearchResourceFormula.builder()
                                                                                  .perTick(ExampleResources.COMPUTE, 10).constant(ExampleResources.QUBIT, 150)
                                                                                  .constant(ExampleResources.MEMORY, 3000).constant(ExampleResources.BANDWIDTH, 6000)
                                                                                  .constant(ExampleResources.STORAGE, 2400).constant(ExampleResources.SIGNAL, 600).build());
        var catalogItems = ObjectList.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE, Items.COAL, Items.QUARTZ,
            Items.DYE, Items.PAPER, Items.BOOK, Items.GLASS_BOTTLE, Items.STRING, Items.LEATHER, Items.FEATHER,
            Items.FLINT, Items.CLAY_BALL, Items.BRICK, Items.NETHERBRICK, Items.GLOWSTONE_DUST, Items.GUNPOWDER,
            Items.SUGAR, Items.SLIME_BALL, Items.ENDER_PEARL, Items.BLAZE_POWDER, Items.MAGMA_CREAM, Items.GHAST_TEAR,
            Items.SPIDER_EYE, Items.FERMENTED_SPIDER_EYE, Items.PRISMARINE_SHARD, Items.PRISMARINE_CRYSTALS,
            Items.RABBIT_HIDE, Items.RABBIT_FOOT, Items.BONE, Items.WHEAT);
        for (int index = 0; index < catalogItems.size(); index++)
            catalog.material(AEItemKey.of(catalogItems.get(index)), catalogItems.get(index) == Items.REDSTONE ? 16_384 : 32 + index % 7);
        result.add(showcase("long_catalog", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM, catalog.build()));
        result.add(showcase("fluid_analysis", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 1, 400).material(AEFluidKey.of(FluidRegistry.WATER), 4000)
                               .material(AEFluidKey.of(FluidRegistry.LAVA), 1000).material(AEItemKey.of(Items.PAPER), 8)
                               .resourceFormula(ResearchResourceFormula.builder().constant(ExampleResources.STORAGE, 800).build()).build()));

        var supplies = new ObjectArrayList<RewardChild>();
        var supplyItems = ObjectList.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.REDSTONE, Items.QUARTZ, Items.PAPER, Items.BOOK,
            Items.REPEATER, Items.COMPARATOR, Items.GLOWSTONE_DUST, Items.SLIME_BALL, Items.ENDER_PEARL, Items.BLAZE_POWDER);
        for (int index = 0; index < supplyItems.size(); index++)
            supplies.add(reward(showcaseId("gallery_supply_" + index), supplyItems.get(index), index == 2 ? 128 : 8));
        var pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.setStackDisplayName("Prototype Survey Pickaxe");
        var bow = new ItemStack(Items.BOW);
        bow.setStackDisplayName("Prototype Field Bow");
        result.add(showcase("reward_gallery", 1, ResearchDownloadDeliveryPolicy.ITEM_ONLY,
            stage(1, 1, 0, 100).material(AEItemKey.of(Items.PAPER), 4)
                               .rewardGroup(1, new RewardGroup(showcaseId("gallery_supplies"), supplies))
                               .rewardGroup(1, group(showcaseId("gallery_tools"),
                                   new RewardChild.Item(showcaseId("gallery_pickaxe"), AEItemKey.of(pickaxe), 1),
                                   new RewardChild.Item(showcaseId("gallery_bow"), AEItemKey.of(bow), 1)))
                               .rewardGroup(1, group(showcaseId("gallery_gems"), reward(showcaseId("gallery_diamond"), Items.DIAMOND, 2),
                                   reward(showcaseId("gallery_emerald"), Items.EMERALD, 4), reward(showcaseId("gallery_quartz"), Items.QUARTZ, 16))).build()));
        result.add(showcase("manual_chain", 3, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 0, 100).material(AEItemKey.of(Items.IRON_INGOT), 4)
                               .rewardGroup(1, group(showcaseId("manual_1"), reward(showcaseId("manual_paper"), Items.PAPER, 8))).build(),
            stage(2, 2, 0, 160).material(AEItemKey.of(Items.GOLD_INGOT), 4)
                               .rewardGroup(2, group(showcaseId("manual_2"), reward(showcaseId("manual_book"), Items.BOOK, 1))).build(),
            stage(3, 3, 0, 220).material(AEItemKey.of(Items.EMERALD), 2)
                               .rewardGroup(3, group(showcaseId("manual_3"), reward(showcaseId("manual_emerald"), Items.EMERALD, 1))).build()));
        result.add(showcase("tier_transition", 3, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM,
            stage(1, 1, 0, 100).material(AEItemKey.of(Items.PAPER), 8).build(),
            stage(2, 2, 1, 200).material(AEItemKey.of(Items.IRON_INGOT), 8)
                               .resourceFormula(ResearchResourceFormula.builder().constant(ExampleResources.COMPUTE, 1000).build()).build(),
            stage(3, 3, 2, 300).material(AEItemKey.of(Items.GOLD_INGOT), 8)
                               .resourceFormula(ResearchResourceFormula.builder().constant(ExampleResources.COMPUTE, 3000).build())
                               .rewardGroup(3, group(showcaseId("transition_3"), reward(showcaseId("transition_diamond"), Items.DIAMOND, 1))).build()));
        result.add(showcase("empty_research", 1, ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM, stage(1, 1, 1, 80).build()));
    }

    private static ResearchLevelStage.Builder stage(int first, int last, int tier, long ticks) {
        return ResearchLevelStage.builder(first, last).researchTier(tier).durationTicks(ticks)
                                 .resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static ResearchDefinition research(String path, int max, ResearchDownloadDeliveryPolicy policy, ResearchLevelStage... stages) {
        return definition(demo(path), max, policy, stages);
    }

    private static ResearchDefinition showcase(String path, int max, ResearchDownloadDeliveryPolicy policy, ResearchLevelStage... stages) {
        return definition(showcaseId(path), max, policy, stages);
    }

    private static ResearchDefinition definition(ResourceLocation id, int max, ResearchDownloadDeliveryPolicy policy, ResearchLevelStage[] stages) {
        var builder = ResearchDefinition.builder(id).maxLevel(max).deliveryPolicy(policy);
        for (var stage : stages) builder.stage(stage);
        return builder.build();
    }

    private static RewardGroup group(ResourceLocation id, RewardChild... children) {
        return new RewardGroup(id, ObjectList.of(children));
    }

    private static RewardChild.Item reward(ResourceLocation id, Item item, long amount) {
        return new RewardChild.Item(id, AEItemKey.of(item), amount);
    }

    private static ResourceLocation demo(String path) {
        return new ResourceLocation("pe_demo", path);
    }

    private static ResourceLocation showcaseId(String path) {
        return new ResourceLocation("pe_showcase", path);
    }

    private static ResourceLocation machineId(String path) {
        return new ResourceLocation("pe_run_tests", path);
    }

    private static Block block(String id) {
        return Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id)), id);
    }
}
