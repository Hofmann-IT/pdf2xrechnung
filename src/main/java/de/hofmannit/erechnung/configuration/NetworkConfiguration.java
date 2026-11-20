package de.hofmannit.erechnung.configuration;

import java.net.InetAddress;
import java.time.Clock;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Bindet den Webserver gemäß {@link NetworkAccess} (ADR 0015, Phase B):
 * <ul>
 *   <li>lokal: HTTP nur auf der Loopback-Adresse,</li>
 *   <li>Firmennetz: zusätzlich ein HTTPS-Connector auf allen Schnittstellen; der lokale
 *       HTTP-Zugang bleibt auf der Loopback-Adresse,</li>
 *   <li>open: HTTP wie von {@code server.address}/{@code server.port} vorgegeben.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class NetworkConfiguration {

    @Bean
    public CertificateSource certificateSource(AppProperties properties) {
        return NetworkAccess.storeSource(properties.network().certificateStore());
    }

    @Bean
    public NetworkAccess networkAccess(AppProperties properties, @Value("${server.port:8080}") int httpPort, CertificateSource source,
                                       Clock clock) {
        return new NetworkAccess(properties.network(), httpPort, source, clock);
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> networkBinding(NetworkAccess access) {
        return new Binding(access);
    }

    /** Läuft nach den Customizern von Spring Boot, damit die Loopback-Bindung nicht überschrieben wird. */
    static final class Binding implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

        private final NetworkAccess access;

        Binding(NetworkAccess access) {
            this.access = access;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            if (access.effective() == NetworkAccess.Mode.OPEN) {
                return;
            }
            factory.setAddress(InetAddress.getLoopbackAddress());
            access.tls().ifPresent(tls -> factory.addAdditionalTomcatConnectors(httpsConnector(access.httpsPort(), tls)));
        }
    }

    /** HTTPS-Connector auf allen Schnittstellen mit dem Schlüssel aus dem übergebenen Speicher (TLS 1.2 und 1.3). */
    static Connector httpsConnector(int port, NetworkAccess.Tls tls) {
        Connector connector = new Connector(Http11NioProtocol.class.getName());
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);
        SSLHostConfig host = new SSLHostConfig();
        host.setProtocols("TLSv1.2+TLSv1.3");
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED);
        certificate.setCertificateKeystore(tls.keyStore());
        certificate.setCertificateKeyAlias(tls.alias());
        if (tls.keyPassword() != null) {
            certificate.setCertificateKeyPassword(new String(tls.keyPassword()));
        }
        host.addCertificate(certificate);
        connector.addSslHostConfig(host);
        return connector;
    }
}
