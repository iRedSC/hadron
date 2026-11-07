package dev.iredsc.hadron.api.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iredsc.hadron.test.Covers;
import org.junit.jupiter.api.Test;

class OperationTest {
    @Covers(value = Operation.class, method = "toString")
    @Test
    void itsTextNamesItsIdAndActor() {
        Actor console = new Actor.Server("Server");
        Operation op = Operation.single(console);
        assertEquals("Operation#" + op.id() + "[" + console + "]", op.toString());
    }
}
