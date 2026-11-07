package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.container.MachineGuiSnapshot;

/** Machine GUI labels use the same AE2S localization contract as hatch labels. */
public enum MachineGuiText implements LocalizationEnum {
    Formed, Unformed, Unavailable, Idle, Running, Blocked, Failed, WorkSlots, Slot, Progress, Recipe, NoWorkSlots;

    private final String translationKey = "gui." + Tags.MOD_ID + ".machine." + name();
    @Override public String getTranslationKey() { return translationKey; }

    public static MachineGuiText status(WorkSlotStatus status) {
        return switch (status) {
            case IDLE -> Idle;
            case RUNNING -> Running;
            case BLOCKED -> Blocked;
            case FAILED -> Failed;
        };
    }

    public static String reason(MachineGuiSnapshot.Entry entry) {
        if (entry.reason().isEmpty()) { return status(entry.status()).getLocal(); }
        for (var text : RecipeText.values()) {
            if (text.getTranslationKey().equals(entry.reason())) { return text.getLocal(entry.arguments().toArray()); }
        }
        // Unknown external reason IDs remain visible. A custom Java screen can render its own contract.
        return entry.arguments().isEmpty() ? entry.reason() : entry.reason() + ": " + String.join(", ", entry.arguments());
    }
}
