package com.circulation.prototype_eng.client.render.construction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.FLUID;
import static com.circulation.prototype_eng.client.render.construction.ConstructionSurface.SOLID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionSurfaceTest {
    @Test
    void adjacentCellsMakeOnlyTheTwelveEdgesAndSixFacesOfABox() {
        var blocks = Set.of(new Point(4, 3, 4), new Point(5, 3, 4));
        var wire = edges(mesh(blocks, Set.of(), 0, 0, (byte) 0));
        assertEquals(12, wire.size());
        assertTrue(wire.contains(new Segment(new Point(4, 3, 4), new Point(6, 3, 4))));
        assertFalse(wire.contains(new Segment(new Point(5, 3, 4), new Point(5, 4, 4))));
        assertEquals(6, mesh(blocks, Set.of(), 0, 0, SOLID).size() / 4);
    }

    @Test
    void preservesConcaveEdgesAndTheOpeningOfAThroughHole() {
        var first = new Point(6, 2, 6);
        var concave = Set.of(first, new Point(7, 2, 6), new Point(6, 2, 7));
        assertTrue(edges(mesh(concave, Set.of(), 0, 0, (byte) 0)).contains(new Segment(new Point(7, 2, 7), new Point(7, 3, 7))));
        Set<Point> ring = new HashSet<>();
        for (int z = 5; z < 8; z++) for (int x = 5; x < 8; x++) if (x != 6 || z != 6) ring.add(new Point(x, 2, z));
        var wire = edges(mesh(ring, Set.of(), 0, 0, (byte) 0));
        assertTrue(wire.contains(new Segment(new Point(6, 3, 6), new Point(7, 3, 6))));
        assertTrue(wire.contains(new Segment(new Point(6, 2, 6), new Point(6, 3, 6))));
        assertFalse(wire.contains(new Segment(new Point(5, 3, 7), new Point(6, 3, 7))));
    }

    @Test
    void ownershipAcrossNegativeNetworkAndHeightBoundariesDoesNotDuplicateEdgesOrFaces() {
        var blocks = Set.of(new Point(-33, 7, 4), new Point(-32, 8, 4));
        var west = edges(mesh(blocks, Set.of(), -48, 0, (byte) 0));
        var east = edges(mesh(blocks, Set.of(), -32, 8, (byte) 0));
        var shared = new Segment(new Point(-32, 8, 4), new Point(-32, 8, 5));
        assertTrue(west.contains(shared));
        assertFalse(east.contains(shared));

        var adjacent = Set.of(new Point(-33, 7, 4), new Point(-32, 7, 4));
        var westFaces = mesh(adjacent, Set.of(), -48, 0, SOLID);
        var eastFaces = mesh(adjacent, Set.of(), -32, 0, SOLID);
        assertEquals(5, westFaces.size() / 4);
        assertEquals(5, eastFaces.size() / 4);
        assertFalse(edges(mesh(adjacent, Set.of(), -48, 0, (byte) 0))
            .contains(new Segment(new Point(-32, 7, 4), new Point(-32, 8, 4))));
    }

    @Test
    void onlyTheSolidOwnsTheFaceSharedWithAFluidBatch() {
        var blocks = Set.of(new Point(5, 4, 5));
        var fluids = Set.of(new Point(6, 4, 5));
        assertEquals(6, mesh(blocks, fluids, 0, 0, SOLID).size() / 4);
        assertEquals(5, mesh(blocks, fluids, 0, 0, FLUID).size() / 4);
    }

    private static Set<Segment> edges(List<Point> vertices) {
        Set<Segment> result = new HashSet<>();
        for (int i = 0; i < vertices.size(); i += 2) assertTrue(result.add(new Segment(vertices.get(i), vertices.get(i + 1))));
        return result;
    }

    private static List<Point> mesh(Set<Point> blocks, Set<Point> fluids, int baseX, int baseY, byte material) {
        var surface = new ConstructionSurface();
        List<Point> vertices = new ArrayList<>();
        boolean finished;
        do {
            finished = surface.step((x, y, z) -> {
                var point = new Point(x + baseX, y + baseY, z);
                return blocks.contains(point) ? SOLID : fluids.contains(point) ? FLUID : 0;
            }, material, (x, y, z) -> vertices.add(new Point(x + baseX, y + baseY, z)));
        } while (!finished);
        return vertices;
    }

    private record Point(int x, int y, int z) {
    }

    private record Segment(Point start, Point end) {
    }
}
