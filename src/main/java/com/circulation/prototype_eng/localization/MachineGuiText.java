package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.container.MachineGuiSnapshot;

/**
 * Machine GUI labels use the same AE2S localization contract as hatch labels.
 */
public enum MachineGuiText implements LocalizationEnum {
    Idle, Running, Error, ErrorWithMessage, Status, MainPage, Placeholder, Structure, Formed, NotFormed, Inactive, CheckingStructure, Threads, Recipes, Parallelism, Execution,
    MainThreadMode, ConcurrentMode, PlannedMode, ThreadRow, RecipeParallelism;

    private final String translationKey = "gui." + Tags.MOD_ID + ".machine." + name();

    public static MachineGuiText status(WorkSlotStatus status) {
        return switch (status) {
            case IDLE -> Idle;
            case RUNNING -> Running;
            case BLOCKED, FAILED -> Error;
        };
    }

    public static String reason(MachineGuiSnapshot.Entry entry) {
        if (entry.reason().isEmpty()) {
            return status(entry.status()).getLocal();
        }
        String reason = entry.arguments().isEmpty() ? entry.reason() : entry.reason() + ": " + String.join(", ", entry.arguments());
        for (var text : RecipeText.values()) {
            if (text.getTranslationKey().equals(entry.reason())) {
                reason = text.getLocal(entry.arguments().toArray());
                break;
            }
        }
        return entry.status() == WorkSlotStatus.BLOCKED || entry.status() == WorkSlotStatus.FAILED
            ? ErrorWithMessage.getLocal(reason) : reason;
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
