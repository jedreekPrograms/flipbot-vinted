package pl.flipbot.playwright.filters.catalog;

import com.microsoft.playwright.Page;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class CatalogUrlState {
    private final Page page;

    public void requirePrice(String parameterName, BigDecimal expectedPrice, String label) {
        if (expectedPrice == null) {
            return;
        }

        String actual = parameter(parameterName);

        if (actual == null || actual.isBlank()) {
            throw new IllegalStateException(
                    "Final Vinted catalog URL does not contain "
                            + parameterName
                            + ". Configured "
                            + label
                            + "="
                            + expectedPrice
                            + ". URL: "
                            + page.url()
            );
        }

        try {
            BigDecimal actualPrice = new BigDecimal(actual);

            if (actualPrice.compareTo(expectedPrice) != 0) {
                throw new IllegalStateException(
                        "Final Vinted catalog URL contains unexpected "
                                + parameterName
                                + ". Expected "
                                + expectedPrice
                                + ", actual "
                                + actual
                                + ". URL: "
                                + page.url()
                );
            }
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Final Vinted catalog URL contains invalid "
                            + parameterName
                            + "='"
                            + actual
                            + "'. URL: "
                            + page.url(),
                    exception
            );
        }
    }

    public boolean priceMatches(String parameterName, BigDecimal expectedPrice) {
        if (expectedPrice == null) {
            return true;
        }

        String actual = parameter(parameterName);

        if (actual == null || actual.isBlank()) {
            return false;
        }

        try {
            return new BigDecimal(actual).compareTo(expectedPrice) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public void requireParameter(String parameterName, String label, double timeoutMilliseconds) {
        if (waitForParameter(parameterName, timeoutMilliseconds)) {
            log.info(
                    "[FILTER VERIFY] {} persisted in URL. {}={}",
                    label,
                    parameterName,
                    parameter(parameterName)
            );

            return;
        }

        throw new IllegalStateException(
                label
                        + " filter action finished, but URL parameter '"
                        + parameterName
                        + "' is missing. URL: "
                        + page.url()
        );
    }

    public boolean waitForParameter(String parameterName, double timeoutMilliseconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutMilliseconds;

        while (System.currentTimeMillis() <= deadline) {
            if (hasParameter(parameterName)) {
                return true;
            }

            page.waitForTimeout(200);
        }

        return false;
    }

    public boolean waitForValue(String parameterName, String expectedValue, double timeoutMilliseconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutMilliseconds;

        while (System.currentTimeMillis() <= deadline) {
            if (expectedValue.equals(parameter(parameterName))) {
                return true;
            }

            page.waitForTimeout(200);
        }

        return false;
    }

    public boolean hasParameter(String parameterName) {
        return parameter(parameterName)
                != null;
    }

    public String parameter(String name) {
        return CatalogUrl.parameter(page.url(), name);
    }
}
