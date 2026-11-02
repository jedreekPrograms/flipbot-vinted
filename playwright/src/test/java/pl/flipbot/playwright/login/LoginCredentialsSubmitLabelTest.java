package pl.flipbot.playwright.login;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoginCredentialsSubmitLabelTest {

    @Test
    public void acceptsKnownLoginSubmitLabels() {
        assertTrue(LoginCredentials.isLoginSubmitLabel("Zaloguj się"));
        assertTrue(LoginCredentials.isLoginSubmitLabel("ZALOGUJ SIĘ"));
        assertTrue(LoginCredentials.isLoginSubmitLabel("Log in"));
        assertTrue(LoginCredentials.isLoginSubmitLabel("Login"));
        assertTrue(LoginCredentials.isLoginSubmitLabel("Kontynuuj"));
        assertTrue(LoginCredentials.isLoginSubmitLabel("Continue"));
        assertTrue(LoginCredentials.isLoginSubmitLabel("  Zaloguj   się  "));
    }

    @Test
    public void rejectsUnrelatedAuthenticationButtons() {
        assertFalse(LoginCredentials.isLoginSubmitLabel("Zarejestruj się"));
        assertFalse(LoginCredentials.isLoginSubmitLabel("Nie pamiętasz hasła?"));
        assertFalse(LoginCredentials.isLoginSubmitLabel("Apple"));
        assertFalse(LoginCredentials.isLoginSubmitLabel("Google"));
        assertFalse(LoginCredentials.isLoginSubmitLabel(""));
        assertFalse(LoginCredentials.isLoginSubmitLabel(null));
    }
}
