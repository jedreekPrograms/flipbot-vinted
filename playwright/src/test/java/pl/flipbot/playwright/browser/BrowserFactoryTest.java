package pl.flipbot.playwright.browser;

import org.junit.Test;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BrowserFactoryTest {

    @Test
    public void restoresChromeNativePopupBlockingWithoutDroppingOtherDefaults() {
        assertEquals(
                List.of("--disable-popup-blocking"),
                BrowserFactory.ignoredPlaywrightDefaultArgs()
        );
    }

    @Test
    public void explicitBundledChoiceDoesNotSetASystemChromeChannel() {
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
        assertSame(browser, BrowserFactory.createBundledBrowser(playwright, false));
        ArgumentCaptor<BrowserType.LaunchOptions> options = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(chromium).launch(options.capture());
        assertNull(options.getValue().channel);
        assertEquals(Boolean.FALSE, options.getValue().headless);
        assertEquals(List.of("--disable-popup-blocking"), options.getValue().ignoreDefaultArgs);
    }

    @Test
    public void missingBundledBinaryFailsInsteadOfRepeatingTheSystemChromeExperiment() {
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new PlaywrightException("Bundled browser is not installed"));
        assertThrows(PlaywrightException.class, () -> BrowserFactory.createBundledBrowser(playwright, false));
        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    public void wholeRuntimeBundledChoiceAlsoAppliesToVisibleLiveWindows() {
        for (boolean headless : List.of(false, true)) {
            Playwright playwright = mock(Playwright.class);
            BrowserType chromium = mock(BrowserType.class);
            Browser browser = mock(Browser.class);
            when(playwright.chromium()).thenReturn(chromium);
            when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
            assertSame(browser, BrowserFactory.createBrowser(playwright, headless, "bundled"));
            ArgumentCaptor<BrowserType.LaunchOptions> options = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
            verify(chromium).launch(options.capture());
            assertNull(options.getValue().channel);
            assertEquals(Boolean.valueOf(headless), options.getValue().headless);
            assertEquals(List.of("--disable-popup-blocking"), options.getValue().ignoreDefaultArgs);
        }
    }

    @Test
    public void explicitRuntimeEngineNeverFallsBackOnFailureOrInvalidChoice() {
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        when(playwright.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class)))
                .thenThrow(new PlaywrightException("Missing engine"));
        assertThrows(PlaywrightException.class, () -> BrowserFactory.createBrowser(playwright, false, "bundled"));
        assertThrows(IllegalArgumentException.class, () -> BrowserFactory.createBrowser(playwright, false, "typo"));
        verify(chromium, times(1)).launch(any(BrowserType.LaunchOptions.class));
    }

    @Test
    public void explicitSystemChromeChoiceUsesTheChromeChannel() {
        Playwright playwright = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        when(playwright.chromium()).thenReturn(chromium);
        BrowserFactory.createBrowser(playwright, true, "chrome");
        ArgumentCaptor<BrowserType.LaunchOptions> options = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(chromium).launch(options.capture());
        assertEquals("chrome", options.getValue().channel);
    }
}
