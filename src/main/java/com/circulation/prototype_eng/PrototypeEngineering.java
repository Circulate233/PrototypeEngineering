package com.circulation.prototype_eng;

import com.circulation.prototype_eng.proxy.CommonProxy;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentDataType;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
    dependencies = "required-after:ae2@[1.1.0,);required-after:ftblib@[5.4.7.2];required-after:ftbutilities@[5.4.1.131];after:appflux@[1.0.0,);after:crafttweaker@[4.1.20,);after:ctm")
public class PrototypeEngineering {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.Instance(Tags.MOD_ID)
    public static PrototypeEngineering instance;

    @SidedProxy(modId = Tags.MOD_ID, clientSide = "com.circulation.prototype_eng.proxy.ClientProxy", serverSide = "com.circulation.prototype_eng.proxy.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void construction(FMLConstructionEvent event) {
        if (Loader.isModLoaded("crafttweaker")) ZenComponentDataType.register();
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit();
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        proxy.serverAboutToStart(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped();
    }

}
