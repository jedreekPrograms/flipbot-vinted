package pl.flipbot.playwright.marketstats;

import com.microsoft.playwright.TimeoutError;
import org.junit.Test;
import pl.flipbot.playwright.target.VintedHumanVerificationRequiredException;
import pl.flipbot.playwright.target.VintedRateLimitException;
import pl.flipbot.playwright.target.VintedSessionBlockedException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarketStatsTrafficBackoffTest {

    @Test
    public void positiveBlockAndChallengeEvidenceStopsTheWholePass() {
        assertTrue(MarketStatsTrafficBackoff.isRequired(new VintedSessionBlockedException("blocked")));
        assertTrue(MarketStatsTrafficBackoff.isRequired(
                new VintedHumanVerificationRequiredException("visible CAPTCHA")));
    }

    @Test
    public void wrappedRateLimitStillStopsTheWholePass() {
        assertTrue(MarketStatsTrafficBackoff.isRequired(new IllegalStateException(
                "filter failed", new VintedRateLimitException("429"))));
    }

    @Test
    public void publicationResolverBackoffRemainsSupported() {
        assertTrue(MarketStatsTrafficBackoff.isRequired(new IllegalStateException(
                "detail failed", new IllegalStateException(
                MarketListingPublishedAtResolver.TRAFFIC_BACKOFF_MARKER + ": 429"))));
    }

    @Test
    public void missingSelectorsAndGenericTimeoutsAreNotClassifiedAsBlocks() {
        assertFalse(MarketStatsTrafficBackoff.isRequired(new TimeoutError("Timeout 10000ms")));
        assertFalse(MarketStatsTrafficBackoff.isRequired(new IllegalStateException("category missing")));
        assertFalse(MarketStatsTrafficBackoff.isRequired(null));
    }
}
