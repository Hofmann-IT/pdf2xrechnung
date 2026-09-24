package de.hofmannit.erechnung.web;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.RunResult;

import org.springframework.stereotype.Component;

/** Formatierungshilfen für Thymeleaf-Templates ({@code @fmt.money(...)} usw.). */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);

    public String money(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return "–";
        }
        try {
            DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY));
            return df.format(new BigDecimal(canonical));
        } catch (NumberFormatException e) {
            return canonical;
        }
    }

    public String money(String canonical, String currency) {
        String m = money(canonical);
        return "–".equals(m) ? m : m + " " + (currency == null ? "" : currency);
    }

    public String date(String iso) {
        if (iso == null || iso.isBlank()) {
            return "–";
        }
        try {
            return LocalDate.parse(iso).format(DATE);
        } catch (RuntimeException e) {
            return iso;
        }
    }

    public String dateTime(Instant instant) {
        return instant == null ? "–" : DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    public String bytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.GERMANY, "%.1f KB", size / 1024.0);
        }
        return String.format(Locale.GERMANY, "%.1f MB", size / (1024.0 * 1024.0));
    }

    public String shortSha(String sha) {
        return sha == null || sha.length() < 12 ? String.valueOf(sha) : sha.substring(0, 12) + "…";
    }

    /** Deutsche Statusbezeichnung. */
    public String status(InvoiceStatus s) {
        if (s == null) {
            return "–";
        }
        return switch (s) {
            case PROCESSING -> "In Verarbeitung";
            case REVIEW -> "Manuelle Prüfung";
            case FAILED -> "Fehlgeschlagen";
            case VALID -> "Validiert";
            case ARCHIVED -> "Archiviert";
            case DISPATCH_PENDING -> "Versand läuft";
            case DISPATCHED -> "Versendet";
            case DISPATCH_FAILED -> "Versand fehlgeschlagen";
            case REJECTED -> "Keine Rechnung";
        };
    }

    /** CSS-Klasse für Statusabzeichen. */
    public String statusClass(InvoiceStatus s) {
        if (s == null) {
            return "badge";
        }
        return switch (s) {
            case PROCESSING, DISPATCH_PENDING -> "badge badge-info";
            case REVIEW -> "badge badge-warn";
            case FAILED, DISPATCH_FAILED -> "badge badge-error";
            case VALID, ARCHIVED, DISPATCHED -> "badge badge-ok";
            case REJECTED -> "badge badge-muted";
        };
    }

    public String result(RunResult r) {
        if (r == null) {
            return "läuft";
        }
        return switch (r) {
            case SUCCESS -> "Erfolgreich";
            case REVIEW -> "Manuelle Prüfung";
            case FAILED -> "Fehlgeschlagen";
            case REJECTED -> "Keine Rechnung";
            case DUPLICATE -> "Duplikat";
        };
    }

    public String formats(String json) {
        if (json == null) {
            return "–";
        }
        String s = json.replace("[", "").replace("]", "").replace("\"", "").trim();
        if (s.isEmpty()) {
            return "–";
        }
        return s.replace("XRECHNUNG_CII", "XRechnung CII").replace("XRECHNUNG_UBL", "XRechnung UBL")
                .replace("ZUGFERD_EN16931", "ZUGFeRD EN16931").replace("ZUGFERD_XRECHNUNG", "ZUGFeRD XRechnung").replace(",", ", ");
    }
}
