package id.co.yokke.incidentdashboard.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.yokke.incidentdashboard.domain.config.security.JwtService;
import id.co.yokke.incidentdashboard.domain.config.security.SecurityProps;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit test: no Spring context, no database, no Docker. {@link JwtService} takes only a
 * {@link SecurityProps}, so every guard here is exercised against the real constructor rather
 * than against a context that might be supplying a value from somewhere else.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-signing-secret-0123456789abcdef";

    private static final String OTHER_SECRET = "a-completely-different-signing-key-abcdef";

    @Test
    @DisplayName("refuses to start without a secret, and names the property")
    void refusesToStartWithoutASecret() {
        assertThatThrownBy(() -> new JwtService(props(null, Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                // Naming the property is the difference between a five-second fix and an
                // hour in the stack trace: this is the message an operator sees on a failed
                // deploy, with no debugger.
                .hasMessageContaining("incident.jwt.secret");

        assertThatThrownBy(() -> new JwtService(props("   ", Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incident.jwt.secret");
    }

    @Test
    @DisplayName("refuses a secret too short for HS256, and names the property")
    void refusesASecretTooShortForHs256() {
        assertThatThrownBy(() -> new JwtService(props("too-short", Duration.ofHours(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incident.jwt.secret");
    }

    @Test
    @DisplayName("round-trips an access token and returns its claims, not a bare username")
    void roundTripsAUsername() {
        JwtService service = new JwtService(props(SECRET, Duration.ofHours(1)));

        Claims claims = service.validate(service.createToken("ops.manager"),
                JwtService.TYP_ACCESS);

        assertThat(claims.getSubject()).isEqualTo("ops.manager");
        assertThat(claims.get("typ", String.class)).isEqualTo(JwtService.TYP_ACCESS);
        assertThat(claims.get("scope", String.class)).isEqualTo(JwtService.SCOPE_FULL);
    }

    @Test
    @DisplayName("a refresh token presented where an access token is required is REJECTED")
    void refreshTokenIsNotAcceptedAsAnAccessToken() {
        JwtService service = new JwtService(props(SECRET, Duration.ofHours(1)));
        String refresh = service.createRefreshToken("ops.manager", "jti-1");

        // Without this check a refresh token is simply a long-lived access token, and the
        // short access expiry is decorative.
        assertThatThrownBy(() -> service.validate(refresh, JwtService.TYP_ACCESS))
                .isInstanceOf(JwtService.JwtInvalidException.class);

        // ...and it is still perfectly valid at the one endpoint that expects it, carrying
        // the jti that AUTH_REFRESH_TOKEN stores.
        Claims claims = service.validate(refresh, JwtService.TYP_REFRESH);
        assertThat(claims.getSubject()).isEqualTo("ops.manager");
        assertThat(claims.getId()).isEqualTo("jti-1");
    }

    @Test
    @DisplayName("an expired token is rejected")
    void rejectsAnExpiredToken() {
        // A negative TTL puts `exp` in the past at issue time - no sleeping, no flakiness.
        JwtService issuer = new JwtService(props(SECRET, Duration.ofSeconds(-60)));
        JwtService verifier = new JwtService(props(SECRET, Duration.ofHours(1)));
        String expired = issuer.createToken("ops.manager");

        assertThatThrownBy(() -> verifier.validate(expired, JwtService.TYP_ACCESS))
                .isInstanceOf(JwtService.JwtExpiredException.class);
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void rejectsATokenSignedWithADifferentKey() {
        JwtService foreign = new JwtService(props(OTHER_SECRET, Duration.ofHours(1)));
        JwtService service = new JwtService(props(SECRET, Duration.ofHours(1)));
        String forged = foreign.createToken("admin");

        assertThatThrownBy(() -> service.validate(forged, JwtService.TYP_ACCESS))
                .isInstanceOf(JwtService.JwtInvalidException.class);
    }

    @Test
    @DisplayName("a change-password scoped token round-trips with the scope readable")
    void carriesTheForcedRotationScope() {
        JwtService service = new JwtService(props(SECRET, Duration.ofHours(1)));

        Claims claims = service.validate(
                service.createToken("rotate.me", JwtService.SCOPE_CHANGE_PASSWORD),
                JwtService.TYP_ACCESS);

        // The claim is the carrier for the forced-rotation gate. If validate() returned a
        // String this assertion could not be written, and PrivilegeInterceptor would have
        // nothing to refuse on.
        assertThat(claims.getSubject()).isEqualTo("rotate.me");
        assertThat(claims.get("scope", String.class))
                .isEqualTo(JwtService.SCOPE_CHANGE_PASSWORD);
    }

    private static SecurityProps props(String secret, Duration accessTtl) {
        SecurityProps props = new SecurityProps();
        props.setSecret(secret);
        props.setAccessTtl(accessTtl);
        props.setRefreshTtl(Duration.ofHours(24));
        return props;
    }
}
