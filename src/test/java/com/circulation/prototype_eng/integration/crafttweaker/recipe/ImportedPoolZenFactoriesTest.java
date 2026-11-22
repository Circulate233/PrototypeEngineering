package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.importing.ImportedPoolTestCatalog;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentDataType;
import crafttweaker.api.data.IData;
import crafttweaker.api.item.IIngredient;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.liquid.ILiquidStack;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.symbols.SymbolType;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedPoolZenFactoriesTest {
    @Test
    void allSourceFactoriesCompileNativeCallbacksAndRemainLazyThroughSharedConfiguration() throws Exception {
        Harness.pools.clear();
        var diagnostics = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment,
            new GenericErrorLogger(new PrintStream(diagnostics, true, StandardCharsets.UTF_8)));
        var dataType = new ZenComponentDataType();
        registry.getTypes().getTypeMap().put(ZenComponentData.class, dataType);
        dataType.complete(registry.getTypes());
        registry.getRoot().put(dataType.getName(), new SymbolType(dataType), registry.getErrorLogger());
        for (var type : List.of(ZenRecipePool.class, ZenRecipeBuilder.class, ZenRecipe.class, ZenResource.class,
            ZenRequirement.class, ZenRecipeCreateCallback.class, ZenRecipeEventCallback.class, ZenRecipeEventContext.class,
            ZenRecipeEventAction.class, ZenRecipeEventTransaction.class, ZenParallelismSelection.class,
            ZenParallelismCallback.class, IData.class, IIngredient.class, IItemStack.class, ILiquidStack.class, Harness.class))
            registry.registerNativeClass(type);
        Set<Class<?>> callbacks = new ObjectLinkedOpenHashSet<>();
        callbacks.add(ZenRecipeCreateCallback.class);
        var script = new StringBuilder("import tests.prototype_eng.ImportFactoryHarness;\n");
        var body = new StringBuilder();
        var declarations = ImportedPoolTestCatalog.types();
        for (int i = 0; i < declarations.size(); i++) {
            var declaration = declarations.get(i);
            var type = Class.forName(declaration.zenClassName());
            var factory = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals("create") && method.getParameterCount() == 4)
                .findFirst().orElseThrow();
            var callback = factory.getParameterTypes()[3];
            if (callbacks.add(callback)) registry.registerNativeClass(callback);
            registry.registerNativeClass(type);
            var handler = Arrays.stream(callback.getMethods()).filter(method -> Modifier.isAbstract(method.getModifiers()))
                .findFirst().orElseThrow();
            script.append("import ").append(type.getAnnotation(ZenClass.class).value()).append(" as Imported").append(i).append(";\n");
            body.append("var pool").append(i).append(" = Imported").append(i).append(".create(\"expanded_imports:")
                .append(declaration.modid()).append('_').append(i).append("\", \"pool.title\", \"v1\", function(");
            for (int parameter = 0; parameter < handler.getParameterCount(); parameter++) {
                if (parameter > 0) body.append(", ");
                body.append('p').append(parameter);
            }
            body.append(") { ImportFactoryHarness.callbackReached(); return true; });\n")
                .append("ImportFactoryHarness.capture(pool").append(i).append(");\n")
                .append("pool").append(i).append(".auxiliaryInputs([]).durationTicks(1).existingJeiPage(\"minecraft:smelting\")")
                .append(".poolType(\"general\");\n");
        }
        script.append(body);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var name = "expanded_import_factories.zs";
        var parsed = new ZenParsedFile(name, "ExpandedImportFactories",
            new ZenTokener(new StringReader(script.toString()), environment, name, false), global);
        ZenModule.compileScripts(name, List.of(parsed), global, false);
        assertEquals("", diagnostics.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        var module = new ZenModule(classes, getClass().getClassLoader());
        Objects.requireNonNull(module.getMain()).run();
        assertEquals(declarations.size(), Harness.pools.size());
        for (int i = 0; i < declarations.size(); i++) {
            var pool = Harness.pools.get(i);
            assertSame(Class.forName(declarations.get(i).zenClassName()), pool.getClass());
            assertTrue(pool.deferred());
            assertTrue(pool.value().machines().isEmpty());
            assertEquals("expanded_imports:" + declarations.get(i).modid() + "_" + i, pool.id());
        }
    }

    @SuppressWarnings("unused")
    @ZenClass("tests.prototype_eng.ImportFactoryHarness")
    public static final class Harness {
        private static final ObjectArrayList<ZenRecipePool> pools = new ObjectArrayList<>();

        @ZenMethod
        public static void capture(ZenRecipePool pool) {
            pools.add(pool);
        }

        @ZenMethod
        public static void callbackReached() {
            throw new AssertionError("Source callback executed while declaring a pool");
        }
    }
}
