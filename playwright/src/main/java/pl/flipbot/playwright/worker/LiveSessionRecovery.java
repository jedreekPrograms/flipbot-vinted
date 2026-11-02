package pl.flipbot.playwright.worker;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.runtime.RuntimeTelemetryReporter;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketplace.MarketplaceUrls;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.session.VintedSessionPersistenceGuard;
import pl.flipbot.playwright.target.VintedSessionBlockedException;
import pl.flipbot.playwright.util.FailureMessages;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

/** Authentication checkpoints and manual recovery for one visible owner/page. */
@Slf4j
final class LiveSessionRecovery {
    private final RuntimeTelemetryReporter telemetryReporter;
    private final BotRunScheduler scheduler;
    private final SessionCooldown cooldown;
    private static final long IDLE_HEALTH_INTERVAL_NANOS = 750_000_000L;
    private long nextHealthProbeNanos;
    private final java.util.function.LongSupplier clock;
    private final VintedSessionPersistenceGuard persistenceGuard = new VintedSessionPersistenceGuard();
    private final HumanVerificationHandler verificationHandler = new HumanVerificationHandler();
    private boolean recoveryObserved;
    private boolean awaitingManualRecovery;
    private boolean manualInterventionProbePending;

    LiveSessionRecovery(RuntimeTelemetryReporter telemetryReporter, BotRunScheduler scheduler) {
        this(telemetryReporter, scheduler, System::nanoTime);
    }

    LiveSessionRecovery(RuntimeTelemetryReporter telemetryReporter, BotRunScheduler scheduler,
                        java.util.function.LongSupplier clock) {
        this.telemetryReporter = telemetryReporter;
        this.scheduler = scheduler;
        this.cooldown = new SessionCooldown(telemetryReporter);
        this.clock = clock;
    }

    boolean awaitingManualRecovery() { return awaitingManualRecovery; }

    boolean consumeManualInterventionProbe() {
        boolean pending = manualInterventionProbePending;
        manualInterventionProbePending = false;
        return pending;
    }

    void afterScheduledJob(BotContext context) {
        nextHealthProbeNanos = 0;
        if (!isHealthyAuthenticated(context) || isVerificationVisible(context.getPage())) {
            recoveryObserved = true;
            awaitingManualRecovery = true;
            log.warn("[SESSION PREVIEW] Bot {} needs manual authentication/verification. Keeping the SAME visible page open until authenticated recovery.",
                    context.getBot().getId());
        }
    }

    BotContext open(
            BotDetailsDto bot,
            BrowserManager browserManager
    ) {
        BotContext context = new BotContext(bot, browserManager);
        Page page = context.getPage();

        try {
            page.navigate(
                    MarketplaceUrls.HOME,
                    new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(30_000)
            );
        } catch (RuntimeException exception) {
            /*
             * A challenge page or slow Vinted response is exactly when the
             * visible session is useful. Keep the browser open instead of
             * turning preview navigation into another failed bot job.
             */
            log.warn(
                    "[SESSION PREVIEW] Initial visible navigation for bot {} did not settle cleanly. Keeping the window open for manual inspection/recovery. url={}, reason={}",
                    bot.getId(),
                    safePageUrl(page),
                    FailureMessages.withType(exception)
            );
        }

        recoveryObserved = !isHealthyAuthenticated(context)
                || isVerificationVisible(page)
                || cooldown.hasBlock(bot.getId());

        log.info(
                "[SESSION PREVIEW] LIVE window READY for bot {}. url={}, storedSessionRestored={}, recoveryObserved={}. Normal bot jobs continue in this headed Chromium owner.",
                bot.getId(),
                safePageUrl(page),
                context.isStoredSessionRestored(),
                recoveryObserved
        );

        return context;
    }

    void observeWhenDue(BotContext context) {
        long now = clock.getAsLong();
        if (now < nextHealthProbeNanos) return;
        nextHealthProbeNanos = now + IDLE_HEALTH_INTERVAL_NANOS;
        observe(context);
    }

    void observe(BotContext context) {
        if (context == null
                || context.getPage() == null
                || context.getPage().isClosed()) {
            return;
        }

        Page page = context.getPage();
        boolean verificationVisible = isVerificationVisible(page);
        boolean healthy = isHealthyAuthenticated(context);

        if (verificationVisible || !healthy) {
            recoveryObserved = true;
            return;
        }

        if (!recoveryObserved) {
            return;
        }

        try {
            context.saveSession();
            telemetryReporter.sessionRecovered(
                    context.getBot().getId()
            );
            scheduler.resumeAfterSessionRecovery(
                    context.getBot().getId()
            );
            recoveryObserved = false;
            awaitingManualRecovery = false;

            log.warn(
                    "[SESSION PREVIEW] Manual session recovery CONFIRMED for bot {}. Strong authenticated Vinted evidence is visible after the previous challenge/auth problem. Fresh storage state was validated and installed through the normal session backup path.",
                    context.getBot().getId()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[SESSION PREVIEW] Bot {} became visibly authenticated after manual recovery, but the refreshed storage state was not safe to install. Existing production session remains untouched. reason={}",
                    context.getBot().getId(),
                    FailureMessages.withType(exception)
            );
        }
    }

    void persistHealthyState(
            BotContext context,
            String reason
    ) {
        if (context == null || !isHealthyAuthenticated(context)) {
            return;
        }

        try {
            context.saveSession();
            log.debug(
                    "[SESSION PREVIEW] Persisted healthy visible session checkpoint for bot {}. reason={}",
                    context.getBot().getId(),
                    reason
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[SESSION PREVIEW] Could not persist healthy visible session checkpoint for bot {}. Existing session remains available. reason={}, error={}",
                    context.getBot().getId(),
                    reason,
                    FailureMessages.withType(exception)
            );
        }
    }

    private boolean isHealthyAuthenticated(BotContext context) {
        if (context == null) {
            return false;
        }

        try {
            return persistenceGuard.check(context)
                    .healthy();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isVerificationVisible(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }

        try {
            return verificationHandler.isHumanVerificationVisible(page);
        } catch (VintedSessionBlockedException exception) {
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    void armManualInterventionProbe(Long botId, AtomicBoolean used) {
        if (used.get()) {
            return;
        }

        if (!cooldown.hasBlock(botId)) {
            return;
        }

        if (!used.compareAndSet(false, true)) {
            return;
        }

        recoveryObserved = true;
        manualInterventionProbePending = true;
        scheduler.wakeForManualSessionIntervention(botId);

        log.warn(
                "[SESSION PREVIEW] Persisted SESSION_BLOCKED detected for bot {} when LIVE preview was requested. The normal cooldown will be bypassed exactly once for a visible manual-recovery attempt. Complete CAPTCHA/authentication in the headed Chromium; successful authenticated work clears the block, while another block returns to the normal cooldown.",
                botId
        );
    }

    private String safePageUrl(Page page) {
        if (page == null) {
            return "<no-page>";
        }

        try {
            return page.url();
        } catch (RuntimeException exception) {
            return "<unavailable>";
        }
    }
}
