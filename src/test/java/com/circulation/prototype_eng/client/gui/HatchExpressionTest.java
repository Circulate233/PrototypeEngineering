package com.circulation.prototype_eng.client.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HatchExpressionTest {
    @Test void aeExpressionsCommitThroughTheEnergyRateBoundary() {
        assertEquals(500, HatchExpression.parse("2k / 4"));
        assertEquals(32, HatchExpression.parse("2^5"));
        assertEquals(12, HatchExpression.parse("2*(2+4)"));
        assertEquals(1, HatchExpression.parse("1.9"));
        assertEquals(-1, HatchExpression.parse("-1"));
        assertEquals(-1, HatchExpression.parse("1 - 2"));
        assertEquals(0, HatchExpression.parse("0"));
    }

    @Test void invalidNegativeAndLargeValuesRetainTheRateCommitContract() {
        assertEquals(0, HatchExpression.parse(""));
        assertEquals(0, HatchExpression.parse("1+"));
        assertEquals(0, HatchExpression.parse("1/0"));
        assertEquals(-1, HatchExpression.parse("-100"));
        assertEquals(Long.MAX_VALUE, HatchExpression.parse("99999999999999999999999"));
    }
}
