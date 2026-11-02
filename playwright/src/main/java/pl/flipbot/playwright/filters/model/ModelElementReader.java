package pl.flipbot.playwright.filters.model;

import com.microsoft.playwright.Locator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.normalizeOptionText;

final class ModelElementReader {
    private ModelElementReader() {}

    static int safeCount(Locator locator) {
        try {
            return locator.count();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /** Read text/metadata together; visibility still uses Playwright's own isVisible(). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> snapshotOptions(Locator options, boolean titlesOnly) {
        try {
            return (List<Map<String, Object>>) options.evaluateAll("""
                    (elements, titlesOnly) => {
                        const texts = el => [el.innerText ?? '', el.textContent ?? '',
                            el.getAttribute('aria-label') ?? '', el.getAttribute('title') ?? ''];
                        return elements.map((el, index) => ({ index, testId: el.getAttribute('data-testid') ?? '',
                            texts: titlesOnly ? [el.innerText ?? ''] : texts(el),
                            labels: titlesOnly ? [] : [...el.querySelectorAll('label')].slice(0, 10)
                                .map((label, index) => ({ index, texts: texts(label) })) }));
                    }
                    """, titlesOnly);
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    static List<String> readCompleteModelOptionTexts(Locator locator) {
        List<Map<String, Object>> snapshots = snapshotOptions(locator, false);
        return snapshots.isEmpty() ? List.of() : optionTexts(snapshots.getFirst(), locator);
    }

    @SuppressWarnings("unchecked")
    static List<String> optionTexts(Map<String, Object> snapshot, Locator locator) {
        Set<String> texts = new LinkedHashSet<>();
        ((List<String>) snapshot.get("texts")).forEach(value -> addModelOptionText(texts, value));
        for (Map<String, Object> label : (List<Map<String, Object>>) snapshot.get("labels")) {
            if (safeIsVisible(locator.locator("label").nth(((Number) label.get("index")).intValue()))) {
                ((List<String>) label.get("texts")).forEach(value -> addModelOptionText(texts, value));
            }
        }
        return List.copyOf(texts);
    }

    static void addModelOptionText(Set<String> texts, String value) {
        if (value == null || normalizeOptionText(value).isBlank()) {
            return;
        }
        texts.add(value);
    }

    static boolean containsIgnoreCase(String value, String fragment) {
        if (value == null || fragment == null || fragment.isBlank()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT)
                .contains(fragment.toLowerCase(Locale.ROOT));
    }

    static boolean safeIsVisible(Locator locator) {
        try {
            return locator.isVisible();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static String safeInnerText(Locator locator) {
        try {
            return locator.innerText();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    static String safeAttribute(Locator locator, String attributeName) {
        try {
            String value = locator.getAttribute(attributeName);
            return value == null ? "" : value;
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
