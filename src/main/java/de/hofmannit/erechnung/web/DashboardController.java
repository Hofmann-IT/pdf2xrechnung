package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.InvoiceFilter;
import de.hofmannit.erechnung.ledger.Rows.InvoiceListRow;
import de.hofmannit.erechnung.ledger.RunResult;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Dashboard (Vorgabe Abschnitt 26): Kennzahlen, Prozessvisualisierung, letzte Vorgänge. */
@Controller
public class DashboardController {

    private final LedgerRepository ledger;
    private final DirectoryLayout layout;
    private final ProfileRegistry registry;
    private final Clock clock;

    public DashboardController(LedgerRepository ledger, DirectoryLayout layout, ProfileRegistry registry, Clock clock) {
        this.ledger = ledger;
        this.layout = layout;
        this.registry = registry;
        this.clock = clock;
    }

    /** Kennzahlen des Dashboards. */
    public record Metrics(int inbox, int processing, int review, int failed, int successToday, int dispatchedToday, int dispatchFailedToday) {
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        int inbox = 0;
        int review = 0;
        int failed = 0;
        for (Tenant t : registry.tenants()) {
            if (!t.enabled()) {
                continue;
            }
            inbox += countFiles(layout.inbox(t), ".pdf");
            review += countDirs(layout.manualReview(t));
            failed += countDirs(layout.failed(t));
        }
        Metrics metrics = new Metrics(inbox, ledger.countOpenRuns(), review, failed,
                ledger.countRunsFinishedSince(startOfDay, RunResult.SUCCESS),
                ledger.countEventsSince(startOfDay, EventType.DISPATCH_SUCCEEDED),
                ledger.countEventsSince(startOfDay, EventType.DISPATCH_FAILED));
        List<InvoiceListRow> recent = ledger.listInvoices(new InvoiceFilter(null, null, null, null, null, null, null, 10));
        model.addAttribute("metrics", metrics);
        model.addAttribute("recent", recent);
        model.addAttribute("active", "dashboard");
        return "dashboard";
    }

    static int countFiles(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    static int countDirs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
