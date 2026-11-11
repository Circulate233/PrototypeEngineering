package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarations;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayPipeline;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
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
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ZenRecipeBridgeTest {
    private static final TestKeyType KEY_TYPE = new TestKeyType();
    private static final AEKey KEY = new TestKey();

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

    private static long effectiveEnergy(RecipeProcess process, ZenRequirement target) {
        return RecipeOverlays.getOrCreate(process).resolve(target.energy(), target.value().path(null)).payload().inputPerTick();
    }

    private static Compiled compile(String script, String name) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenResource.class, ZenResourceMatcher.class, ZenMatcher.class, ZenSelectiveCallback.class,
            ZenSelectiveModifier.class, ZenSelectiveContext.class, ZenOverlayCallback.class, ZenOverlayModifier.class,
            ZenOverlayContext.class, ZenRequirement.class, ZenRecipe.class, ZenRecipePool.class, ZenRecipeBindings.class,
            ZenComponentData.class, Harness.class)) {
            registry.registerNativeClass(type);
        }
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

    private record Compiled(ZenModule module, String diagnostics) {
    }

    private record Captured(ZenRecipeBindings names, ZenRecipe oldRecipe, ZenRecipe newRecipe, ZenRequirement target,
                            ZenRequirement dynamic, ZenMatcher oldMatcher, ZenMatcher newMatcher,
                            ZenOverlayModifier oldOverlay, ZenOverlayModifier newOverlay) {
    }

    @SuppressWarnings("unused")
    @ZenClass("tests.prototype_eng.Harness")
    public static final class Harness {
        private static Captured captured;
        private static ZenRecipeBindings earlyNames;

        @ZenMethod
        public static void captureEarly(ZenRecipeBindings names) {
            earlyNames = names;
        }

        @ZenMethod
        public static ZenResource resource() {
            return new ZenResource(KEY, 1);
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
