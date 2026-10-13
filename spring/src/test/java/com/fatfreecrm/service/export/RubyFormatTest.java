package com.fatfreecrm.service.export;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RubyFormatTest {

    @Test
    void matchesRubyFloatNotationBoundaries() {
        // ruby -e output: 1.0e+15, 123456789012345.0, 9.999999999999998e+15,
        // 0.0001, 1.0e-05, -1.0e+15, -123456789012345.0,
        // -9.999999999999998e+15, -0.0001, -1.0e-05.
        assertEquals("1.0e+15", RubyFormat.rubyFloat(1e15));
        assertEquals("123456789012345.0", RubyFormat.rubyFloat(123456789012345.0));
        assertEquals("9.999999999999998e+15", RubyFormat.rubyFloat(9999999999999998.0));
        assertEquals("0.0001", RubyFormat.rubyFloat(0.0001));
        assertEquals("1.0e-05", RubyFormat.rubyFloat(0.00001));
        assertEquals("-1.0e+15", RubyFormat.rubyFloat(-1e15));
        assertEquals("-123456789012345.0", RubyFormat.rubyFloat(-123456789012345.0));
        assertEquals("-9.999999999999998e+15", RubyFormat.rubyFloat(-9999999999999998.0));
        assertEquals("-0.0001", RubyFormat.rubyFloat(-0.0001));
        assertEquals("-1.0e-05", RubyFormat.rubyFloat(-0.00001));
    }
}
