package pl.flipbot.playwright.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native cookie exclusion reasons. No interception, script, cookie writes or HTTP probes. */
@Slf4j
final class VintedCookieWireDiagnostics {
    private static final Set<String> TOKENS = Set.of("access_token_web", "refresh_token_web");
    private static final int MAX_TRACKED_REQUESTS = 256;
    private static final int MAX_EVENTS = 200;
    private final BrowserContext context;
    private final Long botId;
    private final long contextId;
    private int pages;
    private int events;

    private VintedCookieWireDiagnostics(BrowserContext context, Long botId, long contextId) {
        this.context = context;
        this.botId = botId;
        this.contextId = contextId;
    }

    static void install(BrowserContext context, Long botId, long contextId) {
        VintedCookieWireDiagnostics trace = new VintedCookieWireDiagnostics(context, botId, contextId);
        try {
            context.onPage(trace::attach);
            for (Page page : context.pages()) {
                trace.attach(page);
            }
        } catch (RuntimeException failure) {
            trace.unavailable(failure);
        }
    }

    private void attach(Page page) {
        if (pages >= 3) {
            return;
        }
        int pageId = ++pages;
        try {
            CDPSession session = context.newCDPSession(page);
            WirePage wire = new WirePage(pageId);
            session.on("Network.requestWillBeSent", event -> safely(() -> wire.target(event)));
            session.on("Network.requestWillBeSentExtraInfo", event -> safely(() -> wire.request(event)));
            session.on("Network.responseReceivedExtraInfo", event -> safely(() -> wire.response(event)));
            session.send("Network.enable");
        } catch (RuntimeException failure) {
            unavailable(failure);
        }
    }

    private void safely(Runnable read) {
        if (events >= MAX_EVENTS) {
            return;
        }
        try {
            read.run();
        } catch (RuntimeException failure) {
            unavailable(failure);
        }
    }

    private void unavailable(RuntimeException failure) {
        if (events++ < MAX_EVENTS) {
            log.info("[VINTED COOKIE WIRE UNAVAILABLE] bot={} context={} errorType={}",
                    botId, contextId, failure.getClass().getSimpleName());
        }
    }

    private final class WirePage {
        private final int pageId;
        private int nextId;
        private final Map<String, Target> targets = boundedMap();
        // ExtraInfo can arrive BEFORE requestWillBeSent. Buffer only sanitized
        // summaries, never request bodies, raw headers or token values.
        private final Map<String, List<String>> pending = boundedMap();

        private WirePage(int pageId) {
            this.pageId = pageId;
        }

        private void target(JsonObject event) {
            String id = text(event, "requestId");
            JsonObject request = object(event, "request");
            String url = text(request, "url");
            if (!MarketplaceUrls.isVintedUrl(url)) {
                return;
            }
            URI uri = URI.create(url);
            String path = uri.getPath();
            boolean document = "Document".equals(text(event, "type"));
            boolean watched = document || path.startsWith("/web/api/auth/")
                    || path.startsWith("/api/v2/users/current") || path.startsWith("/api/v2/conversations")
                    || path.startsWith("/api/v2/inbox") || path.startsWith("/crm-event-processor/")
                    || path.startsWith("/inbox-notifications/") || path.startsWith("/messaging/")
                    || path.startsWith("/svc-filters/");
            Target previous = targets.get(id);
            Target target = new Target(previous == null ? ++nextId : previous.id(), watched,
                    uri.getHost() + safeEndpoint(path, document));
            targets.put(id, target);
            if (watched) {
                emit(target, "target=" + target.endpoint() + " method=" + safeWord(text(request, "method")));
            }
            List<String> early = pending.remove(id);
            if (early != null) {
                for (String summary : early) {
                    if (watched || summary.startsWith("response ")) {
                        emit(target, summary);
                    }
                }
            }
        }

        private void request(JsonObject event) {
            List<String> associated = new ArrayList<>();
            for (JsonElement value : array(event, "associatedCookies")) {
                JsonObject item = value.getAsJsonObject();
                JsonObject cookie = object(item, "cookie");
                if (TOKENS.contains(text(cookie, "name")) && associated.size() < 12) {
                    associated.add(text(cookie, "name") + "=" + VintedSessionDiagnostics.tokenLabel(text(cookie, "value"))
                            + ";domain=" + VintedSessionDiagnostics.safeDomain(text(cookie, "domain"))
                            + ";path=" + VintedSessionDiagnostics.safePath(text(cookie, "path"))
                            + ";httpOnly=" + safeWord(text(cookie, "httpOnly"))
                            + ";secure=" + safeWord(text(cookie, "secure"))
                            + ";sameSite=" + safeWord(text(cookie, "sameSite"))
                            + ";blocked=" + reasons(array(item, "blockedReasons")));
                }
            }
            deliver(text(event, "requestId"), "request sent="
                    + VintedSessionDiagnostics.requestTokens(header(object(event, "headers"), "cookie"))
                    + " associated=" + associated, false);
        }

        private void response(JsonObject event) {
            JsonObject headers = object(event, "headers");
            List<String> changes = new ArrayList<>();
            for (String line : header(headers, "set-cookie").split("\\n")) {
                String token = VintedSessionDiagnostics.responseToken(line);
                if (token != null && changes.size() < 12) {
                    changes.add(token);
                }
            }
            List<String> blocked = new ArrayList<>();
            for (JsonElement value : array(event, "blockedCookies")) {
                JsonObject item = value.getAsJsonObject();
                String token = VintedSessionDiagnostics.responseToken(text(item, "cookieLine"));
                if (token != null && blocked.size() < 12) {
                    blocked.add(token + ";blocked=" + reasons(array(item, "blockedReasons")));
                }
            }
            String clear = header(headers, "clear-site-data");
            boolean clearsCookies = clear.contains("\"cookies\"") || clear.contains("\"*\"");
            if (!changes.isEmpty() || !blocked.isEmpty() || clearsCookies) {
                deliver(text(event, "requestId"), "response status=" + safeWord(text(event, "statusCode"))
                        + " set=" + changes + " rejected=" + blocked + " clearSiteCookies=" + clearsCookies, true);
            }
        }

        private void deliver(String id, String summary, boolean cookieChange) {
            Target target = targets.get(id);
            if (target == null) {
                List<String> waiting = pending.computeIfAbsent(id, unused -> new ArrayList<>());
                if (waiting.size() < 4) {
                    waiting.add(summary);
                }
            } else if (target.watched() || cookieChange) {
                if (cookieChange && !target.watched()) {
                    emit(target, "target=" + target.endpoint());
                }
                emit(target, summary);
            }
        }

        private void emit(Target target, String summary) {
            if (events++ < MAX_EVENTS) {
                // A redirect reuses its wire id; target and ExtraInfo events
                // have no guaranteed relative order. Do not invent hop pairing.
                log.info("[VINTED COOKIE WIRE] bot={} context={} page={} wire={} {}",
                        botId, contextId, pageId, target.id(), summary);
            }
        }
    }

    private record Target(int id, boolean watched, String endpoint) { }

    private static <K, V> Map<K, V> boundedMap() {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > MAX_TRACKED_REQUESTS;
            }
        };
    }

    private static String safeEndpoint(String path, boolean document) {
        if ("/".equals(path) || Set.of("/web/api/auth/oauth", "/web/api/auth/refresh", "/api/v2/users/current",
                "/api/v2/inbox", "/api/v2/conversations/stats", "/crm-event-processor/v1/consent_preferences",
                "/crm-event-processor/v1/braze_jwt", "/svc-filters/filters").contains(path)) {
            return path;
        }
        if (path.matches("/(?:api/v2/conversations|inbox)/[0-9]+")) {
            return path.replaceFirst("/[0-9]+$", "/:id");
        }
        return document ? "<document>" : "<other>";
    }

    private static String safeWord(String value) {
        return value.matches("[A-Za-z0-9_]{1,80}") ? value : "unavailable";
    }

    private static List<String> reasons(JsonArray values) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (result.size() < 12) {
                result.add(safeWord(value.getAsString()));
            }
        }
        return result;
    }

    private static String header(JsonObject headers, String name) {
        for (Map.Entry<String, JsonElement> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue().getAsString();
            }
        }
        return "";
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
