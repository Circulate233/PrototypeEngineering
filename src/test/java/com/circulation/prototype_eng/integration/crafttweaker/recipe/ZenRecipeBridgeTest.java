package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.recipe.CompositeRecipePool;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarations;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayPipeline;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.client.recipe.ClientRecipeDefinitions;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentDataMembers;
import com.circulation.prototype_eng.network.clientbound.RecipeDefinitionManifestPacket;
import com.circulation.prototype_eng.recipe.RecipeDefinitionManifestCodec;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenExpansion;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.annotations.ZenSetter;
import stanhebben.zenscript.compiler.IEnvironmentGlobal;
import stanhebben.zenscript.expression.partial.IPartialExpression;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;
import stanhebben.zenscript.symbols.SymbolType;
import stanhebben.zenscript.type.ZenTypeNative;
import stanhebben.zenscript.util.ZenPosition;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZenRecipeBridgeTest {
    private static final TestKeyType KEY_TYPE = new TestKeyType();
    private static final AEKey KEY = new TestKey();

    private static long effectiveEnergy(RecipeProcess process, ZenRequirement target) {
        return RecipeOverlays.getOrCreate(process).resolve(target.energy(), target.value().path(null)).payload().inputPerTick();
    }

    private static ZenRecipe peerRecipe(String revision, int duration) {
        var target = ZenRequirement.energy("bridge:energy", 0, 0, 0, 0, false);
        var candidate = ZenRequirement.energy("bridge:optional_energy", 0, 0, 0, 0, false);
        var selection = ZenRequirement.selective("bridge:selection", new ZenRequirement[]{candidate},
            new ZenSelectiveModifier[]{ZenSelectiveModifier.speedMultiplier("bridge:speed", "speed", 3)});
        return ZenRecipe.create("bridge:recipe", revision, duration, new ZenRequirement[]{target, selection});
    }

    private static Compiled compile(String script, String name, Class<?>... expansions) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        // Ordinary JUnit does not apply Mixins. Register one native type using the production helper.
        var dataType = new WritableDataType();
        registry.getTypes().getTypeMap().put(ZenComponentData.class, dataType);
        dataType.complete(registry.getTypes());
        registry.getRoot().put(dataType.getName(), new SymbolType(dataType), registry.getErrorLogger());
        for (var type : List.of(ZenResource.class, ZenResourceMatcher.class, ZenMatcher.class, ZenSelectiveCallback.class,
            ZenSelectiveModifier.class, ZenSelectiveContext.class, ZenOverlayCallback.class, ZenOverlayModifier.class,
            ZenOverlayContext.class, ZenRequirement.class, ZenRecipe.class, ZenRecipePool.class, ZenRecipeBindings.class,
            ZenRecipeUpdates.class, ZenRecipeUpdateTask.class, ZenRecipeUpdateResult.class,
            ZenRecipeUpdateCallback.class, IData.class, Harness.class)) {
            registry.registerNativeClass(type);
        }
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        for (var expansion : expansions) registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile(name, "NamedBridgeScript_" + name.replace('.', '_'),
            new ZenTokener(new StringReader(script), environment, name, false), global);
        ZenModule.compileScripts(name, List.of(parsed), global, false);
        // ZenScript 1 caches generated classes globally. Each isolated fixture gets its own module.
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        return new Compiled(new ZenModule(classes, ZenRecipeBridgeTest.class.getClassLoader()), output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void realZenScriptCapturesNamedCallbacksRegistersHistoryAndRestoresOriginalEffects() throws Exception {
        Harness.captured = null;
        var preinit = compile("""
            import mods.prototype_eng.recipe.Bindings;
            import tests.prototype_eng.Harness;
            Harness.captureEarly(Bindings.scope("bridge:shared"));
            """, "preinit_bindings.zs");
        assertEquals("", preinit.diagnostics());
        Objects.requireNonNull(preinit.module().getMain()).run();
        String script = """
            import mods.prototype_eng.recipe.Bindings;
            import mods.prototype_eng.recipe.Matcher;
            import mods.prototype_eng.recipe.SelectiveModifier;
            import mods.prototype_eng.recipe.OverlayModifier;
            import mods.prototype_eng.recipe.Requirement;
            import mods.prototype_eng.recipe.Recipe;
            import mods.prototype_eng.recipe.Pool;
            import tests.prototype_eng.Harness;

            var names = Bindings.scope("bridge:shared");
            names.defineMatcher(Matcher.create("bridge:matcher", function(candidate, pattern) {
                return candidate.sameKey(pattern);
            }));
            var matcherOld = names.matcher("bridge:matcher");
            names.defineMatcher(Matcher.create("bridge:matcher", function(candidate, pattern) {
                return false;
            }));
            var matcherNew = names.matcher("bridge:matcher");
            var dynamic = Requirement.dynamicInput("bridge:dynamic", matcherOld, Harness.resource(), 1, [], 32);

            var target = Requirement.energy("bridge:energy", 0, 0, 0, 0, false);
            var candidate = Requirement.energy("bridge:optional_energy", 0, 0, 0, 0, false);
            names.defineSelective(SelectiveModifier.create("bridge:speed", function(context) {
                context.addProcessSpeed("speed", 1.0);
            }));
            var oldSelection = Requirement.selective("bridge:selection", [candidate], [names.selective("bridge:speed")]);
            names.defineSelective(SelectiveModifier.create("bridge:speed", function(context) {
                context.mulProcessSpeed("speed", 3.0);
            }));
            var newSelection = Requirement.selective("bridge:selection", [candidate], [names.selective("bridge:speed")]);
            var oldRecipe = Recipe.create("bridge:recipe", "v1", 10, [target, oldSelection]);
            var newRecipe = Recipe.create("bridge:recipe", "v2", 10, [target, newSelection]);

            names.defineOverlay(OverlayModifier.create("bridge:rates", function(context) {
                context.putEnergyRates(target, "rates", 2, 0, false);
            }));
            var overlayOld = names.overlay("bridge:rates");
            names.defineOverlay(OverlayModifier.create("bridge:rates", function(context) {
                context.putEnergyRates(target, "rates", 5, 0, false);
            }));
            var overlayNew = names.overlay("bridge:rates");
            var pool = Pool.create("bridge:pool", "bridge.pool", [newRecipe]).existingJeiPage("external:category");
            Harness.capture(names, oldRecipe, newRecipe, target, dynamic, matcherOld, matcherNew, overlayOld, overlayNew);
            names.registerPools([pool], [oldRecipe]);
            names.declareManifest("bridge:complete", "behavior-set-v2");
            var clientNext = Recipe.create("bridge:recipe", "v3", 12, [target, newSelection]);
            names.declareClientVariant("bridge:complete", "behavior-set-v3", [clientNext]);
            """;
        var compiled = compile(script, "named_bindings.zs");
        assertEquals("", compiled.diagnostics());
        Objects.requireNonNull(compiled.module().getMain()).run();
        var captured = Objects.requireNonNull(Harness.captured);
        assertSame(Harness.earlyNames, captured.names());
        assertTrue(captured.oldMatcher().value().matches(KEY, KEY));
        assertFalse(captured.newMatcher().value().matches(KEY, KEY));
        assertTrue(captured.dynamic().resource().payload().dynamicInputs().getFirst().matcher().matches(KEY, KEY));

        var registry = new RecipeRegistry();
        DefinitionRegistrations.dispatch(new RecipeRegistrationEvent(registry, new NetworkRecipeExtensions()));
        registry.freeze();
        var declarations = new RecipeDeclarations();
        DefinitionRegistrations.dispatch(new RecipeDeclarationRegistrationEvent(declarations, registry.snapshot()));
        assertEquals(new RecipeDeclarationKey(new ResourceLocation("bridge", "complete"), "behavior-set-v2"),
            declarations.key(registry.snapshot()));
        assertSame(captured.names().resolver(), registry.processCodecs());
        assertSame(captured.newRecipe().recipe(), registry.catalog().recipes().getFirst());
        assertSame(captured.oldRecipe().codec(), captured.names().resolver().snapshot().require(captured.oldRecipe().recipe()));
        assertSame(captured.oldRecipe().codec(), captured.names().resolver().snapshot().require(new ResourceLocation("bridge", "recipe"), "v1"));

        var initial = registry.snapshot();
        var local = new ClientRecipeDeclarations();
        var providers = new RecipeJeiDisplays();
        providers.freeze();
        var event = new ClientRecipeDeclarationRegistrationEvent(local, initial, providers);
        var currentKey = new RecipeDeclarationKey(new ResourceLocation("bridge", "complete"), "behavior-set-v2");
        var nextKey = new RecipeDeclarationKey(new ResourceLocation("bridge", "complete"), "behavior-set-v3");
        var reentered = new boolean[1];
        var listenerFailure = new IllegalStateException("client declaration listener failed");
        local.onRegistered(() -> {
            if (local.get(nextKey) == null || reentered[0]) return;
            reentered[0] = true;
            ScriptClientRecipeDeclarationEvents.declarations(event);
            throw listenerFailure;
        });
        assertSame(listenerFailure, assertThrows(IllegalStateException.class, () -> ScriptClientRecipeDeclarationEvents.declarations(event)));
        assertTrue(reentered[0]);
        assertSame(initial, Objects.requireNonNull(local.get(currentKey)).snapshot());
        var nextLocal = Objects.requireNonNull(local.get(nextKey));
        assertNotSame(initial, nextLocal.snapshot());
        assertTrue(providers.sameProviders(nextLocal.displays()));
        assertSame(initial, registry.snapshot(), "Display variant preparation must not install a directory");
        assertThrows(IllegalArgumentException.class,
            () -> captured.names().resolver().snapshot().require(new ResourceLocation("bridge", "recipe"), "v3"));
        ScriptClientRecipeDeclarationEvents.declarations(event);
        assertSame(nextLocal, local.get(nextKey), "Repeated binding must retain the same private snapshot");

        var renderer = new AcknowledgingRenderer();
        var client = new ClientRecipeDefinitions(local, renderer);
        var connection = new Object();
        client.connected(connection);
        var peer = new RecipeRegistry();
        var peerOld = peerRecipe("v2", 10);
        var peerNext = peerRecipe("v3", 12);
        peer.register(new DefaultRecipePool(new ResourceLocation("bridge", "pool"), "bridge.pool", List.of(peerOld.recipe())));
        peer.declareRevision(peerOld.recipe(), peerOld.revision());
        peer.freeze();
        peer.install(peer.prepareReplacement(peer.snapshot(), List.of(peerNext.definition())));
        assertNotSame(peer.snapshot(), nextLocal.snapshot());
        assertNotSame(peerNext.recipe(), nextLocal.snapshot().catalog().recipes().getFirst());
        var manifest = RecipeDefinitionManifest.capture(UUID.randomUUID(), nextKey, peer.snapshot());
        assertTrue(client.receive(connection, RecipeDefinitionManifestPacket.part(manifest, RecipeDefinitionManifestCodec.encode(manifest), 0)));
        assertEquals(manifest, client.applied());
        assertSame(nextLocal, renderer.applied);
        var unknown = RecipeDefinitionManifest.capture(manifest.serverSession(),
            new RecipeDeclarationKey(new ResourceLocation("bridge", "unknown"), "not-local"), peer.snapshot());
        client.disconnected(connection);
        client.connected(connection);
        assertTrue(client.receive(connection, RecipeDefinitionManifestPacket.part(unknown, RecipeDefinitionManifestCodec.encode(unknown), 0)));
        assertNull(client.applied());
        assertTrue(client.reason().startsWith("Missing local recipe declaration:"));

        var owner = new MachineInstance();
        var oldProcess = new RecipeProcess(owner, captured.oldRecipe().recipe(), 41);
        var newProcess = new RecipeProcess(owner, captured.newRecipe().recipe(), 42);
        assertSame(ProcessResult.SUCCESS, oldProcess.tick());
        assertSame(ProcessResult.SUCCESS, newProcess.tick());
        assertEquals(2, oldProcess.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        assertEquals(3, newProcess.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        RecipeOverlayPipeline.apply(oldProcess, List.of(captured.oldOverlay().value()));
        RecipeOverlayPipeline.apply(newProcess, List.of(captured.newOverlay().value()));
        assertEquals(2, effectiveEnergy(oldProcess, captured.target()));
        assertEquals(5, effectiveEnergy(newProcess, captured.target()));

        var saved = captured.oldRecipe().codec().encode(oldProcess);
        var restored = captured.names().resolver().snapshot().require(new ResourceLocation("bridge", "recipe"), "v1").decode(owner, saved);
        assertSame(captured.oldRecipe().recipe(), restored.recipe());
        assertEquals(2, restored.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        assertEquals(2, effectiveEnergy(restored, captured.target()));
        assertEquals("external:category", Objects.requireNonNull(registry.get(new ResourceLocation("bridge", "pool"))).existingJeiPageId());
    }

    @Test
    void realZenCompilerRejectsAnIncompatibleMatcherFunction() throws Exception {
        var compiled = compile("""
            import mods.prototype_eng.recipe.Matcher;
            Matcher.create("bridge:bad", function(candidate as int, pattern as int) as bool {
                return candidate == pattern;
            });
            """, "bad_matcher.zs");
        assertFalse(compiled.diagnostics().isEmpty());
    }

    @Test
    void realZenCompilerCombinesMixedProvidersWithoutReplacingSharedRecipeAndCodecHandles() throws Exception {
        Harness.combined = null;
        var compiled = compile("""
            import mods.prototype_eng.recipe.Requirement;
            import mods.prototype_eng.recipe.Recipe;
            import mods.prototype_eng.recipe.Pool;
            import tests.prototype_eng.Harness;
            var first = Recipe.create("combined_bridge:first", "v1", 2,
                [Requirement.resources("combined_bridge:io", [Harness.resource()], [Harness.resource(), Harness.resource()])]);
            var second = Recipe.create("combined_bridge:second", "v2", 3,
                [Requirement.energy("combined_bridge:energy", 0, 0, 0, 0, false)]);
            var simple = Pool.simple("combined_bridge:simple", "combined.simple", [first]);
            var general = Pool.create("combined_bridge:general", "combined.general", [first, second]);
            var combined = Pool.combine("combined_bridge:combined", "combined.title", [simple, general, simple]);
            Harness.captureCombined(combined, first, second);
            """, "combined_pools.zs");
        assertEquals("", compiled.diagnostics());
        Objects.requireNonNull(compiled.module().getMain()).run();
        var combined = Objects.requireNonNull(Harness.combined);
        var first = Objects.requireNonNull(Harness.combinedFirst);
        var second = Objects.requireNonNull(Harness.combinedSecond);
        var providers = Objects.requireNonNull(assertInstanceOf(CompositeRecipePool.class, combined.value())).pools();
        assertEquals(2, providers.size());
        assertInstanceOf(SimpleRecipePool.class, providers.getFirst());
        assertEquals(2, SimpleRecipePool.Entry.of(providers.getFirst().recipes().getFirst()).outputs().size());
        assertInstanceOf(DefaultRecipePool.class, providers.getLast());
        assertEquals(List.of(first, second), combined.recipes());
        var registry = new RecipeRegistry();
        registry.register(combined.value());
        var codecs = new RecipeProcessCodecs(combined.recipes().stream().map(ZenRecipe::codec).toList());
        registry.bindProcessCodecs(codecs);
        for (var recipe : combined.recipes()) registry.declareRevision(recipe.recipe(), recipe.revision());
        registry.freeze();
        assertEquals(List.of(first.recipe(), second.recipe()), registry.catalog().recipes());
        assertSame(first.codec(), codecs.snapshot().require(first.recipe()));
        assertSame(second.codec(), codecs.snapshot().require(second.recipe()));
        assertEquals("v2", registry.snapshot().revision(second.recipe()));
    }

    @Test
    void combiningSharedRecipesRejectsConflictingCodecOrRevisionBeforeRegistration() {
        var first = ZenRecipe.create("combined_conflict:recipe", "v1", 2, new ZenRequirement[0]);
        var original = ZenRecipePool.create("combined_conflict:first", "pool.first", new ZenRecipe[]{first});
        var anotherVersion = ZenRecipePool.create("combined_conflict:second", "pool.second", new ZenRecipe[]{new ZenRecipe(first.recipe(), "v2")});
        assertThrows(IllegalArgumentException.class, () -> ZenRecipePool.combine("combined_conflict:combined", "pool.combined",
            new ZenRecipePool[]{original, anotherVersion}));
        var anotherCodec = ZenRecipePool.create("combined_conflict:second", "pool.second", new ZenRecipe[]{new ZenRecipe(first.recipe(), "v1")});
        assertThrows(IllegalArgumentException.class, () -> ZenRecipePool.combine("combined_conflict:combined", "pool.combined",
            new ZenRecipePool[]{original, anotherCodec}));
        var sameCodec = ZenRecipePool.create("combined_conflict:second", "pool.second", new ZenRecipe[]{new ZenRecipe(first.codec())});
        var combined = ZenRecipePool.combine("combined_conflict:combined", "pool.combined", new ZenRecipePool[]{original, sameCodec});
        assertEquals(1, combined.recipes().size());
        assertSame(first, combined.recipes().getFirst());
        var anotherDefinition = ZenRecipePool.create("combined_conflict:second", "pool.second", new ZenRecipe[]{
            ZenRecipe.create("combined_conflict:recipe", "v1", 3, new ZenRequirement[0])});
        assertThrows(IllegalArgumentException.class, () -> ZenRecipePool.combine("combined_conflict:combined", "pool.combined",
            new ZenRecipePool[]{original, anotherDefinition}));
    }

    @Test
    void realZenScriptDataOperatorsAndIDataConversionsPreserveIndependentSnapshots() throws Exception {
        Harness.data = null;
        Harness.dataSnapshot = null;
        var compiled = compile("""
            import mods.prototype_eng.data.ComponentData;
            import tests.prototype_eng.Harness;
            var data = ComponentData.from({initial: 1});
            data["count"] = 2;
            data.flag = true;
            var snapshot = data.asIData();
            data["count"] = 7;
            data.flag = false;
            Harness.captureData(data, snapshot, data["count"] as int, data.flag.asBool(), data.getInt("absent", 11));
            """, "component_data.zs");
        assertEquals("", compiled.diagnostics());
        Objects.requireNonNull(compiled.module().getMain()).run();
        assertEquals(7, Objects.requireNonNull(Harness.data).getInt("count", 0));
        assertFalse(Harness.data.getBool("flag", true));
        assertEquals(7, Harness.scriptCount);
        assertFalse(Harness.scriptFlag);
        assertEquals(11, Harness.scriptDefault);
        var snapshot = Objects.requireNonNull(Harness.dataSnapshot).asMap();
        assertEquals(2, snapshot.get("count").asInt());
        assertTrue(snapshot.get("flag").asBool());
        assertEquals(1, snapshot.get("initial").asInt());

        var invalid = compile("""
            import mods.prototype_eng.data.ComponentData;
            ComponentData.from(5);
            """, "invalid_data.zs");
        assertEquals("", invalid.diagnostics());
        assertThrows(IllegalArgumentException.class, Objects.requireNonNull(invalid.module().getMain())::run);
    }

    @Test
    void realZenScriptDataExpansionsKeepNativeAndDynamicMemberPrecedence() throws Exception {
        Harness.data = null;
        var compiled = compile("""
            import mods.prototype_eng.data.ComponentData;
            import tests.prototype_eng.Harness;
            var data = ComponentData.from({expanded: 3});
            data.expanded = 9;
            data.dynamic = 5;
            Harness.captureData(data, data.asIData(), data.expanded, data.dynamic.asInt() == 5, data.length);
            """, "component_data_expansion.zs", DataExpansion.class);
        assertEquals("", compiled.diagnostics());
        Objects.requireNonNull(compiled.module().getMain()).run();
        var data = Objects.requireNonNull(Harness.data);
        assertEquals(3, data.getInt("expanded", 0));
        assertEquals(9, data.getInt("extensionValue", 0));
        assertEquals(5, data.getInt("dynamic", 0));
        assertEquals(9, Harness.scriptCount);
        assertTrue(Harness.scriptFlag);
        assertEquals(3, Harness.scriptDefault);
    }

    @Test
    void realZenScriptUpdatesPublishWithExactSnapshotAndReportManifestFailureWithoutRollback() throws Exception {
        var original = ZenRecipe.create("runtime_bridge:recipe", "v1", 2, new ZenRequirement[0]);
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(new ResourceLocation("runtime_bridge", "pool"), "runtime_bridge.pool", List.of(original.recipe())));
        registry.declareRevision(original.recipe(), original.revision());
        var codecs = new RecipeProcessCodecs(List.of(original.codec()));
        registry.bindProcessCodecs(codecs);
        registry.freeze();
        var research = new ResearchRegistry();
        research.freeze();
        var extensions = new NetworkRecipeExtensions();
        extensions.publish(registry.snapshot(), research);
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1);
             var updates = new RecipeDefinitionUpdates(registry, extensions, scheduler, UUID.randomUUID(), _ -> {
             })) {
            Harness.publisher = new ZenRecipeUpdates(updates);
            Harness.results.clear();
            var compiled = compile("""
                import mods.prototype_eng.recipe.Recipe;
                import tests.prototype_eng.Harness;
                var update = Harness.publisher();
                var next = Recipe.create("runtime_bridge:recipe", "v2", 4, []);
                Harness.captureTask(update.replace([next], "runtime_bridge:complete", "v2").onComplete(function(result) {
                    Harness.captureResult(result);
                }));
                var stale = Recipe.create("runtime_bridge:recipe", "stale", 6, []);
                update.replace([stale], "runtime_bridge:complete", "stale").onComplete(function(result) {
                    Harness.captureResult(result);
                });
                """, "live_updates.zs");
            assertEquals("", compiled.diagnostics());
            Objects.requireNonNull(compiled.module().getMain()).run();
            assertTrue(Objects.requireNonNull(Harness.task).done());
            assertFalse(Harness.task.failed());
            assertEquals(List.of("PUBLISHED", "REJECTED_STALE"), Harness.results.stream().map(ZenRecipeUpdateResult::status).toList());
            assertTrue(Harness.results.getFirst().published());
            assertEquals(updates.sessionId().toString(), Harness.results.getFirst().session());
            assertSame(original.codec(), codecs.snapshot().require(original.recipe()));
            var first = registry.snapshot();
            var key = new RecipeDeclarationKey(new ResourceLocation("runtime_bridge", "complete"), "v2");
            assertSame(first, RecipeDeclarations.INSTANCE.get(key));

            Harness.publisher = new ZenRecipeUpdates(updates);
            var duplicateKey = compile("""
                import mods.prototype_eng.recipe.Recipe;
                import tests.prototype_eng.Harness;
                var next = Recipe.create("runtime_bridge:recipe", "v3", 8, []);
                Harness.publisher().replace([next], "runtime_bridge:complete", "v2").onComplete(function(result) {
                    Harness.captureResult(result);
                });
                """, "duplicate_manifest.zs");
            assertEquals("", duplicateKey.diagnostics());
            Objects.requireNonNull(duplicateKey.module().getMain()).run();
            var failure = Harness.results.getLast();
            assertEquals("NOTIFICATION_FAILED", failure.status());
            assertTrue(failure.published());
            assertTrue(failure.failure().contains("already binds another snapshot"));
            assertEquals("v3", registry.snapshot().revision(registry.catalog().recipes().getFirst()));
            assertSame(first, RecipeDeclarations.INSTANCE.get(key));
            assertFalse(scheduler.status().faulted());
            updates.stopAccepting();
            var refused = Harness.publisher.replace(new ZenRecipe[]{
                ZenRecipe.create("runtime_bridge:recipe", "v4", 9, new ZenRequirement[0])
            }, "runtime_bridge:complete", "v4").result();
            assertEquals("REJECTED_SESSION", Objects.requireNonNull(refused).status());
            assertFalse(refused.published());
        }
    }

    private record Compiled(ZenModule module, String diagnostics) {
    }

    @ZenExpansion("mods.prototype_eng.data.ComponentData")
    @SuppressWarnings("unused") // Methods are resolved by the real ZenScript compiler.
    public static final class DataExpansion {
        @ZenGetter("expanded")
        public static int expanded(ZenComponentData data) {
            return data.getInt("extensionValue", 0);
        }

        @ZenSetter("expanded")
        public static void expanded(ZenComponentData data, int value) {
            data.setInt("extensionValue", value);
        }

        @ZenGetter("length")
        public static int length(ZenComponentData data) {
            throw new AssertionError("Native data members must take precedence over expansions");
        }
    }

    private static final class WritableDataType extends ZenTypeNative {
        private WritableDataType() {
            super(ZenComponentData.class);
        }

        @Override
        public IPartialExpression getMember(ZenPosition position, IEnvironmentGlobal environment, IPartialExpression value, String name) {
            var member = ZenComponentDataMembers.member(this, position, environment, value, name);
            return member == null ? super.getMember(position, environment, value, name) : member;
        }
    }

    private record Captured(ZenRecipeBindings names, ZenRecipe oldRecipe, ZenRecipe newRecipe, ZenRequirement target,
                            ZenRequirement dynamic, ZenMatcher oldMatcher, ZenMatcher newMatcher,
                            ZenOverlayModifier oldOverlay, ZenOverlayModifier newOverlay) {
    }

    /**
     * Protocol fixture acknowledges actual provider descriptions; it is not native HEI runtime QA.
     */
    private static final class AcknowledgingRenderer implements ClientRecipeDefinitions.Renderer {
        private ClientRecipeDeclarations.Declaration applied;

        @Override
        public ClientRecipeDefinitions.ApplyResult apply(ClientRecipeDeclarations.Declaration declaration) {
            for (var recipe : declaration.snapshot().catalog().recipes())
                assertNotNull(declaration.displays().describe(recipe));
            applied = declaration;
            return new ClientRecipeDefinitions.ApplyResult(true, "Acknowledged display declaration");
        }

        @Override
        public void withdraw(String reason) {
            applied = null;
        }
    }

    @SuppressWarnings("unused")
    @ZenClass("tests.prototype_eng.Harness")
    public static final class Harness {
        private static final ObjectArrayList<ZenRecipeUpdateResult> results = new ObjectArrayList<>();
        private static Captured captured;
        private static ZenRecipeBindings earlyNames;
        private static ZenComponentData data;
        private static IData dataSnapshot;
        private static int scriptCount;
        private static boolean scriptFlag;
        private static int scriptDefault;
        private static ZenRecipeUpdates publisher;
        private static ZenRecipeUpdateTask task;
        private static ZenRecipePool combined;
        private static ZenRecipe combinedFirst;
        private static ZenRecipe combinedSecond;

        @ZenMethod
        public static void captureEarly(ZenRecipeBindings names) {
            earlyNames = names;
        }

        @ZenMethod
        public static void captureData(ZenComponentData value, IData snapshot, int count, boolean flag, int defaultValue) {
            data = value;
            dataSnapshot = snapshot;
            scriptCount = count;
            scriptFlag = flag;
            scriptDefault = defaultValue;
        }

        @ZenMethod
        public static ZenRecipeUpdates publisher() {
            return publisher;
        }

        @ZenMethod
        public static void captureTask(ZenRecipeUpdateTask value) {
            task = value;
        }

        @ZenMethod
        public static void captureResult(ZenRecipeUpdateResult result) {
            results.add(result);
        }

        @ZenMethod
        public static ZenResource resource() {
            return new ZenResource(KEY, 1);
        }

        @ZenMethod
        public static void captureCombined(ZenRecipePool pool, ZenRecipe first, ZenRecipe second) {
            combined = pool;
            combinedFirst = first;
            combinedSecond = second;
        }

        @ZenMethod
        public static void capture(ZenRecipeBindings names, ZenRecipe oldRecipe, ZenRecipe newRecipe, ZenRequirement target,
                                   ZenRequirement dynamic, ZenMatcher oldMatcher, ZenMatcher newMatcher,
                                   ZenOverlayModifier oldOverlay, ZenOverlayModifier newOverlay) {
            captured = new Captured(names, oldRecipe, newRecipe, target, dynamic, oldMatcher, newMatcher, oldOverlay, newOverlay);
        }
    }

    private static final class TestKeyType extends AEKeyType {
        private TestKeyType() {
            super(new ResourceLocation("bridge", "resource"), TestKey.class, new TextComponentString("resource"));
        }

        @Override
        public AEKey readFromPacket(PacketBuffer input) {
            throw new UnsupportedOperationException("No game network in this script fixture");
        }

        @Override
        public AEKey loadKeyFromTag(NBTTagCompound tag) {
            return KEY;
        }
    }

    private static final class TestKey extends AEKey {
        @Override
        public AEKeyType getType() {
            return KEY_TYPE;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public Object getPrimaryKey() {
            return KEY_TYPE;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("bridge", "key");
        }

        @Override
        public Object getReadOnlyStack() {
            return this;
        }

        @Override
        public NBTTagCompound toTag() {
            return new NBTTagCompound();
        }

        @Override
        public NBTBase get(String componentId) {
            return null;
        }

        @Override
        protected ITextComponent computeDisplayName() {
            return new TextComponentString("key");
        }

        @Override
        public void writeToPacket(PacketBuffer output) {
            throw new UnsupportedOperationException("No game network in this script fixture");
        }
    }
}
