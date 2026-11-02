package pl.flipbot.playwright.negotiation.first;

import pl.flipbot.playwright.negotiation.NegotiationConversationResult;
import pl.flipbot.playwright.negotiation.NegotiationConversationSnapshot;

import org.junit.Test;

import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RuntimeRegressionTest {

    @Test
    public void soldStatusPatternUsesOnlyPlaywrightSupportedFlags() {
        Pattern pattern = AdaptiveFirstOfferAction.soldStatusPattern();

        assertEquals(Pattern.CASE_INSENSITIVE, pattern.flags());
    }

    @Test
    public void unknownConversationSnapshotCanPreserveVisibleRawStatus() {
        NegotiationConversationSnapshot snapshot =
                NegotiationConversationSnapshot.unknown("Nieznany status Vinted");

        assertEquals(NegotiationConversationResult.UNKNOWN, snapshot.result());
        assertEquals("Nieznany status Vinted", snapshot.rawStatus());
        assertNull(snapshot.sellerCounterOfferPrice());
    }
}
