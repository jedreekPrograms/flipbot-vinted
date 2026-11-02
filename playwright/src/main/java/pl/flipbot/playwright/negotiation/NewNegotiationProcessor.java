package pl.flipbot.playwright.negotiation;

import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.negotiation.first.*;
import pl.flipbot.playwright.negotiation.first.AdaptiveFirstOfferAction;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.ListingTargetMatcher;

@Slf4j
public final class NewNegotiationProcessor {
    private static final int PREPARATION_FALLBACK_CANDIDATES = 5;
    private final BotContext context;
    private final ListingClient listingClient;
    private final FirstOfferCandidates candidates;
    private final FirstOfferTargetVerifier verifier;
    private final FirstOfferWorkflow workflow;
    private final boolean realOffersEnabled;
    private final int maxRealOffersPerRun;

    public NewNegotiationProcessor(BotContext context, ListingClient listingClient, OfferQuotaClient quota,
                                   ListingStatusUpdater statuses, boolean enabled, int maxOffers) {
        this(context, listingClient, quota, statuses, enabled, maxOffers,
                new CatalogDetailInspectionBudget(FirstOfferCandidates.DETAIL_LIMIT_PER_CYCLE
                        + FirstOfferTargetVerifier.MAX_CANDIDATES_PER_CYCLE));
    }

    public NewNegotiationProcessor(BotContext context, ListingClient listingClient, OfferQuotaClient quota,
                                   ListingStatusUpdater statuses, boolean enabled, int maxOffers,
                                   CatalogDetailInspectionBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("Catalog detail inspection budget is required.");
        }
        this.context = context;
        this.listingClient = listingClient;
        this.realOffersEnabled = enabled;
        this.maxRealOffersPerRun = maxOffers;
        ListingTargetMatcher matcher = new ListingTargetMatcher();
        ListingDetailTargetInspector inspector = new ListingDetailTargetInspector(context, matcher);
        this.candidates = new FirstOfferCandidates(context, statuses, matcher, inspector, budget);
        this.verifier = new FirstOfferTargetVerifier(context, statuses, inspector, budget);
        this.workflow = new FirstOfferWorkflow(new AdaptiveFirstOfferAction(context),
                new FirstOfferActionGuardCoordinator(), quota, statuses);
    }

    public void process(List<ListingResponseDto> priceEligibleListings, Set<String> currentScanListingIds) {
        if (priceEligibleListings == null || priceEligibleListings.isEmpty()) {
            log.info("[REAL OFFER] There are no price-eligible listings to process.");
            return;
        }

        Set<String> currentScanIds = currentScanListingIds == null
                ? Set.of()
                : Set.copyOf(currentScanListingIds);

        BotConfigurationDto configuration = context.getBot().getConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }

        List<ListingResponseDto> targetEligibleListings = candidates.select(
                priceEligibleListings,
                configuration,
                currentScanIds
        );

        if (targetEligibleListings.isEmpty()) {
            log.warn(
                    "[TARGET MATCHER] None of the {} price-eligible DISCOVERED candidates matches the configured target. No quota will be reserved and no negotiation will be started.",
                    priceEligibleListings.size()
            );
            return;
        }

        Long botId = context.getBot().getId();
        int allowedNewNegotiations = listingClient.getAllowedNewNegotiations(botId);

        if (allowedNewNegotiations <= 0) {
            log.info("[REAL OFFER] Bot {} cannot start any new negotiations.", botId);
            return;
        }

        if (!realOffersEnabled) {
            processDryRun(targetEligibleListings, configuration, currentScanIds, allowedNewNegotiations);
            return;
        }

        processRealOffers(
                targetEligibleListings,
                configuration,
                currentScanIds,
                botId,
                allowedNewNegotiations
        );
    }

    private void processDryRun(
            List<ListingResponseDto> targetEligibleListings,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds,
            int allowedNewNegotiations
    ) {
        int desiredVerified = Math.min(targetEligibleListings.size(), allowedNewNegotiations);

        FirstOfferTargetVerifier.FinalVerificationResult finalVerification = verifier.verify(
                targetEligibleListings,
                configuration,
                currentScanListingIds,
                desiredVerified
        );

        log.warn(
                "[REAL OFFER DRY RUN] Real offers are disabled. {} target-eligible DISCOVERED candidates were found. {} candidate(s) were checked by final target verification. {} passed, {} failed target verification, {} could not be verified. Backend allows {} new negotiations. No quota was reserved and no offer was sent.",
                targetEligibleListings.size(),
                finalVerification.checked(),
                finalVerification.verifiedListings().size(),
                finalVerification.mismatches(),
                finalVerification.failures(),
                allowedNewNegotiations
        );
    }

    private void processRealOffers(
            List<ListingResponseDto> targetEligibleListings,
            BotConfigurationDto configuration,
            Set<String> currentScanListingIds,
            Long botId,
            int allowedNewNegotiations
    ) {
        int maximumOffersThisRun = Math.min(allowedNewNegotiations, maxRealOffersPerRun);

        if (maximumOffersThisRun <= 0) {
            log.warn(
                    "[REAL OFFER] Real offers are enabled, but maxRealOffersPerRun={} prevents starting any offer.",
                    maxRealOffersPerRun
            );
            return;
        }

        int desiredVerifiedCandidates = Math.min(
                targetEligibleListings.size(),
                Math.min(FirstOfferTargetVerifier.MAX_CANDIDATES_PER_CYCLE, maximumOffersThisRun + PREPARATION_FALLBACK_CANDIDATES)
        );

        log.warn(
                "[REAL OFFER] Real offers are enabled. Bot {} has {} target-eligible DISCOVERED candidates. Backend allows {} new negotiations. This run is limited to {} real offer(s). Final target verification will inspect up to {} candidates until {} verified candidate(s) are found ({} capacity + up to {} preparation fallbacks). Quota is reserved only after the form is fully prepared and submit is ready.",
                botId,
                targetEligibleListings.size(),
                allowedNewNegotiations,
                maximumOffersThisRun,
                FirstOfferTargetVerifier.MAX_CANDIDATES_PER_CYCLE,
                desiredVerifiedCandidates,
                maximumOffersThisRun,
                PREPARATION_FALLBACK_CANDIDATES
        );

        FirstOfferTargetVerifier.FinalVerificationResult finalVerification = verifier.verify(
                targetEligibleListings,
                configuration,
                currentScanListingIds,
                desiredVerifiedCandidates
        );

        List<ListingResponseDto> finalVerifiedListings = finalVerification.verifiedListings();

        if (finalVerifiedListings.isEmpty()) {
            log.warn(
                    "[REAL OFFER] No candidate passed mandatory final target verification. No quota will be reserved and no offer will be sent."
            );
            return;
        }

        log.info(
                "[REAL OFFER] {} candidate(s) passed mandatory final target verification. They may be tried in order until {} real negotiation(s) are started.",
                finalVerifiedListings.size(),
                maximumOffersThisRun
        );

        int startedNegotiations = 0;

        for (ListingResponseDto listing : finalVerifiedListings) {
            if (startedNegotiations >= maximumOffersThisRun) {
                break;
            }

            FirstOfferWorkflow.Outcome outcome = workflow.execute(botId, listing);
            if (outcome == FirstOfferWorkflow.Outcome.QUOTA_EXHAUSTED) {
                return;
            }
            if (outcome == FirstOfferWorkflow.Outcome.STARTED) {
                startedNegotiations++;
            }
        }

        log.info(
                "[REAL OFFER] Finished real-offer processing. Started {} negotiation(s).",
                startedNegotiations
        );
    }
}
