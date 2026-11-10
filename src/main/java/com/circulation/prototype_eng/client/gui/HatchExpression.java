package com.circulation.prototype_eng.client.gui;

import ae2.client.gui.MathExpressionParser;

import java.text.DecimalFormat;

/** AE's number-entry parser; -1 disables the additional user rate limit and zero forbids transfer. */
final class HatchExpression {
    private static final DecimalFormat FORMAT = new DecimalFormat("###.###");
    static { FORMAT.setParseBigDecimal(true); }
    private HatchExpression() {}

    static long parse(String text) {
        try {
            var value = MathExpressionParser.parse(text, FORMAT);
            return value.map(number -> Math.max(-1, (long) number.doubleValue())).orElse(0L);
        } catch (ArithmeticException invalidNumber) {
            // AE's number-entry widget handles scale failures from this parser the same way.
            return 0;
        }
    }
}
