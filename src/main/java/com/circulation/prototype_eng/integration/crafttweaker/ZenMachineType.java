package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.machine.DataCenterController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.NetworkCenterController;
import com.circulation.prototype_eng.api.machine.ResearchStationController;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotRestartPolicy;
import com.circulation.prototype_eng.api.network.NetworkCenter;
import com.circulation.prototype_eng.api.network.DataCenter;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkConfiguration;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipeBindings;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputerRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResources;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkDeviceCallback;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkDeviceEvent;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputerInputRecipe;

import java.util.Locale;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup machine declaration; referenced structures use the existing Java/JSON structure loader.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.MachineType")
@SuppressWarnings("unused") // Public entry points are resolved by ZenScript's registered type.
public final class ZenMachineType {
    private final ResourceLocation id;
    private final MachineType.Builder builder;
    private final ObjectArrayList<ZenMachineComponent> components = new ObjectArrayList<>();
    @Nullable
    private ZenMachineFactory factory;
    @Nullable
    private ComponentType<MachineInstance, RecipeScanner> scanner;
    @Nullable
    private RecipeProcessCodecs processCodecs;
    @Nullable
    private ZenNetworkConfiguration network;
    private boolean data;
    private boolean controllerContext;
    private boolean registered;
    private List<ComputerRecipe> computerRecipes = List.of();

    private ZenMachineType(String id, String translationKey, String structureId) {
        this.id = new ResourceLocation(id);
        builder = MachineType.builder(this.id, () -> translationKey, new ResourceLocation(structureId));
    }

    @ZenMethod
    public static ZenMachineType create(String id, String translationKey, String structureId) {
        return new ZenMachineType(id, translationKey, structureId);
    }

    /**
     * Declares a passive center using the same public Java role and runtime factory.
     */
    @ZenMethod
    public static ZenMachineType networkCenter(String id, String translationKey, String structureId, int level) {
        return networkCenter(id, translationKey, structureId, level, 0);
    }

    @ZenMethod
    public static ZenMachineType networkCenter(String id, String translationKey, String structureId, int level, long energyPerTick) {
        return networkCenter(id, translationKey, structureId, level, 0, energyPerTick);
    }

    @ZenMethod
    public static ZenMachineType networkCenter(String id, String translationKey, String structureId, int level, long threadCapacity, long energyPerTick) {
        if (level < 1 || threadCapacity < 0 || energyPerTick < 0)
            throw new IllegalArgumentException("Invalid network center level, thread capacity or energy cost");
        var declaration = create(id, translationKey, structureId);
        declaration.factory(new ZenMachineFactory(type -> new NetworkCenterController(type, level, threadCapacity, energyPerTick)));
        declaration.network(new ZenNetworkConfiguration(builder -> builder
            .component(MachineOwnership.TYPE)
            .component(NetworkCenter.DEVICE),
            builder -> builder.recreated(NetworkCenter.DEVICE)));
        return declaration;
    }

    @ZenMethod
    public static ZenMachineType researchStation(String id, String translationKey, String structureId, int level,
                                                 ZenComputingResources rates, long energyPerTick) {
        if (level < 1 || energyPerTick < 0)
            throw new IllegalArgumentException("Invalid research station level or energy cost");
        var speed = rates.value();
        var declaration = create(id, translationKey, structureId);
        declaration.factory(new ZenMachineFactory(type -> new ResearchStationController(type, level, speed, energyPerTick)));
        declaration.network(new ZenNetworkConfiguration(builder -> builder
            .component(MachineOwnership.TYPE).component(ResearchStation.DEVICE),
            builder -> builder.recreated(ResearchStation.DEVICE)));
        return declaration;
    }

    @ZenMethod
    public static ZenMachineType computer(String id, String translationKey, String structureId, long concurrencyLimit,
                                          ZenComputingResources resourceLimits, ZenComputerRecipe[] recipes) {
        var definitions = new ObjectArrayList<ComputerRecipe>(recipes.length);
        for (var recipe : recipes) definitions.add(recipe.value());
        var limits = resourceLimits.value();
        var declaration = create(id, translationKey, structureId);
        declaration.factory(new ZenMachineFactory(type -> new ComputerController(type, concurrencyLimit, limits, definitions)));
        declaration.computerRecipes = definitions;
        declaration.network(new ZenNetworkConfiguration(builder -> builder.component(MachineOwnership.TYPE)
                                                                          .component(ComputerController.COMPONENTS).component(ComputerController.DEVICE),
            builder -> builder.recreated(ComputerController.COMPONENTS, ComputerController.DEVICE)));
        return declaration;
    }

    @ZenMethod
    public static ZenMachineType dataCenter(String id, String translationKey, String structureId, int level, int capacity) {
        var name = new ResourceLocation(id);
        var storage = MachineDataCenterStorage.component(new ResourceLocation(name.getNamespace(), name.getPath() + "/database"),
            level, capacity, ResearchRegistry.INSTANCE);
        var declaration = create(id, translationKey, structureId);
        declaration.factory(new ZenMachineFactory(type -> new DataCenterController(type, storage)));
        declaration.network(new ZenNetworkConfiguration(builder -> builder.component(MachineOwnership.TYPE)
                                                                          .component(storage).component(DataCenter.DEVICE),
            builder -> builder.component(storage, MachineDataCenterStorage.CODEC).recreated(DataCenter.DEVICE)));
        return declaration;
    }

    @ZenMethod
    public ZenMachineType recipePool(String poolId) {
        open();
        builder.recipePool(new ResourceLocation(poolId));
        return this;
    }

    @ZenMethod
    public ZenMachineType recipeGroup(String groupId) {
        open();
        builder.recipeGroup(new ResourceLocation(groupId));
        return this;
    }

    @ZenMethod
    public ZenMachineType componentData() {
        open();
        builder.component(DataComponents.MACHINE);
        data = true;
        return this;
    }

    @ZenMethod
    public ZenMachineType processing(ZenRecipeBindings definitions, int candidates, int probes) {
        open();
        if (scanner != null) throw new IllegalStateException("Recipe processing is already declared");
        var resolver = definitions.resolver();
        var limits = new ScanLimits(candidates, probes);
        if (factory == null) factory = ZenMachineFactory.multithreaded();
        var registry = RecipeRegistry.INSTANCE;
        var descriptor = RecipeScanning.component(new ResourceLocation(id.getNamespace(), id.getPath() + "/recipe_scanner"), host ->
            host.owner().createRecipeScanner(host, registry.snapshot().catalog(), () -> ThreadLocalRandom.current().nextLong())
                .decorateProcessFactory(delegate -> DataComponents.withProcessData(delegate, recipe -> {
                    var codec = resolver.snapshot().get(recipe);
                    return codec != null && codec.supportsComponent(DataComponents.PROCESS);
                }), true).follow(registry, resolver), limits, _ -> true);
        builder.component(MachineWorkSlots.TYPE).component(descriptor);
        scanner = descriptor;
        processCodecs = resolver;
        return this;
    }

    @ZenMethod
    public ZenMachineType factory(ZenMachineFactory handle) {
        open();
        factory = handle;
        return this;
    }

    @ZenMethod
    public ZenMachineType component(ZenMachineComponent handle) {
        open();
        handle.declare(builder);
        components.add(handle);
        return this;
    }

    @ZenMethod
    public ZenMachineType workSlot(String name, String[] recipeGroups, boolean isCore, String restartPolicy) {
        open();
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>();
        for (var group : recipeGroups) groups.add(new ResourceLocation(group));
        builder.workSlot(new WorkSlotDefinition(name, groups, isCore,
            WorkSlotRestartPolicy.valueOf(restartPolicy.toUpperCase(Locale.ROOT))));
        return this;
    }

    @ZenMethod
    public ZenMachineType attribute(String attributeId, double base) {
        open();
        builder.attribute(new ResourceLocation(attributeId), base);
        return this;
    }

    @ZenMethod
    public ZenMachineType executionMode(String mode) {
        open();
        builder.executionMode(ExecutionMode.valueOf(mode.toUpperCase(Locale.ROOT)));
        return this;
    }

    @ZenMethod
    public ZenMachineType requiresNetwork(boolean required) {
        open();
        builder.requiresNetwork(required);
        return this;
    }

    @ZenMethod
    public ZenMachineType executionPolicy(String policy) {
        open();
        builder.executionPolicy(ControllerExecutionPolicy.valueOf(policy.toUpperCase(Locale.ROOT)));
        return this;
    }

    @ZenMethod
    public ZenMachineType controllerInventorySlots(int count) {
        open();
        builder.controllerInventorySlots(count);
        return this;
    }

    @ZenMethod
    public ZenMachineType controllerInventorySlots(@Nullable ZenControllerInventorySize count) {
        open();
        if (count != null && !controllerContext) {
            builder.component(ZenControllerContext.TYPE);
            controllerContext = true;
        }
        builder.controllerInventorySlots(count == null ? null : machine ->
            count.slots(machine.components().get(ZenControllerContext.TYPE)));
        return this;
    }

    @ZenMethod
    public ZenMachineType controllerModel(String modelId) {
        open();
        builder.controllerModel(new ResourceLocation(modelId));
        return this;
    }

    @ZenMethod
    public ZenMachineType network(ZenNetworkConfiguration configuration) {
        open();
        if (network != null) throw new IllegalStateException("Network configuration is already declared");
        network = configuration;
        network.configure(builder);
        return this;
    }

    @ZenMethod
    public void register(String revision) {
        open();
        var definition = buildAssembly(revision);
        DefinitionRegistrations.machines(event -> event.registry().register(definition.type()));
        DefinitionRegistrations.runtime(event -> event.assembly(definition));
        registered = true;
    }

    @ZenMethod
    public ZenMachineType networkEvent(ZenNetworkDeviceCallback callback) {
        builder.networkEvent(event -> callback.apply(new ZenNetworkDeviceEvent(event)));
        return this;
    }

    @ZenMethod
    public ZenMachineType computerInputRecipe(ZenComputerInputRecipe recipe) {
        builder.computerInputRecipe(recipe.value());
        return this;
    }

    MachineAssembly buildAssembly(String revision) {
        var type = builder.build();
        var assembly = MachineAssembly.builder(type, revision).computerRecipes(computerRecipes);
        if (factory != null) assembly.instanceFactory(factory.value());
        for (int index = 0; index < components.size(); index++) {
            var component = components.get(index);
            component.bind(assembly);
        }
        if (network != null) network.configure(assembly);
        if (data) assembly.component(DataComponents.MACHINE, DataComponents.MACHINE_CODEC);
        if (controllerContext) assembly.recreated(ZenControllerContext.TYPE);
        if (scanner != null) {
            assembly.workSlots(new MachineWorkSlotsNbt(revision, processCodecs)).recreated(scanner);
        } else if (!type.workSlots().isEmpty() && !type.componentTypes().contains(MachineWorkSlots.TYPE)) {
            throw new IllegalArgumentException("Work slots require explicit recipe processing");
        }
        return assembly.build();
    }

    private void open() {
        if (registered) throw new IllegalStateException("Machine declaration is already registered");
    }
}
