package utils;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the request-hash generator. The gateway relies on this
 * being deterministic (so repeat requests for the same (id, context)
 * collapse to a single cache document) and version-5 compliant.
 */
class UUIDv5Test {

    @Test
    void fromUTF8_isDeterministic_forSameInput() {
        UUID a = UUIDv5.fromUTF8("A-24HA001_TAG");
        UUID b = UUIDv5.fromUTF8("A-24HA001_TAG");
        assertEquals(a, b);
    }

    @Test
    void fromUTF8_differsForDifferentInputs() {
        UUID a = UUIDv5.fromUTF8("A-24HA001_TAG");
        UUID b = UUIDv5.fromUTF8("A-24HA001_ASSET");
        UUID c = UUIDv5.fromUTF8("B-24HA001_TAG");
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(b, c);
    }

    @Test
    void fromUTF8_producesVersion5Uuid() {
        UUID uuid = UUIDv5.fromUTF8("anything");
        assertNotNull(uuid);
        assertEquals(5, uuid.version(), "SHA-1 UUIDs must be version 5");
        assertEquals(2, uuid.variant(), "RFC 4122 variant must be 2");
    }

    @Test
    void fromUTF8_matchesKnownHashUsedByReadme() {
        // The README documents that {"id":"A-24HA001","context":"TAG"} resolves to
        // the request hash caebb70d-cf1e-5176-afaa-ca094a9d49ac. Guard against
        // accidental drift in the hashing scheme.
        UUID uuid = UUIDv5.fromUTF8("A-24HA001".toUpperCase() + "_" + "TAG".toUpperCase());
        assertEquals(UUID.fromString("caebb70d-cf1e-5176-afaa-ca094a9d49ac"), uuid);
    }

    @Test
    void fromBytes_rejectsNull() {
        assertThrows(NullPointerException.class, () -> UUIDv5.fromBytes(null));
    }
}
