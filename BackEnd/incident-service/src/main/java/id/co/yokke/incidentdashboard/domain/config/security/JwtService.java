package id.co.yokke.incidentdashboard.domain.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates the service's own JWTs. Fully standalone: no call to yob, no shared
 * user store, no SSO.
 *
 * <p>Every token carries three claims — {@code sub}, {@code typ} and {@code scope} — and
 * refresh tokens additionally carry {@code jti}.
 */
@Service
public class JwtService {

    /** Presented on every {@code /api/**} call. Short-lived. */
    public static final String TYP_ACCESS = "access";

    /** Accepted at {@code /auth/refresh-token} and nowhere else. Carries a {@code jti}. */
    public static final String TYP_REFRESH = "refresh";

    /** The scope of an ordinary session: whatever the user's RBAC grants allow. */
    public static final String SCOPE_FULL = "full";

    /**
     * The forced-rotation scope. {@code MUST_CHANGE_PASSWORD='Y'} still logs in — refusing
     * the login outright strands the bootstrap account with no way back — but the token it
     * receives carries this scope, and {@code PrivilegeInterceptor} refuses every route
     * except {@code /auth/change-password} while it is set.
     */
    public static final String SCOPE_CHANGE_PASSWORD = "change-password";

    static final String CLAIM_TYP = "typ";

    static final String CLAIM_SCOPE = "scope";

    /** HS256 produces a 256-bit MAC; a shorter key is weaker than the digest it protects. */
    static final int MIN_SECRET_BYTES = 32;

    static final String SECRET_PROPERTY = "incident.jwt.secret";

    private final SecretKey signingKey;

    private final Duration accessTtl;

    private final Duration refreshTtl;

    /**
     * Fails fast, at construction, on a missing or undersized secret.
     *
     * <p>There is no fallback key and there must never be one. A default would make the
     * service boot happily in production with a signing key that is public knowledge, and
     * every token in the system forgeable — a failure that is invisible until it is
     * catastrophic. Refusing to start is the loud version of the same condition.
     */
    public JwtService(SecurityProps props) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(SECRET_PROPERTY
                    + " is not configured. It has no default: set the INCIDENT_JWT_SECRET "
                    + "environment variable to at least " + MIN_SECRET_BYTES
                    + " bytes of random data.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(SECRET_PROPERTY + " is too short for HS256: "
                    + keyBytes.length + " bytes, minimum " + MIN_SECRET_BYTES + ".");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtl = props.getAccessTtl();
        this.refreshTtl = props.getRefreshTtl();
    }

    /** An ordinary access token: {@code typ=access}, {@code scope=full}. */
    public String createToken(String username) {
        return createToken(username, SCOPE_FULL);
    }

    /**
     * An access token with an explicit scope — the forced-rotation path passes
     * {@link #SCOPE_CHANGE_PASSWORD}. The scope is a CLAIM rather than server-side state
     * because the claim is what {@code AuthFilter} and {@code PrivilegeInterceptor} read; an
     * unclaimed rule is unenforceable prose.
     */
    public String createToken(String username, String scope) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(username)
                .claim(CLAIM_TYP, TYP_ACCESS)
                .claim(CLAIM_SCOPE, scope)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(accessTtl)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * A refresh token: {@code typ=refresh} plus a {@code jti}. The {@code jti} is the handle
     * {@code AUTH_REFRESH_TOKEN} stores, and without it rotation, logout and revocation are
     * all impossible.
     */
    public String createRefreshToken(String username, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(username)
                .setId(jti)
                .claim(CLAIM_TYP, TYP_REFRESH)
                .claim(CLAIM_SCOPE, SCOPE_FULL)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(refreshTtl)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Verifies the signature and expiry, then enforces {@code typ}, and returns the CLAIMS.
     *
     * <p>Returning {@link Claims} rather than a bare username is not a convenience: a
     * {@code String} return discards {@code typ} and {@code scope}, leaving the caller
     * nothing with which to enforce the forced-rotation gate.
     *
     * <p>There is deliberately NO single-argument overload. A type-blind {@code validate}
     * would accept a 24-hour refresh token wherever an access token is expected, making the
     * short access expiry decorative and handing the longest-lived credential the most
     * privilege.
     *
     * @param expectedTyp {@link #TYP_ACCESS} or {@link #TYP_REFRESH}; required
     * @throws JwtExpiredException if the token is past its expiry
     * @throws JwtInvalidException if the signature is wrong, the token is malformed, or its
     *     {@code typ} is not {@code expectedTyp}
     */
    public Claims validate(String token, String expectedTyp) {
        if (expectedTyp == null || expectedTyp.isBlank()) {
            throw new IllegalArgumentException(
                    "expectedTyp is required; there is no type-blind validate().");
        }
        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            throw new JwtExpiredException("Token has expired.", e);
        } catch (JwtException | IllegalArgumentException e) {
            // Deliberately generic: the reason a token failed (bad signature vs. malformed)
            // is useful to an attacker and to nobody else.
            throw new JwtInvalidException("Token is not valid.", e);
        }

        String actualTyp = claims.get(CLAIM_TYP, String.class);
        if (!expectedTyp.equals(actualTyp)) {
            throw new JwtInvalidException(
                    "Expected a '" + expectedTyp + "' token but got '" + actualTyp + "'.");
        }
        return claims;
    }

    /** Base for every token rejection, so callers can fail closed on one catch. */
    public static class JwtInvalidException extends RuntimeException {

        public JwtInvalidException(String message) {
            super(message);
        }

        public JwtInvalidException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Expiry is separated from invalidity because it is the one rejection the client can act
     * on: it means "refresh", not "log in again".
     */
    public static class JwtExpiredException extends JwtInvalidException {

        public JwtExpiredException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
