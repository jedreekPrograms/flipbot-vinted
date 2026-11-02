package pl.flipbot.playwright.session;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.context.BotContext;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class VintedAuthenticationEvidenceTest {
    @Test
    public void inboxInLoginRedirectQueryIsNotAuthenticatedEvidence() {
        Fixture f = new Fixture();
        f.href("/member/register/select_type?ref_url=/inbox/123");
        assertNull(VintedAuthenticationEvidence.signal(f.page));
        assertFalse(new VintedSessionPersistenceGuard().check(f.context).healthy());
    }

    @Test
    public void realInboxPathWithQueryIsAuthenticatedEvidence() {
        Fixture f = new Fixture();
        f.href("/inbox?filter=all");
        assertEquals("visible /inbox link", VintedAuthenticationEvidence.signal(f.page));
        assertTrue(new VintedSessionPersistenceGuard().check(f.context).healthy());
    }

    @Test
    public void lookalikeHostAndLookalikePathAreNotEvidence() {
        Fixture f = new Fixture();
        for (String href : new String[]{"https://www.vinted.pl.example.com/inbox",
                "https://other.example/inbox", "/inbox-other", "/catalog?ref_url=/inbox"}) {
            f.href(href);
            assertNull(href, VintedAuthenticationEvidence.signal(f.page));
        }
    }

    @Test
    public void authPageCannotBeAuthenticatedByStaleInboxHeader() {
        Fixture f = new Fixture();
        when(f.page.url()).thenReturn("https://www.vinted.pl/member/login/email?ref_url=/inbox");
        f.href("/inbox");
        when(f.page.getByTestId("header-conversations-button")).thenReturn(f.links);
        assertNull(VintedAuthenticationEvidence.signal(f.page));
        assertFalse(new VintedSessionPersistenceGuard().check(f.context).healthy());
    }

    @Test
    public void visibleLoginControlOrCredentialFormOverridesStaleInboxLink() {
        Fixture f = new Fixture();
        f.href("/inbox");
        when(f.page.getByTestId("header--login-button")).thenReturn(f.links);
        assertNull(VintedAuthenticationEvidence.signal(f.page));
        when(f.page.getByTestId("header--login-button")).thenReturn(null);
        when(f.page.locator("#username, #password")).thenReturn(f.links);
        assertNull(VintedAuthenticationEvidence.signal(f.page));
    }

    private static final class Fixture {
        final Page page = mock(Page.class);
        final BotContext context = mock(BotContext.class);
        final Locator links = mock(Locator.class);
        Fixture() {
            when(context.getPage()).thenReturn(page);
            when(page.url()).thenReturn("https://www.vinted.pl/");
            when(page.locator("a[href*='/inbox']:visible")).thenReturn(links);
            when(links.count()).thenReturn(1);
            when(links.nth(0)).thenReturn(links);
            when(links.isVisible()).thenReturn(true);
        }
        void href(String value) {
            when(links.getAttribute(eq("href"), any(Locator.GetAttributeOptions.class)))
                    .thenReturn(value);
        }
    }
}
