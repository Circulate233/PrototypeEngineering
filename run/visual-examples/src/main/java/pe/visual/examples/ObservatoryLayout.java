package pe.visual.examples;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;

import static pe.visual.examples.ObservatoryLayout.Surface.*;

final class ObservatoryLayout {
    private static final int DOME_Z = 32;
    private static final int TOWER_Z = 19;

    enum Surface {
        STONE_BRICK, ANDESITE, QUARTZ, CARVED_QUARTZ, PRISMARINE, DARK_PRISMARINE,
        BLUE_GLASS, CYAN_GLASS, SKY_GLASS, SEA_LANTERN, GOLD, GLOWSTONE
    }

    private final BlockPos2ObjectMap<Surface> cells = new BlockPos2ObjectMap<>(12_000);

    static BlockPos2ObjectMap<Surface> build() {
        var layout = new ObservatoryLayout();
        layout.terrace();
        layout.drum();
        layout.dome();
        layout.portico();
        for (int side : new int[]{-1, 1}) {
            layout.tower(side * 28);
            layout.gallery(side);
            layout.court(side);
        }
        layout.orrery();
        return layout.cells;
    }

    private void terrace() {
        for (int z = 1; z <= 61; z++) for (int x = -38; x <= 38; x++) {
            int dx = Math.abs(x), dz = Math.abs(z - DOME_Z);
            if (z < 3 && dx > 3 || dx + dz > 62) continue;
            double radius = Math.hypot(x, z - DOME_Z);
            Surface stone = Math.floorMod(Math.floorDiv(x, 3) + Math.floorDiv(z, 3), 2) == 0 ? STONE_BRICK : ANDESITE;
            if (dx >= 37 || dz >= 28 || dx + dz >= 61) stone = DARK_PRISMARINE;
            if (radius >= 16 && radius <= 17 || radius >= 8 && radius <= 9) stone = DARK_PRISMARINE;
            if (radius < 16 && (dx == 0 || dz == 0 || dx == dz)) stone = GOLD;
            if (z <= 15 && dx <= 3) stone = dx == 3 ? CARVED_QUARTZ : QUARTZ;
            put(x, 0, z, stone);
        }
        for (int x : new int[]{-34, -23, -9, 9, 23, 34}) for (int z : new int[]{8, 49}) {
            put(x, 1, z, CARVED_QUARTZ);
            put(x, 2, z, DARK_PRISMARINE);
            put(x, 3, z, SEA_LANTERN);
            put(x, 4, z, PRISMARINE);
        }
    }

    private void drum() {
        for (int z = DOME_Z - 20; z <= DOME_Z + 20; z++) for (int x = -20; x <= 20; x++) {
            int dz = z - DOME_Z;
            double radius = Math.hypot(x, dz);
            if (radius < 17 || radius > 20.4) continue;
            double bay = Math.abs(Math.IEEEremainder(Math.atan2(x, dz), Math.PI / 8)) * radius;
            for (int y = 1; y <= 16; y++) {
                boolean frontDoor = Math.abs(x) <= 3 && dz < 0 && y <= 6;
                boolean sideDoor = Math.abs(z - TOWER_Z) <= 1 && y <= 5;
                if (frontDoor || sideDoor) continue;
                if (y < 14 && (radius > 19 || radius < 17.6)) continue;
                Surface stone;
                if (y == 1 || y == 13 || y == 15) stone = DARK_PRISMARINE;
                else if (y >= 14 || y == 2 || y == 12) stone = CARVED_QUARTZ;
                else if (y >= 4 && y <= 11 && bay <= (y >= 10 ? 1.0 : 2.0))
                    stone = y >= 9 ? SKY_GLASS : CYAN_GLASS;
                else if (bay > 2.9) stone = QUARTZ;
                else stone = STONE_BRICK;
                put(x, y, z, stone);
            }
        }
        for (int[] lamp : new int[][]{{0, -17}, {12, -12}, {17, 0}, {12, 12}, {0, 17}, {-12, 12}, {-17, 0}, {-12, -12}}) {
            put(lamp[0], 11, DOME_Z + lamp[1], SEA_LANTERN);
            put(lamp[0], 12, DOME_Z + lamp[1], CARVED_QUARTZ);
        }
    }

    private void dome() {
        for (int y = 17; y <= 31; y++) {
            double outer = domeRadius(y - 0.5);
            double inner = Math.min(outer - 1.1, domeRadius(y + 0.5) - 0.65);
            for (int dz = -20; dz <= 20; dz++) for (int x = -20; x <= 20; x++) {
                double radius = Math.hypot(x, dz);
                if (radius > outer || radius < Math.max(2.75, inner)) continue;
                double rib = Math.abs(Math.IEEEremainder(Math.atan2(x, dz), Math.PI / 6)) * radius;
                Surface pane;
                if (rib <= 0.66) pane = QUARTZ;
                else if (y == 22 || y == 27) pane = PRISMARINE;
                else pane = y < 22 ? BLUE_GLASS : y < 27 ? CYAN_GLASS : SKY_GLASS;
                put(x, y, DOME_Z + dz, pane);
            }
        }
        for (int dz = -4; dz <= 4; dz++) for (int x = -4; x <= 4; x++) {
            double radius = Math.hypot(x, dz);
            if (radius <= 4.3 && radius > 2.4) put(x, 32, DOME_Z + dz, DARK_PRISMARINE);
            if (radius <= 3.75 && radius > 2.4) {
                for (int y = 33; y <= 35; y++) put(x, y, DOME_Z + dz, x * dz == 0 ? QUARTZ : SKY_GLASS);
            }
            if (radius <= 3.75) put(x, 36, DOME_Z + dz, PRISMARINE);
            if (radius <= 2.3) put(x, 37, DOME_Z + dz, CARVED_QUARTZ);
        }
        put(0, 38, DOME_Z, SEA_LANTERN);
        put(0, 39, DOME_Z, GOLD);
    }

    private static double domeRadius(double y) {
        double height = (y - 16.0) / 16.0;
        return 19.4 * Math.sqrt(Math.max(0, 1.0 - height * height));
    }

    private void portico() {
        for (int x : new int[]{-6, -4, 4, 6}) for (int z : new int[]{8, 11}) {
            put(x, 1, z, CARVED_QUARTZ);
            for (int y = 2; y <= 6; y++) put(x, y, z, QUARTZ);
            put(x, 7, z, CARVED_QUARTZ);
        }
        for (int x = -7; x <= 7; x++) for (int z = 7; z <= 15; z++) {
            put(x, 7, z, z == 7 || Math.abs(x) == 7 ? CARVED_QUARTZ : QUARTZ);
            int crest = 8 + (7 - Math.abs(x)) / 2;
            put(x, crest, z, DARK_PRISMARINE);
            if (z == 7) for (int y = 8; y < crest; y++) put(x, y, z, PRISMARINE);
        }
        for (int x : new int[]{-4, 0, 4}) put(x, 6, 10, SEA_LANTERN);
        put(0, 9, 7, GOLD);
        put(0, 10, 7, SEA_LANTERN);
    }

    private void tower(int center) {
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
            int x = center + dx, z = TOWER_Z + dz;
            for (int y = 1; y <= 16; y++) {
                int width = y == 7 || y == 15 || y == 16 ? 7 : 6;
                if (!octagon(dx, dz, width) || octagon(dx, dz, 5)) continue;
                boolean opening = Math.abs(dz) <= 1 || Math.abs(dx) <= 1 && dz < 0;
                if (opening && y <= 5) continue;
                Surface stone;
                if (y == 1 || y == 7 || y == 15) stone = DARK_PRISMARINE;
                else if (y >= 9 && y <= 13 && (Math.abs(dx) <= 1 || Math.abs(dz) <= 1)) stone = SKY_GLASS;
                else stone = Math.abs(dx) >= 3 && Math.abs(dz) >= 3 || y == 16 ? CARVED_QUARTZ : QUARTZ;
                put(x, y, z, stone);
            }
            if (octagon(dx, dz, 5)) put(x, 8, z, QUARTZ);
            for (int y = 17; y <= 22; y++) {
                int width = 23 - y;
                if (octagon(dx, dz, width) && !octagon(dx, dz, width - 2))
                    put(x, y, z, y == 17 || y == 20 ? PRISMARINE : DARK_PRISMARINE);
            }
        }
        put(center, 6, TOWER_Z, SEA_LANTERN);
        put(center, 7, TOWER_Z, CARVED_QUARTZ);
        put(center, 22, TOWER_Z, CARVED_QUARTZ);
        put(center, 23, TOWER_Z, SEA_LANTERN);
        put(center, 24, TOWER_Z, GOLD);
    }

    private static boolean octagon(int x, int z, int radius) {
        int ax = Math.abs(x), az = Math.abs(z);
        return ax <= radius && az <= radius && ax + az <= Math.max(radius, 2 * radius - 2);
    }

    private void gallery(int side) {
        for (int x = 14; x <= 23; x++) for (int dz = -3; dz <= 3; dz++) {
            int z = TOWER_Z + dz;
            if (Math.abs(dz) == 3 && x % 3 == 2) {
                put(side * x, 1, z, CARVED_QUARTZ);
                for (int y = 2; y <= 6; y++) put(side * x, y, z, QUARTZ);
            }
            put(side * x, 7, z, CARVED_QUARTZ);
            if (Math.abs(dz) <= 2) put(side * x, 8, z, Math.abs(dz) == 2 ? DARK_PRISMARINE : CYAN_GLASS);
        }
        put(side * 18, 7, TOWER_Z, SEA_LANTERN);
    }

    private void court(int side) {
        for (int centerZ : new int[]{39, 52}) {
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                if (!octagon(dx, dz, 4)) continue;
                Surface lamp = !octagon(dx, dz, 3) ? DARK_PRISMARINE
                    : (dx == 0 || dz == 0) && Math.abs(dx + dz) <= 2 ? SEA_LANTERN : CYAN_GLASS;
                put(side * 27 + dx, 1, centerZ + dz, lamp);
            }
        }
    }

    private void orrery() {
        for (int dz = -6; dz <= 6; dz++) for (int x = -6; x <= 6; x++) {
            double radius = Math.hypot(x, dz);
            if (orbit(radius)) {
                put(x, 8, DOME_Z + dz, GOLD);
                int y = 10 + Math.floorDiv(x, 2);
                put(x, y, DOME_Z + dz, (x + dz) % 4 == 0 ? SEA_LANTERN : GOLD);
                if (Math.floorMod(x, 2) == 0 && orbit(Math.hypot(x - 1, dz))) put(x, y - 1, DOME_Z + dz, GOLD);
            }
            if (radius <= 1.5) for (int y = 9; y <= 11; y++) put(x, y, DOME_Z + dz, GLOWSTONE);
        }
        for (int x = -5; x <= 5; x++) put(x, 8, DOME_Z, DARK_PRISMARINE);
        for (int y = 12; y < 36; y++) put(0, y, DOME_Z, y % 4 == 0 ? GOLD : DARK_PRISMARINE);
    }

    private static boolean orbit(double radius) {
        return radius >= 4.95 && radius <= 6.5;
    }

    private void put(int x, int y, int z, Surface surface) {
        cells.put(BlockPos2ObjectMap.toLong(x, y, z), surface);
    }
}
