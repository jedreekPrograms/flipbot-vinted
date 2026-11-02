package pl.flipbot.playwright.browser;

import com.microsoft.playwright.BrowserContext;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Context ownership metadata only; never reads or changes browser storage. */
public final class BrowserInteractionMode {
    private static final Map<BrowserContext, Boolean> HEADLESS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private BrowserInteractionMode() { }

    public static void register(BrowserContext context, boolean headless) {
        HEADLESS.put(Objects.requireNonNull(context), headless);
    }

    public static boolean isHeadless(BrowserContext context) {
        // Manually created/diagnostic contexts retain the historical manual
        // verification behavior unless their owner explicitly registers them.
        return Boolean.TRUE.equals(HEADLESS.get(context));
    }
}
