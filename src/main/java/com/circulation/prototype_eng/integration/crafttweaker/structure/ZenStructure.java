package com.circulation.prototype_eng.integration.crafttweaker.structure;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDefinitions;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@ZenRegister
@ZenClass("mods.prototype_eng.structure.Structure")
@SuppressWarnings("unused") // ZenScript resolves the registered public factories.
public record ZenStructure(StructureDefinition definition) {
    public ZenStructure {
        Objects.requireNonNull(definition);
    }

    @ZenMethod
    public static ZenStructure template(String id, String orientation, int x, int y, int z, boolean hideWorldBlocks,
                                        ZenStructurePattern pattern, ZenStructurePredicate[] validators, ZenStructure[] children) {
        return new ZenStructure(new StructureDefinition(StructureDefinitions.template(new ResourceLocation(id),
            BlockOrientation.valueOf(orientation.toUpperCase(Locale.ROOT)), new BlockPos(x, y, z), hideWorldBlocks,
            pattern.value(), ZenStructurePredicate.values(validators), values(children))));
    }

    @ZenMethod
    public static ZenStructure slice(String id, String orientation, int x, int y, int z, boolean hideWorldBlocks,
                                     ZenStructurePattern pattern, int minCount, int maxCount, int stepX, int stepY, int stepZ,
                                     ZenStructurePredicate[] validators, ZenStructure[] children) {
        return new ZenStructure(new StructureDefinition(StructureDefinitions.slice(new ResourceLocation(id),
            BlockOrientation.valueOf(orientation.toUpperCase(Locale.ROOT)), new BlockPos(x, y, z), hideWorldBlocks,
            pattern.value(), minCount, maxCount, new BlockPos(stepX, stepY, stepZ),
            ZenStructurePredicate.values(validators), values(children))));
    }

    private static List<MachineStructure> values(ZenStructure[] structures) {
        var values = new ObjectArrayList<MachineStructure>(structures.length);
        for (var structure : structures) values.add(structure.definition.shape());
        return values;
    }

    @ZenMethod
    public ZenStructure translated(String translationKey) {
        return new ZenStructure(new StructureDefinition(definition.shape(), translationKey, definition.displays()));
    }

    @ZenMethod
    public void register() {
        DefinitionRegistrations.runtime(event -> event.definition(definition));
    }

    @ZenGetter("id")
    public String id() {
        return definition.id().toString();
    }
}
