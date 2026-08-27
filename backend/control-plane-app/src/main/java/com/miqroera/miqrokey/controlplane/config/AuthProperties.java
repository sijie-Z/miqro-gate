package com.miqroera.miqrokey.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "miqrokey")
public class AuthProperties {

    /** Session cookie name. */
    private String sessionCookieName = "MIQROKEY_SESSION";

    /** CSRF cookie name. */
    private String csrfCookieName = "MIQROKEY_CSRF";

    /** Session idle timeout (default 30 min). */
    private Duration sessionIdleTimeout = Duration.ofMinutes(30);

    /** Session absolute timeout (default 12 hours). */
    private Duration sessionAbsoluteTimeout = Duration.ofHours(12);

    /** Maximum login failures before temporary lockout. */
    private int loginMaxFailures = 5;

    /** Base lockout duration for the first lockout event. */
    private Duration loginLockBase = Duration.ofMinutes(1);

    /** Path to bootstrap secret file (only needed for first admin creation). */
    private String bootstrapSecretFile;

    /**
     * Whether to set the {@code Secure} flag on cookies. Derived from
     * {@code miqrokey.production} and the active Spring profiles at startup.
     * Manually setting this to {@code false} when production mode is active will
     * cause a startup failure.
     */
    private boolean cookieSecure = false;

    /**
     * Production mode. When true, insecure cookies cause startup failure, Origin
     * header is strictly required (no localhost fallback), and every allowlist
     * origin must be a bare https://host[:port] with no
     * path/query/fragment/userinfo.
     */
    private boolean production = false;

    /**
     * Explicit Origin allowlist (scheme://host:port). Only checked when production
     * is true or an Origin header is present. Default includes localhost for
     * development.
     */
    private List<String> originAllowlist = List.of("http://localhost:5173", "http://localhost:8080",
            "http://127.0.0.1:5173", "http://127.0.0.1:8080");

    /**
     * Public base URL of the gateway, shown to users as their Virtual Key's
     * {@code baseUrl} (e.g. {@code https://gateway.example.internal}).
     */
    private String gatewayBaseUrl = "http://localhost:8081";

    /**
     * Grace period during which a rotated Virtual Key remains routable
     * ({@code PT0S} = the old key stops working as soon as the gateway snapshot
     * refreshes). Configure a positive duration for a rotation grace window.
     */
    private Duration virtualKeyRotateGrace = Duration.ZERO;

    /**
     * Grace period during which a rotated or disabled credential version remains
     * decryptable ({@code PT0S} = the old version stops being the active secret as
     * soon as the gateway snapshot refreshes). In-flight requests that already
     * decrypted the old secret always complete regardless of this value.
     */
    private Duration credentialDrainGrace = Duration.ZERO;

    public String getSessionCookieName() {
        return sessionCookieName;
    }
    public void setSessionCookieName(String v) {
        this.sessionCookieName = v;
    }
    public String getCsrfCookieName() {
        return csrfCookieName;
    }
    public void setCsrfCookieName(String v) {
        this.csrfCookieName = v;
    }
    public Duration getSessionIdleTimeout() {
        return sessionIdleTimeout;
    }
    public void setSessionIdleTimeout(Duration v) {
        this.sessionIdleTimeout = v;
    }
    public Duration getSessionAbsoluteTimeout() {
        return sessionAbsoluteTimeout;
    }
    public void setSessionAbsoluteTimeout(Duration v) {
        this.sessionAbsoluteTimeout = v;
    }
    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }
    public void setLoginMaxFailures(int v) {
        this.loginMaxFailures = v;
    }
    public Duration getLoginLockBase() {
        return loginLockBase;
    }
    public void setLoginLockBase(Duration v) {
        this.loginLockBase = v;
    }
    public String getBootstrapSecretFile() {
        return bootstrapSecretFile;
    }
    public void setBootstrapSecretFile(String v) {
        this.bootstrapSecretFile = v;
    }
    public boolean isCookieSecure() {
        return cookieSecure;
    }
    public void setCookieSecure(boolean v) {
        this.cookieSecure = v;
    }
    public boolean isProduction() {
        return production;
    }
    public void setProduction(boolean v) {
        this.production = v;
    }
    public List<String> getOriginAllowlist() {
        return originAllowlist;
    }
    public void setOriginAllowlist(List<String> v) {
        this.originAllowlist = v;
    }
    public String getGatewayBaseUrl() {
        return gatewayBaseUrl;
    }
    public void setGatewayBaseUrl(String v) {
        this.gatewayBaseUrl = v;
    }
    public Duration getVirtualKeyRotateGrace() {
        return virtualKeyRotateGrace;
    }
    public void setVirtualKeyRotateGrace(Duration v) {
        this.virtualKeyRotateGrace = v;
    }
    public Duration getCredentialDrainGrace() {
        return credentialDrainGrace;
    }
    public void setCredentialDrainGrace(Duration v) {
        this.credentialDrainGrace = v;
    }
}
