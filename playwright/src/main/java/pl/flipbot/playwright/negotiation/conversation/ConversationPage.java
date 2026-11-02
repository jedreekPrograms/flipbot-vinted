package pl.flipbot.playwright.negotiation.conversation;

import com.microsoft.playwright.Page;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.negotiation.ConversationIdentityCoordinator;
import pl.flipbot.playwright.negotiation.ConversationIdentityResolver;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

@Slf4j
@RequiredArgsConstructor
final class ConversationPage {
    private final BotContext context;
    private final HumanVerificationHandler humanVerificationHandler;

    ListingResponseDto openActive(ListingResponseDto listing) {
        Objects.requireNonNull(listing, "Listing cannot be null");
        validateListing(listing);
        open(listing);
        return validateOpenedConversation(context.getPage(), listing);
    }

    void openRecovery(ListingResponseDto listing) {
        Objects.requireNonNull(listing, "Listing cannot be null");
        validateRecoveryListing(listing);
        open(listing);
        verifyOpenedConversationReadOnly(context.getPage(), listing);
    }

    private void open(ListingResponseDto listing) {
        log.info("[CONVERSATION] Opening conversation {} for backend listing {}, marketplace listing {}.",
                listing.conversationId(), listing.id(), listing.listingId());
        new MarketplaceNavigator(context).goToTrustedVintedUrl(listing.conversationUrl());
        humanVerificationHandler.waitUntilVerified(context.getPage());
    }

    private static void requireBackendId(ListingResponseDto listing) {
        if (listing.id() == null) throw new IllegalArgumentException("Backend listing ID cannot be null");
    }

    private static void requireAddress(ListingResponseDto listing, String label) {
        if (listing.conversationId() == null || listing.conversationId().isBlank()) {
            throw new IllegalArgumentException(label + " " + listing.id() + " has no conversation ID");
        }
        if (listing.conversationUrl() == null || listing.conversationUrl().isBlank()) {
            throw new IllegalArgumentException(label + " " + listing.id() + " has no conversation URL");
        }
    }

    private void verifyOpenedConversationReadOnly(Page page, ListingResponseDto listing) {
        VintedAuthenticationRequiredException.throwIfRedirected(page, "verifying a recovered conversation");
        ConversationIdentityResolver.ConversationIdentityAssessment assessment =
                new ConversationIdentityResolver().assess(listing.conversationId(), page.url());

        if (!assessment.matchesExpectedConversation()) {
            throw new IllegalStateException(
                    "Recovery inspection belongs to an unexpected conversation. Expected: "
                            + listing.conversationId()
                            + ", actual: "
                            + assessment.actualConversationId()
                            + ", URL: "
                            + page.url()
            );
        }

        if (assessment.canonicalRedirect()) {
            /*
             * Terminal recovery is intentionally read-only until business
             * evidence justifies a state transition. Do not mutate the stored
             * conversation identity merely because Vinted redirected an old
             * route.
             */
            log.info(
                    "[NEGOTIATION RECOVERY] Vinted canonicalized terminal conversation {} to {} for listing {}. Read-only inspection will continue without persisting the route.",
                    listing.conversationId(),
                    assessment.actualConversationId(),
                    listing.listingId()
            );
        }
    }

    private ListingResponseDto validateOpenedConversation(Page page, ListingResponseDto listing) {
        VintedAuthenticationRequiredException.throwIfRedirected(page, "verifying an active conversation");
        ListingResponseDto canonicalListing =
                new ConversationIdentityCoordinator(context)
                        .verifyAndCanonicalize(listing, "Opened conversation");

        log.info("[CONVERSATION] Opened expected conversation {}", canonicalListing.conversationId());

        return canonicalListing;
    }

    private void validateRecoveryListing(ListingResponseDto listing) {
        requireBackendId(listing);

        if (!"REJECTED".equals(listing.status())
                && !"EXPIRED".equals(listing.status())) {
            throw new IllegalArgumentException(
                    "Recovery inspection only supports recent technical terminal listings. Backend listing: "
                            + listing.id()
                            + ", current status: "
                            + listing.status()
            );
        }

        if (listing.currentStep() == null || listing.currentStep() <= 0) {
            throw new IllegalArgumentException(
                    "Terminal negotiation "
                            + listing.id()
                            + " has an invalid current step: "
                            + listing.currentStep()
            );
        }

        requireAddress(listing, "Terminal negotiation");
    }

    private void validateListing(ListingResponseDto listing) {
        requireBackendId(listing);

        if (!"NEGOTIATING".equals(listing.status())) {
            throw new IllegalArgumentException(
                    "Conversation can only be inspected for a NEGOTIATING "
                            + "listing. Backend listing: "
                            + listing.id()
                            + ", current status: "
                            + listing.status()
            );
        }

        requireAddress(listing, "Negotiating listing");
    }
}
