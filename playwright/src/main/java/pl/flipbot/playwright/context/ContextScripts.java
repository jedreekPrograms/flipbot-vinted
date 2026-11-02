package pl.flipbot.playwright.context;

import pl.flipbot.playwright.util.BrowserScripts;

/** Immutable script text is loaded once; installing it retains the original context lifecycle. */
final class ContextScripts {
    static final String PREVENT_EXTRA_PAGES = BrowserScripts.read(ContextScripts.class, "prevent-extra-pages.js");
    static final String OBSERVER_MODEL_LABELS = BrowserScripts.read(ContextScripts.class, "observer-model-labels.js");
    private ContextScripts() {}
}
