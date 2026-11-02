package pl.flipbot.playwright.filters.model;

import static pl.flipbot.playwright.util.FailureMessages.summary;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static pl.flipbot.playwright.filters.model.ModelElementReader.safeAttribute;
import static pl.flipbot.playwright.filters.model.ModelElementReader.safeCount;
import static pl.flipbot.playwright.filters.model.ModelElementReader.safeIsVisible;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.exactModelCheckboxSelector;
import static pl.flipbot.playwright.filters.model.ModelOptionIdentity.exactModelSuffixTestId;

@Slf4j
@RequiredArgsConstructor
final class ModelOptionSelection {
    private final Page page;

    void select(Locator row, String model, String collectionId) {
        if (isExactModelSelected(row, collectionId)) {
            log.info("[FILTER MODEL] Exact model '{}' / collectionId={} is already selected.", model, collectionId);
            return;
        }
        Locator checkbox = row.locator(exactModelCheckboxSelector(collectionId)).first();
        boolean hasCheckbox = safeCount(checkbox) > 0;
        log.info("[FILTER MODEL] Selecting exact model '{}' / collectionId={}. rowTestId='{}', "
                        + "nativeCheckboxPresent={}, checkboxAriaLabel='{}'.",
                model, collectionId, safeAttribute(row, "data-testid"), hasCheckbox,
                hasCheckbox ? safeAttribute(checkbox, "aria-label") : "");

        Activation suffix = activateExactSuffix(row, model, collectionId);
        if (suffix.selected()) {
            return;
        }
        Activation normalRow = activate(row, model, collectionId, "canonical row click", 1_500,
                () -> row.click(new Locator.ClickOptions().setTimeout(2_000)));
        if (normalRow.selected()
                || activate(row, model, collectionId, "canonical row DOM activation", 1_500,
                        () -> row.evaluate("element => element.click()")).selected()
                || activateWithKeyboard(row, model, collectionId)
                || (hasCheckbox && activate(row, model, collectionId, "exact checkbox DOM fallback", 1_500,
                        () -> checkbox.evaluate("element => element.click()")).selected())) {
            return;
        }

        String message = "Exact Vinted model row was found, but its exact checkbox never became selected. Model='"
                + model + "', collectionId=" + collectionId + ", rowTestId='" + safeAttribute(row, "data-testid")
                + "', checkboxPresent=" + hasCheckbox + ", checkboxAriaLabel='"
                + (hasCheckbox ? safeAttribute(checkbox, "aria-label") : "") + "'.";
        RuntimeException primaryFailure = suffix.failure() != null ? suffix.failure() : normalRow.failure();
        if (primaryFailure != null) {
            throw new IllegalStateException(message, primaryFailure);
        }
        throw new IllegalStateException(message);
    }

    private Activation activateExactSuffix(Locator row, String model, String collectionId) {
        // Scope every pointer path to the suffix inside the independently proven row.
        Locator suffix = row.locator("[data-testid='" + exactModelSuffixTestId(collectionId) + "']").first();
        if (safeCount(suffix) == 0 || !safeIsVisible(suffix)) {
            log.warn("[FILTER MODEL] Exact suffix '{}' is missing or not visible inside canonical row for '{}' / "
                            + "collectionId={}. Trying bounded compatibility fallbacks.",
                    exactModelSuffixTestId(collectionId), model, collectionId);
            return new Activation(false, null);
        }
        try {
            log.info("[FILTER MODEL] Clicking exact model suffix '{}' for '{}' / collectionId={}.",
                    exactModelSuffixTestId(collectionId), model, collectionId);
            suffix.click(new Locator.ClickOptions().setTimeout(2_000));
            if (confirmSelection(row, model, collectionId, "exact suffix Playwright click", 1_500)) {
                return new Activation(true, null);
            }
            // Some Vinted variants put pointer-events:none on this subtree.
            suffix.evaluate("element => element.click()");
            if (confirmSelection(row, model, collectionId, "exact suffix DOM click", 1_500)) {
                return new Activation(true, null);
            }
            suffix.scrollIntoViewIfNeeded();
            var box = suffix.boundingBox();
            if (box != null && box.width > 0 && box.height > 0) {
                page.mouse().click(box.x + box.width / 2.0, box.y + box.height / 2.0);
                if (confirmSelection(row, model, collectionId, "physical exact-suffix click", 1_500)) {
                    return new Activation(true, null);
                }
            }
            log.warn("[FILTER MODEL] All exact-suffix click paths for '{}' / collectionId={} left the checkbox "
                    + "unchecked. Trying bounded compatibility fallbacks.", model, collectionId);
            return new Activation(false, null);
        } catch (RuntimeException exception) {
            log.warn("[FILTER MODEL] Exact suffix activation failed for '{}' / collectionId={}: {}. "
                    + "Trying bounded compatibility fallbacks.", model, collectionId, summary(exception));
            return new Activation(false, exception);
        }
    }

    private boolean activateWithKeyboard(Locator row, String model, String collectionId) {
        for (String key : List.of("Enter", "Space")) {
            if (activate(row, model, collectionId, "row keyboard activation '" + key + "'", 1_000,
                    () -> row.press(key)).selected()) {
                return true;
            }
        }
        return false;
    }

    private Activation activate(Locator row, String model, String collectionId, String path,
                                double selectionTimeoutMs, Runnable action) {
        try {
            action.run();
            return new Activation(confirmSelection(row, model, collectionId, path, selectionTimeoutMs), null);
        } catch (RuntimeException exception) {
            log.debug("[FILTER MODEL] {} failed for '{}' / collectionId={}.", path, model, collectionId, exception);
            return new Activation(false, exception);
        }
    }

    private boolean confirmSelection(Locator row, String model, String collectionId,
                                     String path, double selectionTimeoutMs) {
        if (!waitForExactModelSelected(row, collectionId, selectionTimeoutMs)) {
            return false;
        }
        log.info("[FILTER MODEL] Exact model '{}' / collectionId={} selected by {}.", model, collectionId, path);
        return true;
    }

    private record Activation(boolean selected, RuntimeException failure) {}

    private boolean waitForExactModelSelected(
            Locator modelRow,
            String collectionId,
            double timeoutMilliseconds
    ) {
        long deadline = System.currentTimeMillis() + (long) timeoutMilliseconds;

        while (System.currentTimeMillis() <= deadline) {
            if (isExactModelSelected(modelRow, collectionId)) {
                return true;
            }

            page.waitForTimeout(100);
        }

        return false;
    }

    private boolean isExactModelSelected(Locator modelRow, String collectionId) {
        try {
            Locator exactCheckbox = modelRow.locator(exactModelCheckboxSelector(collectionId)).first();

            if (exactCheckbox.count() > 0) {
                /*
                 * When the exact native checkbox exists, it is the authoritative
                 * state oracle. Do not accept a generic row/role state that could
                 * describe another custom control in the same Cell.
                 */
                return exactCheckbox.isChecked();
            }
        } catch (RuntimeException ignored) {
            // Fall through only for DOM variants that do not expose the input.
        }

        try {
            Locator roleCheckbox = modelRow.getByRole(AriaRole.CHECKBOX).first();

            if (roleCheckbox.count() > 0) {
                String ariaChecked = roleCheckbox.getAttribute("aria-checked");

                if ("true".equalsIgnoreCase(ariaChecked)) {
                    return true;
                }

                try {
                    if (roleCheckbox.isChecked()) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                    // Custom role=checkbox is not necessarily an <input>.
                }
            }
        } catch (RuntimeException ignored) {
            // Try row/custom attributes below.
        }

        String rowAriaChecked = safeAttribute(modelRow, "aria-checked");
        if ("true".equalsIgnoreCase(rowAriaChecked)) {
            return true;
        }

        String rowAriaSelected = safeAttribute(modelRow, "aria-selected");
        if ("true".equalsIgnoreCase(rowAriaSelected)) {
            return true;
        }

        String rowDataState = safeAttribute(modelRow, "data-state");
        return "checked".equalsIgnoreCase(rowDataState)
                || "selected".equalsIgnoreCase(rowDataState);
    }


}
