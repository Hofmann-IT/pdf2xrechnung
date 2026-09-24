package de.hofmannit.erechnung.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.dispatch.LocalCommandRunner.CommandResult;

import org.junit.jupiter.api.Test;

class LocalCommandRunnerTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @Test
    void runsExecutableWithSeparateArgumentsAndCapturesOutput() throws Exception {
        CommandResult r = new LocalCommandRunner().run(JAVA, List.of("-version"), null, Duration.ofSeconds(30));
        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isZero();
        assertThat(r.timedOut()).isFalse();
        assertThat(r.stderr() + r.stdout()).containsIgnoringCase("version");
        assertThat(r.command()).containsExactly(JAVA, "-version");
    }

    @Test
    void argumentsAreNeverShellInterpreted() throws Exception {
        // Ein Argument mit Shell-Metazeichen wird wörtlich übergeben: java meldet die gesamte Zeichenkette
        // als eine unbekannte Option, statt dass "echo"/"dir" ausgeführt würden.
        CommandResult r = new LocalCommandRunner().run(JAVA, List.of("-XX:+NichtVorhanden; echo pwned && dir"), null, Duration.ofSeconds(30));
        assertThat(r.exitCode()).isNotZero();
        assertThat(r.stdout() + r.stderr()).contains("NichtVorhanden");
        assertThat(r.command().get(1)).isEqualTo("-XX:+NichtVorhanden; echo pwned && dir");
    }

    @Test
    void timeoutTerminatesProcess() throws Exception {
        Path classes = Path.of("target", "test-classes").toAbsolutePath();
        long start = System.currentTimeMillis();
        CommandResult r = new LocalCommandRunner().run(JAVA,
                List.of("-cp", classes.toString(), "de.hofmannit.erechnung.testsupport.Sleeper", "60000"), null, Duration.ofSeconds(2));
        assertThat(r.timedOut()).isTrue();
        assertThat(r.success()).isFalse();
        assertThat(System.currentTimeMillis() - start).isLessThan(30000);
    }

    @Test
    void shellInterpretersAreRejected() {
        for (String shell : List.of("cmd.exe", "C:\\Windows\\System32\\cmd.exe", "/bin/sh", "bash", "powershell")) {
            assertThatThrownBy(() -> new LocalCommandRunner().run(shell, List.of("-c", "echo x"), null, Duration.ofSeconds(1)))
                    .isInstanceOf(IOException.class).hasMessageContaining("Shell");
        }
    }

    @Test
    void templatePlaceholdersAreReplacedPerArgumentOrFail() {
        assertThat(TextTemplate.render("{zugferdPdf}", Map.of("zugferdPdf", "C:\\a b\\x.pdf"), Map.of())).isEqualTo("C:\\a b\\x.pdf");
        assertThatThrownBy(() -> TextTemplate.render("{ublXml}", Map.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
