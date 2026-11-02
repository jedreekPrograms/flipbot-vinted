package pl.flipbot.playwright.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads a script relative to its owning class from Maven, IntelliJ or a packaged classpath. */
public final class BrowserScripts {
    private BrowserScripts() {}

    public static String read(Class<?> owner, String name) {
        try (InputStream input = owner.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing browser script: " + owner.getName() + "/" + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read browser script: " + owner.getName() + "/" + name, exception);
        }
    }
}
