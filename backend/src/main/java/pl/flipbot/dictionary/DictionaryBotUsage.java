package pl.flipbot.dictionary;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.flipbot.bot.BotStatus;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.BotConfigurationRepository;
import pl.flipbot.bot.configuration.TargetMode;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.ListingStatus;

import static pl.flipbot.dictionary.DictionaryValues.samePath;
import static pl.flipbot.dictionary.DictionaryValues.sameText;

/** Existing dictionary links belong to the main product; additional-product rules are unchanged. */
@Component
@RequiredArgsConstructor
class DictionaryBotUsage {
    private final BotConfigurationRepository configurationRepository;
    private final ListingRepository listingRepository;
    List<BotConfiguration> usingBrand(String brand) {
        return configurationRepository.findAll().stream()
                .filter(configuration -> sameText(configuration.getBrand(), brand)).toList();
    }

    List<BotConfiguration> usingModel(String brand, String model) {
        return usingBrand(brand).stream()
                .filter(configuration -> configurationUsesModelName(configuration, model)).toList();
    }

    List<BotConfiguration> usingCategory(List<String> path) {
        return configurationRepository.findAll().stream()
                .filter(configuration -> samePath(configuration.getCategoryPath(), path)).toList();
    }

    void requireEditable(List<BotConfiguration> configurations) {
        for (BotConfiguration configuration : configurations) {
            if (configuration.getBot().getStatus() != BotStatus.STOPPED) {
                throw new IllegalStateException("Dictionary entry cannot be edited while it is used by a running bot.");
            }
            Long botId = configuration.getBot().getId();
            boolean hasNegotiating = !listingRepository
                    .findByBotIdAndStatusOrderByIdAsc(botId, ListingStatus.NEGOTIATING)
                    .isEmpty();
            boolean hasActionRequired = !listingRepository
                    .findByBotIdAndStatusOrderByIdAsc(botId, ListingStatus.ACTION_REQUIRED)
                    .isEmpty();
            if (hasNegotiating || hasActionRequired) {
                throw new IllegalStateException("Dictionary entry cannot be edited while a using bot has active negotiations or action-required listings.");
            }
        }
    }

    private boolean configurationUsesModelName(BotConfiguration configuration, String modelName) {
        TargetMode targetMode = configuration.getTargetMode() == null
                ? TargetMode.VINTED_MODEL
                : configuration.getTargetMode();
        if (targetMode == TargetMode.SEARCH_QUERY) {
            return sameText(configuration.getSearchQuery(), modelName);
        }
        return sameText(configuration.getModel(), modelName);
    }
}
