package com.circulation.prototype_eng.proxy;

import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.api.client.gui.MachineScreenRegistry;
import com.circulation.prototype_eng.block.HatchBlocks;
import com.circulation.prototype_eng.client.gui.EnergyHatchGui;
import com.circulation.prototype_eng.client.gui.ResourceHatchGui;
import com.circulation.prototype_eng.client.gui.ResearchGui;
import com.circulation.prototype_eng.container.ResearchContainer;
import com.circulation.prototype_eng.client.MachineControllerModels;
import com.circulation.prototype_eng.client.OrientationToolModels;
import com.circulation.prototype_eng.container.EnergyHatchContainer;
import com.circulation.prototype_eng.container.ResourceHatchContainer;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.util.Objects;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.structure.reload.StructureCatalogPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.client.preview.WorldProjectionManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import com.circulation.prototype_eng.client.preview.PreviewClientOpenQueue;
import com.circulation.prototype_eng.client.preview.PreviewEmbeddedInput;
import com.circulation.prototype_eng.client.preview.PreviewGuiResources;
import com.circulation.prototype_eng.client.preview.ProjectionInteractions;
import com.circulation.prototype_eng.client.preview.StructurePreviewCommand;
import net.minecraftforge.client.ClientCommandHandler;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.machine.MachineControllerBlockHost;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.client.render.ClientMachineRenderSnapshots;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.machine.render.ControllerRenderChunks;
import java.util.function.Consumer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.client.render.BasicMachineControllerRenderer;
import com.circulation.prototype_eng.client.render.DirectVertexBufferPool;
import com.circulation.prototype_eng.client.render.MachineControllerRenderer;
import com.circulation.prototype_eng.client.render.MachineModelBloom;
import com.circulation.prototype_eng.client.render.MachineModelRenderState;
import com.circulation.prototype_eng.client.render.MachineRenderOptions;
import com.circulation.prototype_eng.client.render.ModelAssets;
import com.circulation.prototype_eng.client.render.ModelBufferPool;
import com.circulation.prototype_eng.client.render.ModelBuildCache;
import com.circulation.prototype_eng.client.render.ModelBuildExecutor;
import com.circulation.prototype_eng.client.render.ModelClientThread;
import com.circulation.prototype_eng.client.render.ReusableModelVboUploader;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import com.circulation.prototype_eng.client.gui.ScannerInstrumentGui;
import com.circulation.prototype_eng.client.gui.BuilderInstrumentGui;
import com.circulation.prototype_eng.container.StructureToolContainer;
import com.circulation.prototype_eng.client.gui.ClientHatchConfigurations;
import com.circulation.prototype_eng.storage.HatchConfigurationPacket;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
@SuppressWarnings("resource") // Proxy retains the client manager; connection/world hooks retire its attachment leases.
public class ClientProxy extends CommonProxy {
    private ModelBuildCache modelBuilds;
    private ModelClientThread modelBuildThread;
    private World modelBuildWorld;
    private MachineModelRenderState modelRenderState;
    private AutoCloseable modelRenderListener;
    private MachineRenderOptions modelRenderOptions = MachineRenderOptions.defaults();
    private ClientStructureCatalog structures;
    private ClientHatchConfigurations hatchConfigurations;
    private StructurePreviews previews;
    private WorldProjectionManager projectionManager;
    private MachineRenderBindings renderBindings;
    private ClientMachineRenderSnapshots renderSnapshots;

    public StructurePreviews previews() {
        var catalog = structures();
        if (previews == null || previews.closed()) {
            if (projectionManager != null) projectionManager.close();
            previews = new StructurePreviews(catalog);
            projectionManager = new WorldProjectionManager(previews, catalog);
            MinecraftForge.EVENT_BUS.register(projectionManager);
        }
        return previews;
    }

    public ClientStructureCatalog structures() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) throw new IllegalStateException("Structure view requires the client thread");
        if (structures == null) structures = new ClientStructureCatalog();
        return structures;
    }

    /** Concrete owned CPU model service, shared by the following runtime and preview consumers. */
    public ModelBuildCache modelBuilds() {
        structures();
        var minecraft = Minecraft.getMinecraft();
        if (modelBuilds != null && modelBuildWorld != minecraft.world) retireModelBuilds();
        if (modelBuilds == null) {
            if (modelBuildThread == null) modelBuildThread = ModelClientThread.minecraft();
            var owner = modelBuildThread;
            var assets = new ModelAssets(owner, minecraft.getResourceManager(), minecraft.gameDir.toPath().resolve("resources"));
            modelBuilds = new ModelBuildCache(owner, assets,
                new ModelBuildExecutor(modelRenderOptions.workers(), modelRenderOptions.fixedThreads()),
                new ModelBufferPool(), new DirectVertexBufferPool(), new ReusableModelVboUploader(owner), modelRenderOptions.mappedOutput());
            modelBuildWorld = minecraft.world;
        }
        return modelBuilds;
    }

    private void retireModelBuilds() {
        var previous = modelBuilds;
        var render = modelRenderState;
        var listener = modelRenderListener;
        modelBuilds = null;
        modelBuildWorld = null;
        modelRenderState = null;
        modelRenderListener = null;
        Throwable failure = null;
        for (var resource : new AutoCloseable[]{listener, render, previous})
            if (resource != null) try {
                resource.close();
            } catch (Exception | Error exception) {
                if (failure == null) failure = exception;
                else if (failure != exception) failure.addSuppressed(exception);
            }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure != null) throw new IllegalStateException(failure);
    }

    public MachineRenderOptions modelRenderOptions() {
        structures();
        return modelRenderOptions;
    }

    @SuppressWarnings("unused")
    // Addons configure the concrete renderer before registration or change its owned service at runtime.
    public void modelRenderOptions(MachineRenderOptions options) {
        structures();
        if (modelRenderOptions.equals(options)) return;
        modelRenderOptions = Objects.requireNonNull(options);
        retireModelBuilds();
    }

    public MachineModelRenderState modelRenderState() {
        var cache = modelBuilds();
        if (modelRenderState == null) {
            modelRenderState = new MachineModelRenderState(cache, modelRenderOptions);
            modelRenderListener = renderSnapshots().onInvalidated(modelRenderState::invalidateViews);
        }
        return modelRenderState;
    }

    public void flushMachineModels() {
        if (modelRenderState != null) modelRenderState.flush();
    }

    @Override
    public AxisAlignedBB controllerRenderBounds(MachineControllerBlockEntity tile) {
        var owned = MachineControllerRenderer.ownedBounds(tile);
        if (owned != null) return owned;
        if (modelRenderState != null) return modelRenderState.bounds(tile);
        if (renderSnapshots != null) {
            var view = renderSnapshots.view(tile.getWorld(), tile, tile.getPos());
            if (view != null) {
                var bounds = view.snapshot().bounds();
                return new AxisAlignedBB(bounds.min(), bounds.max().add(1, 1, 1));
            }
        }
        return super.controllerRenderBounds(tile);
    }

    @SubscribeEvent
    public void flushMachineModels(RenderWorldLastEvent event) {
        modelCleanup(this::flushMachineModels, MachineModelBloom::clear);
    }

    @SubscribeEvent
    public void machineModelFrame(TickEvent.RenderTickEvent event) {
        MachineModelBloom.clear();
    }

    private static void modelCleanup(Runnable... actions) {
        Throwable failure = null;
        for (var action : actions)
            try {
                action.run();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }

    public MachineRenderBindings renderBindings() {
        structures();
        if (renderBindings == null) renderBindings = new MachineRenderBindings();
        return renderBindings;
    }

    public ClientMachineRenderSnapshots renderSnapshots() {
        structures();
        if (renderSnapshots == null) renderSnapshots = new ClientMachineRenderSnapshots(structures(), renderBindings());
        return renderSnapshots;
    }

    @Override
    public boolean controllerDisplayConnectionCurrent(MachineControllerBlockEntity tile, @Nullable NetworkManager manager) {
        var minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread())
            throw new IllegalStateException("Controller display requires the client owner");
        var handler = minecraft.getConnection();
        return handler != null && minecraft.world != null && tile.getWorld() == minecraft.world &&
            (manager == null || handler.getNetworkManager() == manager) && handler.getNetworkManager().isChannelOpen() &&
            structures().connectedTo(handler.getNetworkManager());
    }

    @Override
    public void receiveControllerRender(MachineControllerBlockEntity tile, @Nullable NetworkManager manager,
                                        ControllerRenderChunks.Part part, Consumer<MachineRenderSnapshot> installed) {
        if (!controllerDisplayConnectionCurrent(tile, manager)) return;
        var owner = attachRenderOwner(tile, part.display().type(), installed);
        renderSnapshots().receive(owner, part);
    }

    @Override
    public void receiveControllerDisplay(MachineControllerBlockEntity tile, @Nullable NetworkManager manager,
                                         ControllerDisplayData display,
                                         Consumer<MachineRenderSnapshot> installed) {
        if (controllerDisplayConnectionCurrent(tile, manager))
            renderSnapshots().withdraw(attachRenderOwner(tile, display.type(), installed), display);
    }

    private ClientMachineRenderSnapshots.Owner attachRenderOwner(MachineControllerBlockEntity tile,
                                                                 ResourceLocation type,
                                                                 Consumer<MachineRenderSnapshot> installed) {
        var minecraft = Minecraft.getMinecraft();
        var connection = Objects.requireNonNull(minecraft.getConnection()).getNetworkManager();
        var world = tile.getWorld();
        var position = tile.getPos().toImmutable();
        return renderSnapshots().attach(world, tile, connection, position, type,
            () -> minecraft.world == world && tile.getWorld() == world && !tile.isInvalid() && position.equals(tile.getPos()) &&
                world.isBlockLoaded(position, false) && world.getTileEntity(position) == tile &&
                world.getBlockState(position).getBlock() instanceof MachineControllerBlockHost host && host.type().id().equals(type), installed);
    }

    @Override
    public void releaseControllerRender(MachineControllerBlockEntity tile) {
        structures();
        if (renderSnapshots != null) renderSnapshots.release(tile.getWorld(), tile, tile.getPos());
    }

    @Override
    public @Nullable MachineRenderSnapshot currentControllerRender(MachineControllerBlockEntity tile) {
        if (!controllerDisplayConnectionCurrent(tile, null) || renderSnapshots == null) return null;
        var view = renderSnapshots.view(tile.getWorld(), tile, tile.getPos());
        return view == null ? null : view.snapshot();
    }

    @SubscribeEvent
    public void renderWorldUnloaded(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) return;
        var world = event.getWorld();
        var originalBuilds = modelBuildWorld == world ? modelBuilds : null;
        Minecraft.getMinecraft().addScheduledTask(() -> modelCleanup(() -> {
                if (originalBuilds != null && modelBuilds == originalBuilds) retireModelBuilds();
            },
            () -> {
            if (renderSnapshots != null) renderSnapshots.unloadWorld(world);
        }));
    }

    @SubscribeEvent
    public void renderTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        modelCleanup(() -> {
                if (modelRenderState != null) modelRenderState.tick();
            },
            () -> {
                if (modelBuildThread != null) modelBuildThread.drain();
            },
            () -> {
                if (renderSnapshots != null) renderSnapshots.tick();
    });
    }

    private ClientHatchConfigurations hatchConfigurations() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("Hatch tooltip requires client owner");
        if (hatchConfigurations == null) hatchConfigurations = new ClientHatchConfigurations();
        return hatchConfigurations;
    }

    public @Nullable HatchConfigurationSnapshot hatchConfiguration() {
        var handler = Minecraft.getMinecraft().getConnection();
        if (handler == null) return null;
        var display = hatchConfigurations();
        return display.connectedTo(handler.getNetworkManager()) ? display.view() : null;
    }

    @Override
    public void receiveHatchConfiguration(HatchConfigurationPacket packet, Object connection) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (!(connection instanceof NetHandlerPlayClient handler) || Minecraft.getMinecraft().getConnection() != handler)
                return;
            try {
                hatchConfigurations().receive(handler.getNetworkManager(), packet);
            } catch (RuntimeException invalid) {
                PrototypeEngineering.LOGGER.error("Cannot install authoritative hatch configuration", invalid);
            }
        });
    }

    @Override public void receiveStructureCatalog(StructureCatalogPacket packet, Object connection) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var minecraft = Minecraft.getMinecraft();
            if (!(connection instanceof NetHandlerPlayClient handler) || minecraft.getConnection() != handler) return;
            var manager = handler.getNetworkManager();
            try { structures().receive(manager, packet); }
            catch (RuntimeException failure) { PrototypeEngineering.LOGGER.error("Cannot install authoritative structure catalog", failure); }
        });
    }

    @SubscribeEvent
    public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var current = Minecraft.getMinecraft().getConnection();
            if (event.getManager().isChannelOpen() && (current == null || current.getNetworkManager() == event.getManager())) {
                structures().connected(event.getManager());
                hatchConfigurations().connected(event.getManager());
            }
        });
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> modelCleanup(() -> {
                if (structures().connectedTo(event.getManager())) retireModelBuilds();
            },
            () -> structures().disconnected(event.getManager()),
            () -> hatchConfigurations().disconnected(event.getManager())));

    }
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        new BasicMachineControllerRenderer().register();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(PreviewGuiResources.INSTANCE);
        MinecraftForge.EVENT_BUS.register(PreviewEmbeddedInput.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ProjectionInteractions.INSTANCE);
        MinecraftForge.EVENT_BUS.register(PreviewClientOpenQueue.INSTANCE);
        ProjectionInteractions.INSTANCE.registerKeys();
        ClientCommandHandler.instance.registerCommand(new StructurePreviewCommand());
        if (Minecraft.getMinecraft().getResourceManager() instanceof IReloadableResourceManager previewResources)
            previewResources.registerReloadListener(PreviewGuiResources.INSTANCE);
        if (Minecraft.getMinecraft().getResourceManager() instanceof IReloadableResourceManager resources)
            resources.registerReloadListener(_ -> {
                retireModelBuilds();
                if (renderSnapshots != null) renderSnapshots.invalidateResources();
                if (projectionManager != null) projectionManager.invalidateResources();
            });
    }

    @SubscribeEvent
    public void registerModels(ModelRegistryEvent event) {
        for (var block : HatchBlocks.all()) {
            ModelLoader.setCustomModelResourceLocation(Item.getItemFromBlock(block), 0,
                new ModelResourceLocation(Objects.requireNonNull(block.getRegistryName()), "inventory"));
        }
        MachineControllerModels.registerModels();
        OrientationToolModels.registerModels();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void bakeControllerModels(ModelBakeEvent event) {
        MachineControllerModels.bakeModels(event);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return switch (getServerGuiElement(id, player, world, x, y, z)) {
            case StructureToolContainer tool -> tool.builder()
                ? new BuilderInstrumentGui(tool, player.inventory)
                : new ScannerInstrumentGui(tool, player.inventory);
            case ResearchContainer research -> new ResearchGui(research, player.inventory);
            case ResourceHatchContainer resource -> new ResourceHatchGui(resource, player.inventory);
            case EnergyHatchContainer energy -> new EnergyHatchGui(energy, player.inventory);
            case AEBaseContainer container when container.getTarget() instanceof MachineControllerHost machine ->
                MachineScreenRegistry.create(machine.type().id(), container, player.inventory);
            case null, default -> null;
        };
    }
}
