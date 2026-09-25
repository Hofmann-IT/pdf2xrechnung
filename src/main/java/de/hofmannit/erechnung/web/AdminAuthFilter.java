package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import de.hofmannit.erechnung.configuration.AppProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Zugriffsschutz für den Verwaltungsbereich (ADR 0012): HTTP-Basic-Anmeldung für
 * {@code /verwaltung/**} und die Export-Einstellungen gegen {@code app.admin.username} und das
 * Passwort aus der Umgebungsvariable {@code ADMIN_PASSWORD}. Ohne gesetztes Passwort sind die
 * geschützten Seiten gesperrt (503), nie offen. Keine zusätzliche Bibliothek, kein Sitzungs-
 * zustand; der Browser sendet die Anmeldung je Anfrage.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    static final String REALM = "PDF-zu-E-Rechnung Verwaltung";

    private final AppProperties.Admin admin;

    public AdminAuthFilter(AppProperties properties) {
        this.admin = properties.admin();
    }

    static boolean isProtected(String path) {
        return path.equals("/verwaltung") || path.startsWith("/verwaltung/") || path.equals("/rechnungen/export/einstellungen");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !isProtected(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (admin == null || !admin.configured()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("<!DOCTYPE html><html lang=\"de\"><meta charset=\"utf-8\"><title>Verwaltung gesperrt</title>"
                    + "<body style=\"font-family:system-ui;margin:3rem\"><h1>Verwaltung gesperrt</h1>"
                    + "<p>Die Umgebungsvariable <code>ADMIN_PASSWORD</code> ist nicht gesetzt. Setzen Sie sie für den Dienst und starten Sie die Anwendung neu.</p></body></html>");
            return;
        }
        if (authorized(request.getHeader("Authorization"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<!DOCTYPE html><html lang=\"de\"><meta charset=\"utf-8\"><title>Anmeldung erforderlich</title>"
                + "<body style=\"font-family:system-ui;margin:3rem\"><h1>Anmeldung erforderlich</h1><p>Der Verwaltungsbereich verlangt Benutzername und Passwort.</p></body></html>");
    }

    boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        byte[] user = decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8);
        byte[] pass = decoded.substring(colon + 1).getBytes(StandardCharsets.UTF_8);
        boolean userOk = MessageDigest.isEqual(user, admin.username().getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(pass, admin.password().getBytes(StandardCharsets.UTF_8));
        return userOk & passOk;
    }
}
