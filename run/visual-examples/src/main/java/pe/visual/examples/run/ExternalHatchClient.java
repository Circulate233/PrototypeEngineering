package pe.visual.examples.run;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import pe.visual.examples.VisualExamples;

import java.util.Objects;

@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID, value = Side.CLIENT)
public final class ExternalHatchClient {
    private ExternalHatchClient() { }
    @SubscribeEvent public static void registerModel(ModelRegistryEvent event) {
        var item = Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(ExternalHatchExamples.BLOCK_ID));
        ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(ExternalHatchExamples.BLOCK_ID, "inventory"));
    }
}
