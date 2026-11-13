package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Objects;
import java.util.UUID;

/**
 * Validation of download metadata and exact registered item identity.
 */
final class DownloadValidation {
    private static final UUID ZERO_UUID = new UUID(0, 0);

    private DownloadValidation() {
    }

    static UUID requireUuid(UUID value, String label) {
        if (ZERO_UUID.equals(Objects.requireNonNull(value, label))) {
            throw new IllegalArgumentException(label + " must not be the zero UUID");
        }
        return value;
    }

    static String requireTeamKey(String teamKey) {
        if (Objects.requireNonNull(teamKey, "Download team key").isBlank() || !teamKey.equals(teamKey.trim())) {
            throw new IllegalArgumentException("Download team key must be non-blank and already trimmed");
        }
        return teamKey;
    }

    static ItemStack copyMaterial(ItemStack material) {
        var copy = copyStack(material, "Download material");
        if (copy.getCount() != 1) {
            throw new IllegalArgumentException("Download material count must be exactly one");
        }
        return copy;
    }

    static AEItemKey key(ItemStack material) {
        validateStack(material, "Download material key");
        return Objects.requireNonNull(AEItemKey.of(material));
    }

    static ItemStack copyStack(ItemStack source, String label) {
        validateStack(source, label);
        return source.copy();
    }

    private static void validateStack(ItemStack stack, String label) {
        Objects.requireNonNull(stack, label);
        if (stack.isEmpty() || stack.getCount() < 1 || stack.getMetadata() < 0) {
            throw new IllegalArgumentException(label + " must be non-empty with positive count and non-negative metadata");
        }
        var item = stack.getItem();
        var id = item.getRegistryName();
        if (id == null || ForgeRegistries.ITEMS.getValue(id) != item) {
            throw new IllegalArgumentException(label + " requires the exact registered item identity");
        }
    }
}
