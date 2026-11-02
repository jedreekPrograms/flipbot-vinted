package pl.flipbot.playwright.worker;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.JobListingClient;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.catalog.CatalogWorkProcessor;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.login.LoginService;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.negotiation.MultiProductExistingNegotiationProcessor;
import pl.flipbot.playwright.session.JobSessionPersistence;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.session.VintedSessionPersistenceGuard;
import pl.flipbot.playwright.target.VintedSessionBlockDetector;
import pl.flipbot.playwright.target.VintedSessionBlockedException;
import pl.flipbot.playwright.target.VintedSessionFailureClassifier;
import pl.flipbot.playwright.util.FailureMessages;

@Slf4j
public class ScheduledBotRunExecutor {

    private static final ScheduledRealActionConfig REAL_ACTION_CONFIG =
            ScheduledRealActionConfig.fromEnvironment();
    private static final ScheduledActionLimitConfig ACTION_LIMIT_CONFIG =
            ScheduledActionLimitConfig.fromEnvironment();

    private static final long SESSION_BLOCK_CLASSIFICATION_WINDOW_MS = 2_000L;
    private static final long SESSION_BLOCK_CLASSIFICATION_POLL_MS = 200L;

    private final BotDetailsDto bot;
    private final BrowserManager browserManager;
    private final VintedSessionBlockDetector sessionBlockDetector =
            new VintedSessionBlockDetector();
    private final JobSessionPersistence sessionPersistence = new JobSessionPersistence();
    private final VintedSessionPersistenceGuard sessionPersistenceGuard =
            new VintedSessionPersistenceGuard();

    public ScheduledBotRunExecutor(
            BotDetailsDto bot,
            BrowserManager browserManager
    ) {
        if (bot == null || bot.getId() == null || bot.getId() <= 0) {
            throw new IllegalArgumentException(
                    "Bot details with a positive ID are required."
            );
        }

        this.bot = bot;
        this.browserManager = browserManager;
    }

    public void executeJob(ScheduledJobType jobType) {
        if (jobType == null) {
            throw new IllegalArgumentException("Scheduled job type is required.");
        }
        executeInternal(jobType, null);
    }

    /** Runs a production job in the visible owner's existing session. */
    public void executeJob(ScheduledJobType jobType, BotContext liveContext) {
        if (jobType == null) {
            throw new IllegalArgumentException(
                    "A live context may only run a production catalog or negotiation job."
            );
        }
        if (liveContext == null || liveContext.getBot() == null
                || !bot.getId().equals(liveContext.getBot().getId())) {
            throw new IllegalArgumentException("The live context must belong to this bot.");
        }
        executeInternal(jobType, liveContext);
    }

    private void executeInternal(ScheduledJobType jobType, BotContext liveContext) {
        Long botId = bot.getId();
        BotContext context = liveContext != null ? liveContext : new BotContext(bot, browserManager);
        boolean loginReady = false;
        boolean jobCompleted = false;
        boolean authenticatedCheckpointReady = false;

        try {
            boolean firstOfferRequested =
                    jobType == ScheduledJobType.CATALOG_SCAN
                            && REAL_ACTION_CONFIG.realOffersEnabledFor(botId);
            boolean nextStepRequested =
                    jobType == ScheduledJobType.NEGOTIATION_CHECK
                            && REAL_ACTION_CONFIG.realNextStepsEnabledFor(botId);
            boolean realOffersEnabled = firstOfferRequested;
            boolean realNextStepsEnabled = nextStepRequested;
            boolean productionModeEnabled = REAL_ACTION_CONFIG.productionModeEnabled();

            int maxRealOffersPerRun = ACTION_LIMIT_CONFIG.effectiveMaxRealOffers(
                    productionModeEnabled
            );
            int maxRealNextStepsPerRun = ACTION_LIMIT_CONFIG.effectiveMaxRealNextSteps(
                    productionModeEnabled
            );

            LoginService loginService = new LoginService(context);
            ListingClient listingClient = new JobListingClient(botId);
            OfferQuotaClient offerQuotaClient = new OfferQuotaClient();

            ListingStatusUpdater listingStatusUpdater = new ListingStatusUpdater(
                    context,
                    listingClient
            );

            logExecutionMode(
                    jobType,
                    botId,
                    realOffersEnabled,
                    realNextStepsEnabled,
                    maxRealOffersPerRun,
                    maxRealNextStepsPerRun
            );

            boolean freshLoginSaved = loginService.login();
            loginReady = true;

            VintedSessionPersistenceGuard.Check checkpointCheck =
                    sessionPersistenceGuard.check(
                            context
                    );

            if (!checkpointCheck.healthy()) {
                throw new IllegalStateException(
                        "Refusing to replace the stored session for bot "
                                + botId
                                + " because login returned without strong authenticated evidence. reason="
                                + checkpointCheck.reason()
                );
            }

            if (!freshLoginSaved) {
                context.saveSession();
            }
            authenticatedCheckpointReady = true;
            log.debug(
                    "[SESSION] Captured authenticated pre-job checkpoint for bot {}. verifiedBy={}",
                    botId,
                    checkpointCheck.reason()
            );

            if (firstOfferRequested || nextStepRequested) {
                RealActionPreflight.Result preflight = new RealActionPreflight().validate(
                        bot, jobType, listingClient, firstOfferRequested, nextStepRequested);
                if (!preflight.ready()) {
                    realOffersEnabled = false;
                    realNextStepsEnabled = false;
                    log.error("[REAL ACTION PREFLIGHT] Bot {} / {} has invalid configuration; real submit disabled for this job.", botId, jobType);
                }
            }

            switch (jobType) {
                case NEGOTIATION_CHECK -> new MultiProductExistingNegotiationProcessor(
                        context, listingClient, offerQuotaClient, listingStatusUpdater,
                        realNextStepsEnabled, maxRealNextStepsPerRun).process();
                case CATALOG_SCAN -> new CatalogWorkProcessor(
                        context, listingClient, offerQuotaClient, listingStatusUpdater,
                        realOffersEnabled, maxRealOffersPerRun).process();
            }

            jobCompleted = true;
        } catch (VintedSessionBlockedException | VintedAuthenticationRequiredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            classifyLateSessionBlock(
                    context,
                    jobType,
                    botId
            );

            if (VintedSessionFailureClassifier.shouldUseProtectiveCooldown(exception)) {
                String jobLabel = jobType.name();

                log.warn(
                        "[SESSION BLOCK] Bot {} hit a repeated Vinted authentication stall during {}. Treating it as a protective session cooldown instead of RUN_FAILED so all bot jobs back off together.",
                        botId,
                        jobLabel
                );
                throw new VintedSessionBlockedException(
                        "Vinted authentication stalled after repeated submit/verification attempts while running "
                                + jobLabel
                                + " for bot "
                                + botId
                                + ". Applying protective session cooldown. Original failure: "
                                + FailureMessages.summary(exception)
                );
            }

            throw exception;
        } finally {
            sessionPersistence.finish(
                    context, loginReady, jobCompleted, authenticatedCheckpointReady
            );

            if (liveContext != null) {
                log.info(
                        "[BROWSER LIFECYCLE] Bot {} {} finished. Keeping the LIVE owner's context/page open, including any authentication challenge.",
                        botId, jobType
                );
            } else {
                closeOwnedContext(context, botId, jobType);
            }
        }
    }

    private void closeOwnedContext(BotContext context, Long botId, ScheduledJobType jobType) {
        log.info(
                "[BROWSER LIFECYCLE] Bot {} {} job is finished. Closing only this job's isolated browser context/page.",
                botId,
                jobType
        );

        try {
            context.close();
        } catch (Exception exception) {
            browserManager.markNotReusable(
                    "isolated BrowserContext cleanup failed for bot "
                            + botId
            );

            log.warn(
                    "[SCHEDULED JOB] Could not close browser context cleanly for bot {}. The outer browser runtime is quarantined and will be closed before another job can use it.",
                    botId,
                    exception
            );
        }
    }

    private void classifyLateSessionBlock(
            BotContext context,
            ScheduledJobType jobType,
            Long botId
    ) {
        Page page = context.getPage();
        if (page == null || page.isClosed()) {
            return;
        }

        String jobLabel = jobType.name();
        long deadline = System.currentTimeMillis() + SESSION_BLOCK_CLASSIFICATION_WINDOW_MS;

        while (true) {
            sessionBlockDetector.throwIfBlocked(
                    page,
                    "classifying failed " + jobLabel + " for bot " + botId
            );

            if (System.currentTimeMillis() >= deadline
                    || page.isClosed()) {
                return;
            }

            page.waitForTimeout(SESSION_BLOCK_CLASSIFICATION_POLL_MS);
        }
    }

    private void logExecutionMode(
            ScheduledJobType jobType,
            Long botId,
            boolean realOffersEnabled,
            boolean realNextStepsEnabled,
            int maxRealOffersPerRun,
            int maxRealNextStepsPerRun
    ) {
        String jobLabel = jobType.name();

        if (!realOffersEnabled && !realNextStepsEnabled) {
            log.info(
                    "[SCHEDULED JOB] Preparing {} for bot {} in DRY RUN / NO REAL SUBMIT mode.",
                    jobLabel,
                    botId
            );
            return;
        }

        log.info("[SCHEDULED JOB] {} / bot {} mode={}, firstOffers={}, nextSteps={}, firstOfferLimit={}, nextStepLimit={}.",
                jobLabel, botId, REAL_ACTION_CONFIG.mode(), realOffersEnabled, realNextStepsEnabled,
                maxRealOffersPerRun, maxRealNextStepsPerRun);
    }
}
