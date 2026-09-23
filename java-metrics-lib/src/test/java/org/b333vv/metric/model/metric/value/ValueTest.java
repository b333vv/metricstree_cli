package org.b333vv.metric.model.metric.value;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueTest {

    @Test
    void doubleValuesFormatWithDotRegardlessOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(new Locale("ru", "RU"));
        try {
            assertEquals("312.7522", Value.of(312.7522).toString());
            assertEquals("0.5", Value.of(0.5).toString());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void longValuesFormatUnchanged() {
        assertEquals("42", Value.of(42L).toString());
    }
}
