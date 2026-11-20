package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import de.hofmannit.erechnung.admin.SetupService;
import de.hofmannit.erechnung.admin.UserAccountService;
import de.hofmannit.erechnung.admin.UserAccountService.Account;
import de.hofmannit.erechnung.admin.UserAccountService.LoginResult;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anmeldung und Zugriffsregeln für die gesamte Oberfläche (ADR 0015).
 *
 * <ul>
 *   <li>Alle Seiten verlangen eine Anmeldung per Formular ({@code /anmelden}); frei bleiben nur
 *       statische Ressourcen, die Rechnungsvorlagen, die Fehlerseite und – solange noch kein
 *       Administrator existiert – der Einrichtungs-Assistent.</li>
 *   <li>Verwaltung, Export-Einstellungen und der Assistent nach der Vergabe des ersten
 *       Administratorkontos sind Administratoren vorbehalten.</li>
 *   <li>Sitzung per Cookie, CSRF-Schutz für alle schreibenden Anfragen (Token als verstecktes
 *       Formularfeld, von Thymeleaf über {@code th:action} gesetzt).</li>
 *   <li>Gesperrte oder in der Rolle geänderte Konten verlieren ihre Sitzung mit der nächsten
 *       Anfrage ({@link AccountStateFilter}).</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String ROLE_PREFIX = "ROLE_";
    private static final LoginUrlAuthenticationEntryPoint LOGIN_PAGE = new LoginUrlAuthenticationEntryPoint("/anmelden");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SetupService setup, UserAccountService accounts) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/webjars/**", "/vorlagen/**", "/favicon.svg", "/favicon.ico", "/error", "/anmelden").permitAll()
                        .requestMatchers("/einrichtung", "/einrichtung/**")
                        .access((authentication, context) -> new AuthorizationDecision(setup.passwordMissing() || isAdmin(authentication.get())))
                        .requestMatchers("/verwaltung", "/verwaltung/**", "/rechnungen/export/einstellungen", "/rechnungen/export/einstellungen/**")
                        .hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/anmelden").loginProcessingUrl("/anmelden")
                        .usernameParameter("benutzer").passwordParameter("passwort")
                        .defaultSuccessUrl("/", false)
                        .failureHandler(SecurityConfig::loginFailed))
                .logout(logout -> logout.logoutUrl("/abmelden").logoutSuccessUrl("/anmelden?abgemeldet"))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(SecurityConfig::loginRequired))
                // Die manuelle Prüfung zeigt die Original-PDF in einem Rahmen derselben Anwendung; fremde Seiten dürfen nicht einbetten
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .addFilterBefore(new AccountStateFilter(accounts), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Einzige Anmeldequelle. Als Bean deklariert, damit Spring Boot keinen Standardbenutzer mit
     * generiertem Passwort anlegt.
     */
    @Bean
    AuthenticationProvider accountAuthenticationProvider(UserAccountService accounts) {
        return new AccountAuthenticationProvider(accounts);
    }

    static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getAuthorities().stream().anyMatch(a -> (ROLE_PREFIX + "ADMIN").equals(a.getAuthority()));
    }

    private static void loginFailed(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, ServletException {
        String target = e instanceof LockedException ? "/anmelden?gesperrt" : "/anmelden?fehler";
        new SimpleUrlAuthenticationFailureHandler(target).onAuthenticationFailure(request, response, e);
    }

    /**
     * Ohne gültige Sitzung: zur Anmeldeseite. HTMX-Teilanfragen erhalten 401 mit {@code HX-Redirect},
     * damit die ganze Seite wechselt und nicht die Anmeldeseite in ein Fragment geladen wird.
     */
    private static void loginRequired(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException, ServletException {
        if (request.getHeader("HX-Request") != null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("HX-Redirect", request.getContextPath() + "/anmelden");
            return;
        }
        LOGIN_PAGE.commence(request, response, e);
    }

    static List<GrantedAuthority> authorities(Account account) {
        return List.of(new SimpleGrantedAuthority(ROLE_PREFIX + account.role().name()));
    }

    /** Prüft Benutzername und Passwort über {@link UserAccountService} (Konten, Betreiber-Zugang, Sperre nach Fehlversuchen). */
    static final class AccountAuthenticationProvider implements AuthenticationProvider {

        private final UserAccountService accounts;

        AccountAuthenticationProvider(UserAccountService accounts) {
            this.accounts = accounts;
        }

        @Override
        public Authentication authenticate(Authentication authentication) {
            Object credentials = authentication.getCredentials();
            LoginResult result = accounts.login(authentication.getName(), credentials == null ? null : credentials.toString());
            if (result.locked()) {
                throw new LockedException("Benutzer vorübergehend gesperrt");
            }
            Account account = result.account().orElseThrow(() -> new BadCredentialsException("Benutzername oder Passwort falsch"));
            return UsernamePasswordAuthenticationToken.authenticated(account.username(), null, authorities(account));
        }

        @Override
        public boolean supports(Class<?> type) {
            return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
        }
    }

    /** Beendet Sitzungen, deren Konto inzwischen gesperrt wurde oder eine andere Rolle hat. */
    static final class AccountStateFilter extends OncePerRequestFilter {

        private final UserAccountService accounts;

        AccountStateFilter(UserAccountService accounts) {
            this.accounts = accounts;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication current = SecurityContextHolder.getContext().getAuthentication();
            if (current instanceof UsernamePasswordAuthenticationToken && current.isAuthenticated()) {
                Optional<Account> account = accounts.find(current.getName());
                boolean valid = account.isPresent() && account.get().active()
                        && current.getAuthorities().stream().anyMatch(a -> (ROLE_PREFIX + account.get().role().name()).equals(a.getAuthority()));
                if (!valid) {
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.invalidate();
                    }
                    SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        }
    }
}
