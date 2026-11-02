package pl.flipbot.playwright.session;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Passive, bounded diagnostics. Never logs credentials, token values, bodies or query strings. */
@Slf4j
public final class VintedSessionDiagnostics {
    private static final Pattern NETWORK_FAILURE_CODE = Pattern.compile("net::[A-Z0-9_]+");
    private static final byte[] TOKEN_LABEL_SALT = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    private static final AtomicLong CONTEXT_IDS = new AtomicLong();
    private static final Set<String> TOKEN_COOKIES = Set.of("access_token_web", "refresh_token_web");
    private static final Set<String> AUTH_PATHS = Set.of(
            "/web/api/auth/oauth", "/web/api/auth/refresh", "/api/v2/users/current",
            "/api/v2/inbox", "/api/v2/conversations/stats", "/messaging/real-time/subscribe",
            "/inbox-notifications/v1/notifications/unread_count",
            "/crm-event-processor/v1/braze_jwt", "/crm-event-processor/v1/consent_preferences");

    private VintedSessionDiagnostics() {
    }

    public static void install(BrowserContext context, Long botId) {
        Set<String> reported = new HashSet<>();
        AuthTrace authTrace = new AuthTrace(context, botId);
        VintedCookieWireDiagnostics.install(context, botId, authTrace.contextId);
        context.onResponse(response -> {
            try {
                String url = response.url();
                if (!MarketplaceUrls.isVintedUrl(url)) {
                    return;
                }
                authTrace.record(response);
                int status = response.status();
                boolean authDocument = response.request().isNavigationRequest()
                        && MarketplaceUrls.isAuthenticationUrl(url);
                if (status < 400 && !authDocument) {
                    return;
                }
                String path = URI.create(url).getPath();
                String key = status + " " + path;
                if (reported.size() < 20 && reported.add(key)) {
                    log.warn("[VINTED HTTP] bot={} status={} path={} authDocument={}. No response body, query or credentials recorded.",
                            botId, status, path, authDocument);
                }
            } catch (RuntimeException ignored) {
                // Diagnostics must never interrupt browser event processing.
            }
        });
        context.onRequestFailed(request -> {
            try {
                String url = request.url();
                String type = request.resourceType();
                if (!MarketplaceUrls.isVintedUrl(url)
                        || !("document".equals(type) || "fetch".equals(type) || "xhr".equals(type))) {
                    return;
                }
                String path = URI.create(url).getPath();
                String failure = networkFailureCode(request.failure());
                String key = "failed " + type + " " + path + " " + failure;
                if (reported.size() < 20 && reported.add(key)) {
                    log.warn("[VINTED REQUEST FAILED] bot={} type={} path={} failure={}. The transfer failed or was cancelled; this alone does not identify the cause.",
                            botId, type, path, failure);
                }
            } catch (RuntimeException ignored) {
                // Diagnostics must not affect normal navigation or recovery.
            }
        });
    }

    private static final class AuthTrace {
        private final BrowserContext context;
        private final Long botId;
        private final long contextId = CONTEXT_IDS.incrementAndGet();
        private int events;
        private int snapshots;
        private final Set<String> omissionSnapshots = new HashSet<>();
        private final Set<String> hostsWithTokens = new HashSet<>();

        private AuthTrace(BrowserContext context, Long botId) {
            this.context = context;
            this.botId = botId;
        }

        private void record(Response response) {
            // Independent of the warning quota: successful login and the first
            // failed refresh are both needed to reconstruct a lost session.
            if (events >= 60) {
                return;
            }
            String stage = "requestHeaders";
            try {
                Request request = response.request();
                URI uri = URI.create(response.url());
                String path = uri.getPath();
                String endpoint = AUTH_PATHS.contains(path) ? path
                        : path.matches("/api/v2/conversations/[0-9]+") ? "/api/v2/conversations/:id"
                        : request.isNavigationRequest() && "document".equals(request.resourceType())
                                ? "<document>" : null;
                if (endpoint == null || "OPTIONS".equals(request.method())) {
                    return;
                }
                // Reserve before synchronous header reads, which can dispatch
                // other Playwright events. The trace never issues HTTP requests.
                int sequence = ++events;
                String cookieHeader = request.headerValue("cookie");
                String sent = requestTokens(cookieHeader);
                boolean hasTokens = !"access=[],refresh=[]".equals(sent);
                boolean lostTokens = !hasTokens && hostsWithTokens.contains(uri.getHost());
                if (hasTokens) {
                    hostsWithTokens.add(uri.getHost());
                }
                String authorization = request.headerValue("authorization");
                String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                        ? tokenLabel(authorization.substring(7).trim()) : authorization == null ? "absent" : "other";
                stage = "responseHeaders";
                List<String> setCookies = new ArrayList<>();
                // Java RawHeaders.getAll returns null when this header is
                // absent, even though headerValues is typed as List<String>.
                List<String> cookieHeaders = response.headerValues("set-cookie");
                if (cookieHeaders != null) {
                    for (String cookie : cookieHeaders) {
                        String description = responseToken(cookie);
                        if (description != null && setCookies.size() < 8) {
                            setCookies.add(description);
                        }
                    }
                }
                String challenge = response.headerValue("www-authenticate");
                boolean invalidToken = challenge != null
                        && challenge.toLowerCase(Locale.ROOT).contains("invalid_token");
                boolean dataDomeChallenge = response.status() == 403 && response.headerValue("x-dd-b") != null;
                log.info("[VINTED AUTH TRACE] bot={} context={} event={} method={} endpoint={}{} status={} sent={} bearer={} set={} invalidToken={} dataDomeChallengeHeader={}",
                        botId, contextId, sequence, request.method(), uri.getHost(), endpoint,
                        response.status(), sent, bearer, setCookies, invalidToken, dataDomeChallenge);

                String snapshotReason = "/web/api/auth/oauth".equals(path) && response.status() == 200
                        ? "loginAccepted"
                        : lostTokens && omissionSnapshots.add(uri.getHost()) ? "tokensMissingOnPreviouslyTokenBearingHost"
                        : response.status() == 401 ? "unauthorized"
                        : "/web/api/auth/refresh".equals(path) && response.status() >= 400 ? "refreshFailed"
                        : null;
                if (snapshots < 6 && snapshotReason != null) {
                    stage = "cookieJar";
                    snapshots++;
                    List<String> jar = new ArrayList<>();
                    for (Cookie cookie : context.cookies(List.of(
                            "https://www.vinted.pl/api/v2/inbox", "https://www.vinted.pl/web/api/auth/refresh",
                            "https://api.vinted.pl/", "https://sse.vinted.pl/"))) {
                        if (TOKEN_COOKIES.contains(cookie.name) && jar.size() < 12) {
                            jar.add(cookie.name + "=" + tokenLabel(cookie.value)
                                    + ";domain=" + safeDomain(cookie.domain)
                                    + ";path=" + safePath(cookie.path)
                                    + ";expires=" + (cookie.expires == null ? "unavailable"
                                            : cookie.expires < 0 ? "session"
                                            : Instant.ofEpochSecond(cookie.expires.longValue()))
                                    + ";httpOnly=" + cookie.httpOnly + ";secure=" + cookie.secure
                                    + ";sameSite=" + cookie.sameSite);
                        }
                    }
                    log.info("[VINTED AUTH JAR] bot={} context={} event={} reason={} cookies={}",
                            botId, contextId, sequence, snapshotReason, jar);
                }
            } catch (RuntimeException failure) {
                // Diagnostic failure must not affect login, navigation or recovery.
                log.info("[VINTED AUTH TRACE UNAVAILABLE] bot={} context={} event={} stage={} errorType={}. No exception message or credentials recorded.",
                        botId, contextId, events, stage, failure.getClass().getSimpleName());
            }
        }
    }

    static String requestTokens(String header) {
        List<String> access = new ArrayList<>();
        List<String> refresh = new ArrayList<>();
        if (header != null) {
            for (String part : header.split(";")) {
                int separator = part.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String name = part.substring(0, separator).trim();
                List<String> labels = "access_token_web".equals(name) ? access
                        : "refresh_token_web".equals(name) ? refresh : null;
                if (labels != null && labels.size() < 4) {
                    labels.add(tokenLabel(part.substring(separator + 1).trim()));
                }
            }
        }
        return "access=" + access + ",refresh=" + refresh;
    }

    static String responseToken(String header) {
        String[] parts = header.split(";");
        int separator = parts[0].indexOf('=');
        if (separator < 0 || !TOKEN_COOKIES.contains(parts[0].substring(0, separator).trim())) {
            return null;
        }
        String result = parts[0].substring(0, separator).trim() + "="
                + tokenLabel(parts[0].substring(separator + 1).trim());
        for (int i = 1; i < parts.length; i++) {
            String[] attribute = parts[i].trim().split("=", 2);
            if (attribute.length != 2) {
                if ("httponly".equalsIgnoreCase(attribute[0]) || "secure".equalsIgnoreCase(attribute[0])
                        || "partitioned".equalsIgnoreCase(attribute[0])) {
                    result += ";" + attribute[0].toLowerCase(Locale.ROOT);
                }
                continue;
            }
            String value = attribute[1].trim();
            switch (attribute[0].toLowerCase(Locale.ROOT)) {
                case "domain" -> result += ";domain=" + safeDomain(value);
                case "path" -> result += ";path=" + safePath(value);
                case "max-age" -> result += ";maxAge=" + (value.matches("-?[0-9]{1,12}") ? value : "unparsed");
                case "samesite" -> result += ";sameSite=" + (Set.of("strict", "lax", "none").contains(value.toLowerCase(Locale.ROOT))
                        ? value.toLowerCase(Locale.ROOT) : "unparsed");
                case "expires" -> {
                    try {
                        result += ";expires=" + ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    } catch (RuntimeException ignored) {
                        result += ";expires=unparsed";
                    }
                }
                default -> { /* Never log arbitrary attribute values. */ }
            }
        }
        return result;
    }

    static String tokenLabel(String value) {
        if (value == null || value.isEmpty()) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(TOKEN_LABEL_SALT);
            return "id-" + HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    static String safeDomain(String value) {
        String domain = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String host = domain.replaceFirst("^\\.", "");
        return domain.length() <= 253 && domain.matches("\\.?[a-z0-9.-]+")
                && ("vinted.pl".equals(host) || host.endsWith(".vinted.pl")) ? domain : "other";
    }

    static String safePath(String value) {
        return value != null && value.matches("/[a-zA-Z0-9/_-]{0,100}") ? value : "scoped";
    }

    private static String networkFailureCode(String rawFailure) {
        Matcher match = NETWORK_FAILURE_CODE.matcher(rawFailure == null ? "" : rawFailure);
        return match.find() ? match.group() : "<unavailable>";
    }
}
