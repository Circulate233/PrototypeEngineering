package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.math.BlockPos;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.structure.Pattern")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public final class ZenStructurePattern {
    private final Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate> cells = new Object2ObjectLinkedOpenHashMap<>();

    private ZenStructurePattern() {
    }

    @ZenMethod
    public static ZenStructurePattern create() {
        return new ZenStructurePattern();
    }

    @ZenMethod
    public ZenStructurePattern put(int x, int y, int z, ZenStructurePredicate predicate) {
        var position = new BlockPos(x, y, z);
        if (cells.putIfAbsent(position, Objects.requireNonNull(predicate).value()) != null)
            throw new IllegalArgumentException("Duplicate structure position: " + position);
        return this;
    }

    /** Shape construction freezes the same public pattern; this builder never escapes to matching. */
    public StructurePattern value() {
        return new StructurePattern(cells);
    }
}
