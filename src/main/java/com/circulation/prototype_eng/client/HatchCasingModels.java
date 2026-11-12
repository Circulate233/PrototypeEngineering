package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.block.HatchBlock;
import com.circulation.prototype_eng.client.render.CasingTextureType;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import team.chisel.ctm.client.model.AbstractCTMBakedModel;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Model-derived membership of the mechanical casing substrate; cached once per state and reload. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class HatchCasingModels {
    private static final ResourceLocation CASING = new ResourceLocation(Tags.MOD_ID, "block_casing");
    private static final FaceCache FACES = new FaceCache(HatchCasingModels::findFaces);

    private HatchCasingModels() {
    }

    @SubscribeEvent
    public static void modelsBaked(ModelBakeEvent event) {
        FACES.clear();
    }

    public static boolean casingFace(IBlockState state, EnumFacing face) {
        var block = state.getBlock();
        return eligible(block.getClass(), block.getRegistryName()) && FACES.casingFace(state, face);
    }

    static boolean eligible(Class<? extends Block> blockClass, ResourceLocation id) {
        return HatchBlock.class.isAssignableFrom(blockClass) || CASING.equals(id);
    }

    private static int findFaces(IBlockState state) {
        var baked = Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getModelForState(state);
        return findFaces(state, baked);
    }

    static int findFaces(IBlockState state, IBakedModel baked) {
        if (!(baked instanceof AbstractCTMBakedModel ctm)) return 0;
        // Read the untransformed native parent, never CTM's context-building getQuads path.
        var parent = ctm.getParent(0);
        var general = parent.getQuads(state, null, 0);
        int result = 0;
        for (var face : EnumFacing.VALUES) {
            if (containsCasing(ctm, parent.getQuads(state, face, 0), face) || containsCasing(ctm, general, face))
                result |= 1 << face.ordinal();
        }
        return result;
    }

    private static boolean containsCasing(IBakedModel baked, List<BakedQuad> quads, EnumFacing face) {
        if (!(baked instanceof AbstractCTMBakedModel model)) return false;
        for (var quad : quads) {
            if (quad.getFace() != face) continue;
            var texture = model.getModel().getTexture(quad.getSprite().getIconName());
            if (texture != null && texture.getType() instanceof CasingTextureType) return true;
        }
        return false;
    }

    static final class FaceCache {
        private final Function<IBlockState, Integer> resolve;
        private volatile ConcurrentHashMap<IBlockState, Integer> values = new ConcurrentHashMap<>(128);

        FaceCache(ToIntFunction<IBlockState> resolver) {
            resolve = resolver::applyAsInt;
        }

        boolean casingFace(IBlockState state, EnumFacing face) {
            return (values.computeIfAbsent(state, resolve) & 1 << face.ordinal()) != 0;
        }

        void clear() {
            // An in-flight query may finish against the old models without populating the new epoch.
            values = new ConcurrentHashMap<>(128);
        }
    }
}
