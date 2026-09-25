package de.hofmannit.erechnung.web;

import java.io.IOException;

import de.hofmannit.erechnung.admin.SetupService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Leitet beim ersten Aufruf alle Seiten auf den Einrichtungs-Assistenten um, solange kein
 * aktiver Mandant oder kein Admin-Passwort vorhanden ist (ADR 0013). Statische Ressourcen und
 * der Assistent selbst bleiben erreichbar.
 */
/** Läuft vor dem Zugriffsschutz: Während der Einrichtung gilt die Umleitung, nicht die Sperre. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SetupRedirectFilter extends OncePerRequestFilter {

    private final SetupService setup;

    public SetupRedirectFilter(SetupService setup) {
        this.setup = setup;
    }

    static boolean exempt(String path) {
        return path.startsWith("/einrichtung") || path.startsWith("/css/") || path.startsWith("/webjars/") || path.equals("/error")
                || path.equals("/favicon.ico");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return exempt(path) || !"GET".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (setup.needed()) {
            response.sendRedirect(request.getContextPath() + "/einrichtung");
            return;
        }
        chain.doFilter(request, response);
    }
}
