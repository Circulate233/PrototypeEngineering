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
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

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
            var declarations = ImportedPoolTestCatalog.types();
            var javaTypes = new HashSet<Class<?>>();
            var zenTypes = new HashSet<Class<?>>();
            var scriptCallbacks = new HashSet<Class<?>>();
            for (var declaration : declarations) {
                var suffix = declaration.modid() + "." + declaration.name();
                var id = new ResourceLocation("optional_import_loading", suffix.toLowerCase(Locale.ROOT));
                var javaType = loader.loadClass(declaration.javaClassName());
                assertSame(loader, javaType.getClassLoader());
                assertTrue(javaTypes.add(javaType));
                var constructor = javaType.getConstructor(ResourceLocation.class, String.class);
                var pool = constructor.newInstance(id, "optional.pool.title");
                assertSame(javaType, pool.getClass());
                assertNotSame(pool, constructor.newInstance(id, "optional.pool.title"));
                var callbackType = loader.loadClass(javaType.getName() + "$OnCreate");
                var filtered = javaType.getConstructor(ResourceLocation.class, String.class, callbackType)
                    .newInstance(id, "optional.pool.title", null);
                assertSame(javaType, filtered.getClass());
                assertNotSame(pool, filtered);
                assertEquals(id, pool.getClass().getMethod("id").invoke(pool));
                assertEquals("optional.pool.title", pool.getClass().getMethod("translationKey").invoke(pool));
                assertTrue(((List<?>) pool.getClass().getMethod("machines").invoke(pool)).isEmpty());
                var zenType = loader.loadClass(declaration.zenClassName());
                assertTrue(zenTypes.add(zenType));
                var factory = zenType.getMethod("create", String.class, String.class, String.class);
                var zenPool = factory.invoke(null, id.toString(), "optional.pool.title", "v1");
                assertSame(zenType, zenPool.getClass());
                assertNotSame(zenPool, factory.invoke(null, id.toString(), "optional.pool.title", "v1"));
                assertEquals(id.toString(), zenPool.getClass().getMethod("id").invoke(zenPool));
                assertEquals(true, zenPool.getClass().getMethod("deferred").invoke(zenPool));
                assertSame(javaType, zenPool.getClass().getMethod("value").invoke(zenPool).getClass());
                var callbackFactory = Arrays.stream(zenType.getDeclaredMethods())
                    .filter(method -> method.getName().equals("create") && method.getParameterCount() == 4)
                    .findFirst().orElseThrow();
                var scriptCallbackType = callbackFactory.getParameterTypes()[3];
                scriptCallbacks.add(scriptCallbackType);
                var scriptCallback = Proxy.newProxyInstance(loader, new Class<?>[]{scriptCallbackType}, (_, method, _) -> {
                    throw new AssertionError("Creation callback invoked before first recipe access for " + suffix + ": " + method);
                });
                var configured = callbackFactory.invoke(null, id.toString(), "optional.pool.title", "v1", scriptCallback);
                assertSame(zenType, configured.getClass());
                assertNotSame(zenPool, configured);
                assertEquals(true, configured.getClass().getMethod("deferred").invoke(configured));
                var configuredPool = configured.getClass().getMethod("value").invoke(configured);
                assertSame(javaType, configuredPool.getClass());
                assertTrue(((List<?>) configuredPool.getClass().getMethod("machines").invoke(configuredPool)).isEmpty());
                var poolType = (Enum<?>) configuredPool.getClass().getMethod("poolType").invoke(configuredPool);
                assertSame(configuredPool,
                    configuredPool.getClass().getMethod("poolType", poolType.getDeclaringClass()).invoke(configuredPool, poolType));
                assertEquals(0, loader.rejected.get(), suffix);
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
            for (var simpleName : List.of("ZenRecipePool", "ZenRecipeBuilder",
                "ZenRecipeEventContext", "ZenRecipeEventCallback", "ZenRecipeEventAction", "ZenRecipeEventTransaction",
                "ZenRecipe", "ZenRequirement", "ZenResource", "ZenParallelismSelection", "ZenParallelismCallback")) {
                registry.registerNativeClass(loader.loadClass(RECIPE_PACKAGE + simpleName));
            }
            for (var callback : scriptCallbacks) registry.registerNativeClass(callback);
            for (var declaration : declarations) {
                registry.registerNativeClass(loader.loadClass(declaration.zenClassName()));
            }
            assertEquals("", diagnostics.toString(StandardCharsets.UTF_8));
            assertEquals(0, loader.rejected.get());
        }
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
