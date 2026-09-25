package de.hofmannit.erechnung.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import de.hofmannit.erechnung.configuration.AppProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Zugriffsschutz des Verwaltungsbereichs ohne Spring-Kontext (ADR 0012). */
class AdminAuthFilterTest {

    private static AdminAuthFilter filter(String password) {
        return new AdminAuthFilter(new AppProperties(null, null, null, null, null, null, null, new AppProperties.Admin("admin", password)));
    }

    private static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void protectedPathsOnly() {
        assertThat(AdminAuthFilter.isProtected("/verwaltung")).isTrue();
        assertThat(AdminAuthFilter.isProtected("/verwaltung/x")).isTrue();
        assertThat(AdminAuthFilter.isProtected("/rechnungen/export/einstellungen")).isTrue();
        assertThat(AdminAuthFilter.isProtected("/rechnungen/export")).isFalse();
        assertThat(AdminAuthFilter.isProtected("/")).isFalse();
        assertThat(AdminAuthFilter.isProtected("/verwaltungx")).isFalse();
    }

    @Test
    void withoutConfiguredPasswordTheAreaIsLockedNotOpen() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/verwaltung");
        req.addHeader("Authorization", basic("admin:"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("").doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("ADMIN_PASSWORD");
        assertThat(chain.getRequest()).as("Anfrage darf nicht weitergeleitet werden").isNull();
    }

    @Test
    void wrongOrMissingCredentialsGet401WithChallenge() throws Exception {
        for (String header : new String[] {null, "Bearer x", basic("admin:falsch"), basic("root:geheim"), basic("nurname"), "Basic %%%"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/verwaltung");
            if (header != null) {
                req.addHeader("Authorization", header);
            }
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter("geheim").doFilter(req, res, chain);
            assertThat(res.getStatus()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(res.getHeader("WWW-Authenticate")).startsWith("Basic realm=");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void correctCredentialsPassThroughAndUnprotectedPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/verwaltung");
        req.addHeader("Authorization", basic("admin:geheim"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("geheim").doFilter(req, res, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);

        MockHttpServletRequest open = new MockHttpServletRequest("GET", "/rechnungen");
        MockFilterChain openChain = new MockFilterChain();
        filter("").doFilter(open, new MockHttpServletResponse(), openChain);
        assertThat(openChain.getRequest()).isNotNull();
    }
}
