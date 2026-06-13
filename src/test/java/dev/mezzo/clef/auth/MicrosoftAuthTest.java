package dev.mezzo.clef.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MicrosoftAuthTest {

    @Test
    void undashRebuildsUuidFromMojangFormat() {
        // Notch's well-known profile id (dashless as returned by the MC profile API).
        UUID expected = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(expected, MicrosoftAuth.undashUuid("069a79f444e94726a5befca90e38aaf5"));
    }

    @Test
    void undashAcceptsAlreadyDashedInput() {
        UUID dashed = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(dashed, MicrosoftAuth.undashUuid(dashed.toString()));
    }

    @Test
    void emptyClientIdRejected() {
        assertThrows(MicrosoftAuth.AuthException.class, () -> new MicrosoftAuth(""));
        assertThrows(MicrosoftAuth.AuthException.class, () -> new MicrosoftAuth(null));
    }
}
