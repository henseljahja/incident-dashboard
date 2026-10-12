package id.co.yokke.incidentdashboard.domain.config.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code incident.jwt} configuration block.
 *
 * <p><b>{@code secret} has no default, and must not acquire one.</b> A shipped fallback is
 * how signing secrets reach source control: the sibling {@code yob} service hardcodes its key
 * in {@code JWTUtil.getSignKey()}, which means every deployment of it — and everyone who has
 * ever read the repository — shares one forgeable key. {@link JwtService} refuses to
 * construct without a configured value; see its constructor.
 *
 * <p>It is deliberately NOT declared in {@code application.yml} either. Spring's relaxed
 * binding maps the environment variable {@code INCIDENT_JWT_SECRET} onto
 * {@code incident.jwt.secret} with no configuration entry at all, so the only way to supply
 * it is out-of-band — which is the intent.
 */
@ConfigurationProperties(prefix = "incident.jwt")
public class SecurityProps {

    /** HS256 signing secret. No default. Must be at least 32 bytes. */
    private String secret;

    /**
     * Access-token lifetime. Short on purpose: it is the credential presented on every call,
     * so it is the one most likely to leak through a log, a proxy or a browser history.
     */
    private Duration accessTtl = Duration.ofHours(1);

    /**
     * Refresh-token lifetime. Long, and therefore only ever accepted at
     * {@code /auth/refresh-token} — see {@code JwtService.validate(token, expectedTyp)}.
     */
    private Duration refreshTtl = Duration.ofHours(24);

    /**
     * Whether the refresh cookie carries {@code Secure}. Defaults to {@code true}, and every
     * real deployment must leave it there — {@code Secure} is what keeps the 24h refresh token
     * off the wire in plaintext.
     *
     * <p>It exists as a knob only because a browser silently DISCARDS a {@code Secure} cookie
     * sent from a non-HTTPS origin, with one exception: {@code localhost}, which every browser
     * treats as a trustworthy origin. So a stack served over plain HTTP from an IP or hostname
     * accepts the login, drops the cookie, and then fails every {@code /auth/refresh} with a
     * 401 — the session dies on the first reload and the silent refresh-on-401 never fires.
     * Nothing in the response says "cookie rejected", and testing on {@code localhost} cannot
     * reproduce it. Found exactly that way on the 172.16.54.213 demo stack, 2026-08-26.
     *
     * <p>Setting this false puts a bearer credential in cleartext on the network. It is
     * defensible only on an isolated internal host that cannot get TLS, and the fix is TLS.
     */
    private boolean cookieSecure = true;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
