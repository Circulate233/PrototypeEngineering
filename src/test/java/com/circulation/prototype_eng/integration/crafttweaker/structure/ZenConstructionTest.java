package com.circulation.prototype_eng.integration.crafttweaker.structure;

import crafttweaker.api.block.IBlockState;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ZenConstructionTest {
    @Test
    void externalAnchorRegistrationCompilesWithTheRealZenScriptSdk() throws Exception {
        String script = """
            import mods.prototype_eng.structure.Construction;
            Construction.registerBlock("minecraft:gold_block", "builder_test:external", "builder.test.external");
            """;
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        registry.registerNativeClass(IBlockState.class);
        registry.registerNativeClass(ZenConstruction.class);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile("external_construction.zs", "ExternalConstructionScript",
            new ZenTokener(new StringReader(script), environment, "external_construction.zs", false), global);
        ZenModule.compileScripts("external_construction.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        assertFalse(classes.isEmpty());
    }
}
