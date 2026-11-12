package com.circulation.prototype_eng.client.render;

import net.minecraft.client.renderer.block.model.BakedQuad;
import org.jetbrains.annotations.Nullable;
import team.chisel.ctm.api.texture.ICTMTexture;
import team.chisel.ctm.api.texture.ITextureContext;
import team.chisel.ctm.api.texture.TextureType;
import team.chisel.ctm.api.texture.ISubmap;
import team.chisel.ctm.api.util.TextureInfo;
import team.chisel.ctm.Configurations;
import team.chisel.ctm.client.texture.ctx.TextureContextCTM;
import team.chisel.ctm.client.texture.render.TextureCTM;
import team.chisel.ctm.client.texture.type.TextureTypeCTM;
import team.chisel.ctm.client.util.CTMLogic;
import team.chisel.ctm.client.util.Dir;
import team.chisel.ctm.client.util.Submap;

import java.util.Arrays;
import java.util.List;

/** Full-face 47-state source artwork, using CTM's actual neighbor/state/obscured-face context. */
@TextureType("prototype_eng:optifine_full")
@SuppressWarnings("unchecked") // ITextureType's generic factory conflicts with TextureTypeCTM's native concrete override.
public final class FullCtmTextureType extends TextureTypeCTM {
    private static final Dir[] SIDES = {Dir.TOP, Dir.RIGHT, Dir.BOTTOM, Dir.LEFT};
    private static final Dir[] CORNERS = {Dir.TOP_RIGHT, Dir.BOTTOM_RIGHT, Dir.BOTTOM_LEFT, Dir.TOP_LEFT};
    // Each source sheet contains sixteen row-major 16px tiles. Tile 47 is an author preview.
    // Bits 0..3: top/right/bottom/left; bits 4..7: TR/BR/BL/TL after cardinal normalization.
    private static final int[] SOURCE_MASKS = {
        0, 2, 10, 8, 4, 38, 110, 76, 5, 55, 255, 205, 1, 19, 155, 137,
        6, 12, 7, 14, 3, 9, 11, 13, 39, 78, 23, 46, 27, 141, 139, 77,
        31, 47, 207, 159, 143, 79, 111, 63, 223, 191, 95, 175, 239, 127, 15
    };
    private static final int[] TILES = createTiles();
    private static final Tile[] SOURCE_TILES = createSourceTiles();

    private static Tile[] createSourceTiles() {
        var tiles = new Tile[SOURCE_MASKS.length];
        for (int index = 0; index < tiles.length; index++)
            tiles[index] = new Tile(1 + index / 16, Submap.X4[index % 16 / 4][index % 4]);
        return tiles;
    }

    private static int[] createTiles() {
        var result = new int[256];
        Arrays.fill(result, -1);
        for (int tile = 0; tile < SOURCE_MASKS.length; tile++) result[SOURCE_MASKS[tile]] = tile;
        return result;
    }

    private static int tile(CTMLogic connections) {
        int mask = 0;
        for (int side = 0; side < 4; side++) if (connections.connected(SIDES[side])) mask |= 1 << side;
        for (int corner = 0; corner < 4; corner++) {
            int adjacent = 1 << corner | 1 << (corner + 1) % 4;
            if ((mask & adjacent) == adjacent && connections.connected(CORNERS[corner])) mask |= 1 << (corner + 4);
        }
        int tile = TILES[mask];
        if (tile < 0) throw new IllegalStateException("Invalid normalized CTM neighborhood: " + mask);
        return tile;
    }

    @Override
    public ICTMTexture<? extends TextureTypeCTM> makeTexture(TextureInfo info) {
        return new FullTexture(this, info);
    }

    @Override
    public int requiredTextures() {
        return 4; // A standalone inventory/base face, followed by the three original sheets.
    }

    @Override
    public int getQuadsPerSide() {
        return 1;
    }

    static Tile select(CTMLogic connections) {
        return SOURCE_TILES[tile(connections)];
    }

    record Tile(int sprite, ISubmap uv) {}

    private static final class FullTexture extends TextureCTM<FullCtmTextureType> {
        private FullTexture(FullCtmTextureType type, TextureInfo info) {
            super(type, info);
        }

        @Override
        public List<BakedQuad> transformQuad(BakedQuad baked, @Nullable ITextureContext context, int quadGoal) {
            var quad = makeQuad(baked, context);
            if (Configurations.disableCTM || !(context instanceof TextureContextCTM ctm))
                return List.of(quad.transformUVs(sprites[0]).rebake());
            var tile = select(ctm.getCTM(baked.getFace()));
            return List.of(quad.transformUVs(sprites[tile.sprite()], tile.uv()).rebake());
        }
    }
}
