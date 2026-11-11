package com.circulation.prototype_eng.integration.crafttweaker.structure;

import com.circulation.prototype_eng.api.structure.StructureFacts;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.block.IBlockState;
import crafttweaker.api.data.IData;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.world.IBlockPos;
import crafttweaker.mc1120.data.NBTConverter;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;

/** Script view of the existing snapshot query contract; it grants no live IWorld access. */
@ZenRegister
@ZenClass("mods.prototype_eng.structure.Facts")
@SuppressWarnings("unused") // Registered script getters and methods.
public record ZenStructureFacts(StructureFacts value) {
    public ZenStructureFacts {
        Objects.requireNonNull(value);
    }

    @ZenGetter("x") public int x() { return value.position().getX(); }
    @ZenGetter("y") public int y() { return value.position().getY(); }
    @ZenGetter("z") public int z() { return value.position().getZ(); }
    @ZenGetter("offsetX") public int offsetX() { return value.relativePosition().getX(); }
    @ZenGetter("offsetY") public int offsetY() { return value.relativePosition().getY(); }
    @ZenGetter("offsetZ") public int offsetZ() { return value.relativePosition().getZ(); }
    @ZenGetter("blockPos") public IBlockPos blockPos() { return CraftTweakerMC.getIBlockPos(value.position()); }
    @ZenGetter("relativePos") public IBlockPos relativePos() { return CraftTweakerMC.getIBlockPos(value.relativePosition()); }
    @ZenGetter("controller") public ZenStructureFacts controller() { return new ZenStructureFacts(value.atController(BlockPos.ORIGIN)); }

    @ZenMethod
    public ZenStructureFacts at(int x, int y, int z) { return new ZenStructureFacts(value.at(new BlockPos(x, y, z))); }

    @ZenMethod
    public ZenStructureFacts atWorld(int x, int y, int z) { return new ZenStructureFacts(value.atWorld(new BlockPos(x, y, z))); }

    @ZenMethod
    public @Nullable IData read(ZenStructureQuery query) { return NBTConverter.from(value.read(Objects.requireNonNull(query).value()), true); }

    @ZenGetter("available") public boolean available() { return value.available(); }
    @ZenGetter("biomeName") public String biomeName() { return Objects.requireNonNull(value.read(StructureQueries.BIOME_NAME)); }
    @ZenGetter("biomeId") public String biomeId() { return Objects.requireNonNull(value.read(StructureQueries.BIOME)).toString(); }
    @ZenGetter("biomeTemperature") public float biomeTemperature() { return Objects.requireNonNull(value.read(StructureQueries.BIOME_TEMPERATURE)); }
    @ZenGetter("biomeRainfall") public float biomeRainfall() { return Objects.requireNonNull(value.read(StructureQueries.BIOME_RAINFALL)); }
    @ZenGetter("dimension") public int dimension() { return Objects.requireNonNull(value.read(StructureQueries.DIMENSION)); }
    @ZenGetter("worldTime") public long worldTime() { return Objects.requireNonNull(value.read(StructureQueries.WORLD_TIME)); }
    @ZenGetter("totalWorldTime") public long totalWorldTime() { return Objects.requireNonNull(value.read(StructureQueries.TOTAL_WORLD_TIME)); }
    @ZenGetter("daytime") public boolean daytime() { return Objects.requireNonNull(value.read(StructureQueries.DAYTIME)); }
    @ZenGetter("raining") public boolean raining() { return Objects.requireNonNull(value.read(StructureQueries.RAINING)); }
    @ZenGetter("thundering") public boolean thundering() { return Objects.requireNonNull(value.read(StructureQueries.THUNDERING)); }
    @ZenGetter("skyVisible") public boolean skyVisible() { return Objects.requireNonNull(value.read(StructureQueries.SKY_VISIBLE)); }
    @ZenGetter("rainingAt") public boolean rainingAt() { return Objects.requireNonNull(value.read(StructureQueries.RAINING_AT)); }
    @ZenGetter("light") public int light() { return Objects.requireNonNull(value.read(StructureQueries.LIGHT)); }
    @ZenGetter("strongPower") public int strongPower() { return Objects.requireNonNull(value.read(StructureQueries.STRONG_POWER)); }
    @ZenGetter("height") public int height() { return Objects.requireNonNull(value.read(StructureQueries.HEIGHT)); }
    @ZenGetter("notAir") public boolean notAir() { return Objects.requireNonNull(value.read(StructureQueries.NON_AIR)); }
    @ZenGetter("hasTileEntity") public boolean hasTileEntity() { return Objects.requireNonNull(value.read(StructureQueries.HAS_TILE_ENTITY)); }
    @ZenGetter("resourceHatch") public boolean resourceHatch() { return Objects.requireNonNull(value.read(StructureQueries.RESOURCE_HATCH)); }
    @ZenGetter("energyInputHatch") public boolean energyInputHatch() { return Objects.requireNonNull(value.read(StructureQueries.ENERGY_INPUT_HATCH)); }
    @ZenGetter("energyOutputHatch") public boolean energyOutputHatch() { return Objects.requireNonNull(value.read(StructureQueries.ENERGY_OUTPUT_HATCH)); }
    @ZenGetter("blockState") public IBlockState blockState() { return CraftTweakerMC.getBlockState(Objects.requireNonNull(value.read(StructureQueries.BLOCK_STATE))); }
    @ZenGetter("tileNbt") public @Nullable IData tileNbt() { return NBTConverter.from(value.read(StructureQueries.TILE_NBT), true); }
}
