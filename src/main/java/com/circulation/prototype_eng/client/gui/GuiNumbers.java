package com.circulation.prototype_eng.client.gui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;

/** NumberFormatUtil's original PM label/tooltip formatting. */
final class GuiNumbers {
    private static final NumberFormat GROUPED = NumberFormat.getIntegerInstance(Locale.US);
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US));

    private GuiNumbers() {}

    static String grouped(long value) { return GROUPED.format(value); }

    static String compact(long value) {
        long amount = Math.max(0, value);
        if (amount >= 1_000_000_000) return DECIMAL.format(amount / 1_000_000_000.0) + "G";
        if (amount >= 1_000_000) return DECIMAL.format(amount / 1_000_000.0) + "M";
        if (amount >= 1_000) return DECIMAL.format(amount / 1_000.0) + "k";
        return grouped(amount);
    }
}
