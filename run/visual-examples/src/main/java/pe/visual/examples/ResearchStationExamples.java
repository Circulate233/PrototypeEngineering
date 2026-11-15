package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchRegistrationEvent;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.init.Items;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.ResearchStationController;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.List;
import java.util.Objects;

/**
 * Java station and research examples with unequal material totals, network limits and station rates.
 */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class ResearchStationExamples {
    private static final ResourceLocation ID = new ResourceLocation("pe_run_tests", "java_research_station");
    private static final ResourceLocation SHAPE = new ResourceLocation("pe_run_tests", "java_research_station_shape");

    private ResearchStationExamples() {
    }

    private enum Text implements LocalizationEnum {
        NAME;

        @Override
        public String getTranslationKey() {
            return "pe_run_tests.java_research_station";
        }
    }

    @SubscribeEvent
    public static void machines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(ID, Text.NAME, SHAPE)
                                             .component(MachineOwnership.TYPE).component(ResearchStation.DEVICE).build());
    }

    @SubscribeEvent
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        var cells = new BlockPos2ObjectMap<StructurePredicate>();
        cells.put(new BlockPos(-1, 0, 0), block("prototype_eng:input_hatch_1"));
        cells.put(new BlockPos(1, 0, 0), block("prototype_eng:output_hatch_1"));
        cells.put(new BlockPos(0, 0, 1), block("prototype_eng:energy_input_hatch_1"));
        event.structure(StructureDefinitions.template(SHAPE, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(cells), List.of(), List.of()));
        var rates = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        rates.addTo(ExampleResources.COMPUTE, 10);
        rates.addTo(ExampleResources.BANDWIDTH, 32);
        rates.addTo(ExampleResources.SIGNAL, 2);
        rates.addTo(ExampleResources.MEMORY, 16);
        rates.addTo(ExampleResources.STORAGE, 8);
        event.assembly(MachineAssembly.builder(Objects.requireNonNull(event.registry().get(ID)), "research-station-v2")
                                      .instanceFactory(type -> new ResearchStationController(type, 1, rates, 8))
                                      .recreated(ResearchStation.DEVICE).build());
    }

    @SubscribeEvent
    public static void research(ResearchRegistrationEvent event) {
        String[] names = {"parallel", "compression", "coordination", "sampling", "connection"};
        for (int index = 0; index < names.length; index++) {
            String name = names[index];
            var formula = ResearchResourceFormula.builder().perTick(ExampleResources.COMPUTE, 5 + index)
                                                 .constant(ExampleResources.BANDWIDTH, 600D * (index + 1));
            if (index >= 2) formula.perTick(ExampleResources.SIGNAL, 0.5);
            if (index == 4) formula.perTick(ExampleResources.MEMORY, 8).constant(ExampleResources.STORAGE, 1_200);
            var stage = ResearchLevelStage.builder(1, 1).durationTicks(600).researchTier(1).resourceFormula(formula.build())
                                          .material(AEItemKey.of(Items.IRON_INGOT), 2)
                                          .material(AEItemKey.of(Items.REDSTONE), 10)
                                          .material(AEItemKey.of(Items.GOLD_NUGGET), 100).build();
            event.registry().register(ResearchDefinition.builder(new ResourceLocation("pe_run_tests", "java_research_" + name))
                                                        .maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                        .displayName(() -> "pe_run_tests.research." + name).icon(AEItemKey.of(Items.BOOK))
                                                        .stage(stage).build());
        }
    }

    private static StructurePredicate block(String id) {
        return StructurePredicates.state(Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id))).getDefaultState());
    }
}
