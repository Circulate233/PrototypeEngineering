package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
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
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.List;
import java.util.Objects;

/**
 * A Java station with its own rate and FE upkeep; no recipe-controller runtime is installed.
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
        event.assembly(MachineAssembly.builder(Objects.requireNonNull(event.registry().get(ID)), "research-station-v1")
                                      .instanceFactory(type -> new ResearchStationController(type, 1, Reference2DoubleMaps.singleton(ExampleResources.COMPUTE, 10), 8))
                                      .recreated(ResearchStation.DEVICE).build());
    }

    private static StructurePredicate block(String id) {
        return StructurePredicates.state(Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id))).getDefaultState());
    }
}
