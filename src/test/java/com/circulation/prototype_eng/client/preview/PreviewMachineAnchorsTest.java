package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("resource") // Each test owns its local binding registry for the entire fixture lifetime.
class PreviewMachineAnchorsTest {
    private static final ResourceLocation ROOT = id("root"), SLICE = id("slice"), MACHINE = id("machine");
    private static final MachineType TYPE = MachineType.builder(MACHINE, StructurePreviewText.Title, ROOT).build();

    private static ResourceLocation id(String name) {
        return new ResourceLocation("preview_models_test", name);
    }

    private static GeckoModelBinding binding() {
        return new GeckoModelBinding(id("geo"), id("texture"));
    }

    private static StructureCatalogView catalog() {
        var display = new StructureDisplay("material", List.of(new StructureDisplay.BlockOption(id("block"), 0, Map.of())), null);
        var root = new StructureCatalogView.Node(ROOT, BlockOrientation.NORTH_UP, true, StructurePreviewProvider.Kind.TEMPLATE,
            BlockPos.ORIGIN, 1, 1, BlockPos.ORIGIN, List.of(new BlockPos(1, 0, 0)), List.of(1, 1));
        var slice = new StructureCatalogView.Node(SLICE, BlockOrientation.NORTH_UP, false, StructurePreviewProvider.Kind.SLICE,
            new BlockPos(2, 0, 0), 0, 3, new BlockPos(1, 0, 0), List.of(new BlockPos(0, 1, 0)), List.of());
        return new StructureCatalogView(UUID.randomUUID(), 0, List.of(root, slice), Map.of(
            ROOT, new StructureCatalogView.Entry(0, null, Map.of(new BlockPos(1, 0, 0), display)),
            SLICE, new StructureCatalogView.Entry(1, null, Map.of(new BlockPos(0, 1, 0), display))));
    }

    private static StructurePreviewSettings settings(BlockOrientation orientation) {
        return new StructurePreviewSettings(orientation, Map.of(StructurePreviewPath.ROOT.child(0), 0,
            StructurePreviewPath.ROOT.child(1), 3), 20, 100);
    }

    private static StructurePreviewModel model(BlockOrientation orientation) {
        var expansion = new StructurePreviewExpansion(catalog(), ROOT, settings(orientation));
        for (int i = 0; expansion.model() == null && i < 1000; i++) expansion.advance(1);
        return Objects.requireNonNull(expansion.model());
    }

    private static List<StructureRenderAnchors.Anchor> finish(PreviewMachineAnchors selection) {
        assertEquals(0, selection.advance(0));
        assertNull(selection.completed());
        for (int i = 0; selection.completed() == null && i < 1000; i++) assertTrue(selection.advance(1) <= 1);
        return Objects.requireNonNull(selection.completed());
    }

    @Test
    void machineAndStructureModelsRetainExactSharedOccurrenceSlices() {
        var bindings = new MachineRenderBindings();
        bindings.bindMachine(MACHINE, binding());
        bindings.bindStructure(MACHINE, ROOT, new GeckoStructureBinding(binding()));
        bindings.bindStructure(MACHINE, SLICE, new GeckoStructureBinding(binding()));
        var anchors = finish(new PreviewMachineAnchors(model(BlockOrientation.NORTH_UP), TYPE, bindings.view()));
        assertEquals(5, anchors.size());
        assertEquals(StructureRenderAnchors.Kind.MACHINE, anchors.getFirst().part().kind());
        assertEquals(List.of(new BlockPos(2, 0, 0), new BlockPos(3, 0, 0), new BlockPos(4, 0, 0)),
            anchors.stream().filter(a -> a.part().id().equals(SLICE)).map(StructureRenderAnchors.Anchor::origin).toList());
        assertTrue(anchors.stream().filter(a -> a.part().id().equals(SLICE)).allMatch(a -> a.part().occurrencePath().equals(List.of(1))));
    }

    @Test
    void structureOnlyModeStillDrawsEachZeroOrNonzeroOccurrenceOnce() {
        var bindings = new MachineRenderBindings();
        bindings.bindStructure(MACHINE, SLICE, new GeckoStructureBinding(binding(), GeckoStructureBinding.SliceMode.STRUCTURE_ONLY));
        var anchors = finish(new PreviewMachineAnchors(model(BlockOrientation.NORTH_UP), TYPE, bindings.view()));
        assertEquals(2, anchors.size());
        assertEquals(List.of(List.of(0), List.of(1)), anchors.stream().map(a -> a.part().occurrencePath()).toList());
        assertTrue(anchors.stream().allMatch(a -> a.part().slice() == -1 && a.origin().equals(new BlockPos(2, 0, 0))));
    }

    @Test
    void rootRotationAffectsSliceStepOnceAndRequestRetainsRawLocalOffset() {
        var orientation = BlockOrientation.get(EnumFacing.EAST, EnumFacing.UP);
        var model = model(orientation);
        var bindings = new MachineRenderBindings();
        var binding = new GeckoModelBinding(id("geo"), id("texture"), null, null, List.of(), 2, 3, 4, 5, MachineRenderPass.DEFAULT, false);
        bindings.bindStructure(MACHINE, SLICE, new GeckoStructureBinding(binding));
        var anchors = finish(new PreviewMachineAnchors(model, TYPE, bindings.view()));
        assertEquals(List.of(new BlockPos(0, 0, 2), new BlockPos(0, 0, 3), new BlockPos(0, 0, 4)), anchors.stream().map(StructureRenderAnchors.Anchor::origin).toList());
        var request = GeckoRenderRequest.from(new GeckoRenderRequest.Owner(), new GeckoRenderRequest.Epoch(), new GeckoRenderRequest.Epoch(),
            anchors.getFirst(), BlockPos.ORIGIN, GeckoModelBaker.BakeMode.ALL, new GeckoRenderRequest.Sample(0, 1));
        assertEquals(anchors.getFirst().origin(), request.position());
        assertEquals(new Vec3d(2, 3, 4), request.localOffset());
        assertEquals(new Vec3d(-4, 8, 2), anchors.getFirst().modelOffset());
    }

    @Test
    void formingModeRequiresContextAndPreservesFullBomScanAndWorldIntent() {
        var catalog = new ClientStructureCatalog();
        var connection = new Object();
        var published = catalog();
        catalog.connected(connection);
        var bytes = StructureCatalogCodec.encode(published);
        for (int offset = 0, index = 0; offset < bytes.length; offset += StructureCatalogPacket.CHUNK_BYTES, index++)
            catalog.receive(connection, new StructureCatalogPacket(published.session(), published.sequence(), bytes.length, index,
                Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + StructureCatalogPacket.CHUNK_BYTES))));
        try (var service = new StructurePreviews(catalog)) {
            var settings = settings(BlockOrientation.NORTH_UP);
            var bare = new StructurePreviewView(service, ROOT, settings);
            bare.advance(1000);
            assertFalse(bare.formedPreview(bare.token(), true));
            var ui = new StructurePreviewView(service, ROOT, settings, TYPE);
            ui.advance(1000);
            var model = Objects.requireNonNull(ui.model());
            var whole = ui.materials(StructureDisplay::key, "unknown");
            assertTrue(service.project(ROOT, settings, BlockPos.ORIGIN, new StructureProjectionOptions()));
            var intent = service.projection();
            var oldToken = ui.token();
            var click = new StructurePreviewView.Click(oldToken, model.cells().getFirst());
            assertTrue(ui.formedPreview(oldToken, true));
            assertNotSame(oldToken, ui.token());
            assertFalse(ui.choose(click));
            assertFalse(ui.formedPreview(oldToken, false));
            assertSame(model, ui.model());
            assertSame(intent, service.projection());
            assertEquals(whole, ui.materials(StructureDisplay::key, "unknown"));
            assertTrue(ui.sceneSamples(100).isEmpty());
            ui.scan = true;
            ui.scan(new Object(), (_, _) -> StructureProjectionState.Status.MISSING, 100);
            assertEquals(whole.stream().mapToLong(StructurePreviewView.Material::count).sum(),
                ui.materials(StructureDisplay::key, "unknown").stream().mapToLong(StructurePreviewView.Material::mismatch).sum());
            ui.threeDimensional = false;
            ui.layer = 1;
            assertFalse(ui.sceneSamples(100).isEmpty());
            assertFalse(ui.formingModelsVisible());
        }
    }

    @Test
    void unrelatedMachineCannotSelectModelsForTheSameBareStructure() {
        var other = MachineType.builder(id("other"), StructurePreviewText.Title, id("other_structure")).build();
        var model = model(BlockOrientation.NORTH_UP);
        assertThrows(IllegalArgumentException.class, () -> new PreviewMachineAnchors(model, other, new MachineRenderBindings().view()));
    }
}
