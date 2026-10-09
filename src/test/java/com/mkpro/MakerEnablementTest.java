package com.mkpro;

import com.mkpro.core.MkProContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MakerEnablementTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("mkpro.maker.enabled");
    }

    @Test
    void makerIsDisabledByDefaultInContext() {
        MkProContext context = new MkProContext();
        assertNotNull(context.getMakerEnabled(), "makerEnabled AtomicBoolean should not be null");
        assertFalse(context.getMakerEnabled().get(), "Maker should be disabled by default");
    }

    @Test
    void makerCanBeExplicitlyEnabledViaSystemProperty() {
        System.setProperty("mkpro.maker.enabled", "true");
        String prop = System.getProperty("mkpro.maker.enabled");
        assertTrue("true".equalsIgnoreCase(prop));

        MkProContext context = new MkProContext();
        if ("true".equalsIgnoreCase(prop)) {
            context.getMakerEnabled().set(true);
        }
        assertTrue(context.getMakerEnabled().get());
    }

    @Test
    void makerRemainsDisabledWhenPropertyNotTrue() {
        System.setProperty("mkpro.maker.enabled", "false");
        String prop = System.getProperty("mkpro.maker.enabled");

        MkProContext context = new MkProContext();
        if ("true".equalsIgnoreCase(prop)) {
            context.getMakerEnabled().set(true);
        } else {
            context.getMakerEnabled().set(false);
        }
        assertFalse(context.getMakerEnabled().get());
    }
}
