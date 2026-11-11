package com.circulation.prototype_eng.integration.crafttweaker.structure;

import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import crafttweaker.api.world.IBlockPos;
import crafttweaker.api.world.IWorld;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;

/**
 * Observational sampler called only on the server owner, before detached matching. It must not
 * mutate the world, force-load chunks or retain world authority. Declare every sampled position in
 * the predicate's read offsets. Only its copied data result reaches a worker.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.structure.Capture")
@FunctionalInterface
public interface ZenStructureCapture {
    @Nullable IData capture(IWorld world, IBlockPos position);
}
