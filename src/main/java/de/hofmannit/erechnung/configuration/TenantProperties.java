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
     */
    public record Tenant(
            String id,
            String name,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("") String inboxSubdirectory,
            List<String> profiles,
            Map<String, String> fixedValues) {

        public Tenant {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            fixedValues = fixedValues == null ? Map.of() : Map.copyOf(fixedValues);
        }
    }
}
