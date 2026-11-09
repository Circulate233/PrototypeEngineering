package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.MathExpressionParser;

import java.text.DecimalFormat;

/** AE's existing number-entry parser; the energy rate still commits a nonnegative long. */
final class HatchExpression {
    private static final DecimalFormat FORMAT = new DecimalFormat("###.###");
    static { FORMAT.setParseBigDecimal(true); }
    private HatchExpression() {}

    static long parse(String text) {
        try {
            var value = MathExpressionParser.parse(text, FORMAT);
            return value.map(number -> Math.max(0, (long) number.doubleValue())).orElse(0L);
        } catch (ArithmeticException invalidNumber) {
            // AE's number-entry widget handles scale failures from this parser the same way.
            return 0;
        }
    }
}
