package pl.flipbot.marketstats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import pl.flipbot.bot.configuration.BotAdditionalTarget;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.dictionary.DictionaryCategory;
import pl.flipbot.dictionary.DictionaryModel;

import static pl.flipbot.marketstats.MarketProductMatcher.*;

/** Resolves one unambiguous category path without reading the database. */
final class MarketTargetCategories {
    private static final String CATEGORY_PATH_SEPARATOR_REGEX = "\\s*>\\s*";
    private MarketTargetCategories() {}

    static Resolution resolve(
            DictionaryModel model,
            List<BotConfiguration> configurations,
            List<BotAdditionalTarget> additionalTargets
    ) {
        DictionaryCategory dictionaryCategory = model.getCategory();

        if (dictionaryCategory != null
                && dictionaryCategory.getPath() != null
                && !dictionaryCategory.getPath().isBlank()) {
            return new Resolution(
                    splitCategoryPath(dictionaryCategory.getPath()),
                    true
            );
        }

        List<List<String>> paths = new ArrayList<>();

        configurations.stream()
                .filter(configuration -> matchesModel(model, configuration))
                .map(BotConfiguration::getCategoryPath)
                .filter(Objects::nonNull)
                .filter(path -> !path.isEmpty())
                .map(List::copyOf)
                .forEach(paths::add);

        additionalTargets.stream()
                .filter(target -> matchesModel(model, target))
                .map(BotAdditionalTarget::getCategoryPath)
                .filter(Objects::nonNull)
                .filter(path -> !path.isEmpty())
                .map(List::copyOf)
                .forEach(paths::add);

        if (paths.isEmpty()) {
            return new Resolution(
                    List.of(),
                    false
            );
        }

        List<String> first = paths.getFirst();

        boolean allEqual = paths.stream()
                .allMatch(path -> samePath(first, path));

        if (!allEqual) {
            return new Resolution(
                    List.of(),
                    false
            );
        }

        return new Resolution(
                List.copyOf(first),
                true
        );
    }

    private static List<String> splitCategoryPath(
            String storedPath
    ) {
        if (storedPath == null || storedPath.isBlank()) {
            return List.of();
        }

        return Arrays.stream(
                        storedPath.split(CATEGORY_PATH_SEPARATOR_REGEX)
                )
                .map(String::trim)
                .filter(element -> !element.isBlank())
                .toList();
    }

    private static boolean samePath(
            List<String> left,
            List<String> right
    ) {
        if (left == null
                || right == null
                || left.size() != right.size()) {
            return false;
        }

        for (int index = 0; index < left.size(); index++) {
            if (!sameText(left.get(index), right.get(index))) {
                return false;
            }
        }

        return true;
    }
    record Resolution(
            List<String> path,
            boolean resolved
    ) {
    }
}
