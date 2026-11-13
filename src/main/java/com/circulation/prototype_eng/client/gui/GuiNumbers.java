package com.circulation.prototype_eng.client.gui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Original PM label/tooltip formatting, including off-owner search-tree tooltips.
 */
final class GuiNumbers {
    private static final ThreadLocal<NumberFormat> GROUPED = ThreadLocal.withInitial(() -> NumberFormat.getIntegerInstance(Locale.US));
    private static final ThreadLocal<DecimalFormat> DECIMAL = ThreadLocal.withInitial(() -> new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US)));

    private GuiNumbers() {
    }

    static String grouped(long value) {
        return GROUPED.get().format(value);
    }

    static String compact(long value) {
        long amount = Math.max(0, value);
        if (amount >= 1_000_000_000) return DECIMAL.get().format(amount / 1_000_000_000.0) + "G";
        if (amount >= 1_000_000) return DECIMAL.get().format(amount / 1_000_000.0) + "M";
        if (amount >= 1_000) return DECIMAL.get().format(amount / 1_000.0) + "k";
        return grouped(amount);
    }
}
