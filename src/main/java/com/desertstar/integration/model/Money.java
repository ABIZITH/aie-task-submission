package com.desertstar.integration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding method used throughout: BigDecimal, scale 2, HALF_UP.
 * Chosen because it matches how most ERPs round invoice lines and is easy to
 * explain/defend to a customer (documented in docs/discovery-and-design.md).
 */
public final class Money {

    private Money() {
    }

    public static BigDecimal of(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** True if two amounts are equal once both are rounded to 2dp. */
    public static boolean equalsRounded(BigDecimal a, BigDecimal b) {
        return round(a).compareTo(round(b)) == 0;
    }

    public static double toDouble(BigDecimal value) {
        return round(value).doubleValue();
    }
}
