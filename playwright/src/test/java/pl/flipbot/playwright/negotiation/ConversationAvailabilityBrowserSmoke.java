package pl.flipbot.playwright.negotiation;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.negotiation.first.FirstOfferItemPage;

import static org.mockito.Mockito.*;

/** Real DOM controls and delayed hydration; every request is fulfilled locally. */
public final class ConversationAvailabilityBrowserSmoke {
    private ConversationAvailabilityBrowserSmoke() {}

    public static void verify(BrowserContext browser) {
        browser.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody("<!doctype html><meta charset='utf-8'>")));
        Page page = browser.newPage();
        try {
            page.navigate("https://www.vinted.pl/inbox/123");
            BotContext context = mock(BotContext.class);
            when(context.getPage()).thenReturn(page);
            ListingResponseDto listing = mock(ListingResponseDto.class);
            when(listing.conversationId()).thenReturn("123");
            when(listing.listingId()).thenReturn("456");
            ConversationAvailabilityDetector availability = new ConversationAvailabilityDetector(context);
            ConversationContactAvailabilityDetector contact = new ConversationContactAvailabilityDetector(context);

            page.setContent("""
                    <aside>Przedmiot jest niedostępny</aside>
                    <section data-testid="conversation-content">Oczekujące</section>
                    <button data-testid="make-offer-request-button">Zaproponuj cenę</button>
                    <textarea data-testid="composer--input"></textarea>
                    """);
            require(!availability.isUnavailable(listing), "A sidebar item closed the current conversation");
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.AVAILABLE,
                    "Usable message and offer controls were not recognized");

            page.setContent("""
                    <section data-testid="conversation-content">
                        <div>Przedmiot jest niedostępny</div>
                        <div>Przedmiot został sprzedany lub usunięty</div>
                    </section>
                    """);
            require(availability.isUnavailable(listing), "The screenshot's Polish sold banner was missed");
            page.evaluate("history.replaceState(null, '', '/inbox/987?referrer=%2Finbox%2F123')");
            require(availability.isUnavailable(listing), "A proven canonical redirect hid the sold banner");
            page.evaluate("history.replaceState(null, '', '/inbox/1234')");
            require(!availability.isUnavailable(listing), "A prefix-matching conversation closed a different item");
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.INSPECTION_UNAVAILABLE,
                    "An unrelated conversation was counted as a contact failure");
            page.evaluate("history.replaceState(null, '', '/inbox/123')");

            page.setContent("""
                    <section data-testid="conversation-content"><p data-testid="offer-status-title">Odrzucone</p></section>
                    <button data-testid="make-offer-request-button">Zaproponuj cenę</button>
                    """);
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                    "An offer button hid the missing message composer");

            page.setContent("""
                    <section data-testid="conversation-content"><p data-testid="offer-status-title">Odrzucone</p></section>
                    <button data-testid="make-offer-request-button">Zaproponuj cenę</button>
                    <textarea data-testid="composer--input" readonly>Nie można pisać</textarea>
                    """);
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.SUSPECTED_UNAVAILABLE,
                    "A read-only message field was treated as usable contact");

            page.setContent("""
                    <section data-testid="conversation-content">Oczekujące</section>
                    <textarea data-testid="composer--input"></textarea>
                    <script>setTimeout(() => {
                        const button = document.createElement('button');
                        button.dataset.testid = 'make-offer-request-button';
                        button.textContent = 'Zaproponuj cenę';
                        document.body.append(button);
                    }, 800);</script>
                    """);
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.AVAILABLE,
                    "The guard did not allow the offer button to finish hydrating");

            page.setContent("""
                    <section data-testid="conversation-content"><p data-testid="offer-status-title">Odrzucone</p></section>
                    <textarea data-testid="composer--input"></textarea>
                    """);
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.OFFER_ACTION_UNAVAILABLE,
                    "A stable chat-only conversation was not classified as missing an offer action");

            page.setContent("""
                    <section data-testid="conversation-content">
                        <div data-testid="conversation-message--status-message">Nie możesz wysyłać wiadomości</div>
                    </section>
                    """);
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.CONFIRMED_UNAVAILABLE,
                    "The explicit contact restriction was missed");

            page.setContent("<section data-testid='conversation-content'><p>Ładowanie rozmowy</p></section>");
            require(contact.inspect(listing).state() == ConversationContactAssessment.State.INSPECTION_UNAVAILABLE,
                    "An unloaded conversation counted as a blocked seller");

            FirstOfferItemPage item = new FirstOfferItemPage(context);
            for (boolean nativeSelector : new boolean[]{true, false}) {
                page.setContent("<h1 data-testid='item-page-summary-plugin'>Telefon</h1><button "
                        + (nativeSelector ? "data-testid='item-buyer-offer-button' " : "")
                        + "disabled>Zaproponuj cenę</button><script>setTimeout(() => "
                        + "document.querySelector('button').disabled = false, 800)</script>");
                var button = item.waitForOfferButtonOrNull(page, listing);
                require(button != null && button.isEnabled(), "A disabled first-offer button was used before hydration");
            }
            System.out.println("CONVERSATION AVAILABILITY BROWSER SMOKE PASS soldBanner=true canonicalRedirect=true "
                    + "conversationScope=true missingComposer=true readOnlyComposer=true delayedOfferAction=true explicitRestriction=true "
                    + "unloadedPageDeferred=true disabledFirstOfferDeferred=true allRequestsFulfilledLocally=true");
        } finally {
            page.close();
            browser.unroute("**/*");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
