package dev.iredsc.hadron.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iredsc.hadron.test.Covers;
import org.junit.jupiter.api.Test;

class MemorySectionTest {
    @Covers(value = MemorySection.class, method = "toString")
    @Test
    void itsTextNamesItsPath() {
        ConfigurationSection section = new YamlConfiguration().createSection("shop.prices");
        assertEquals("MemorySection[path='shop.prices']", section.toString());
    }
}
