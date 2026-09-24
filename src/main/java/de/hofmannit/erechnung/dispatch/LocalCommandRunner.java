package de.hofmannit.erechnung.dispatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sichere lokale Prozessausführung (Vorgabe Abschnitt 23):
 * {@link ProcessBuilder} mit Executable und Argumenten als getrennte Liste, keine Shell,
 * keine String-Interpolation, Timeout, Exit-Code, begrenzt mitgeschnittene Ausgaben.
 */
@Component
public class LocalCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalCommandRunner.class);
    private static final int MAX_CAPTURE = 64 * 1024;
    private static final List<String> FORBIDDEN_EXECUTABLES = List.of("sh", "bash", "zsh", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe");

    /** Ergebnis eines Aufrufs. */
    public record CommandResult(List<String> command, int exitCode, boolean timedOut, String stdout, String stderr, Duration duration) {
        public boolean success() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * @param executable       Programm (absoluter Pfad oder Name im PATH); Shells sind nicht zulässig
     * @param arguments        Argumente, bereits mit Platzhalterwerten befüllt, je Element ein Argument
     * @param workingDirectory Arbeitsverzeichnis oder {@code null}
     * @param timeout          maximale Laufzeit; danach wird der Prozess beendet
     */
    public CommandResult run(String executable, List<String> arguments, Path workingDirectory, Duration timeout) throws IOException {
        if (executable == null || executable.isBlank()) {
            throw new IOException("Kein Executable konfiguriert");
        }
        String name = Path.of(executable).getFileName().toString().toLowerCase();
        if (FORBIDDEN_EXECUTABLES.contains(name)) {
            throw new IOException("Shell-Interpreter sind als Executable nicht zulässig: " + executable);
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDirectory != null) {
            if (!Files.isDirectory(workingDirectory)) {
                throw new IOException("Arbeitsverzeichnis existiert nicht: " + workingDirectory);
            }
            pb.directory(workingDirectory.toFile());
        }
        long start = System.nanoTime();
        Process process = pb.start();
        process.getOutputStream().close();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> capture(process.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> capture(process.getErrorStream()));
        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Unterbrochen beim Warten auf " + executable, e);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String stdout = out.join();
        String stderr = err.join();
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        int exit = finished ? process.exitValue() : -1;
        CommandResult result = new CommandResult(List.copyOf(command), exit, !finished, stdout, stderr, duration);
        if (result.success()) {
            log.info("Kommando erfolgreich: {} (exit {}, {} ms)", command.get(0), exit, duration.toMillis());
        } else {
            log.warn("Kommando fehlgeschlagen: {} (exit {}, timeout={}, {} ms) stderr: {}", command.get(0), exit, !finished,
                    duration.toMillis(), abbreviate(stderr));
        }
        return result;
    }

    private static String capture(InputStream in) {
        try (in) {
            byte[] all = in.readNBytes(MAX_CAPTURE);
            String s = new String(all, Charset.defaultCharset());
            // Rest verwerfen, damit der Prozess nicht blockiert
            while (in.read(new byte[8192]) > 0) {
                // verwerfen
            }
            return s;
        } catch (IOException e) {
            return "";
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() > 500 ? t.substring(0, 500) + "…" : t;
    }
}
