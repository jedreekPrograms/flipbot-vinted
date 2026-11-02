package pl.flipbot.playwright.negotiation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;

/** Shared closure policy for active conversations and late-response recovery. */
@Slf4j
@RequiredArgsConstructor
final class NegotiationContactGuard {
    private final BotContext context;
    private final ListingStatusUpdater statuses;
    private final ConversationContactAvailabilityDetector detector;
    private final ConsecutiveContactUnavailableTracker tracker;

    boolean shouldSkip(ListingResponseDto listing) {
        ConversationContactAssessment contact = detector.inspect(listing);
        return switch (contact.state()) {
            case AVAILABLE -> {
                clear(listing);
                yield false;
            }
            case INSPECTION_UNAVAILABLE -> {
                log.warn("[CONTACT AVAILABILITY] Deferring listing {} without counting a contact failure: {}",
                        listing.listingId(), contact.reason());
                yield true;
            }
            case CONFIRMED_UNAVAILABLE -> {
                statuses.markContactUnavailable(listing);
                clear(listing);
                log.warn("[CONTACT AVAILABILITY] Listing {} changed to CONTACT_UNAVAILABLE because Vinted explicitly disabled contact. Reason: {}",
                        listing.listingId(), contact.reason());
                yield true;
            }
            case OFFER_ACTION_UNAVAILABLE, SUSPECTED_UNAVAILABLE -> {
                int observations = tracker.recordSuspected(context.getBot().getId(), listing.listingId());
                if (tracker.shouldClose(observations)) {
                    statuses.markNegotiationUnavailable(listing);
                    clear(listing);
                    log.warn("[CONTACT AVAILABILITY] Listing {} changed to UNAVAILABLE after {} consecutive loaded-conversation checks without both usable offer and message controls. This does not identify who blocked whom. Last observation: {}",
                            listing.listingId(), observations, contact.reason());
                } else {
                    log.warn("[CONTACT AVAILABILITY] Deferring listing {} because negotiation/contact controls are incomplete. Observation count={}/{}. No quota or offer will be attempted. Last observation: {}",
                            listing.listingId(), observations,
                            ConsecutiveContactUnavailableTracker.REQUIRED_CONSECUTIVE_SUSPECTED_CHECKS,
                            contact.reason());
                }
                yield true;
            }
        };
    }

    void clear(ListingResponseDto listing) {
        tracker.clear(context.getBot().getId(), listing.listingId());
    }
}
