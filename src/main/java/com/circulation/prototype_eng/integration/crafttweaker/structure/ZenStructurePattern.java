package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.math.BlockPos;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenRegister
@ZenClass("mods.prototype_eng.structure.Pattern")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public final class ZenStructurePattern {
    private final BlockPos2ObjectMap<StructurePredicate> cells = new BlockPos2ObjectMap<>(16);

    private ZenStructurePattern() {
    }

    @ZenMethod
    public static ZenStructurePattern create() {
        return new ZenStructurePattern();
    }

    @ZenMethod
    public ZenStructurePattern put(int x, int y, int z, ZenStructurePredicate predicate) {
        long position = BlockPos2ObjectMap.toLong(x, y, z);
        if (cells.putIfAbsent(position, predicate.value()) != null)
            throw new IllegalArgumentException("Duplicate structure position: " + BlockPos.fromLong(position));
        return this;
    }

    /**
     * Shape construction freezes the same public pattern; this builder never escapes to matching.
     */
    public StructurePattern value() {
        return new StructurePattern(cells);
    }
}
