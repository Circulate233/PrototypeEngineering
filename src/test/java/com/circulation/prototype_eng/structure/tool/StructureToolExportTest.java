package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StructureToolExportTest {
    @Test
    void repeatedExportsHaveUniqueIdsAndReloadTogether(@TempDir Path directory) throws Exception {
        var cells = List.of(new StructureToolExport.Cell(new BlockPos(1, 2, 3), new ResourceLocation("minecraft", "stone"), 1, Map.of("mode", "value")));
        String content = StructureToolExport.json("../My Structure", "测试结构", cells);
        var first = StructureToolExport.save(directory, "../My Structure", content);
        var second = StructureToolExport.save(directory, "../My Structure", content);
        assertNotEquals(first, second);
        assertEquals(directory, first.getParent());
        var a = new Gson().fromJson(Files.readString(first), JsonObject.class);
        var b = new Gson().fromJson(Files.readString(second), JsonObject.class);
        assertEquals("prototype_eng:my_structure", a.get("id").getAsString());
        assertEquals("prototype_eng:my_structure_2", b.get("id").getAsString());
        assertEquals("测试结构", a.get("translationKey").getAsString());
        assertDoesNotThrow(() -> StructureJsonLoader.readDirectory(directory));
        assertEquals(content, Files.readString(first));
    }
}
