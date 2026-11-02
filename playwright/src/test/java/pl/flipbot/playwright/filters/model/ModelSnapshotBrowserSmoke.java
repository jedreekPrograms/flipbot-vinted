package pl.flipbot.playwright.filters.model;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Compare the batched read with the previous Playwright-per-field read in a real browser. */
public final class ModelSnapshotBrowserSmoke {
    private ModelSnapshotBrowserSmoke() {}

    public static void verify(BrowserContext browser) {
        Page page = browser.newPage();
        try {
            page.setContent("""
                    <div id="row" aria-label="Galaxy S25" title="row metadata">
                      <span>Galaxy S25</span><label aria-label="visible label">Name <b>Variant</b></label>
                      <label hidden title="hidden metadata">Hidden text</label>
                      <label style="visibility:hidden" aria-label="invisible metadata">Invisible text</label>
                      <label style="display:contents" title="contents metadata">Contents text</label>
                      <label style="opacity:0" title="opacity metadata">Transparent text</label>
                      <label style="width:0;height:0;display:block;overflow:hidden" title="zero metadata"></label>
                      <label title="7">Seven</label><label title="8">Eight</label><label title="9">Nine</label>
                      <label title="10">Ten</label><label title="11">Eleven beyond label cap</label>
                    </div>
                    """);
            Locator row = page.locator("#row");
            Set<String> previous = new LinkedHashSet<>();
            collect(previous, row);
            Locator labels = row.locator("label");
            for (int i = 0; i < Math.min(labels.count(), 10); i++) {
                if (labels.nth(i).isVisible()) collect(previous, labels.nth(i));
            }
            List<String> batched = ModelElementReader.readCompleteModelOptionTexts(row);
            if (!List.copyOf(previous).equals(batched)) {
                throw new AssertionError("Batched model text/metadata/visibility changed: " + previous + " != " + batched);
            }
            System.out.println("MODEL SNAPSHOT SMOKE PASS textAndMetadata=true nativeVisibility=true labelCap=true");
        } finally { page.close(); }
    }

    private static void collect(Set<String> values, Locator element) {
        for (String value : new String[]{element.innerText(), element.textContent(), element.getAttribute("aria-label"), element.getAttribute("title")}) {
            if (value != null && !ModelOptionIdentity.normalizeOptionText(value).isBlank()) values.add(value);
        }
    }
}
