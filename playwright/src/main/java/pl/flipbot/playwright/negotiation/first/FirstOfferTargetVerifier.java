package pl.flipbot.playwright.negotiation.first;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.negotiation.CatalogDetailInspectionBudget;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.ListingUnavailableDuringVerificationException;
import pl.flipbot.playwright.target.VintedRateLimitException;

import static pl.flipbot.playwright.negotiation.first.FirstOfferTargetEvidence.getConfiguredTargetLabel;
import static pl.flipbot.playwright.negotiation.first.FirstOfferTargetEvidence.hasCurrentExactModelProof;
import static pl.flipbot.playwright.util.FailureMessages.summary;

@Slf4j
@RequiredArgsConstructor
public final class FirstOfferTargetVerifier {
    private static final double DETAIL_INSPECTION_PACING_MS = 1_500;

    public static final int MAX_CANDIDATES_PER_CYCLE = 20;
    private final BotContext context;
    private final ListingStatusUpdater listingStatusUpdater;
    private final ListingDetailTargetInspector listingDetailTargetInspector;
    private final CatalogDetailInspectionBudget detailInspectionBudget;

    public FinalVerificationResult verify(
            List<ListingResponseDto> targetEligibleListings,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds,
            int desiredVerifiedCount
    ) {
        int candidatesToCheck = Math.min(targetEligibleListings.size(), MAX_CANDIDATES_PER_CYCLE);

        if (candidatesToCheck <= 0) {
            return new FinalVerificationResult(List.of(), 0, 0, 0);
        }

        log.info(
                "[FINAL VERIFY] Starting mandatory final target verification. Target-eligible={}, candidatesLimit={}, desiredVerified={}. No quota has been reserved.",
                targetEligibleListings.size(),
                candidatesToCheck,
                desiredVerifiedCount
        );

        List<ListingResponseDto> verifiedListings = new ArrayList<>();
        int checked = 0;
        int mismatches = 0;
        int failures = 0;
        int realItemPageRequests = 0;
        int deferredByGlobalDetailBudget = 0;

        for (ListingResponseDto listing : targetEligibleListings) {
            if (checked >= candidatesToCheck) {
                break;
            }

            if (desiredVerifiedCount > 0 && verifiedListings.size() >= desiredVerifiedCount) {
                break;
            }

            checked++;

            boolean currentExactModelProof = hasCurrentExactModelProof(
                    listing,
                    configuration,
                    currentScanListingIds
            );

            if (currentExactModelProof) {
                log.info(
                        "[FINAL VERIFY] Candidate {}/{}. Backend listing={}, marketplace listing={}, catalog title='{}', price={}, targetMode={}, target='{}', source=CURRENT_EXACT_VINTED_MODEL_SCAN.",
                        checked,
                        candidatesToCheck,
                        listing.id(),
                        listing.listingId(),
                        listing.title(),
                        listing.originalPrice(),
                        configuration.getTargetMode(),
                        getConfiguredTargetLabel(configuration)
                );

                verifiedListings.add(listing);
                continue;
            }

            boolean cached = listingDetailTargetInspector.hasCachedFullTitle(listing.listingId());
            boolean liveItemPageRequest = !cached;

            if (liveItemPageRequest && !detailInspectionBudget.tryAcquire()) {
                deferredByGlobalDetailBudget++;
                log.info(
                        "[FINAL VERIFY] Marketplace listing {} needs a live item-page verification, but the shared catalog detail budget is exhausted ({}/{}). It remains DISCOVERED for a later catalog cycle.",
                        listing.listingId(),
                        detailInspectionBudget.used(),
                        detailInspectionBudget.limit()
                );
                continue;
            }

            if (liveItemPageRequest && realItemPageRequests > 0) {
                context.getPage().waitForTimeout(DETAIL_INSPECTION_PACING_MS);
            }

            if (liveItemPageRequest) {
                realItemPageRequests++;
            }

            String verificationSource = cached
                    ? "LIVE_ITEM_IDENTITY_CACHE"
                    : "VINTED_ITEM_PAGE";

            log.info(
                    "[FINAL VERIFY] Candidate {}/{}. Backend listing={}, marketplace listing={}, catalog title='{}', price={}, targetMode={}, target='{}', source={}.",
                    checked,
                    candidatesToCheck,
                    listing.id(),
                    listing.listingId(),
                    listing.title(),
                    listing.originalPrice(),
                    configuration.getTargetMode(),
                    getConfiguredTargetLabel(configuration),
                    verificationSource
            );

            try {
                boolean matchesTarget = listingDetailTargetInspector.matchesConfiguredTarget(
                        listing,
                        configuration
                );

                if (matchesTarget) {
                    verifiedListings.add(listing);
                    log.info(
                            "[FINAL VERIFY] Marketplace listing {} PASSED mandatory final target verification. Verified candidates: {}/{}.",
                            listing.listingId(),
                            verifiedListings.size(),
                            desiredVerifiedCount
                    );
                } else {
                    mismatches++;
                    listingStatusUpdater.markTargetMismatch(context.getBot().getId(), listing);
                    log.warn(
                            "[FINAL VERIFY] Marketplace listing {} FAILED mandatory final target verification and was persisted as SKIPPED_TARGET_MISMATCH. It will not block later backlog candidates.",
                            listing.listingId()
                    );
                }
            } catch (VintedRateLimitException exception) {
                log.warn(
                        "[RATE LIMIT] Vinted rate limit detected during mandatory final verification of marketplace listing {}. Stopping this work cycle.",
                        listing.listingId()
                );
                throw exception;
            } catch (ListingUnavailableDuringVerificationException exception) {
                listingStatusUpdater.markUnavailable(context.getBot().getId(), listing);
                log.info(
                        "[FINAL VERIFY] Marketplace listing {} became unavailable and was persisted as UNAVAILABLE. Verification continues with the next candidate.",
                        listing.listingId()
                );
            } catch (Exception exception) {
                failures++;
                log.warn(
                        "[FINAL VERIFY] Could not verify marketplace listing {}: {}. It remains DISCOVERED and will be retried in a later cycle; verification will continue with the next candidate now.",
                        listing.listingId(),
                        summary(exception)
                );
                log.trace(
                        "[FINAL VERIFY] Full verification error for marketplace listing {}.",
                        listing.listingId(),
                        exception
                );
            }
        }

        log.info(
                "[FINAL VERIFY] Finished. Checked={}, passed={}, mismatches={}, failures={}, real item-page requests={}, deferred by shared catalog detail budget={}, shared budget={}/{}.",
                checked,
                verifiedListings.size(),
                mismatches,
                failures,
                deferredByGlobalDetailBudget,
                detailInspectionBudget.used(),
                detailInspectionBudget.limit()
        );

        return new FinalVerificationResult(List.copyOf(verifiedListings), checked, mismatches, failures);
    }

    public record FinalVerificationResult(
            List<ListingResponseDto> verifiedListings,
            int checked,
            int mismatches,
            int failures
    ) {
    }
}
