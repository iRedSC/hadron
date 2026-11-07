package dev.iredsc.hadron.api.actor;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttributionTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DRIVER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Covers(value = Attribution.class, method = "none")
    @Covers(value = Attribution.class, method = "isEmpty")
    @Test
    void noneIsEmptyAndActsForNobody() {
        Attribution none = Attribution.none();
        assertTrue(none.isEmpty());
        assertEquals(Optional.empty(), none.responsible());
        assertEquals(Optional.empty(), none.controller());
        assertEquals(Optional.empty(), none.authority());
        assertEquals(Optional.empty(), none.actingFor());
        assertSame(none, Attribution.none());
    }

    @Covers(value = Attribution.class, method = "of")
    @Covers(value = Attribution.class, method = "actingFor")
    @Test
    void ofMakesTheResponsiblePlayerTheOneActedFor() {
        Attribution a = Attribution.of(OWNER);
        assertEquals(Optional.of(OWNER), a.responsible());
        assertEquals(Optional.empty(), a.controller());
        assertEquals(Optional.of(OWNER), a.actingFor());
        assertFalse(a.isEmpty());
    }

    @Covers(value = Attribution.class, method = "of")
    @Test
    void ofNullIsRefusedPointingAtNone() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> Attribution.of(null));
        assertTrue(e.getMessage().contains("Attribution.none()"), e.getMessage());
    }

    @Covers(value = Attribution.class, method = "actingFor")
    @Covers(value = Attribution.class, method = "withController")
    @Test
    void aControllerIsWhomProtectionChecksOverTheOwner() {
        Attribution charmed = Attribution.of(OWNER).withController(DRIVER);
        assertEquals(Optional.of(DRIVER), charmed.actingFor());
        assertEquals(Optional.of(OWNER), charmed.responsible());
    }

    @Covers(value = Attribution.class, method = "withAuthority")
    @Covers(value = Attribution.class, method = "actingFor")
    @Test
    void authorityAloneActsForNobody() {
        Attribution a = Attribution.none().withAuthority(ADMIN);
        assertFalse(a.isEmpty());
        assertEquals(Optional.of(ADMIN), a.authority());
        assertEquals(Optional.empty(), a.actingFor());
    }

    @Covers(value = Attribution.class, method = "withResponsible")
    @Covers(value = Attribution.class, method = "withController")
    @Covers(value = Attribution.class, method = "withAuthority")
    @Test
    void withMethodsReturnCopiesAndLeaveTheOriginal() {
        Attribution base = Attribution.of(OWNER);
        Attribution changed = base.withResponsible(DRIVER).withController(OWNER).withAuthority(ADMIN);
        assertEquals(Optional.of(OWNER), base.responsible());
        assertEquals(Optional.empty(), base.controller());
        assertEquals(Optional.of(DRIVER), changed.responsible());
        assertEquals(Optional.of(OWNER), changed.controller());
        assertEquals(Optional.of(ADMIN), changed.authority());
    }
    @Covers(value = Attribution.class, method = "isEmpty")
    @Test
    void clearingEveryPartEqualsNone() {
        Attribution cleared = Attribution.of(OWNER).withResponsible(null);
        assertTrue(cleared.isEmpty());
        assertEquals(Attribution.none(), cleared);
        assertEquals(Attribution.none().hashCode(), cleared.hashCode());
    }
    @Test
    void equalityComparesAllThreeParts() {
        Attribution a = Attribution.of(OWNER).withController(DRIVER).withAuthority(ADMIN);
        Attribution b = Attribution.of(OWNER).withController(DRIVER).withAuthority(ADMIN);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.withAuthority(null));
        assertNotEquals(a, a.withController(null));
        assertNotEquals(a, a.withResponsible(ADMIN));
        assertTrue(a.toString().contains(OWNER.toString()));
    }

    @Covers(value = Actor.class, method = "attribution")
    @Covers(value = Actor.class, method = "actingFor")
    @Test
    void serverNatureAndUnknownActorsCarryNoAttribution() {
        assertTrue(new Actor.Server("Server").attribution().isEmpty());
        assertTrue(Actor.Nature.INSTANCE.attribution().isEmpty());
        assertTrue(Actor.Unknown.INSTANCE.attribution().isEmpty());
        assertEquals(Optional.empty(), Actor.Unknown.INSTANCE.actingFor());
        assertThrows(NullPointerException.class, () -> new Actor.Server(null));
    }
}
