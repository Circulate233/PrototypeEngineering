package com.circulation.prototype_eng.client.render;

import com.circulation.prototype_eng.client.HatchCasingModels;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import team.chisel.ctm.api.texture.ICTMTexture;
import team.chisel.ctm.api.texture.TextureType;
import team.chisel.ctm.api.util.TextureInfo;
import team.chisel.ctm.client.texture.render.TextureCTM;
import team.chisel.ctm.client.texture.type.TextureTypeCTM;
import team.chisel.ctm.client.util.CTMLogic;

/**
 * Native CTM artwork and neighbor logic, with a model-derived shared casing substrate.
 */
@TextureType("prototype_eng:casing")
@SuppressWarnings("unchecked") // CTM's generic factory conflicts with TextureTypeCTM's concrete override.
public final class CasingTextureType extends TextureTypeCTM {
    @Override
    public ICTMTexture<? extends TextureTypeCTM> makeTexture(TextureInfo info) {
        return new CasingTexture(this, info);
    }

    private static final class CasingTexture extends TextureCTM<CasingTextureType> {
        private final boolean legacyConnections;

        private CasingTexture(CasingTextureType type, TextureInfo info) {
            super(type, info);
            legacyConnections = info.getInfo().map(metadata -> metadata.has("connect_to")).orElse(false);
        }

        @Override
        public boolean connectTo(CTMLogic logic, IBlockState state, IBlockState neighbor, EnumFacing face) {
            if (HatchCasingModels.casingFace(state, face)) return HatchCasingModels.casingFace(neighbor, face);
            return legacyConnections && super.connectTo(logic, state, neighbor, face);
        }
    }
}
