package com.circulation.prototype_eng.api.recipe.importing;

import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;
import stanhebben.zenscript.symbols.SymbolType;
import stanhebben.zenscript.type.ZenTypeNative;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalImportLoadingTest {
    private static final String RECIPE_PACKAGE = "com.circulation.prototype_eng.integration.crafttweaker.recipe.";

    @Test
    void allFactoriesAndNativeScriptTypesLoadWithoutAnyOptionalSourceModClasses() throws Exception {
        var production = ImportedRecipePool.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new WithoutSourceMods(new URL[]{production}, getClass().getClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("ic2.api.recipe.MachineRecipe"));
            loader.rejected.set(0);
            var declarations = List.of(new PoolType("minecraft", "FurnaceRecipePool"),
                new PoolType("ic2", "CompressorRecipePool"), new PoolType("ic2", "MaceratorRecipePool"),
                new PoolType("nuclearcraft", "AlloyFurnaceRecipePool"), new PoolType("nuclearcraft", "InfuserRecipePool"),
                new PoolType("nuclearcraft", "ChemicalReactorRecipePool"), new PoolType("nuclearcraft", "MelterRecipePool"),
                new PoolType("tconstruct", "MeltingRecipePool"), new PoolType("tconstruct", "AlloyRecipePool"),
                new PoolType("thaumcraft", "InfusionRecipePool"), new PoolType("thermalexpansion", "InsolatorRecipePool"),
                new PoolType("thermalexpansion", "InsolatorTreeRecipePool"));
            var javaTypes = new HashSet<Class<?>>();
            var zenTypes = new HashSet<Class<?>>();
            for (var declaration : declarations) {
                var suffix = declaration.modid + "." + declaration.name;
                var id = new ResourceLocation("optional_import_loading", declaration.name.toLowerCase(Locale.ROOT));
                var javaType = loader.loadClass("com.circulation.prototype_eng.api.recipe.importing." + suffix);
                assertSame(loader, javaType.getClassLoader());
                assertTrue(javaTypes.add(javaType));
                var constructor = javaType.getConstructor(ResourceLocation.class, String.class);
                var pool = constructor.newInstance(id, "optional.pool.title");
                assertSame(javaType, pool.getClass());
                assertNotSame(pool, constructor.newInstance(id, "optional.pool.title"));
                var noContext = declaration.modid.equals("tconstruct") && declaration.name.equals("AlloyRecipePool");
                var callbackType = noContext ? Predicate.class : BiPredicate.class;
                Object callback = noContext ? (Predicate<Object>) _ -> true : (BiPredicate<Object, Object>) (_, _) -> true;
                var filtered = javaType.getConstructor(ResourceLocation.class, String.class, callbackType)
                    .newInstance(id, "optional.pool.title", callback);
                assertSame(javaType, filtered.getClass());
                assertNotSame(pool, filtered);
                assertEquals(id, pool.getClass().getMethod("id").invoke(pool));
                assertEquals("optional.pool.title", pool.getClass().getMethod("translationKey").invoke(pool));
                assertTrue(((List<?>) pool.getClass().getMethod("machines").invoke(pool)).isEmpty());
                var zenType = loader.loadClass(RECIPE_PACKAGE + "importing." + declaration.modid + ".Zen" + declaration.name);
                assertTrue(zenTypes.add(zenType));
                var factory = zenType.getMethod("create", String.class, String.class, String.class);
                var zenPool = factory.invoke(null, id.toString(), "optional.pool.title", "v1");
                assertSame(zenType, zenPool.getClass());
                assertNotSame(zenPool, factory.invoke(null, id.toString(), "optional.pool.title", "v1"));
                assertEquals(id.toString(), zenPool.getClass().getMethod("id").invoke(zenPool));
                assertEquals(true, zenPool.getClass().getMethod("deferred").invoke(zenPool));
                assertSame(javaType, zenPool.getClass().getMethod("value").invoke(zenPool).getClass());
            }

            var diagnostics = new ByteArrayOutputStream();
            var registry = new GenericRegistry(new GenericCompileEnvironment(),
                new GenericErrorLogger(new PrintStream(diagnostics, true, StandardCharsets.UTF_8)));
            var dataClass = loader.loadClass("com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData");
            var dataType = (ZenTypeNative) loader.loadClass("com.circulation.prototype_eng.integration.crafttweaker.ZenComponentDataType")
                                                 .getConstructor().newInstance();
            registry.getTypes().getTypeMap().put(dataClass, dataType);
            dataType.complete(registry.getTypes());
            registry.getRoot().put(dataType.getName(), new SymbolType(dataType), registry.getErrorLogger());
            for (var simpleName : List.of("ZenRecipePool", "ZenRecipeBuilder", "ZenRecipeCreateCallback",
                "ZenRecipeEventContext", "ZenRecipeEventCallback", "ZenRecipeEventAction", "ZenRecipeEventTransaction",
                "ZenRecipe", "ZenRequirement", "ZenResource", "ZenParallelismSelection", "ZenParallelismCallback")) {
                registry.registerNativeClass(loader.loadClass(RECIPE_PACKAGE + simpleName));
            }
            for (var suffix : List.of("minecraft.ZenFurnaceImportContext", "minecraft.ZenFurnaceImportCallback",
                "ic2.ZenIc2ImportContext", "ic2.ZenIc2ImportCallback", "nuclearcraft.ZenNuclearImportContext",
                "nuclearcraft.ZenNuclearImportCallback", "tconstruct.ZenMeltingImportContext", "tconstruct.ZenMeltingImportCallback",
                "thaumcraft.ZenInfusionImportContext", "thaumcraft.ZenInfusionImportCallback",
                "thermalexpansion.ZenInsolatorImportContext", "thermalexpansion.ZenInsolatorImportCallback")) {
                registry.registerNativeClass(loader.loadClass(RECIPE_PACKAGE + "importing." + suffix));
            }
            for (var declaration : declarations) {
                registry.registerNativeClass(loader.loadClass(RECIPE_PACKAGE + "importing." + declaration.modid + ".Zen" + declaration.name));
            }
            assertEquals("", diagnostics.toString(StandardCharsets.UTF_8));
            assertEquals(0, loader.rejected.get());
        }
    }

    private record PoolType(String modid, String name) {
    }

    private static final class WithoutSourceMods extends URLClassLoader {
        private final AtomicInteger rejected = new AtomicInteger();

        private WithoutSourceMods(URL[] paths, ClassLoader parent) {
            super(paths, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                for (var prefix : List.of("ic2.", "nc.", "slimeknights.", "thaumcraft.", "cofh.", "baubles.")) {
                    if (name.startsWith(prefix)) {
                        rejected.incrementAndGet();
                        throw new ClassNotFoundException("Optional source mod excluded by test: " + name);
                    }
                }
                if (!name.startsWith("com.circulation.prototype_eng.")) return super.loadClass(name, resolve);
                var loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
