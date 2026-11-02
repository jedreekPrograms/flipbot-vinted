package pl.flipbot.playwright.marketstats;

import pl.flipbot.playwright.target.VintedRateLimitException;
import pl.flipbot.playwright.target.VintedSessionBlockedException;

final class MarketStatsTrafficBackoff {

    private MarketStatsTrafficBackoff() {
    }

    static boolean isRequired(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof VintedSessionBlockedException
                    || current instanceof VintedRateLimitException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null
                    && message.contains(MarketListingPublishedAtResolver.TRAFFIC_BACKOFF_MARKER)) {
                return true;
            }
        }
        return false;
    }
}
