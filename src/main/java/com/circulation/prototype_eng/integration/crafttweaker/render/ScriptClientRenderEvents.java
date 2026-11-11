package com.circulation.prototype_eng.integration.crafttweaker.render;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.render.ClientRenderDeclarations;
import com.circulation.prototype_eng.api.client.render.MachineRenderRegistrationEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/** No optional script types are linked by this client registration subscriber. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class ScriptClientRenderEvents {
    private ScriptClientRenderEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void bindings(MachineRenderRegistrationEvent event) {
        ClientRenderDeclarations.dispatch(event);
    }
}
