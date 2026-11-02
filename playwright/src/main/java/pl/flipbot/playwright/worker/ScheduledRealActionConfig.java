package pl.flipbot.playwright.worker;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.util.RuntimeEnvironment;

/** Explicit submission mode; quota and final form checks are independent. */
@Slf4j
public record ScheduledRealActionConfig(
        Mode mode, Set<Long> allowedBotIds, boolean firstOffers, boolean nextSteps
) {
    public enum Mode { DRY_RUN, PRODUCTION }

    public ScheduledRealActionConfig {
        allowedBotIds = Set.copyOf(allowedBotIds);
    }

    public static ScheduledRealActionConfig fromEnvironment() {
        ScheduledRealActionConfig config = parse(System.getenv());
        log.info("[ACTION MODE] mode={}, botScope={}, firstOffers={}, nextSteps={}. Daily quota and per-job limits remain enforced.",
                config.mode, config.allowedBotIds.isEmpty() ? "ALL RUNNING BOTS" : config.allowedBotIds,
                config.firstOffers, config.nextSteps);
        return config;
    }

    static ScheduledRealActionConfig parse(Map<String, String> environment) {
        String rawMode = environment.getOrDefault("FLIPBOT_RUN_MODE", "DRY_RUN");
        Mode mode = Mode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        String scope = environment.get("FLIPBOT_REAL_ACTION_BOT_IDS");
        Set<Long> ids = scope == null || scope.isBlank() ? Set.of()
                : Arrays.stream(scope.split(",", -1)).map(String::trim)
                .map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
        if (ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("FLIPBOT_REAL_ACTION_BOT_IDS requires positive bot IDs");
        }
        return new ScheduledRealActionConfig(mode, ids,
                RuntimeEnvironment.booleanValue("FLIPBOT_REAL_OFFERS_ENABLED", environment.get("FLIPBOT_REAL_OFFERS_ENABLED"), true),
                RuntimeEnvironment.booleanValue("FLIPBOT_REAL_NEXT_STEPS_ENABLED", environment.get("FLIPBOT_REAL_NEXT_STEPS_ENABLED"), true));
    }

    public boolean productionModeEnabled() { return mode == Mode.PRODUCTION; }

    public boolean realOffersEnabledFor(Long botId) { return firstOffers && isArmedFor(botId); }

    public boolean realNextStepsEnabledFor(Long botId) { return nextSteps && isArmedFor(botId); }

    private boolean isArmedFor(Long botId) {
        return productionModeEnabled() && botId != null && botId > 0
                && (allowedBotIds.isEmpty() || allowedBotIds.contains(botId));
    }
}
