package pl.flipbot.playwright.marketstats;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.filters.FilterService;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.marketstats.dto.MarketStatsTargetDto;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.util.ErrorMessages;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
final class MarketTargetFilters {
    private static final String VINTED_MODEL = "VINTED_MODEL";

    private static final String SEARCH_QUERY = "SEARCH_QUERY";

    private static final String STRATEGY_DICTIONARY_FILTERS = "DICTIONARY_FILTERS";

    private static final String STRATEGY_GLOBAL_BRAND_TEXT = "GLOBAL_BRAND_TEXT_FALLBACK";

    private static final String STRATEGY_TEXT_ONLY = "TEXT_ONLY_FALLBACK";

    private final MarketObserverPage observer;

    PreparedScan prepareScan(BotContext context, BotDetailsDto observerBot, MarketStatsTargetDto target) {
        boolean resolvedCategory = hasResolvedCategory(target);
        String requestedTargetMode = resolveTargetMode(target.targetMode());

        if (VINTED_MODEL.equals(requestedTargetMode) && !resolvedCategory) {
            throw new IllegalStateException(
                    "VINTED_MODEL market target " + target.brandName() + " / " + target.modelName()
                            + " has no resolved category. Exact Vinted model filtering is mandatory; refusing SEARCH_QUERY fallback."
            );
        }

        BotDetailsDto primaryBot = buildScanBot(observerBot, target);
        String primaryStrategy = resolvedCategory
                ? STRATEGY_DICTIONARY_FILTERS
                : STRATEGY_GLOBAL_BRAND_TEXT;

        if (!resolvedCategory) {
            log.warn(
                    "[MARKET STATS] modelId={} {} / {} has no resolved category. SEARCH_QUERY collector will try brand + text search without a category first.",
                    target.modelId(), target.brandName(), target.modelName()
            );
        }

        try {
            applyTargetFilters(context, target, primaryBot, primaryStrategy);

            return new PreparedScan(primaryBot, primaryStrategy, SEARCH_QUERY.equals(requestedTargetMode));
        } catch (RuntimeException primaryFailure) {
            if (containsInterruptedException(primaryFailure)
                    || MarketStatsTrafficBackoff.isRequired(primaryFailure)) {
                throw primaryFailure;
            }

            log.warn(
                    "[MARKET STATS] Primary filter strategy failed for modelId={} {} / {}. strategy={}, targetMode={}, reason={}",
                    target.modelId(), target.brandName(), target.modelName(),
                    primaryStrategy, requestedTargetMode, ErrorMessages.fullMessage(primaryFailure, "unknown error")
            );

            if (VINTED_MODEL.equals(requestedTargetMode)) {
                log.error(
                        "[MARKET STATS] Exact Vinted model filter is mandatory for modelId={} {} / {}. This target will FAIL CLOSED; no category-text or text-only fallback is allowed.",
                        target.modelId(), target.brandName(), target.modelName()
                );

                throw new IllegalStateException(
                        "Exact Vinted model filtering failed for "
                                + target.brandName() + " / " + target.modelName()
                                + "; refusing to collect statistics from a different search strategy.",
                        primaryFailure
                );
            }

            BotDetailsDto textOnlyBot = buildTextOnlyFallbackBot(observerBot, target);
            applyTargetFilters(context, target, textOnlyBot, STRATEGY_TEXT_ONLY);

            return new PreparedScan(textOnlyBot, STRATEGY_TEXT_ONLY, true);
        }
    }

    private void applyTargetFilters(
            BotContext context,
            MarketStatsTargetDto target,
            BotDetailsDto scanBot,
            String strategy
    ) {
        MarketplaceNavigator navigator = new MarketplaceNavigator(context);
        FilterService filterService = new FilterService(context);

        log.info(
                "[MARKET STATS] Applying target. modelId={}, strategy={}, categoryPath={}, brand='{}', targetMode={}, model='{}', searchQuery='{}', minPrice={}, maxPrice={}.",
                target.modelId(), strategy, scanBot.getConfiguration().getCategoryPath(),
                scanBot.getConfiguration().getBrand(), scanBot.getConfiguration().getTargetMode(),
                scanBot.getConfiguration().getModel(), scanBot.getConfiguration().getSearchQuery(),
                scanBot.getConfiguration().getMinPrice(), scanBot.getConfiguration().getMaxPrice()
        );

        navigator.goToCatalog();
        observer.prepare(context);
        filterService.applyFilters(scanBot);
    }

    private BotDetailsDto buildScanBot(BotDetailsDto observerBot, MarketStatsTargetDto target) {
        BotConfigurationDto configuration = new BotConfigurationDto();
        configuration.setMarketplace("VINTED");

        boolean resolvedCategory = hasResolvedCategory(target);
        configuration.setCategoryPath(resolvedCategory ? List.copyOf(target.categoryPath()) : List.of());
        configuration.setBrand(target.brandName());

        if (!resolvedCategory) {
            configuration.setTargetMode(SEARCH_QUERY);
            configuration.setModel(null);
            configuration.setSearchQuery(target.modelName());
        } else {
            String targetMode = resolveTargetMode(target.targetMode());
            configuration.setTargetMode(targetMode);

            if (SEARCH_QUERY.equals(targetMode)) {
                configuration.setModel(null);
                configuration.setSearchQuery(target.modelName());
            } else {
                configuration.setModel(target.modelName());
                configuration.setSearchQuery(null);
            }
        }

        applyObserverPriceRange(configuration, target);
        return buildBotWithConfiguration(observerBot, configuration);
    }

    private BotDetailsDto buildTextOnlyFallbackBot(BotDetailsDto observerBot, MarketStatsTargetDto target) {
        BotConfigurationDto configuration = new BotConfigurationDto();
        configuration.setMarketplace("VINTED");
        configuration.setCategoryPath(List.of());
        configuration.setBrand(null);
        configuration.setTargetMode(SEARCH_QUERY);
        configuration.setModel(null);
        configuration.setSearchQuery(target.brandName().trim() + " " + target.modelName().trim());
        applyObserverPriceRange(configuration, target);

        return buildBotWithConfiguration(observerBot, configuration);
    }

    private void applyObserverPriceRange(BotConfigurationDto configuration, MarketStatsTargetDto target) {
        configuration.setMinPrice(target.minPrice());
        configuration.setMaxPrice(target.maxPrice());
    }

    private BotDetailsDto buildBotWithConfiguration(
            BotDetailsDto observerBot,
            BotConfigurationDto configuration
    ) {
        BotDetailsDto scanBot = new BotDetailsDto();
        scanBot.setId(observerBot.getId());
        scanBot.setName("Market stats observer");
        scanBot.setEmail(observerBot.getEmail());
        scanBot.setPassword(observerBot.getPassword());
        scanBot.setConfiguration(configuration);
        return scanBot;
    }

    static void validateTarget(MarketStatsTargetDto target) {
        if (target == null
                || target.modelId() == null
                || target.modelId() <= 0
                || isBlank(target.brandName())
                || isBlank(target.modelName())) {
            throw new IllegalArgumentException("Invalid market statistics target: " + target);
        }

        if (target.minPrice() != null && target.minPrice().signum() <= 0) {
            throw new IllegalArgumentException("Market statistics minimum price must be positive: " + target);
        }

        if (target.maxPrice() != null && target.maxPrice().signum() <= 0) {
            throw new IllegalArgumentException("Market statistics maximum price must be positive: " + target);
        }

        if (target.minPrice() != null
                && target.maxPrice() != null
                && target.minPrice().compareTo(target.maxPrice()) > 0) {
            throw new IllegalArgumentException(
                    "Market statistics minimum price cannot exceed maximum price: " + target
            );
        }
    }

    private boolean hasResolvedCategory(MarketStatsTargetDto target) {
        return target.categoryResolved()
                && target.categoryPath() != null
                && !target.categoryPath().isEmpty();
    }

    private String resolveTargetMode(String rawTargetMode) {
        return SEARCH_QUERY.equals(rawTargetMode) ? SEARCH_QUERY : VINTED_MODEL;
    }

    private boolean containsInterruptedException(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }


    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record PreparedScan(BotDetailsDto scanBot, String strategy, boolean accessoryFiltering) {
        boolean trustsExactModelFilter() {
            return VINTED_MODEL.equals(scanBot.getConfiguration().getTargetMode())
                    && STRATEGY_DICTIONARY_FILTERS.equals(strategy);
        }

        boolean usedFallback() {
            return !STRATEGY_DICTIONARY_FILTERS.equals(strategy);
        }
    }
}
