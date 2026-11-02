package pl.flipbot.playwright.negotiation.conversation;

import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.negotiation.NegotiationConversationSnapshot;
import pl.flipbot.playwright.privatecore.PrivateVintedCoreBridge;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

/** Public orchestration; conversation DOM inspection remains in the private core. */
@RequiredArgsConstructor
final class ConversationSnapshotReader {
    private final HumanVerificationHandler verification;

    NegotiationConversationSnapshot read(Page page, ListingResponseDto listing) {
        checkAuthentication(page);
        NegotiationConversationSnapshot snapshot = PrivateVintedCoreBridge.inspectConversation(
                page,
                listing.listingId(),
                listing.conversationId(),
                listing.originalPrice(),
                () -> {
                    checkAuthentication(page);
                    verification.waitUntilVerified(page);
                }
        );
        checkAuthentication(page);
        return snapshot;
    }

    private void checkAuthentication(Page page) {
        VintedAuthenticationRequiredException.throwIfRedirected(page, "reading a negotiation conversation");
    }
}
