package pl.flipbot.playwright.negotiation.conversation;

import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.negotiation.NegotiationConversationResult;
import pl.flipbot.playwright.negotiation.NegotiationConversationSnapshot;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

@Slf4j
public final class ConversationReader {
    private final BotContext context;
    private final ConversationPage page;
    private final ConversationSnapshotReader snapshots;

    public ConversationReader(BotContext context) {
        this.context = context;
        HumanVerificationHandler verification = new HumanVerificationHandler();
        this.page = new ConversationPage(context, verification);
        this.snapshots = new ConversationSnapshotReader(verification);
    }

    public NegotiationConversationSnapshot inspectSnapshot(ListingResponseDto listing) {
        return read(page.openActive(listing));
    }

    public NegotiationConversationSnapshot inspectRecoverySnapshot(ListingResponseDto listing) {
        page.openRecovery(listing);
        return read(listing);
    }

    private NegotiationConversationSnapshot read(ListingResponseDto listing) {
        NegotiationConversationSnapshot snapshot = snapshots.read(context.getPage(), listing);
        logSnapshot(listing, snapshot);
        return snapshot;
    }

    private void logSnapshot(ListingResponseDto listing, NegotiationConversationSnapshot snapshot) {
        if (snapshot.result() == NegotiationConversationResult.SELLER_COUNTER_OFFER) {
            log.info(
                    "[CONVERSATION] Conversation {} for listing {} "
                            + "was classified as SELLER_COUNTER_OFFER. "
                            + "Seller price: {}",
                    listing.conversationId(),
                    listing.listingId(),
                    snapshot.sellerCounterOfferPrice()
            );

            return;
        }

        log.info(
                "[CONVERSATION] Conversation {} for listing {} "
                        + "was classified as {}. Raw status: {}",
                listing.conversationId(),
                listing.listingId(),
                snapshot.result(),
                snapshot.rawStatus()
        );
    }
}
