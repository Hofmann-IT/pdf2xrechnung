package de.hofmannit.erechnung.inboundvalidation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.mustangproject.ZUGFeRD.Profile;
import org.mustangproject.ZUGFeRD.Profiles;

/**
 * Bekannte Spezifikationskennungen (BT-24) und ihre Bezeichnung. Die ZUGFeRD-/Factur-X- und
 * XRechnung-Kennungen stammen aus {@link Profiles} der eingebundenen Mustang-Version und werden
 * nicht selbst definiert. Unbekannte Kennungen werden als "nicht eindeutig bestimmbar" gemeldet.
 */
public final class ProfileCatalog {

    /** Peppol-BIS-Billing-3.0-Kennung, wie sie der in Mustang enthaltene CII→UBL-Konverter standardmäßig setzt. */
    static final String PEPPOL_BILLING_3 = "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";

    private static final String XRECHNUNG_PREFIX_CURRENT = "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_";
    private static final String XRECHNUNG_PREFIX_LEGACY = "urn:cen.eu:en16931:2017#compliant#urn:xoev-de:kosit:standard:xrechnung_";

    private static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        for (String name : new String[] {"MINIMUM", "BASICWL", "BASIC", "EN16931", "EXTENDED", "XRECHNUNG"}) {
            Profile p = Profiles.getByName(name);
            KNOWN.put(p.getID(), zugferdLabel(p));
        }
        KNOWN.put(PEPPOL_BILLING_3, "Peppol BIS Billing 3.0 (EN 16931)");
    }

    private ProfileCatalog() {
    }

    /**
     * Ermittelt die Bezeichnung zur Spezifikationskennung.
     *
     * @return Bezeichnung oder leer, wenn die Kennung unbekannt ist
     */
    public static Optional<String> describe(String customizationId) {
        if (customizationId == null || customizationId.isBlank()) {
            return Optional.empty();
        }
        String id = customizationId.trim();
        String known = KNOWN.get(id);
        if (known != null) {
            return Optional.of(known);
        }
        if (id.startsWith(XRECHNUNG_PREFIX_CURRENT)) {
            return Optional.of("XRechnung " + id.substring(XRECHNUNG_PREFIX_CURRENT.length()));
        }
        if (id.startsWith(XRECHNUNG_PREFIX_LEGACY)) {
            return Optional.of("XRechnung " + id.substring(XRECHNUNG_PREFIX_LEGACY.length()) + " (ältere Kennung)");
        }
        return Optional.empty();
    }

    /** XRechnung-Kennung (aktuelle oder ältere Form) – entscheidet über die Pflicht zur KoSIT-Prüfung. */
    public static boolean isXRechnung(String customizationId) {
        if (customizationId == null) {
            return false;
        }
        String id = customizationId.trim();
        return id.startsWith(XRECHNUNG_PREFIX_CURRENT) || id.startsWith(XRECHNUNG_PREFIX_LEGACY);
    }

    /** Version aus der XRechnung-Kennung, z. B. {@code 3.0}; leer, wenn nicht XRechnung. */
    public static Optional<String> xrechnungVersion(String customizationId) {
        if (!isXRechnung(customizationId)) {
            return Optional.empty();
        }
        String id = customizationId.trim();
        String prefix = id.startsWith(XRECHNUNG_PREFIX_CURRENT) ? XRECHNUNG_PREFIX_CURRENT : XRECHNUNG_PREFIX_LEGACY;
        String rest = id.substring(prefix.length());
        int hash = rest.indexOf('#');
        return Optional.of(hash < 0 ? rest : rest.substring(0, hash));
    }

    private static String zugferdLabel(Profile p) {
        return switch (p.getName()) {
            case "XRECHNUNG" -> "XRechnung 3.0 (ZUGFeRD/Factur-X-Profil XRECHNUNG)";
            case "EN16931" -> "EN 16931 (ZUGFeRD/Factur-X-Profil EN 16931 / COMFORT)";
            default -> "ZUGFeRD/Factur-X-Profil " + p.getXMPName();
        };
    }
}
