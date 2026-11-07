package com.circulation.prototype_eng;

import com.circulation.prototype_eng.proxy.CommonProxy;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.scheduler.SchedulerConfig;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import com.circulation.prototype_eng.storage.HatchCapabilityRegistration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
        dependencies = "required-after:ae2@[1.1.0,);after:appflux@[1.0.0,)")
public class PrototypeEngineering {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.Instance(Tags.MOD_ID)
    public static PrototypeEngineering instance;

    @SidedProxy(modId = Tags.MOD_ID, clientSide = "com.circulation.prototype_eng.proxy.ClientProxy", serverSide = "com.circulation.prototype_eng.proxy.CommonProxy")
    public static CommonProxy proxy;
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        HatchConfig.load(event.getModConfigurationDirectory());
        SchedulerConfig.load(event.getModConfigurationDirectory());
        OrientationToolConfig.load(event.getModConfigurationDirectory());
        HatchCapabilityRegistration.register();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, proxy);
        LOGGER.info("Loading {} {}", Tags.MOD_NAME, Tags.VERSION);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        MinecraftForge.EVENT_BUS.post(new RecipeRegistrationEvent(RecipeRegistry.INSTANCE));
        RecipeRegistry.INSTANCE.freeze();
        var machines = new MachineRuntimeRegistrationEvent(MachineTypeRegistry.INSTANCE);
        MinecraftForge.EVENT_BUS.post(machines);
        MachineTypeRegistry.INSTANCE.resolveRuntime(machines.structures(), machines.assemblies());
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        ServerTaskSchedulers.start(event.getServer());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        ServerTaskSchedulers.stop();
    }

}
