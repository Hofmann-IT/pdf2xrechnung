# =====================================================================================
# PDF-zu-E-Rechnung – Container-Image (Phase 6)
#
# Zwei Stufen: Build mit Maven (Tests laufen standardmäßig mit), Laufzeit mit reinem JRE 21.
# Zur Laufzeit findet keine Netzwerkkommunikation statt außer zum konfigurierten SMTP-Server;
# alle Validierungsressourcen liegen im Image unter /app/validator.
#
#   docker build -t pdf-zu-erechnung .
#   docker build --build-arg SKIP_TESTS=true -t pdf-zu-erechnung .     (nur wenn die Tests
#                                                                        bereits anderweitig liefen)
# =====================================================================================

# ---- Build -------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SKIP_TESTS=false
WORKDIR /build

# Abhängigkeiten zuerst (Layer-Cache), dann Quellen
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
COPY config ./config
COPY profiles ./profiles
COPY validator ./validator
RUN mvn -q -B verify -DskipTests=${SKIP_TESTS}

# ---- Laufzeit ----------------------------------------------------------------------
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="PDF-zu-E-Rechnung" \
      org.opencontainers.image.description="On-Premises PDF-zu-E-Rechnung Converter und E-Rechnungs-Validator" \
      org.opencontainers.image.vendor="Hofmann IT"

ENV TZ=Europe/Berlin \
    LANG=de_DE.UTF-8 \
    JAVA_OPTS="-Xmx1g -XX:+UseSerialGC -Dfile.encoding=UTF-8"

# Unprivilegierter Dienstbenutzer; alle Arbeitsverzeichnisse gehören ihm.
RUN groupadd --system --gid 1000 erechnung \
 && useradd --system --uid 1000 --gid erechnung --home-dir /app --shell /usr/sbin/nologin erechnung \
 && mkdir -p /app/config /app/profiles /app/validator /app/inbox /app/processing /app/output /app/failed \
             /app/manual-review /app/rejected /app/archive /app/data /app/inbound-validation /app/logs /app/examples \
 && chown -R erechnung:erechnung /app

WORKDIR /app
COPY --from=build --chown=erechnung:erechnung /build/target/pdf-zu-erechnung.jar /app/pdf-zu-erechnung.jar
# Validierungsressourcen (KoSIT-Konfiguration) sind Teil des Images: kein Download zur Laufzeit.
COPY --from=build --chown=erechnung:erechnung /build/validator /app/validator
# Beispielkonfiguration und -profil zum Kopieren in die Volumes (werden nicht automatisch aktiv).
COPY --from=build --chown=erechnung:erechnung /build/config /app/examples/config
COPY --from=build --chown=erechnung:erechnung /build/profiles /app/examples/profiles

USER erechnung
EXPOSE 8080
VOLUME ["/app/inbox", "/app/processing", "/app/output", "/app/archive", "/app/config", "/app/profiles", "/app/data"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pdf-zu-erechnung.jar \"$@\"", "--"]
