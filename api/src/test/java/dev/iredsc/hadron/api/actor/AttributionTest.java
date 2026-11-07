package dev.iredsc.hadron.api.actor;

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

    @Test
    void noneIsEmptyAndActsForNobody() {
        // COVERS: Attribution#none
        // COVERS: Attribution#isEmpty
        Attribution none = Attribution.none();
        assertTrue(none.isEmpty());
        assertEquals(Optional.empty(), none.responsible());
        assertEquals(Optional.empty(), none.controller());
        assertEquals(Optional.empty(), none.authority());
        assertEquals(Optional.empty(), none.actingFor());
        assertSame(none, Attribution.none());
    }

    @Test
    void ofMakesTheResponsiblePlayerTheOneActedFor() {
        // COVERS: Attribution#of
        // COVERS: Attribution#actingFor
        Attribution a = Attribution.of(OWNER);
        assertEquals(Optional.of(OWNER), a.responsible());
        assertEquals(Optional.empty(), a.controller());
        assertEquals(Optional.of(OWNER), a.actingFor());
        assertFalse(a.isEmpty());
    }

    @Test
    void ofNullIsRefusedPointingAtNone() {
        // COVERS: Attribution#of
        NullPointerException e = assertThrows(NullPointerException.class, () -> Attribution.of(null));
        assertTrue(e.getMessage().contains("Attribution.none()"), e.getMessage());
    }

    @Test
    void aControllerIsWhomProtectionChecksOverTheOwner() {
        // COVERS: Attribution#actingFor
        // COVERS: Attribution#withController
        Attribution charmed = Attribution.of(OWNER).withController(DRIVER);
        assertEquals(Optional.of(DRIVER), charmed.actingFor());
        assertEquals(Optional.of(OWNER), charmed.responsible());
    }

    @Test
    void authorityAloneActsForNobody() {
        // COVERS: Attribution#withAuthority
        // COVERS: Attribution#actingFor
        Attribution a = Attribution.none().withAuthority(ADMIN);
        assertFalse(a.isEmpty());
        assertEquals(Optional.of(ADMIN), a.authority());
        assertEquals(Optional.empty(), a.actingFor());
    }

    @Test
    void withMethodsReturnCopiesAndLeaveTheOriginal() {
        // COVERS: Attribution#withResponsible
        // COVERS: Attribution#withController
        // COVERS: Attribution#withAuthority
        Attribution base = Attribution.of(OWNER);
        Attribution changed = base.withResponsible(DRIVER).withController(OWNER).withAuthority(ADMIN);
        assertEquals(Optional.of(OWNER), base.responsible());
        assertEquals(Optional.empty(), base.controller());
        assertEquals(Optional.of(DRIVER), changed.responsible());
        assertEquals(Optional.of(OWNER), changed.controller());
        assertEquals(Optional.of(ADMIN), changed.authority());
    }

    @Test
    void clearingEveryPartEqualsNone() {
        // COVERS: Attribution#equals
        // COVERS: Attribution#isEmpty
        Attribution cleared = Attribution.of(OWNER).withResponsible(null);
        assertTrue(cleared.isEmpty());
        assertEquals(Attribution.none(), cleared);
        assertEquals(Attribution.none().hashCode(), cleared.hashCode());
    }

    @Test
    void equalityComparesAllThreeParts() {
        // COVERS: Attribution#equals
        // COVERS: Attribution#hashCode
        Attribution a = Attribution.of(OWNER).withController(DRIVER).withAuthority(ADMIN);
        Attribution b = Attribution.of(OWNER).withController(DRIVER).withAuthority(ADMIN);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.withAuthority(null));
        assertNotEquals(a, a.withController(null));
        assertNotEquals(a, a.withResponsible(ADMIN));
        assertTrue(a.toString().contains(OWNER.toString()));
    }

    @Test
    void serverNatureAndUnknownActorsCarryNoAttribution() {
        // COVERS: Actor#attribution
        // COVERS: Actor#actingFor
        assertTrue(new Actor.Server("Server").attribution().isEmpty());
        assertTrue(Actor.Nature.INSTANCE.attribution().isEmpty());
        assertTrue(Actor.Unknown.INSTANCE.attribution().isEmpty());
        assertEquals(Optional.empty(), Actor.Unknown.INSTANCE.actingFor());
        assertThrows(NullPointerException.class, () -> new Actor.Server(null));
    }
}
