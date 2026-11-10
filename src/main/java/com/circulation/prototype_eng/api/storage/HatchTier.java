package com.circulation.prototype_eng.api.storage;

/**
 * PM's ten existing hatch tiers, with the original item/energy defaults retained.
 */
public enum HatchTier {
    LV1(1, 1, 1, 64, 16_000, 128_000, 32),
    LV2(2, 2, 1, 128, 32_000, 512_000, 128),
    LV3(3, 4, 2, 128, 64_000, 2_000_000, 512),
    LV4(4, 9, 4, 256, 128_000, 8_000_000, 2_048),
    LV5(5, 16, 8, 512, 256_000, 32_000_000, 8_192),
    LV6(6, 25, 12, 1_024, 512_000, 128_000_000, 32_768),
    LV7(7, 36, 18, 2_048, 1_024_000, 512_000_000, 131_072),
    LV8(8, 49, 24, 4_096, 2_048_000, 2_000_000_000, 524_288),
    LV9(9, 64, 32, 8_192, 4_096_000, 8_000_000_000L, 2_097_152),
    LV10(10, 81, 40, 16_384, 8_192_000, 32_000_000_000L, 8_388_608);

    private static final HatchTier[] TIERS = values();
    private final int level;
    private final int slots;
    private final int mixedSlotsPerSide;
    private final long itemsPerSlot;
    private final long fluidPerSlot;
    private final long energyCapacity;
    private final long energyTransfer;

    HatchTier(int level, int slots, int mixedSlotsPerSide, long itemsPerSlot,
              long fluidPerSlot, long energyCapacity, long energyTransfer) {
        this.level = level;
        this.slots = slots;
        this.mixedSlotsPerSide = mixedSlotsPerSide;
        this.itemsPerSlot = itemsPerSlot;
        this.fluidPerSlot = fluidPerSlot;
        this.energyCapacity = energyCapacity;
        this.energyTransfer = energyTransfer;
    }

    public static HatchTier of(int level) {
        if (level < 1 || level > TIERS.length) {
            throw new IllegalArgumentException("Hatch tier must be 1-10: " + level);
        }
        return TIERS[level - 1];
    }

    public int level() {
        return level;
    }

    public int slotsPerSide(HatchMode mode) {
        return mode == HatchMode.MIXED ? mixedSlotsPerSide : slots;
    }

    public long itemsPerSlot() {
        return itemsPerSlot;
    }

    public long fluidPerSlot() {
        return fluidPerSlot;
    }

    public long energyCapacity() {
        return energyCapacity;
    }

    /**
     * PM's EnergyStorageImpl applies this limit per operation, despite older FE/t labels.
     */
    public long energyTransfer() {
        return energyTransfer;
    }
}
