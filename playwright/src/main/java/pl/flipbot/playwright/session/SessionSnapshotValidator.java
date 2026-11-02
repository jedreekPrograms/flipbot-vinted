package pl.flipbot.playwright.session;

import com.fasterxml.jackson.databind.JsonNode;

final class SessionSnapshotValidator {

    private SessionSnapshotValidator() {
    }

    static void validateShape(Long botId, JsonNode root) {
        if (root == null
                || !root.isObject()
                || !root.path("cookies").isArray()
                || !root.path("origins").isArray()) {
            throw new IllegalStateException(
                    "Playwright produced an invalid storageState JSON for bot "
                            + botId
                            + "; refusing to replace the active session."
            );
        }
    }

    static void validateDoesNotLoseEstablishedCookies(
            Long botId,
            JsonNode active,
            JsonNode staged
    ) {
        if (active == null || staged == null) {
            return;
        }

        JsonNode activeCookies = active.path("cookies");
        JsonNode stagedCookies = staged.path("cookies");

        if (!activeCookies.isArray() || !stagedCookies.isArray()) {
            return;
        }

        if (!activeCookies.isEmpty() && stagedCookies.isEmpty()) {
            throw new IllegalStateException(
                    "Playwright produced a cookie-empty session state for bot "
                            + botId
                            + " while the active session still contains cookies; refusing to replace the last-known-good session."
            );
        }

        // An old authenticated header may remain visible after the browser
        // loses its tokens. Analytics/consent cookies do not make that state a
        // replacement for the authenticated checkpoint.
        for (String token : new String[]{"access_token_web", "refresh_token_web"}) {
            if (containsVintedToken(activeCookies, token) && !containsVintedToken(stagedCookies, token)) {
                throw new IllegalStateException("Playwright session state for bot " + botId
                        + " lost established Vinted " + token
                        + "; refusing to replace the last-known-good session.");
            }
        }
    }

    private static boolean containsVintedToken(JsonNode cookies, String name) {
        for (JsonNode cookie : cookies) {
            String domain = cookie.path("domain").asText("").toLowerCase(java.util.Locale.ROOT);
            if (domain.startsWith(".")) {
                domain = domain.substring(1);
            }
            if (name.equals(cookie.path("name").asText())
                    && !cookie.path("value").asText("").isBlank()
                    && ("vinted.pl".equals(domain) || domain.endsWith(".vinted.pl"))) {
                return true;
            }
        }
        return false;
    }
}
