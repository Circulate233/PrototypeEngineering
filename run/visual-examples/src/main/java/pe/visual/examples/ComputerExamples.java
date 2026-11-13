package pe.visual.examples;

import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.GenericStack;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistrationEvent;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResources;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.item.PrototypeCreativeTab;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;

import java.util.List;
import java.util.Objects;

/**
 * A composed computer: one component item, a water-to-cooling recipe, and component-paid FE.
 */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class ComputerExamples {
    private static final ResourceLocation ID = id("java_computer");
    private static final ResourceLocation SHAPE = id("java_computer_shape");
    public static final NetworkResourceType COOLING = new NetworkResourceType(id("cooling"), false, true);

    private ComputerExamples() {
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("pe_run_tests", path);
    }

    private enum Text implements LocalizationEnum {
        NAME;

        @Override
        public String getTranslationKey() {
            return "pe_run_tests.java_computer";
        }
    }

    @SubscribeEvent
    public static void resources(NetworkResourceRegistrationEvent event) {
        event.registry().register(COOLING);
    }

    @SubscribeEvent
    public static void items(RegistryEvent.Register<Item> event) {
        event.getRegistry().register(new ComputingCard("computer_card", NetworkResources.COMPUTE));
        event.getRegistry().register(new ComputingCard("net_computer_card", new NetworkResourceType(id("net_compute"), false)));
    }

    @SubscribeEvent
    public static void machines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(ID, Text.NAME, SHAPE).component(MachineOwnership.TYPE)
                                             .component(ComputerController.COMPONENTS).component(ComputerController.DEVICE).build());
    }

    @SubscribeEvent
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        var cells = new BlockPos2ObjectMap<StructurePredicate>();
        cells.put(new BlockPos(-1, 0, 0), StructurePredicates.state(Objects.requireNonNull(
            ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng", "computation_hatch_4"))).getDefaultState()));
        cells.put(new BlockPos(0, 0, 1), StructurePredicates.state(Objects.requireNonNull(
            ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng", "energy_input_hatch_1"))).getDefaultState()));
        event.structure(StructureDefinitions.template(SHAPE, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(cells), List.of(), List.of()));
        var cooling = new ComputerRecipe(id("java_cooling"), List.of(new GenericStack(
            AEFluidKey.of(new FluidStack(FluidRegistry.WATER, 1_000)), 1_000)), Object2LongMaps.singleton(COOLING, 1_000));
        event.assembly(MachineAssembly.builder(Objects.requireNonNull(event.registry().get(ID)), "computer-v1")
                                      .instanceFactory(type -> new ComputerController(type, 8, Object2LongMaps.singleton(NetworkResources.COMPUTE, 1_000), List.of(cooling)))
                                      .recreated(ComputerController.COMPONENTS, ComputerController.DEVICE).build());
    }

    public static class ComputingCard extends Item implements NetworkComputerComponent {
        private final Capture definition;

        public ComputingCard(String name, NetworkResourceType resource) {
            setRegistryName(id(name));
            setTranslationKey("pe_run_tests." + name);
            setCreativeTab(PrototypeCreativeTab.INSTANCE);
            var supplied = new Object2LongOpenHashMap<NetworkResourceType>();
            supplied.put(NetworkResources.THREAD, 8);
            supplied.put(resource, 1_000);
            definition = new Capture(supplied, Object2LongMaps.singleton(COOLING, 1_000), 32, () -> true);
        }

        @Override
        public Capture capture(ItemStack item, MachineInstance computer) {
            return definition;
        }
    }

    @Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID, value = Side.CLIENT)
    public static final class Client {
        private Client() {
        }

        @SubscribeEvent
        public static void models(ModelRegistryEvent event) {
            for (String name : List.of("computer_card", "net_computer_card")) {
                var key = id(name);
                ModelLoader.setCustomModelResourceLocation(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(key)), 0,
                    new ModelResourceLocation(key, "inventory"));
            }
        }
    }
}
