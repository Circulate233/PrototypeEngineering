package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.NetworkCenterController;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.NetworkCenter;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;
import java.util.Objects;

/**
 * Java counterpart to the run script's center: controller with one iron block above it.
 */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class NetworkCenterExamples {
    private static final ResourceLocation ID = new ResourceLocation("pe_run_tests", "java_network_center");
    private static final ResourceLocation SHAPE = new ResourceLocation("pe_run_tests", "java_network_center_shape");

    private NetworkCenterExamples() {
    }

    private enum Text implements LocalizationEnum {
        NAME;

        @Override
        public String getTranslationKey() {
            return "pe_run_tests.java_network_center";
        }
    }

    @SubscribeEvent
    public static void machines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(ID, Text.NAME, SHAPE)
                                             .component(MachineOwnership.TYPE).component(NetworkCenter.DEVICE).build());
    }

    @SubscribeEvent
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        var pattern = new BlockPos2ObjectMap<StructurePredicate>();
        pattern.put(new BlockPos(0, 1, 0), StructurePredicates.state(Blocks.IRON_BLOCK.getDefaultState()));
        event.structure(StructureDefinitions.template(SHAPE, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(pattern), List.of(), List.of()));
        var type = Objects.requireNonNull(event.registry().get(ID));
        event.assembly(MachineAssembly.builder(type, "network-center-v1")
                                      .instanceFactory(value -> new NetworkCenterController(value, 1))
                                      .recreated(NetworkCenter.DEVICE).build());
    }
}
