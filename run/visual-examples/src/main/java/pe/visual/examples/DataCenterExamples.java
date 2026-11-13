package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.DataCenterController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
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
 * A level-one, sixteen-entry database using the standard physical persistence and network role.
 */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class DataCenterExamples {
    private static final ResourceLocation ID = new ResourceLocation("pe_run_tests", "java_data_center");
    private static final ResourceLocation SHAPE = new ResourceLocation("pe_run_tests", "java_data_center_shape");
    private static final ComponentType<MachineInstance, MachineDataCenterStorage> STORAGE =
        MachineDataCenterStorage.component(new ResourceLocation("pe_run_tests", "java_data_center/database"), 1, 16, ResearchRegistry.INSTANCE);

    private DataCenterExamples() {
    }

    private enum Text implements LocalizationEnum {
        NAME;

        @Override
        public String getTranslationKey() {
            return "pe_run_tests.java_data_center";
        }
    }

    @SubscribeEvent
    public static void machines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(ID, Text.NAME, SHAPE).component(MachineOwnership.TYPE)
                                             .component(STORAGE).component(DataCenter.DEVICE).build());
    }

    @SubscribeEvent
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        var cells = new BlockPos2ObjectMap<StructurePredicate>();
        cells.put(new BlockPos(0, 1, 0), StructurePredicates.state(Objects.requireNonNull(
            ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", "iron_block"))).getDefaultState()));
        event.structure(StructureDefinitions.template(SHAPE, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(cells), List.of(), List.of()));
        event.assembly(MachineAssembly.builder(Objects.requireNonNull(event.registry().get(ID)), "data-center-v1")
                                      .instanceFactory(type -> new DataCenterController(type, STORAGE))
                                      .component(STORAGE, MachineDataCenterStorage.CODEC).recreated(DataCenter.DEVICE).build());
    }
}
