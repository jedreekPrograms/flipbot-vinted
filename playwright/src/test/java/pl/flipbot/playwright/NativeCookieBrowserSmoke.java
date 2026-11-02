package pl.flipbot.playwright;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.slf4j.LoggerFactory;
import pl.flipbot.playwright.session.VintedSessionDiagnostics;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual HTTPS and native cookie decisions; all Vinted DNS resolves to a local fixture. */
final class NativeCookieBrowserSmoke {
    static void verify(Playwright playwright, boolean bundled) throws IOException {
        Path directory = Files.createTempDirectory("flipbot-native-cookie-smoke-");
        HttpsServer server = null;
        Logger logger = (Logger) LoggerFactory.getLogger("pl.flipbot.playwright.session");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(localTls(directory)));
            AtomicBoolean sawAccessCookie = new AtomicBoolean();
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                if ("/web/api/auth/oauth".equals(path)) {
                    exchange.getResponseHeaders().add("Set-Cookie", "access_token_web=native-access-secret; Domain=.vinted.pl; Path=/; Max-Age=604800; Secure; HttpOnly; SameSite=Lax");
                    exchange.getResponseHeaders().add("Set-Cookie", "refresh_token_web=native-refresh-secret; Domain=.www.vinted.pl; Path=/; Max-Age=604800; Secure; HttpOnly; SameSite=Lax");
                } else if ("/cookie-invalid".equals(path)) {
                    exchange.getResponseHeaders().add("Set-Cookie", "access_token_web=native-rejected-secret; Domain=unrelated.example; Path=/; Secure");
                } else if ("/cookie-clear".equals(path)) {
                    exchange.getResponseHeaders().add("Set-Cookie", "access_token_web=; Domain=.vinted.pl; Path=/; Max-Age=0; Secure; HttpOnly");
                    exchange.getResponseHeaders().add("Set-Cookie", "refresh_token_web=; Domain=.www.vinted.pl; Path=/; Max-Age=0; Secure; HttpOnly");
                } else if (path.startsWith("/inbox/") && cookie != null && cookie.contains("access_token_web=native-access-secret")) {
                    sawAccessCookie.set(true);
                }
                byte[] body = "<p>Local native cookie fixture</p>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            String base = "https://www.vinted.pl:" + server.getAddress().getPort();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true)
                    .setIgnoreDefaultArgs(List.of("--disable-popup-blocking"))
                    .setArgs(List.of("--host-resolver-rules=MAP www.vinted.pl 127.0.0.1", "--no-proxy-server"));
            if (!bundled) {
                options.setChannel("chrome");
            }
            try (Browser browser = playwright.chromium().launch(options);
                 BrowserContext context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
                context.route("**/*", route -> {
                    if (route.request().url().startsWith(base + "/")) {
                        // Exercise production's resume-without-header-overrides
                        // through a real network stack, not route.fulfill.
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
                VintedSessionDiagnostics.install(context, 0L);
                Page page = context.newPage();
                page.navigate(base + "/web/api/auth/oauth");
                page.navigate(base + "/inbox/987654321?private=native-query-secret");
                page.navigate(base + "/cookie-invalid");
                if (!sawAccessCookie.get() || context.cookies().stream().noneMatch(cookie -> "native-access-secret".equals(cookie.value))) {
                    throw new AssertionError("Native HTTPS lost an accepted cookie before the explicit deletion fixture");
                }
                page.navigate(base + "/cookie-clear");
                page.navigate(base + "/inbox/987654321");
                if (context.cookies().stream().anyMatch(cookie -> cookie.name.endsWith("_token_web"))) {
                    throw new AssertionError("Native cookie deletion fixture was not applied");
                }
                String text = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
                if (!text.contains("[VINTED COOKIE WIRE]") || !text.contains("blocked=[InvalidDomain]")
                        || !text.contains("request sent=access=[id-") || !text.contains(";maxAge=0")) {
                    throw new AssertionError("Native trace missed a transmitted, rejected or deleted cookie");
                }
                if (text.contains("native-access-secret") || text.contains("native-refresh-secret")
                        || text.contains("native-rejected-secret") || text.contains("native-query-secret")) {
                    throw new AssertionError("Native trace exposed a synthetic credential/query");
                }
                System.out.println("NATIVE COOKIE BROWSER SMOKE PASS realHttps=true resumeWithoutOverrides=true acceptedCookieSent=true rejectedCookieReason=true serverDeletionObserved=true noCredentialValues=true noExternalTraffic=true");
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            if (server != null) {
                server.stop(0);
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static SSLContext localTls(Path directory) throws IOException {
        String password = "local-fixture-only";
        Path store = directory.resolve("local.p12");
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        try {
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "fixture",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=www.vinted.pl",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password, "-noprompt")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Local TLS fixture key generation timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Could not generate the local TLS fixture key");
            }
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) {
                keys.load(input, password.toCharArray());
            }
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keys, password.toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(factory.getKeyManagers(), null, null);
            return tls;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Local TLS fixture setup interrupted", failure);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IOException("Could not configure the local TLS fixture", failure);
        }
    }
}
