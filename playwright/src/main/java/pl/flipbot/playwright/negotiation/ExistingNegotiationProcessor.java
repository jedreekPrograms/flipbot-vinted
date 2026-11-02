package pl.flipbot.playwright.negotiation;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.NegotiationActivityClient;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.negotiation.conversation.ConversationReader;
import pl.flipbot.playwright.negotiation.next.NextOfferPreparation;
import pl.flipbot.playwright.negotiation.next.NextOfferWorkflow;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.target.VintedRateLimitException;

@Slf4j
public class ExistingNegotiationProcessor {

    private final BotContext context;
    private final ListingClient listingClient;
    private final ListingStatusUpdater listingStatusUpdater;
    private final ConversationReader conversationProcessor;
    private final ConversationAvailabilityDetector availabilityDetector;
    private final ConversationActivityDetector activityDetector;
    private final NegotiationContactGuard contactGuard;
    private final NegotiationDecisionService decisionService;
    private final PendingNegotiationPolicy pendingPolicy;
    private final NextOfferPreparation nextStepExecutor;
    private final NextOfferWorkflow preparedNextStepCoordinator;
    private final ExistingNegotiationSupport support;
    private final boolean realNextStepsEnabled;
    private final int maxRealNextStepsPerRun;

    public ExistingNegotiationProcessor(
            BotContext context,
            ListingClient listingClient,
            OfferQuotaClient offerQuotaClient,
            ListingStatusUpdater listingStatusUpdater,
            boolean realNextStepsEnabled,
            int maxRealNextStepsPerRun
    ) {
        this.context = context;
        this.listingClient = listingClient;
        this.listingStatusUpdater = listingStatusUpdater;
        this.conversationProcessor = new ConversationReader(context);
        this.availabilityDetector = new ConversationAvailabilityDetector(context);
        this.activityDetector = new ConversationActivityDetector(context);
        this.contactGuard = new NegotiationContactGuard(context, listingStatusUpdater,
                new ConversationContactAvailabilityDetector(context), new ConsecutiveContactUnavailableTracker());
        this.decisionService = new NegotiationDecisionService();
        this.pendingPolicy = new PendingNegotiationPolicy();
        this.nextStepExecutor = new NextOfferPreparation(context);
        this.preparedNextStepCoordinator = new NextOfferWorkflow(context, offerQuotaClient);
        this.support = new ExistingNegotiationSupport(
                context,
                listingClient,
                listingStatusUpdater,
                new NegotiationActivityClient()
        );
        this.realNextStepsEnabled = realNextStepsEnabled;
        this.maxRealNextStepsPerRun = maxRealNextStepsPerRun;
    }

    public boolean process() {
        Long botId = context.getBot().getId();

        /*
         * Active conversations always have priority. Terminal recovery is a
         * bounded safety net and must never delay normal negotiations.
         */
        List<ListingResponseDto> listings =
                listingClient.getNegotiatingListings(botId);

        log.info(
                "Bot {} currently has {} active negotiations",
                botId,
                listings.size()
        );

        boolean sentAny = inspectExistingNegotiations(listings);

        List<ListingResponseDto> recoveryCandidates =
                listingClient.getNegotiationRecoveryCandidates(botId);
        new TerminalConversationRecovery(context, listingClient, listingStatusUpdater,
                conversationProcessor, support, contactGuard).inspect(recoveryCandidates);

        return sentAny;
    }


    private boolean inspectExistingNegotiations(List<ListingResponseDto> listings) {
        if (listings.isEmpty()) {
            log.info("[CONVERSATION] There are no active negotiations to inspect.");
            return false;
        }

        BotConfigurationDto currentProductConfiguration =
                context.getBot().getConfiguration();
        if (currentProductConfiguration == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }

        log.info(
                "[CONVERSATION] Starting inspection of {} active negotiations.",
                listings.size()
        );

        int inspected = 0;
        int sent = 0;

        for (ListingResponseDto listing : listings) {
            BotConfigurationDto configuration =
                    NegotiationStrategyConfigurationResolver.resolve(
                            currentProductConfiguration,
                            listing.negotiationStrategySnapshot()
                    );
            context.getBot().setConfiguration(configuration);

            try {
                inspected++;
                log.info(
                        "[CONVERSATION] Inspecting negotiation {}/{}. Backend listing {}, marketplace listing {}, conversation {}, current step {}, strategy v{}",
                        inspected,
                        listings.size(),
                        listing.id(),
                        listing.listingId(),
                        listing.conversationId(),
                        listing.currentStep(),
                        listing.negotiationStrategyVersion()
                );

                if (!support.matchesConfiguredTarget(listing, configuration)) {
                    ListingResponseDto finished = support.finishWrongTargetNegotiation(listing);
                    clearContactUnavailableSuspicion(listing);
                    log.error(
                            "[TARGET GUARD] Listing {} stopped as wrong target. Status={}. No additional offer was sent.",
                            listing.listingId(),
                            finished.status()
                    );
                    continue;
                }

                NegotiationConversationSnapshot snapshot = conversationProcessor.inspectSnapshot(listing);

                if (availabilityDetector.isUnavailable(listing)) {
                    ListingResponseDto unavailable = listingStatusUpdater.markNegotiationUnavailable(listing);
                    clearContactUnavailableSuspicion(listing);
                    log.warn(
                            "[AVAILABILITY] Listing {} changed from NEGOTIATING to UNAVAILABLE. No new quota slot was reserved.",
                            unavailable.listingId()
                    );
                    continue;
                }

                if ((snapshot.result() == NegotiationConversationResult.PENDING
                        || snapshot.result() == NegotiationConversationResult.UNKNOWN
                        || snapshot.result() == NegotiationConversationResult.REJECTED
                        || snapshot.result() == NegotiationConversationResult.CANCELLED)
                        && contactGuard.shouldSkip(listing)) {
                    continue;
                }

                if (snapshot.result() == NegotiationConversationResult.CANCELLED) {
                    ListingResponseDto expired =
                            listingStatusUpdater.markExpired(listing);
                    clearContactUnavailableSuspicion(listing);
                    log.warn(
                            "[CONVERSATION] Listing {} changed from NEGOTIATING to EXPIRED because Vinted marks the latest own offer as CANCELLED ('{}'). No quota or follow-up offer will be attempted.",
                            expired.listingId(),
                            snapshot.rawStatus()
                    );
                    continue;
                }

                ConversationActivitySnapshot activity = activityDetector.inspect();
                support.logConversationActivity(listing, activity);
                support.persistConversationActivity(listing, activity, snapshot);

                boolean stepSent;
                if (snapshot.result() == NegotiationConversationResult.PENDING) {
                    PendingNegotiationDecision pending = pendingPolicy.decide(
                            listing,
                            activity,
                            configuration
                    );

                    switch (pending.action()) {
                        case WAIT -> log.info(
                                "[PENDING POLICY] Listing {} remains NEGOTIATING. Reason: {}",
                                listing.listingId(),
                                pending.reason()
                        );
                        case EXPIRE -> {
                            ListingResponseDto expired =
                                    listingStatusUpdater.markExpired(listing);
                            clearContactUnavailableSuspicion(listing);
                            log.warn(
                                    "[PENDING POLICY] Listing {} changed from NEGOTIATING to EXPIRED. Reason: {}",
                                    expired.listingId(),
                                    pending.reason()
                            );
                        }
                        case SEND_NEXT_STEP -> {
                            stepSent = processNextStep(
                                    listing,
                                    NegotiationDecision.sendNextStep(
                                            pending.nextStep(),
                                            null,
                                            pending.reason()
                                    )
                            );

                            if (stepSent) {
                                sent++;
                                log.warn(
                                        "[NEXT STEP REAL] Sent next steps during this run: {}/{}",
                                        sent,
                                        maxRealNextStepsPerRun
                                );
                                if (sent >= maxRealNextStepsPerRun) {
                                    return true;
                                }
                            }
                        }
                    }
                    continue;
                } else {
                    NegotiationDecision decision = decisionService.decide(listing, snapshot, configuration);
                    if (snapshot.result() == NegotiationConversationResult.SELLER_COUNTER_OFFER
                            && decision.type() == NegotiationDecisionType.WAIT
                            && contactGuard.shouldSkip(listing)) {
                        continue;
                    }
                    stepSent = handleDecision(listing, snapshot, decision);
                }

                if (stepSent) {
                    sent++;
                    log.warn(
                            "[NEXT STEP REAL] Sent next steps during this run: {}/{}",
                            sent,
                            maxRealNextStepsPerRun
                    );
                    if (sent >= maxRealNextStepsPerRun) {
                        break;
                    }
                }
            } catch (VintedRateLimitException | VintedAuthenticationRequiredException exception) {
                /*
                 * A rate/session block is bot-wide, not a broken individual
                 * conversation. Let BotWorkerSlot pause every scheduled job.
                 * Swallowing it here used to make the run look successful.
                 */
                throw exception;
            } catch (Exception exception) {
                log.error(
                        "[CONVERSATION] Failed to inspect backend listing {}, marketplace listing {}, conversation {}: {}",
                        listing.id(),
                        listing.listingId(),
                        listing.conversationId(),
                        support.friendlyError(exception)
                );
                log.trace(
                        "[CONVERSATION] Full exception for backend listing {}.",
                        listing.id(),
                        exception
                );
            } finally {
                context.getBot().setConfiguration(currentProductConfiguration);
            }
        }

        log.info(
                "[CONVERSATION] Finished inspection. Inspected: {}, real next steps sent: {}.",
                inspected,
                sent
        );
        return sent > 0;
    }

    private boolean handleDecision(
            ListingResponseDto listing,
            NegotiationConversationSnapshot snapshot,
            NegotiationDecision decision
    ) {
        return switch (decision.type()) {
            case WAIT -> {
                log.info(
                        "[DECISION] Listing {} remains NEGOTIATING. Result: {}. Reason: {}",
                        listing.listingId(),
                        snapshot.result(),
                        decision.reason()
                );
                yield false;
            }
            case MARK_ACTION_REQUIRED -> {
                ListingResponseDto updated = listingStatusUpdater.markActionRequired(listing, decision);
                clearContactUnavailableSuspicion(listing);
                log.warn(
                        "[DECISION] Listing {} changed to ACTION_REQUIRED at price {}. Buy now was NOT clicked. Reason: {}",
                        updated.listingId(),
                        updated.currentPrice(),
                        decision.reason()
                );
                yield false;
            }
            case SEND_NEXT_STEP -> processNextStep(listing, decision);
            case MARK_REJECTED -> {
                ListingResponseDto updated = listingStatusUpdater.markRejected(listing, decision);
                clearContactUnavailableSuspicion(listing);
                log.warn(
                        "[DECISION] Listing {} changed to REJECTED at price {}. Reason: {}",
                        updated.listingId(),
                        updated.currentPrice(),
                        decision.reason()
                );
                yield false;
            }
            case KEEP_UNKNOWN -> {
                log.warn(
                        "[DECISION] Listing {} remains NEGOTIATING because conversation state is unknown. Reason: {}",
                        listing.listingId(),
                        decision.reason()
                );
                yield false;
            }
        };
    }

    private boolean processNextStep(
            ListingResponseDto listing,
            NegotiationDecision decision
    ) {
        if (decision.nextStep() == null) {
            throw new IllegalStateException("Decision SEND_NEXT_STEP contains no next step");
        }

        log.warn(
                "[DECISION] Listing {} qualifies for next step. Current={}, next={}, price={}, seller counter={}. Reason: {}",
                listing.listingId(),
                listing.currentStep(),
                decision.nextStep().getStepNumber(),
                decision.nextStep().getOfferPrice(),
                decision.sellerCounterOfferPrice(),
                decision.reason()
        );

        /*
         * Re-check immediately before an actual next-step preparation. This
         * also covers SELLER_COUNTER_OFFER paths that legitimately bypass the
         * generic missing-offer preflight until a new buyer offer is needed.
         */
        if (contactGuard.shouldSkip(listing)) {
            return false;
        }

        if (realNextStepsEnabled) {
            return preparedNextStepCoordinator.execute(listing, decision);
        }

        log.warn(
                "[NEXT STEP DRY RUN] Real next steps are disabled. Preparing step {} for listing {} without sending it.",
                decision.nextStep().getStepNumber(),
                listing.listingId()
        );

        NextStepPreparationResult result = nextStepExecutor.prepare(
                listing,
                decision.nextStep()
        );

        if (result == NextStepPreparationResult.PREPARED) {
            log.warn(
                    "[NEXT STEP DRY RUN] Listing {} passed validation. Step={}, price={}. Submit was NOT clicked.",
                    listing.listingId(),
                    decision.nextStep().getStepNumber(),
                    decision.nextStep().getOfferPrice()
            );
        } else {
            log.warn(
                    "[NEXT STEP DRY RUN] Listing {} failed validation because price {} for step {} is too low. Backend unchanged.",
                    listing.listingId(),
                    decision.nextStep().getOfferPrice(),
                    decision.nextStep().getStepNumber()
            );
        }
        return false;
    }

    private void clearContactUnavailableSuspicion(ListingResponseDto listing) {
        contactGuard.clear(listing);
    }
}
