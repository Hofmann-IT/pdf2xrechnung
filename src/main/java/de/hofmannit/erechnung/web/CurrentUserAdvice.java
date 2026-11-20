package de.hofmannit.erechnung.web;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Stellt allen Seiten den angemeldeten Benutzer und seine Rolle für Kopfzeile und Navigation bereit. */
@ControllerAdvice
public class CurrentUserAdvice {

    @ModelAttribute("currentUser")
    public String currentUser(Authentication authentication) {
        return authentication == null || authentication instanceof AnonymousAuthenticationToken ? null : authentication.getName();
    }

    @ModelAttribute("currentUserAdmin")
    public boolean currentUserAdmin(Authentication authentication) {
        return SecurityConfig.isAdmin(authentication);
    }
}
