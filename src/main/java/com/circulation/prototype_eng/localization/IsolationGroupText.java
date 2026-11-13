package com.circulation.prototype_eng.localization;

import ae2.api.util.AEColor;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/**
 * Names for resource isolation groups, including the shared uncolored group.
 */
public enum IsolationGroupText implements LocalizationEnum {
    White, LightGray, Gray, Black, Lime, Yellow, Orange, Brown, Red,
    Pink, Magenta, Purple, Blue, LightBlue, Cyan, Green, Uncolored;

    private final String translationKey = "gui." + Tags.MOD_ID + ".isolation_group." + name();

    public static IsolationGroupText of(AEColor color) {
        return switch (color) {
            case WHITE -> White;
            case LIGHT_GRAY -> LightGray;
            case GRAY -> Gray;
            case BLACK -> Black;
            case LIME -> Lime;
            case YELLOW -> Yellow;
            case ORANGE -> Orange;
            case BROWN -> Brown;
            case RED -> Red;
            case PINK -> Pink;
            case MAGENTA -> Magenta;
            case PURPLE -> Purple;
            case BLUE -> Blue;
            case LIGHT_BLUE -> LightBlue;
            case CYAN -> Cyan;
            case GREEN -> Green;
            case TRANSPARENT -> Uncolored;
        };
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
