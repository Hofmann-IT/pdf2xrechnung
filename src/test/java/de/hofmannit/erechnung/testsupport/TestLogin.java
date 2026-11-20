package de.hofmannit.erechnung.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Anmeldung in MockMvc-Tests (ADR 0015): Die Anmeldung wird wie nach einem erfolgreichen
 * Formular-Login gesetzt (Benutzername als Principal, Rolle als Berechtigung).
 */
public final class TestLogin {

    private TestLogin() {
    }

    /** Angemeldeter Benutzer mit Rolle {@code ADMIN} oder {@code USER}. */
    public static RequestPostProcessor as(String username, String role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    /** Alle Anfragen eines Tests laufen als Administrator {@code username} und tragen ein gültiges CSRF-Token. */
    public static MockMvcBuilderCustomizer defaultAdministrator(String username) {
        return builder -> builder.defaultRequest(get("/").with(csrf()).with(as(username, "ADMIN")));
    }
}
