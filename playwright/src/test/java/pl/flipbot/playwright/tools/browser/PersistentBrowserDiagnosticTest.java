package pl.flipbot.playwright.tools.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PersistentBrowserDiagnosticTest {
    @Test
    public void bundledProfileKeepsAutomationDefaultsAndAddsNoPageOrNetworkGuards() {
        assertOptions(true, null);
    }

    @Test
    public void explicitSystemChromeUsesTheSameProfileLaunchPolicy() {
        assertOptions(false, "chrome");
    }

    @Test
    public void onlyBrowserChoiceIsAcceptedAndExistingProfileOrBotStateCannotBeSelected() {
        assertTrue(PersistentBrowserDiagnostic.bundled(new String[0]));
        assertTrue(PersistentBrowserDiagnostic.bundled(new String[]{"bundled"}));
        assertFalse(PersistentBrowserDiagnostic.bundled(new String[]{"chrome"}));
        for (String[] args : new String[][]{{"restore", "3"}, {"sessions/bot-3.json"}, {"bundled", "profile"}}) {
            assertThrows(IllegalArgumentException.class, () -> PersistentBrowserDiagnostic.bundled(args));
        }
    }

    private void assertOptions(boolean bundled, String expectedChannel) {
        BrowserType chromium = mock(BrowserType.class);
        BrowserContext context = mock(BrowserContext.class);
        Path profile = Path.of("temporary-test-profile");
        when(chromium.launchPersistentContext(eq(profile), any())).thenReturn(context);

        assertSame(context, PersistentBrowserDiagnostic.openContext(chromium, profile, bundled, false));

        ArgumentCaptor<BrowserType.LaunchPersistentContextOptions> options =
                ArgumentCaptor.forClass(BrowserType.LaunchPersistentContextOptions.class);
        verify(chromium).launchPersistentContext(eq(profile), options.capture());
        assertEquals(Boolean.FALSE, options.getValue().headless);
        assertEquals(expectedChannel, options.getValue().channel);
        assertEquals(List.of("--disable-popup-blocking"), options.getValue().ignoreDefaultArgs);
        assertNull(options.getValue().args);
        verifyNoMoreInteractions(chromium);
        verifyNoInteractions(context);
    }
}
