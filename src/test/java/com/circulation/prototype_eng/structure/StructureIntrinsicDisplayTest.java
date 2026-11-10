package com.circulation.prototype_eng.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.client.preview.StructurePreviewExpansion;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.structure.tool.StructureBuildPlan;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureIntrinsicDisplayTest {
    private static final StructureQuery<String> VALUE = new StructureQuery<>(id("value"),
        (_, _) -> fail("Detached test must not read a World"), UnaryOperator.identity(), String::equals);
    private static final BlockPos CELL = new BlockPos(1, 0, 0);

    private static ResourceLocation id(String name) { return new ResourceLocation("intrinsic_test", name); }

    private static StructureDisplay display(Map<String, String> nbt, Map<String, String> properties, String tile) {
        return new StructureDisplay("declared", List.of(new StructureDisplay.BlockOption(id("block"), 0, nbt, properties)), tile);
    }

    private static StructurePredicate known(Map<String, String> nbt) {
        return StructurePredicates.test(VALUE, "yes"::equals, display(nbt, Map.of("axis", "x"), null));
    }

    private static TemplateStructure shape(String name, StructurePredicate predicate) {
        return TemplateStructure.create(id(name), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(Map.of(CELL, predicate)), List.of(), List.of());
    }

    @Test
    void shapeOnlyDeclarationFlowsThroughCatalogCodecPreviewAndBuilderWithoutMatcherInference() {
        var calls = new AtomicInteger();
        var declaration = StructurePredicates.test(VALUE, value -> {
            calls.incrementAndGet();
            return "yes".equals(value);
        }, display(Map.of(), Map.of("axis", "x"), null));
        var shape = shape("shape", declaration);
        var catalog = new StructureCatalog(List.of(new StructureDefinition(shape)));
        var wire = StructureCatalogCodec.decode(StructureCatalogCodec.encode(StructureCatalogView.capture(catalog, UUID.randomUUID(), 1)));
        var expansion = new StructurePreviewExpansion(wire, shape.id(), new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        while (expansion.model() == null) expansion.advance(8);
        var model = Objects.requireNonNull(expansion.model());
        assertEquals(0, calls.get());
        assertEquals(Map.of("axis", "x"), Objects.requireNonNull(model.cells().getFirst().display()).options().getFirst().properties());
        assertNotNull(StructureBuildPlan.expand(wire, shape.id(), BlockOrientation.NORTH_UP, Map.of()).cells().getFirst().display());
        var plan = new StructurePlan(shape, BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        var source = new StructureReadSource() {
            @Override public void requireServerThread() { }
            @Override public boolean isAreaLoaded(StructureBounds bounds) { return true; }
            @Override @SuppressWarnings("unchecked") public <T> T read(StructureQuery<T> query, BlockPos position) {
                assertSame(VALUE, query);
                return (T) "yes";
            }
        };
        assertNotNull(plan.match(StructureSnapshot.capture(plan.reads(), source)));
        assertEquals(1, calls.get());
    }

    @Test
    void legacyVersionOnePayloadKeepsEntryDisplaysAndEmptyPropertiesThroughTheActualDecoder() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            output.writeLong(1);
            output.writeLong(2);
            output.writeLong(3);
            output.writeInt(1);
            legacyString(output, id("legacy").toString());
            output.writeByte(2);
            output.writeByte(1);
            output.writeBoolean(false);
            output.writeByte(1);
            legacyPosition(output, BlockPos.ORIGIN);
            output.writeInt(1);
            output.writeInt(1);
            legacyPosition(output, BlockPos.ORIGIN);
            output.writeInt(1);
            legacyPosition(output, CELL);
            output.writeInt(0);
            output.writeInt(1);
            legacyString(output, id("legacy").toString());
            output.writeInt(0);
            output.writeBoolean(false);
            output.writeInt(1);
            legacyPosition(output, CELL);
            legacyString(output, "legacy/key");
            output.writeBoolean(false);
            output.writeInt(1);
            legacyString(output, id("block").toString());
            output.writeByte(3);
            output.writeInt(1);
            legacyString(output, "amount");
            legacyString(output, "7");
        }
        var view = StructureCatalogCodec.decode(bytes.toByteArray());
        assertTrue(view.nodes().getFirst().displays().isEmpty());
        var display = view.entries().get(id("legacy")).displays().get(CELL);
        assertEquals("legacy/key", display.key());
        assertTrue(display.complete());
        assertEquals(Map.of(), display.options().getFirst().properties());
        assertEquals(Map.of("amount", "7"), display.options().getFirst().nbt());
        var expansion = new StructurePreviewExpansion(view, id("legacy"), new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        while (expansion.model() == null) expansion.advance(8);
        assertEquals(display, Objects.requireNonNull(expansion.model()).cells().getFirst().display());
        assertEquals(display, StructureBuildPlan.expand(view, id("legacy"), BlockOrientation.NORTH_UP, Map.of()).cells().getFirst().display());
    }

    @Test
    void emptyNbtPredicateKeepsTilePresenceUndecidedAndActualMatchingStillRequiresTheTile() {
        var declaration = StructurePredicates.all(List.of(known(Map.of()), StructurePredicates.nbt(Map.of())));
        var display = Objects.requireNonNull(declaration.display());
        assertFalse(display.complete());
        assertEquals(Map.of(), display.options().getFirst().nbt());
        var plan = new StructurePlan(shape("empty_nbt", declaration), BlockPos.ORIGIN, BlockOrientation.NORTH_UP);
        for (boolean hasTile : List.of(false, true)) {
            var source = new StructureReadSource() {
                @Override public void requireServerThread() { }
                @Override public boolean isAreaLoaded(StructureBounds bounds) { return true; }
                @Override @SuppressWarnings("unchecked") public <T> T read(StructureQuery<T> query, BlockPos position) {
                    return (T) (query == VALUE ? "yes" : hasTile ? new NBTTagCompound() : null);
                }
            };
            var result = plan.match(StructureSnapshot.capture(plan.reads(), source));
            if (hasTile) assertNotNull(result);
            else assertNull(result);
        }
    }

    @Test
    void repeatedWideOrReservesExpansionBudgetBeforeAllocatingItsSearchFrontier() {
        var alternatives = new ArrayList<StructurePredicate>();
        for (int i = 0; i < 1000; i++) alternatives.add(StructurePredicates.nbt(Map.of("value", Integer.toString(i))));
        var shared = StructurePredicates.any(alternatives);
        var parts = new ArrayList<StructurePredicate>();
        parts.add(known(Map.of()));
        for (int i = 0; i < 1000; i++) parts.add(shared);
        var declaration = StructurePredicates.all(parts);
        var display = Objects.requireNonNull(assertTimeout(Duration.ofSeconds(2), declaration::display));
        assertFalse(display.complete());
        assertEquals(1, display.options().size());
        assertEquals(Map.of(), display.options().getFirst().nbt());
        assertSame(display, declaration.display());
    }

    private static void legacyString(DataOutputStream output, String text) throws IOException {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void legacyPosition(DataOutputStream output, BlockPos position) throws IOException {
        output.writeInt(position.getX());
        output.writeInt(position.getY());
        output.writeInt(position.getZ());
    }

    @Test
    void unregisteredSameIdChildrenRetainIndependentIntrinsicPropertiesAndExplicitRootOverridesWin() {
        var first = shape("child", known(Map.of("side", "a")));
        var second = TemplateStructure.create(id("child"), BlockOrientation.NORTH_UP, new BlockPos(0, 1, 0), false,
            new StructurePattern(Map.of(CELL, known(Map.of("side", "b")))), List.of(), List.of());
        var parent = TemplateStructure.create(id("root"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(Map.of(new BlockPos(2, 0, 0), known(Map.of()))), List.of(), List.of(first, second));
        var override = display(Map.of("override", "yes"), Map.of(), null);
        var view = StructureCatalogCodec.decode(StructureCatalogCodec.encode(StructureCatalogView.capture(
            new StructureCatalog(List.of(new StructureDefinition(parent, null, Map.of(new BlockPos(2, 0, 0), override)))), UUID.randomUUID(), 0)));
        assertEquals(1, view.entries().size());
        assertEquals(3, view.nodes().size());
        var expansion = new StructurePreviewExpansion(view, parent.id(), new StructurePreviewSettings(BlockOrientation.NORTH_UP));
        while (expansion.model() == null) expansion.advance(16);
        var model = Objects.requireNonNull(expansion.model());
        assertEquals(3, model.cells().size());
        assertTrue(model.cells().stream().anyMatch(cell -> override.equals(cell.display())));
        assertTrue(model.cells().stream().anyMatch(cell -> Objects.requireNonNull(cell.display()).options().getFirst().nbt().equals(Map.of("side", "a"))));
        assertTrue(model.cells().stream().anyMatch(cell -> Objects.requireNonNull(cell.display()).options().getFirst().nbt().equals(Map.of("side", "b"))));
        assertTrue(StructureBuildPlan.expand(view, parent.id(), BlockOrientation.NORTH_UP, Map.of()).cells().stream().allMatch(cell -> cell.display() != null));
    }

    @Test
    void orBranchesDoNotBorrowStatesOrCrossMergeNbtAndUnknownAndOffsetsStayPartial() {
        var unknown = StructurePredicates.test(VALUE, _ -> fail("Presentation must not execute condition"));
        var disjunction = StructurePredicates.any(List.of(known(Map.of("x", "a")), unknown));
        var display = Objects.requireNonNull(disjunction.display());
        assertEquals(List.of(Map.of("x", "a")), display.options().stream().map(StructureDisplay.BlockOption::nbt).toList());
        assertFalse(display.complete());
        var nbtArm = Objects.requireNonNull(StructurePredicates.any(List.of(known(Map.of()), StructurePredicates.nbt(Map.of("x", "a")))).display());
        assertEquals(List.of(Map.of()), nbtArm.options().stream().map(StructureDisplay.BlockOption::nbt).toList());
        assertFalse(nbtArm.complete());
        var conjunction = Objects.requireNonNull(StructurePredicates.all(List.of(known(Map.of()), unknown,
            StructurePredicates.at(new BlockPos(3, 0, 0), known(Map.of("remote", "never"))))).display());
        assertEquals(Map.of(), conjunction.options().getFirst().nbt());
        assertFalse(conjunction.complete());
        assertNull(StructurePredicates.any(List.of(StructurePredicates.nbt(Map.of("x", "a")), unknown)).display());
        assertNull(StructurePredicates.at(new BlockPos(1, 0, 0), known(Map.of())).display());
    }

    @Test
    void andNbtConflictsRemoveOnlyConflictingBranchesAndTileConflictCannotRevive() {
        assertNull(StructurePredicates.all(List.of(known(Map.of("x", "a")), StructurePredicates.nbt(Map.of("x", "b")))).display());
        var alternatives = Objects.requireNonNull(StructurePredicates.any(List.of(
            StructurePredicates.all(List.of(known(Map.of("x", "a")), StructurePredicates.nbt(Map.of("y", "one")))),
            known(Map.of("x", "b")))).display());
        assertEquals(2, alternatives.options().size());
        assertTrue(alternatives.options().stream().anyMatch(option -> option.nbt().equals(Map.of("x", "a", "y", "one"))));
        assertTrue(alternatives.options().stream().anyMatch(option -> option.nbt().equals(Map.of("x", "b"))));
        var parts = new ArrayList<StructurePredicate>();
        for (String tile : List.of("{x:1}", "{x:2}", "{x:3}")) parts.add(StructurePredicates.test(VALUE, _ -> true, display(Map.of(), Map.of(), tile)));
        var conflict = Objects.requireNonNull(StructurePredicates.all(parts).display());
        assertFalse(conflict.complete());
        assertNull(conflict.tileNbt());
    }

    @Test
    void canonicalKeysSeparateAmbiguousMapTextAndPropertiesFromNbt() {
        var first = Objects.requireNonNull(StructurePredicates.all(List.of(known(Map.of("a", "b, c=d")))).display());
        var second = Objects.requireNonNull(StructurePredicates.all(List.of(known(Map.of("a", "b", "c", "d")))).display());
        assertNotEquals(first.key(), second.key());
        var nbt = new StructureDisplay.BlockOption(id("block"), 0, Map.of("axis", "x"));
        var properties = new StructureDisplay.BlockOption(id("block"), 0, Map.of(), Map.of("axis", "x"));
        assertNotEquals(BuilderState.optionKey(nbt), BuilderState.optionKey(properties));
    }

    @Test
    void exponentiallyManyNbtChoicesHaveBoundedProjectionAndCachedCompletedRepresentation() {
        var parts = new ArrayList<StructurePredicate>();
        parts.add(known(Map.of()));
        for (int i = 0; i < 40; i++) parts.add(StructurePredicates.any(List.of(
            StructurePredicates.nbt(Map.of("key" + i, "a")), StructurePredicates.nbt(Map.of("key" + i, "b")))));
        var declaration = StructurePredicates.all(parts);
        var display = Objects.requireNonNull(assertTimeout(Duration.ofSeconds(2), declaration::display));
        assertEquals(32, display.options().size());
        assertTrue(display.options().stream().allMatch(option -> option.nbt().size() == 40));
        assertFalse(display.complete());
        assertSame(display, declaration.display());
    }

    @Test
    void deepUndecidedFactsRetainKnownStateAndMatchingTransformsNeverFreezePresentation() {
        StructurePredicate declaration = known(Map.of());
        for (int i = 0; i < 10000; i++) declaration = StructurePredicates.all(List.of(declaration));
        var captured = declaration;
        var display = Objects.requireNonNull(assertTimeout(Duration.ofSeconds(2), captured::display));
        assertEquals(Map.of("axis", "x"), display.options().getFirst().properties());
        assertFalse(display.complete());
        var calls = new AtomicInteger();
        var pattern = countingPattern(calls);
        var transformed = pattern.transform(StructureTransform.of(BlockOrientation.EAST_UP));
        assertEquals(0, calls.get());
        assertEquals(1, transformed.displays().size());
        assertEquals(1, calls.get());
        assertSame(transformed.displays(), transformed.displays());
    }

    private static StructurePattern countingPattern(AtomicInteger calls) {
        var custom = new StructurePredicate() {
            @Override public void collectReads(StructureReadPlan.Builder plan, BlockPos position) { }
            @Override public boolean matches(StructureSnapshot snapshot, BlockPos position) { return true; }
            @Override public StructureDisplay display() { calls.incrementAndGet(); return StructureIntrinsicDisplayTest.display(Map.of(), Map.of(), null); }
        };
        return new StructurePattern(Map.of(CELL, custom));
    }
}
