package pl.flipbot.playwright.tools.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.nio.file.Path;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import pl.flipbot.playwright.session.SessionManager;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BrowserDiagnosticTest {
    @Test
    public void freshModeDoesNotReadOrWriteAnExistingSession() {
        SessionManager sessions = mock(SessionManager.class);
        assertNull(BrowserDiagnostic.Options.parse(new String[0]).resolveSessionPath(sessions));
        assertNull(BrowserDiagnostic.Options.parse(new String[]{"fresh"}).resolveSessionPath(sessions));
        verifyNoInteractions(sessions);
    }

    @Test
    public void restoreOnlyValidatesAndReadsTheChosenBotFile() {
        SessionManager sessions = mock(SessionManager.class);
        Path expected = Path.of("sessions", "bot-3.json");
        when(sessions.sessionExists(3L)).thenReturn(true);
        when(sessions.sessionFile(3L)).thenReturn(expected);
        assertEquals(expected, BrowserDiagnostic.Options.parse(new String[]{"restore", "3"})
                .resolveSessionPath(sessions));
        verify(sessions).sessionExists(3L);
        verify(sessions).sessionFile(3L);
        verifyNoMoreInteractions(sessions);
    }

    @Test
    public void missingOrMalformedSavedSessionNeverSilentlyBecomesFresh() {
        SessionManager sessions = mock(SessionManager.class);
        var options = BrowserDiagnostic.Options.parse(new String[]{"restore", "3"});
        assertThrows(IllegalStateException.class, () -> options.resolveSessionPath(sessions));
        when(sessions.sessionExists(3L)).thenThrow(new IllegalStateException("invalid session JSON"));
        assertThrows(IllegalStateException.class, () -> options.resolveSessionPath(sessions));
        verify(sessions, never()).sessionFile(anyLong());
    }

    @Test
    public void invalidArgumentsFailBeforeLaunchingChrome() {
        for (String[] args : new String[][]{
                {"restore"}, {"restore", "0"}, {"restore", "-1"},
                {"restore", "three"}, {"fresh", "3"}, {"save", "3"}}) {
            assertThrows(IllegalArgumentException.class, () -> BrowserDiagnostic.Options.parse(args));
        }
    }

    @Test
    public void freshBrowserContextHasNoRestoredStateAndNoFlipbotGuards() {
        assertContextOptions(null);
    }

    @Test
    public void restoredBrowserContextUsesOnlyTheChosenStateAndNoFlipbotGuards() {
        assertContextOptions(Path.of("sessions", "bot-3.json"));
    }

    @Test
    public void diagnosticLocationOmitsCredentialsQueryAndFragment() {
        assertEquals("https://www.vinted.pl/member/login/email",
                BrowserDiagnostic.locationWithoutQuery(
                        "https://user:secret@www.vinted.pl/member/login/email?token=secret#secret"));
        assertEquals("<non-web page>", BrowserDiagnostic.locationWithoutQuery("about:blank"));
        assertEquals("<unavailable>", BrowserDiagnostic.locationWithoutQuery("not a URL"));
    }

    @Test
    public void defaultUsesSupportedChromiumAndChromeStaysExplicit() {
        assertTrue(BrowserDiagnostic.Options.parse(new String[0]).bundled());
        assertFalse(BrowserDiagnostic.Options.parse(new String[]{"fresh", "chrome"}).bundled());
    }

    @Test
    public void supportedBrowserChoiceDoesNotImplyRestoringAProductionSession() {
        var fresh = BrowserDiagnostic.Options.parse(new String[]{"fresh", "bundled"});
        assertTrue(fresh.bundled());
        assertNull(fresh.botId());
        var restored = BrowserDiagnostic.Options.parse(new String[]{"restore", "3", "bundled"});
        assertTrue(restored.bundled());
        assertEquals(Long.valueOf(3), restored.botId());
    }

    @Test
    public void plainModeKeepsTheBrowserChoiceAndNeverRestoresASession() {
        SessionManager sessions = mock(SessionManager.class);
        var plain = BrowserDiagnostic.Options.parse(new String[]{"plain", "bundled"});
        assertFalse(plain.monitoring());
        assertTrue(plain.bundled());
        assertNull(plain.resolveSessionPath(sessions));
        assertTrue(BrowserDiagnostic.Options.parse(new String[]{"plain"}).bundled());
        assertTrue(BrowserDiagnostic.Options.parse(new String[]{"fresh", "bundled"}).monitoring());
        assertTrue(BrowserDiagnostic.Options.parse(new String[]{"restore", "3"}).monitoring());
        assertThrows(IllegalArgumentException.class,
                () -> BrowserDiagnostic.Options.parse(new String[]{"plain", "restore", "3"}));
        verifyNoInteractions(sessions);
    }

    @Test
    public void plainModeNeitherRegistersHttpListenersNorReadsCookiesOrThePageDom() {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(page.isClosed()).thenReturn(false, true);

        BrowserDiagnostic.configureObservation(context, null, false);
        BrowserDiagnostic.observe(page, context, false);

        verifyNoInteractions(context);
        verify(page, times(2)).isClosed();
        verify(page).waitForTimeout(250);
        verifyNoMoreInteractions(page);
    }

    @Test
    public void ordinaryDiagnosticStillRegistersPassiveHttpListeners() {
        BrowserContext context = mock(BrowserContext.class);
        BrowserDiagnostic.configureObservation(context, 3L, true);
        verify(context).onResponse(any());
        verify(context).onRequestFailed(any());
        verify(context).onPage(any());
        verify(context).pages();
        verifyNoMoreInteractions(context);
    }

    private void assertContextOptions(Path savedState) {
        Browser browser = mock(Browser.class);
        BrowserContext context = mock(BrowserContext.class);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        assertSame(context, BrowserDiagnostic.openContext(browser, savedState));
        ArgumentCaptor<Browser.NewContextOptions> options = ArgumentCaptor.forClass(Browser.NewContextOptions.class);
        verify(browser).newContext(options.capture());
        assertEquals(savedState, options.getValue().storageStatePath);
        assertNull(options.getValue().storageState);
        assertNull(options.getValue().serviceWorkers);
        verifyNoMoreInteractions(browser);
        verifyNoInteractions(context);
    }
}
