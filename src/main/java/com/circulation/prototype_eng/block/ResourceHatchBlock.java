package com.circulation.prototype_eng.block;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.storage.ResourceHatchBlockEntity;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.Objects;

/** Default tiered storage hatch; independent entities extend HatchBlock with their own entity type. */
public class ResourceHatchBlock extends HatchBlock<ResourceHatchBlockEntity> {
    private final HatchMode mode;
    private final HatchTier tier;

    public ResourceHatchBlock(HatchMode mode, HatchTier tier) {
        this(new ResourceLocation(Tags.MOD_ID, mode.name().toLowerCase(Locale.ROOT)
            + "_hatch_" + tier.level()), mode, tier);
    }

    public ResourceHatchBlock(ResourceLocation id, HatchMode mode, HatchTier tier) {
        super(id, ResourceHatchBlockEntity.class, 5.0F);
        this.mode = Objects.requireNonNull(mode);
        this.tier = Objects.requireNonNull(tier);
    }

    public HatchMode mode() {
        return mode;
    }

    public HatchTier tier() {
        return tier;
    }

    @Override
    protected ResourceHatchBlockEntity createHatch(World world, IBlockState state) {
        return new ResourceHatchBlockEntity(mode, tier);
    }
}
