package de.hofmannit.erechnung.configuration;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mandanten aus {@code config/tenant.yaml} (Präfix {@code tenants}).
 *
 * @param tenants Liste aller Mandanten; mindestens ein aktiver Mandant ist erforderlich
 */
@ConfigurationProperties(prefix = "")
public record TenantProperties(List<Tenant> tenants) {

    /**
     * Ein Mandant.
     *
     * @param id               technische, stabile Kennung (Teil von {@code tenant_id + sha256});
     *                         nur {@code [a-z0-9-]}
     * @param name             Anzeigename
     * @param enabled          inaktive Mandanten werden vom Watcher ignoriert
     * @param inboxSubdirectory Unterverzeichnis unterhalb von {@code inbox/}, {@code ""} = Wurzel.
     *                         Zwei Mandanten dürfen nicht dasselbe Inbox-Verzeichnis verwenden.
     * @param profiles         Namen der Profile aus {@code profiles/*.yaml}, die für diesen
     *                         Mandanten in Frage kommen (Reihenfolge = Prüfreihenfolge)
     * @param fixedValues      Fixed Values für Mapping-Regeln vom Typ {@code fixed}
     *                         (Firmenangaben, die nur grafisch im Briefpapier vorhanden sind)
     * @param export           Exporteinstellungen (Rechnungsausgangsbuch, DATEV)
     */
    public record Tenant(
            String id,
            String name,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String inboxSubdirectory,
            List<String> profiles,
            Map<String, String> fixedValues,
            Export export) {

        public Tenant {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            fixedValues = fixedValues == null ? Map.of() : Map.copyOf(fixedValues);
        }

        /** DATEV-Konfiguration oder {@code null}, wenn nicht konfiguriert. */
        public Datev datev() {
            return export == null ? null : export.datev();
        }
    }

    /** Exporteinstellungen je Mandant. */
    public record Export(Datev datev) {
    }

    /** Strategie für das Debitorenkonto (#7 Konto) im Buchungsstapel. */
    public enum DebtorStrategy {
        /** Alle Rechnungen auf ein Sammel-Debitorenkonto. */
        COLLECTIVE,
        /** Konto je Kunde aus {@code customerAccounts}; fehlende Zuordnung ist ein Fehler. */
        PER_CUSTOMER
    }

    /**
     * Erlöskonto je Steuerfall.
     *
     * @param account Erlöskonto (Sachkonto)
     * @param buKey   BU-Schlüssel (vierstellig) oder {@code null} bei Automatikkonten
     */
    public record RevenueAccount(String account, String buKey) {
    }

    /**
     * DATEV-Format „Buchungsstapel" (siehe docs/datev-format-referenz.md). Alle Werte stammen vom
     * Steuerberater bzw. aus dem DATEV-Mandat; die Beispielkonfiguration enthält Platzhalter.
     *
     * @param enabled            Export anbieten
     * @param consultantNumber   Beraternummer (Kopfzeile #11, 4–7 Ziffern)
     * @param clientNumber       Mandantennummer (Kopfzeile #12, 1–5 Ziffern)
     * @param fiscalYearStart    Wirtschaftsjahresbeginn als {@code MM-DD}, z. B. {@code 01-01}
     * @param accountLength      Sachkontenlänge (Kopfzeile #14, 4–8)
     * @param chartOfAccounts    Sachkontenrahmen (Kopfzeile #27), z. B. {@code 03} oder {@code 04}
     * @param debtorStrategy     Sammeldebitor oder Konto je Kunde
     * @param collectiveDebtorAccount Sammel-Debitorenkonto bei {@code COLLECTIVE}
     * @param customerAccounts   Kunde (BT-44, exakt) → Debitorenkonto bei {@code PER_CUSTOMER}
     * @param revenueAccounts    Schlüssel {@code <Geschäftsfall>:<Steuersatz>} (z. B. {@code DOMESTIC_STANDARD:19})
     *                           oder {@code <Geschäftsfall>} → Erlöskonto
     * @param origin             Herkunft (Kopfzeile #8, max. 2 Zeichen)
     * @param exportedBy         Exportiert von (Kopfzeile #9)
     * @param dictationShortcut  Diktatkürzel (Kopfzeile #18, 2 oder 4 Großbuchstaben)
     * @param lockRecords        Festschreibung (Kopfzeile #21 und Feld #114)
     * @param bookingTextTemplate Buchungstext (Feld #14, max. 60 Zeichen) mit Platzhaltern
     *                           {@code {invoiceNumber}}, {@code {customerName}}, {@code {invoiceDate:<Pattern>}}
     */
    public record Datev(
            @DefaultValue("false") boolean enabled,
            String consultantNumber,
            String clientNumber,
            @DefaultValue("01-01") String fiscalYearStart,
            @DefaultValue("4") int accountLength,
            @DefaultValue("03") String chartOfAccounts,
            @DefaultValue("COLLECTIVE") DebtorStrategy debtorStrategy,
            String collectiveDebtorAccount,
            Map<String, String> customerAccounts,
            Map<String, RevenueAccount> revenueAccounts,
            @DefaultValue("RE") String origin,
            @DefaultValue("") String exportedBy,
            @DefaultValue("") String dictationShortcut,
            @DefaultValue("true") boolean lockRecords,
            @DefaultValue("Rechnung {invoiceNumber} {customerName}") String bookingTextTemplate) {

        public Datev {
            customerAccounts = customerAccounts == null ? Map.of() : Map.copyOf(customerAccounts);
            revenueAccounts = revenueAccounts == null ? Map.of() : Map.copyOf(revenueAccounts);
        }
    }
}
