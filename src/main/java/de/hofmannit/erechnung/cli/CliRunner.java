package de.hofmannit.erechnung.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import de.hofmannit.erechnung.watcher.ReprocessService;
import de.hofmannit.erechnung.watcher.ReprocessService.ReprocessException;
import de.hofmannit.erechnung.watcher.RunOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Kommandozeilenbefehle (Vorgabe Abschnitte 32 und 16). Wird nur aktiv, wenn die Anwendung mit
 * einem bekannten Befehl gestartet wurde ({@link CliArguments#isCliCommand}); dann laufen weder
 * Web-Server noch Watcher.
 *
 * <pre>
 * calibrate --samples &lt;dir|pdf&gt; --profile &lt;yaml&gt; [--tenant &lt;id&gt;] [--out &lt;datei&gt;]
 * reprocess --tenant &lt;id&gt; --sha &lt;sha256|präfix&gt; --user &lt;name&gt; --reason &lt;text&gt; [--confirm]
 * </pre>
 */
@Component
public class CliRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final CalibrationService calibration;
    private final ReprocessService reprocess;
    private int exitCode;

    public CliRunner(CalibrationService calibration, ReprocessService reprocess) {
        this.calibration = calibration;
        this.reprocess = reprocess;
    }

    @Override
    public void run(ApplicationArguments args) {
        String[] raw = args.getSourceArgs();
        if (!CliArguments.isCliCommand(raw)) {
            return;
        }
        CliArguments cli = CliArguments.parse(raw);
        try {
            switch (cli.command()) {
                case "calibrate" -> exitCode = calibrate(cli);
                case "reprocess" -> exitCode = reprocess(cli);
                default -> exitCode = usage("Unbekannter Befehl: " + cli.command());
            }
        } catch (IllegalArgumentException e) {
            exitCode = usage(e.getMessage());
        } catch (Exception e) {
            log.error("CLI-Befehl fehlgeschlagen", e);
            System.err.println("Fehler: " + e.getMessage());
            exitCode = 2;
        }
    }

    private int calibrate(CliArguments cli) throws Exception {
        Path samples = Path.of(cli.require("samples"));
        Path profile = Path.of(cli.require("profile"));
        String report = calibration.calibrate(samples, profile, cli.option("tenant").orElse(null));
        if (cli.option("out").isPresent()) {
            Path out = Path.of(cli.option("out").get());
            if (Files.exists(out)) {
                System.err.println("Ausgabedatei existiert bereits und wird nicht überschrieben: " + out);
                return 2;
            }
            Files.write(out, report.getBytes(StandardCharsets.UTF_8));
            System.out.println("Report geschrieben: " + out);
        } else {
            System.out.println(report);
        }
        return report.contains("NICHT bestanden") || report.contains("FEHLER") ? 1 : 0;
    }

    private int reprocess(CliArguments cli) throws Exception {
        try {
            RunOutcome outcome = reprocess.reprocess(cli.require("tenant"), cli.require("sha"), cli.require("user"),
                    cli.require("reason"), cli.flag("confirm"));
            System.out.println("Reprocess abgeschlossen: Run " + outcome.runNumber() + " → " + outcome.result() + " (" + outcome.message() + ")");
            return outcome.result() == de.hofmannit.erechnung.ledger.RunResult.SUCCESS ? 0 : 1;
        } catch (ReprocessException e) {
            System.err.println("Reprocess nicht ausgeführt: " + e.getMessage());
            if (e.isConfirmationRequired()) {
                System.err.println("Mit --confirm ausdrücklich bestätigen. Ein Reprocess versendet niemals automatisch erneut.");
            }
            return 3;
        }
    }

    private static int usage(String message) {
        System.err.println(message);
        System.err.println("""
                Verwendung:
                  calibrate --samples <dir|pdf> --profile <yaml> [--tenant <id>] [--out <datei>]
                  reprocess --tenant <id> --sha <sha256|präfix> --user <name> --reason <text> [--confirm]""");
        return 64;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
