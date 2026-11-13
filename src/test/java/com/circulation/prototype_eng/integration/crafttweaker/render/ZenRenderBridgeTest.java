package com.circulation.prototype_eng.integration.crafttweaker.render;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.render.ClientRenderDeclarations;
import com.circulation.prototype_eng.api.client.render.GeckoModelBinding;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.api.client.render.MachineRenderRegistrationEvent;
import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.machine.render.MachineAnimationState;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.StructurePreviewProvider;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ZenRenderBridgeTest {
    private static final ResourceLocation MACHINE = id("machine");
    private static final ResourceLocation COMPONENT = id("component");
    private static final ResourceLocation STRUCTURE = id("structure");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("render_test", path);
    }

    @Test
    void compilerFreezesDeclarationsIntoTheExactNativeRegistryAndDataKeysProduceDetachedWorkerLayers() throws Exception {
        var installed = new MachineRenderBindings();
        var foreign = new MachineRenderBindings();
        var event = new MachineRenderRegistrationEvent(installed);
        var previous = installed.view();
        ClientRenderDeclarations.register(received -> {
            assertSame(event, received);
            assertSame(installed, received.bindings());
            assertThrows(IllegalStateException.class, () -> ClientRenderDeclarations.register(_ -> fail("Late declaration")));
            assertThrows(IllegalStateException.class, () -> ClientRenderDeclarations.dispatch(received));
        });
        var module = compile("""
            import mods.prototype_eng.render.RenderBindings;
            var binding = RenderBindings.gecko().geo("render_test:geo/model.json")
                .texture("render_test:textures/model.png").animation("render_test:animations/model.json")
                .defaultAnimation("idle").animationLayer("base").animationLayer("overlay")
                .animationStateKey("state").animationLayersStateKey("layers")
                .modelOffset(1.0, 2.0, 3.0).yOffset(4.0).forceGlobalRenderer(true);
            RenderBindings.bindGeckoToMachineType("render_test:machine", binding);
            binding.pass("transparent");
            RenderBindings.bindGeckoToComponentType("render_test:component", binding);
            binding.pass("bloom").sliceMode("structure_only");
            RenderBindings.bindGeckoToStructure("render_test:machine", "render_test:structure", binding);
            binding.pass("bloom_transparent").sliceMode("per_slice");
            RenderBindings.bindGeckoToStructure("render_test:machine", "render_test:slices", binding);
            """);
        Objects.requireNonNull(module.getMain()).run();
        assertTrue(installed.view().machines().isEmpty());
        ScriptClientRenderEvents.bindings(event);
        assertFalse(installed.current(previous));
        assertTrue(foreign.view().machines().isEmpty());
        var view = installed.view();
        var machine = view.machines().get(MACHINE);
        assertEquals(id("geo/model.json"), machine.geo());
        assertEquals(List.of("base", "overlay"), machine.layers());
        assertEquals(MachineRenderPass.DEFAULT, machine.pass());
        assertEquals(MachineRenderPass.TRANSPARENT, view.components().get(COMPONENT).pass());
        var structure = view.structures().get(new MachineRenderBindings.StructureKey(MACHINE, STRUCTURE));
        assertEquals(MachineRenderPass.BLOOM, structure.model().pass());
        assertEquals(GeckoStructureBinding.SliceMode.STRUCTURE_ONLY, structure.sliceMode());
        var slices = view.structures().get(new MachineRenderBindings.StructureKey(MACHINE, id("slices")));
        assertEquals(MachineRenderPass.BLOOM_TRANSPARENT, slices.model().pass());
        assertEquals(GeckoStructureBinding.SliceMode.PER_SLICE, slices.sliceMode());
        assertThrows(IllegalStateException.class, () -> ZenRenderBindings.bindGeckoToMachineType("render_test:late", ZenRenderBindings.gecko().geo("render_test:g").texture("render_test:t")));

        var values = new NBTTagCompound();
        values.setString("state", "single");
        values.setString("layers", " server_base, , server_overlay ");
        var data = new ComponentData.Snapshot(1, values);
        var snapshot = new MachineRenderSnapshot(new ControllerDisplayData(MACHINE, BlockOrientation.EAST_UP, true, 1, 1),
            new MachineRenderSnapshot.CatalogToken(UUID.randomUUID(), 0), BlockPos.ORIGIN, STRUCTURE,
            List.of(new MachineRenderSnapshot.Occurrence(-1, -1, STRUCTURE, new BlockPos(10, 20, 30), 3,
                StructurePreviewProvider.Kind.SLICE, new BlockPos(0, 1, 0), false, List.of())), List.of(), List.of(), null);
        var anchors = StructureRenderAnchors.select(snapshot, view, data);
        assertEquals(1, anchors.size());
        var anchor = anchors.getFirst();
        assertEquals(new Vec3d(-3, 6, 1), anchor.modelOffset());
        assertEquals(new BlockPos(10, 20, 30), anchor.origin());
        assertTrue(anchor.binding().forceGlobalRenderer());
        assertEquals(List.of("server_base", "server_overlay"), anchor.layers());
        var request = GeckoRenderRequest.from(new GeckoRenderRequest.Owner(), new GeckoRenderRequest.Epoch(), new GeckoRenderRequest.Epoch(),
            anchor, BlockPos.ORIGIN, GeckoModelBaker.BakeMode.ANIMATED_ONLY, new GeckoRenderRequest.Sample(0, 1));
        try (var worker = Executors.newSingleThreadExecutor()) {
            assertEquals(List.of("server_base", "server_overlay"), worker.submit(request::layers).get(5, TimeUnit.SECONDS));
            assertEquals(List.of("server_base", "server_overlay"), worker.submit(() -> machine.effectiveLayers(null, data)).get(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("typed"), machine.effectiveLayers(new MachineAnimationState.Snapshot(1, List.of("typed")), data));
        var noLayers = values.copy();
        noLayers.setString("layers", " , ");
        assertEquals(List.of("single"), machine.effectiveLayers(null, new ComponentData.Snapshot(2, noLayers)));
        var noState = noLayers.copy();
        noState.setString("state", " ");
        assertEquals(List.of("base", "overlay"), machine.effectiveLayers(null, new ComponentData.Snapshot(3, noState)));
        assertEquals(List.of(), new GeckoModelBinding(id("geo/static.json"), id("textures/static.png")).effectiveLayers(null, data));
    }

    @SuppressWarnings("SameParameterValue") // One complete compiled program exercises this registration phase once.
    private ZenModule compile(String script) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenRenderBindings.class, ZenGeckoBinding.class, IData.class))
            registry.registerNativeClass(type);
        // SDK primitive caster rules cache their first environment, including ordinary data expansions.
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile("render_bindings.zs", "RenderBindingsScript",
            new ZenTokener(new StringReader(script), environment, "render_bindings.zs", false), global);
        ZenModule.compileScripts("render_bindings.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        return new ZenModule(classes, getClass().getClassLoader());
    }
}
