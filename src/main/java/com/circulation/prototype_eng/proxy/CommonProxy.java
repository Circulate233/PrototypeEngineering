package com.circulation.prototype_eng.proxy;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.OrientationToolConfig;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.research.ResearchRegistrationEvent;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistrationEvent;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistry;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistrationEvent;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistrationEvent;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.machine.ServerMachineIdentities;
import com.circulation.prototype_eng.machine.ServerNetworkRuntime;
import com.circulation.prototype_eng.research.ServerResearch;
import com.circulation.prototype_eng.scheduler.SchedulerConfig;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.storage.HatchCapabilityRegistration;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.api.gui.MachineGuiRegistry;
import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import com.circulation.prototype_eng.container.ResearchContainer;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

import com.circulation.prototype_eng.api.gui.EnergyHatchGuiHost;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistrationEvent;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistry;
import com.circulation.prototype_eng.structure.json.BuiltinStructurePredicates;
import com.circulation.prototype_eng.structure.json.ForgeStructureBlocks;
import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import com.circulation.prototype_eng.structure.reload.StructureCatalogPacket;
import com.circulation.prototype_eng.structure.reload.StructureNetwork;
import com.circulation.prototype_eng.structure.reload.StructureReloadCommand;
import java.nio.file.Path;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.machine.render.ControllerRenderChunks;
import java.util.function.Consumer;
import net.minecraft.network.NetworkManager;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.math.AxisAlignedBB;
import com.circulation.prototype_eng.scheduler.SchedulerCommand;

/** Shared lifecycle for dedicated and integrated servers, inherited by the client proxy. */
public class CommonProxy implements IGuiHandler {
    public AxisAlignedBB controllerRenderBounds(MachineControllerBlockEntity tile) {
        return new AxisAlignedBB(tile.getPos());
    }
    private StructureJsonLoader structureData = StructureJsonLoader.empty();
    private Path structureDirectory;
    private StructureCatalog javaStructures;
    private StructurePredicateRegistry structureFactories;
    public void preInit(FMLPreInitializationEvent event) {
        HatchConfig.load(event.getModConfigurationDirectory());
        SchedulerConfig.load(event.getModConfigurationDirectory());
        OrientationToolConfig.load(event.getModConfigurationDirectory());
        structureDirectory = event.getModConfigurationDirectory().toPath().resolve(Tags.MOD_ID).resolve("structures");
        structureData = StructureJsonLoader.readDirectory(structureDirectory);
        StructureNetwork.register();
        HatchCapabilityRegistration.register();
        NetworkRegistry.INSTANCE.registerGuiHandler(PrototypeEngineering.instance, this);
        PrototypeEngineering.LOGGER.info("Loading {} {}", Tags.MOD_NAME, Tags.VERSION);
    }

    public void postInit() {
        MinecraftForge.EVENT_BUS.post(new NetworkResourceRegistrationEvent(NetworkResourceRegistry.INSTANCE));
        NetworkResourceRegistry.INSTANCE.freeze();
        MinecraftForge.EVENT_BUS.post(new NetworkComputerComponentRegistrationEvent(NetworkComputerComponentRegistry.INSTANCE));
        NetworkComputerComponentRegistry.INSTANCE.freeze(NetworkResourceRegistry.INSTANCE);
        MinecraftForge.EVENT_BUS.post(new ResearchRegistrationEvent(ResearchRegistry.INSTANCE));
        ResearchRegistry.INSTANCE.freeze(NetworkResourceRegistry.INSTANCE);
        MinecraftForge.EVENT_BUS.post(new ResearchItemDownloadRegistrationEvent(ResearchItemDownloadRegistry.INSTANCE));
        ResearchItemDownloadRegistry.INSTANCE.freeze(ResearchRegistry.INSTANCE);
        MinecraftForge.EVENT_BUS.post(new RecipeRegistrationEvent(RecipeRegistry.INSTANCE, NetworkRecipeExtensions.INSTANCE));
        RecipeRegistry.INSTANCE.freeze(MachineTypeRegistry.INSTANCE.types());
        NetworkRecipeExtensions.INSTANCE.publish(RecipeRegistry.INSTANCE.catalog(), ResearchRegistry.INSTANCE, NetworkResourceRegistry.INSTANCE);
        var structurePredicates = new StructurePredicateRegistry();
        BuiltinStructurePredicates.register(structurePredicates);
        MinecraftForge.EVENT_BUS.post(new StructurePredicateRegistrationEvent(structurePredicates));
        structurePredicates.freeze();
        var machines = new MachineRuntimeRegistrationEvent(MachineTypeRegistry.INSTANCE);
        MinecraftForge.EVENT_BUS.post(machines);
        javaStructures = machines.structureDefinitions();
        structureFactories = structurePredicates;
        var structures = structureData.resolve(javaStructures, structurePredicates, new ForgeStructureBlocks());
        MachineTypeRegistry.INSTANCE.resolveRuntime(structures, machines.assemblies());
    }

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        ServerTaskSchedulers.start(event.getServer());
        try {
            ServerMachineIdentities.start(event.getServer());
            ServerNetworkRuntime.start(event.getServer());
            ServerStructureReloads.start(event.getServer(), structureDirectory, javaStructures, structureFactories);
        }
        catch (RuntimeException | Error failure) {
            try {
                ServerTaskSchedulers.stop();
                ServerStructureReloads.stop();
                ServerNetworkRuntime.stop();
                ServerMachineIdentities.stop();
            }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) { failure.addSuppressed(cleanup); } }
            throw failure;
        }
    }

    public void serverStarting(FMLServerStartingEvent event) {
        ServerResearch.start(event.getServer());
        event.registerServerCommand(new StructureReloadCommand());
        event.registerServerCommand(new SchedulerCommand());
    }

    public void serverStopping() {
        ServerResearch.stop();
    }

    public void serverStopped() {
        ServerTaskSchedulers.stop();
        ServerStructureReloads.stop();
        ServerResearch.stop();
        ServerNetworkRuntime.stop();
        ServerMachineIdentities.stop();
    }

    /** Client override schedules onto the actual client thread; common class loads no client API. */
    public void receiveStructureCatalog(StructureCatalogPacket packet, Object connection) {
        throw new IllegalStateException("Server proxy cannot receive a client structure catalog");
    }

    public boolean controllerDisplayConnectionCurrent(MachineControllerBlockEntity tile, @Nullable NetworkManager manager) {
        return false;
    }

    public void receiveControllerRender(MachineControllerBlockEntity tile, @Nullable NetworkManager manager,
                                        ControllerRenderChunks.Part part, Consumer<MachineRenderSnapshot> installed) {
        throw new IllegalStateException("Server proxy cannot receive client controller render state");
    }

    public void releaseControllerRender(MachineControllerBlockEntity tile) {
    }

    public void receiveControllerDisplay(MachineControllerBlockEntity tile, @Nullable NetworkManager manager,
                                         ControllerDisplayData display,
                                         Consumer<MachineRenderSnapshot> installed) {
    }

    public @Nullable MachineRenderSnapshot currentControllerRender(MachineControllerBlockEntity tile) {
        return null;
    }

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == ResearchGuiAccess.GUI_ID) { return new ResearchContainer(player.inventory); }
        if (id != 0 || !world.isBlockLoaded(new BlockPos(x, y, z))) { return null; }
        return switch (world.getTileEntity(new BlockPos(x, y, z))) {
            case MachineControllerHost machine -> MachineGuiRegistry.create(player.inventory, machine);
            case ResourceHatchGuiHost resource -> new ResourceHatchContainer(player.inventory, resource);
            case EnergyHatchGuiHost energy -> new EnergyHatchContainer(player.inventory, energy);
            case null, default -> null;
        };
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) { return null; }
}
