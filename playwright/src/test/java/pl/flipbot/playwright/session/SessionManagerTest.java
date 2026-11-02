package pl.flipbot.playwright.session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionManagerTest {

    @Test
    public void identicalCheckpointDoesNotReplaceTheLastDistinctBackup() throws Exception {
        Path sessions = temporaryFolder.newFolder("same-state").toPath();
        SessionManager manager = new SessionManager(sessions);
        String state = "{\"cookies\":[],\"origins\":[]}";
        Files.writeString(manager.sessionFile(3L), state);
        Path backup = Files.createDirectories(sessions.resolve("backups")).resolve("bot-3-last-known-good.json");
        Files.writeString(backup, "previous-distinct-checkpoint");
        Path staged = sessions.resolve("identical.tmp");
        Files.writeString(staged, state);
        manager.installStagedSession(3L, staged);
        assertEquals(state, Files.readString(manager.sessionFile(3L)));
        assertEquals("previous-distinct-checkpoint", Files.readString(backup));
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void repositoryRootResolvesToPlaywrightModuleSessions()
            throws Exception {
        Path repositoryRoot = temporaryFolder.newFolder("repo-root").toPath();
        Path playwrightModule = createPlaywrightModule(repositoryRoot);

        Path resolved = SessionManager.resolveDefaultSessionDirectory(
                repositoryRoot,
                null
        );

        assertEquals(
                playwrightModule.resolve("sessions").toAbsolutePath().normalize(),
                resolved
        );
    }

    @Test
    public void moduleWorkingDirectoryResolvesToSameSessionDirectory()
            throws Exception {
        Path repositoryRoot = temporaryFolder.newFolder("module-cwd").toPath();
        Path playwrightModule = createPlaywrightModule(repositoryRoot);

        Path resolved = SessionManager.resolveDefaultSessionDirectory(
                playwrightModule,
                null
        );

        assertEquals(
                playwrightModule.resolve("sessions").toAbsolutePath().normalize(),
                resolved
        );
    }

    @Test
    public void nestedTargetWorkingDirectoryStillFindsModuleSessions()
            throws Exception {
        Path repositoryRoot = temporaryFolder.newFolder("target-cwd").toPath();
        Path playwrightModule = createPlaywrightModule(repositoryRoot);
        Path nestedWorkingDirectory = Files.createDirectories(
                playwrightModule.resolve("target/classes")
        );

        Path resolved = SessionManager.resolveDefaultSessionDirectory(
                nestedWorkingDirectory,
                null
        );

        assertEquals(
                playwrightModule.resolve("sessions").toAbsolutePath().normalize(),
                resolved
        );
    }

    @Test
    public void configuredSessionDirectoryOverridesDiscovery()
            throws Exception {
        Path repositoryRoot = temporaryFolder.newFolder("override-root").toPath();
        createPlaywrightModule(repositoryRoot);

        Path resolved = SessionManager.resolveDefaultSessionDirectory(
                repositoryRoot,
                "custom-sessions"
        );

        assertEquals(
                repositoryRoot.resolve("custom-sessions")
                        .toAbsolutePath()
                        .normalize(),
                resolved
        );
    }

    @Test
    public void zeroByteSessionFailsClosed()
            throws Exception {
        Path sessions = temporaryFolder.newFolder("zero-byte").toPath();
        SessionManager manager = new SessionManager(sessions);
        Files.createFile(manager.sessionFile(6L));

        try {
            manager.sessionExists(6L);
            fail("Zero-byte stored session should fail closed");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Refusing to treat it as a missing session"));
        }
    }

    @Test
    public void validatedStagedSessionReplacesActiveSessionAndRotatesOneBackup()
            throws Exception {
        Path sessions = temporaryFolder.newFolder("atomic-valid").toPath();
        SessionManager manager = new SessionManager(sessions);
        Path activeSession = manager.sessionFile(3L);
        Path stagedSession = sessions.resolve("staged.json.tmp");

        byte[] original = "{\"cookies\":[],\"origins\":[],\"marker\":\"old\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "{\"cookies\":[],\"origins\":[],\"marker\":\"new\"}"
                .getBytes(StandardCharsets.UTF_8);

        Files.write(activeSession, original);
        Files.write(stagedSession, replacement);

        manager.installStagedSession(3L, stagedSession);

        assertArrayEquals(replacement, Files.readAllBytes(activeSession));
        assertFalse(Files.exists(stagedSession));

        Path backup = sessions.resolve("backups/bot-3-last-known-good.json");
        assertTrue(Files.exists(backup));
        assertArrayEquals(original, Files.readAllBytes(backup));
    }

    @Test
    public void repeatedSuccessfulReplacementsKeepOnlyLatestRotatingBackup()
            throws Exception {
        Path sessions = temporaryFolder.newFolder("rotating-last-known-good").toPath();
        SessionManager manager = new SessionManager(sessions);
        Path activeSession = manager.sessionFile(3L);
        Path firstStaged = sessions.resolve("first.json.tmp");
        Path secondStaged = sessions.resolve("second.json.tmp");

        byte[] first = "{\"cookies\":[],\"origins\":[],\"marker\":\"first\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"cookies\":[],\"origins\":[],\"marker\":\"second\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] third = "{\"cookies\":[],\"origins\":[],\"marker\":\"third\"}"
                .getBytes(StandardCharsets.UTF_8);

        Files.write(activeSession, first);
        Files.write(firstStaged, second);
        manager.installStagedSession(3L, firstStaged);

        Files.write(secondStaged, third);
        manager.installStagedSession(3L, secondStaged);

        Path backups = sessions.resolve("backups");
        List<Path> files;
        try (var stream = Files.list(backups)) {
            files = stream.toList();
        }

        assertEquals(1, files.size());
        assertEquals("bot-3-last-known-good.json", files.getFirst().getFileName().toString());
        assertArrayEquals(second, Files.readAllBytes(files.getFirst()));
        assertArrayEquals(third, Files.readAllBytes(activeSession));
    }

    @Test
    public void emptyStagedSessionNeverTruncatesExistingActiveSession()
            throws Exception {
        Path sessions = temporaryFolder.newFolder("atomic-empty").toPath();
        SessionManager manager = new SessionManager(sessions);
        Path activeSession = manager.sessionFile(3L);
        Path stagedSession = sessions.resolve("empty.json.tmp");

        byte[] original = "{\"cookies\":[{\"name\":\"session\"}],\"origins\":[]}"
                .getBytes(StandardCharsets.UTF_8);

        Files.write(activeSession, original);
        Files.createFile(stagedSession);

        try {
            manager.installStagedSession(3L, stagedSession);
            fail("Empty staged session should have been rejected");
        } catch (IllegalStateException expected) {
            // expected
        }

        assertArrayEquals(original, Files.readAllBytes(activeSession));
        assertTrue(Files.exists(stagedSession));
    }

    @Test
    public void malformedStagedSessionNeverReplacesExistingActiveSession()
            throws Exception {
        Path sessions = temporaryFolder.newFolder("atomic-malformed").toPath();
        SessionManager manager = new SessionManager(sessions);
        Path activeSession = manager.sessionFile(3L);
        Path stagedSession = sessions.resolve("malformed.json.tmp");

        byte[] original = "{\"cookies\":[],\"origins\":[]}"
                .getBytes(StandardCharsets.UTF_8);

        Files.write(activeSession, original);
        Files.writeString(stagedSession, "{not-json");

        try {
            manager.installStagedSession(3L, stagedSession);
            fail("Malformed staged session should have been rejected");
        } catch (IllegalStateException expected) {
            // expected
        }

        assertArrayEquals(original, Files.readAllBytes(activeSession));
    }


    @Test(expected = IllegalArgumentException.class)
    public void sessionFileRejectsNonPositiveBotId() {
        SessionManager manager = new SessionManager(
                temporaryFolder.getRoot().toPath()
        );

        manager.sessionFile(0L);
    }

    @Test
    public void missingAuthTokensCannotOverwriteActiveSessionOrItsLastKnownGoodBackup() throws Exception {
        String original = """
                {"cookies":[
                  {"name":"access_token_web","value":"old-access-fixture","domain":".vinted.pl","path":"/"},
                  {"name":"refresh_token_web","value":"old-refresh-fixture","domain":".www.vinted.pl","path":"/"}
                ],"origins":[]}
                """;
        List<String> losses = List.of(
                "{\"cookies\":[{\"name\":\"analytics\",\"value\":\"still-present\"}],\"origins\":[]}",
                "{\"cookies\":[{\"name\":\"access_token_web\",\"value\":\"old-access-fixture\",\"domain\":\".vinted.pl\"}],\"origins\":[]}",
                "{\"cookies\":[{\"name\":\"refresh_token_web\",\"value\":\"old-refresh-fixture\",\"domain\":\".www.vinted.pl\"}],\"origins\":[]}"
        );
        for (int i = 0; i < losses.size(); i++) {
            Path sessions = temporaryFolder.newFolder("auth-token-loss-" + i).toPath();
            SessionManager manager = new SessionManager(sessions);
            Path active = manager.sessionFile(11L);
            Path backup = Files.createDirectories(sessions.resolve("backups")).resolve("bot-11-last-known-good.json");
            Path staged = sessions.resolve("staged.json.tmp");
            Files.writeString(active, original);
            Files.writeString(backup, "previous-backup-fixture");
            Files.writeString(staged, losses.get(i));
            try {
                manager.installStagedSession(11L, staged);
                fail("Lost Vinted auth token should not replace the checkpoint");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("lost established Vinted"));
                assertFalse(expected.getMessage().contains("old-access-fixture"));
                assertFalse(expected.getMessage().contains("old-refresh-fixture"));
            }
            assertEquals(original, Files.readString(active));
            assertEquals("previous-backup-fixture", Files.readString(backup));
        }
    }

    @Test
    public void rotatingBothTokensAndMigratingTheAccessDomainCanReplaceTheCheckpoint() throws Exception {
        Path sessions = temporaryFolder.newFolder("auth-token-rotation").toPath();
        SessionManager manager = new SessionManager(sessions);
        String original = """
                {"cookies":[
                  {"name":"access_token_web","value":"old-access-fixture","domain":".www.vinted.pl","path":"/"},
                  {"name":"refresh_token_web","value":"old-refresh-fixture","domain":".www.vinted.pl","path":"/"}
                ],"origins":[]}
                """;
        String rotated = """
                {"cookies":[
                  {"name":"access_token_web","value":"new-access-fixture","domain":".vinted.pl","path":"/"},
                  {"name":"refresh_token_web","value":"new-refresh-fixture","domain":".www.vinted.pl","path":"/"}
                ],"origins":[]}
                """;
        Files.writeString(manager.sessionFile(11L), original);
        Path staged = sessions.resolve("rotated.json.tmp");
        Files.writeString(staged, rotated);
        manager.installStagedSession(11L, staged);
        assertEquals(rotated, Files.readString(manager.sessionFile(11L)));
        assertEquals(original, Files.readString(sessions.resolve("backups/bot-11-last-known-good.json")));
    }

    private Path createPlaywrightModule(Path repositoryRoot)
            throws Exception {
        Path module = Files.createDirectories(
                repositoryRoot.resolve("playwright")
        );
        Files.createFile(module.resolve("pom.xml"));
        Files.createDirectories(
                module.resolve("src/main/java/pl/flipbot/playwright")
        );
        return module;
    }
}
