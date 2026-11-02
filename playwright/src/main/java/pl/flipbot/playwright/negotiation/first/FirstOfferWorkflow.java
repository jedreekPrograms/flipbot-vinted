package pl.flipbot.playwright.negotiation.first;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.api.quota.dto.OfferQuotaReservationResponseDto;
import pl.flipbot.playwright.negotiation.FirstOfferActionGuardCoordinator;
import pl.flipbot.playwright.negotiation.NegotiationPreparationResult;
import pl.flipbot.playwright.negotiation.NegotiationStartResult;
import pl.flipbot.playwright.target.VintedRateLimitException;

import static pl.flipbot.playwright.util.FailureMessages.summary;

/** One candidate's prepare/guard/quota/submit lifecycle; no second submit on ambiguous delivery. */
@Slf4j
@RequiredArgsConstructor
public final class FirstOfferWorkflow {
    private final FirstOfferAction action;
    private final FirstOfferActionGuardCoordinator guard;
    private final OfferQuotaClient quota;
    private final ListingStatusUpdater statuses;

    public enum Outcome { STARTED, SKIPPED, QUOTA_EXHAUSTED }

    public Outcome execute(Long botId, ListingResponseDto listing) {
        if (!prepare(botId, listing) || !readyBeforeQuota(listing)) {
            return Outcome.SKIPPED;
        }
        var requestId = guard.acquire(botId, listing);
        if (requestId == null) {
            action.cancelPreparedOfferSafely();
            log.warn("[REAL OFFER] FIRST_OFFER guard refused marketplace listing {}. Trying the next verified "
                    + "candidate without reserving quota or submitting.", listing.listingId());
            return Outcome.SKIPPED;
        }

        OfferQuotaReservationResponseDto reservation;
        try {
            reservation = quota.reserveSlot(botId, requestId);
        } catch (Exception failure) {
            guard.releaseBeforeSubmitSafely(botId, listing, requestId, "quota reservation failed before real submit");
            action.cancelPreparedOfferSafely();
            throw failure;
        }
        if (!reservation.reserved()) {
            guard.releaseBeforeSubmitSafely(botId, listing, requestId, "daily quota was not reserved");
            action.cancelPreparedOfferSafely();
            log.warn("[REAL OFFER] Daily offer quota exhausted for bot {}. Used: {}/{}, remaining: {}.",
                    botId, reservation.used(), reservation.limit(), reservation.remaining());
            return Outcome.QUOTA_EXHAUSTED;
        }

        try {
            NegotiationStartResult result = action.submitPreparedFirstNegotiation(listing);
            if (result != NegotiationStartResult.STARTED) {
                throw new IllegalStateException("Unexpected negotiation start result after prepared submit: " + result);
            }
            guard.releaseAfterConfirmedSuccessBestEffort(botId, listing, requestId);
            log.warn("[REAL OFFER] Real negotiation STARTED for marketplace listing {}. Daily quota used: {}/{}, remaining: {}.",
                    listing.listingId(), reservation.used(), reservation.limit(), reservation.remaining());
            return Outcome.STARTED;
        } catch (Exception failure) {
            if (action.reconcilePreparedFirstNegotiationAfterAmbiguousSubmit(listing)) {
                guard.releaseAfterConfirmedSuccessBestEffort(botId, listing, requestId);
                log.warn("[REAL OFFER] Post-submit failure for listing {} was reconciled from strong Vinted "
                                + "conversation evidence. The backend is NEGOTIATING. Original error: {}",
                        listing.listingId(), summary(failure));
                return Outcome.STARTED;
            }
            log.error("[REAL OFFER] Unproven delivery after quota reservation for marketplace listing {}: {}. "
                            + "Quota and persisted FIRST_OFFER guard remain reserved to prevent a duplicate offer.",
                    listing.listingId(), summary(failure));
            log.trace("[REAL OFFER] Full post-reservation submission error for marketplace listing {}.", listing.listingId(), failure);
            throw failure;
        }
    }

    private boolean prepare(Long botId, ListingResponseDto listing) {
        NegotiationPreparationResult result;
        try {
            result = action.prepareFirstOffer(listing);
        } catch (VintedRateLimitException failure) {
            throw failure;
        } catch (Exception failure) {
            log.error("[REAL OFFER PREPARE] Failed before quota reservation for marketplace listing {}: {}. "
                    + "Trying the next verified candidate.", listing.listingId(), summary(failure));
            log.trace("[REAL OFFER PREPARE] Full preparation error for marketplace listing {}.", listing.listingId(), failure);
            action.cancelPreparedOfferSafely();
            return false;
        }
        if (result == NegotiationPreparationResult.PREPARED) {
            return true;
        }
        if (result == NegotiationPreparationResult.LISTING_UNAVAILABLE) {
            statuses.markUnavailable(botId, listing);
        } else if (result == NegotiationPreparationResult.TARGET_MISMATCH) {
            statuses.markTargetMismatch(botId, listing);
        } else if (result == NegotiationPreparationResult.OFFER_TOO_LOW) {
            statuses.markOfferTooLow(botId, listing);
        } else if (result == NegotiationPreparationResult.CANNOT_NEGOTIATE) {
            statuses.markCannotNegotiate(botId, listing);
            log.warn("[REAL OFFER] Marketplace listing {} exposes no negotiation action. Trying the next "
                    + "fully verified candidate without reserving quota.", listing.listingId());
        } else {
            action.cancelPreparedOfferSafely();
            throw new IllegalStateException("Unexpected negotiation preparation result: " + result);
        }
        return false;
    }

    private boolean readyBeforeQuota(ListingResponseDto listing) {
        try {
            action.assertPreparedOfferReady(listing);
            return true;
        } catch (Exception failure) {
            log.error("[REAL OFFER PREPARE] Prepared form became invalid before quota reservation for "
                    + "marketplace listing {}: {}. Trying the next verified candidate.", listing.listingId(), summary(failure));
            log.trace("[REAL OFFER PREPARE] Full pre-quota readiness error for marketplace listing {}.", listing.listingId(), failure);
            action.cancelPreparedOfferSafely();
            return false;
        }
    }
}
