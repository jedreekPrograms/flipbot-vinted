package pl.flipbot.marketstats;

import pl.flipbot.bot.configuration.BotAdditionalTarget;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.TargetMode;
import pl.flipbot.dictionary.DictionaryModel;

/** The same product identity rules are used by target discovery and planning. */
final class MarketProductMatcher {
    private MarketProductMatcher() {}

    static boolean matchesModel(DictionaryModel model, BotConfiguration product) {
        return product != null && product.getBot() != null
                && !Boolean.TRUE.equals(product.getBot().getMarketStatsObserver())
                && matches(model, product.getBrand(), product.getTargetMode(), product.getModel(), product.getSearchQuery());
    }

    static boolean matchesModel(DictionaryModel model, BotAdditionalTarget product) {
        return product != null && Boolean.TRUE.equals(product.getActive())
                && product.getConfiguration() != null && product.getConfiguration().getBot() != null
                && !Boolean.TRUE.equals(product.getConfiguration().getBot().getMarketStatsObserver())
                && matches(model, product.getBrand(), product.getTargetMode(), product.getModel(), product.getSearchQuery());
    }

    private static boolean matches(DictionaryModel model, String brand, TargetMode mode, String nativeModel, String query) {
        TargetMode effectiveMode = mode == null ? TargetMode.VINTED_MODEL : mode;
        return sameText(model.getBrand().getName(), brand) && resolveTargetMode(model) == effectiveMode
                && sameText(model.getName(), effectiveMode == TargetMode.SEARCH_QUERY ? query : nativeModel);
    }

    static TargetMode resolveTargetMode(DictionaryModel model) {
        return model.getTargetMode() == null ? TargetMode.VINTED_MODEL : model.getTargetMode();
    }

    static int trackingGeneration(MarketModelScanState state) {
        return state == null || state.getTrackingGeneration() == null ? 1 : Math.max(1, state.getTrackingGeneration());
    }

    static boolean sameText(String left, String right) {
        return left != null && right != null && normalizeText(left).equalsIgnoreCase(normalizeText(right));
    }

    private static String normalizeText(String value) { return value.trim().replaceAll("\\s+", " "); }
}
