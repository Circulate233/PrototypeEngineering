package com.circulation.prototype_eng.client.render;

import org.junit.jupiter.api.Test;
import team.chisel.ctm.client.util.CTMLogic;
import team.chisel.ctm.client.util.Dir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FullCtmTextureTest {
    private static final Dir[] DIRECTIONS = {Dir.TOP, Dir.RIGHT, Dir.BOTTOM, Dir.LEFT,
        Dir.TOP_RIGHT, Dir.BOTTOM_RIGHT, Dir.BOTTOM_LEFT, Dir.TOP_LEFT};

    @Test
    void nativeConnectionMapsSelectFullSourceTilesWhoseVisibleEdgesMatchTheActualNeighbors() throws Exception {
        var images = new BufferedImage[3];
        for (int sheet = 0; sheet < images.length; sheet++)
            images[sheet] = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(
                "/assets/prototype_eng/textures/ctm/cartesian_glass/side_full_" + sheet + ".png")));
        var selected = new HashSet<String>();
        int[][] sides = {{8, 1}, {14, 8}, {8, 14}, {1, 8}};
        int[][] corners = {{14, 1}, {14, 14}, {1, 14}, {1, 1}};
        for (int neighbors = 0; neighbors < 256; neighbors++) {
            var tile = FullCtmTextureType.select(new Connections(neighbors));
            int sheet = tile.sprite() - 1;
            int x = Math.round(tile.uv().getXOffset() * 4), y = Math.round(tile.uv().getYOffset() * 4);
            assertEquals(16, tile.uv().getWidth() * 4, .001);
            assertEquals(16, tile.uv().getHeight() * 4, .001);
            selected.add(sheet + "/" + x + "/" + y);
            for (int side = 0; side < 4; side++) {
                int alpha = images[sheet].getRGB(x + sides[side][0], y + sides[side][1]) >>> 24;
                assertEquals((neighbors & 1 << side) != 0, alpha < 255, "visible edge at neighborhood " + neighbors + " side " + side);
                int next = (side + 1) % 4;
                if ((neighbors & 1 << side) != 0 && (neighbors & 1 << next) != 0) {
                    int cornerAlpha = images[sheet].getRGB(x + corners[side][0], y + corners[side][1]) >>> 24;
                    assertEquals((neighbors & 1 << (side + 4)) != 0, cornerAlpha < 255, "visible inner corner at neighborhood " + neighbors);
                }
            }
        }
        assertEquals(47, selected.size());
    }

    private static final class Connections extends CTMLogic {
        private Connections(int neighbors) {
            for (int direction = 0; direction < DIRECTIONS.length; direction++)
                setConnectedState(DIRECTIONS[direction], (neighbors & 1 << direction) != 0);
        }
    }
}
