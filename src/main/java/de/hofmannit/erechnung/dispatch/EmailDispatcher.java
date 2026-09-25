package de.hofmannit.erechnung.dispatch;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.RuntimeConfig;
import de.hofmannit.erechnung.configuration.RuntimeSettings;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP-Versand (Vorgabe Abschnitt 22). Der konfigurierte SMTP-Server ist die einzige erlaubte
 * ausgehende Netzwerkverbindung. Zugangsdaten stammen ausschließlich aus Umgebungsvariablen
 * (siehe {@code config/application.yaml}).
 *
 * <p>Verwendete Spring-APIs: {@link JavaMailSenderImpl} und {@link MimeMessageHelper}
 * (spring-context-support, über spring-boot-starter-mail).
 */
@Component
public class EmailDispatcher {

    private final RuntimeSettings settings;
    /** Zugangsdaten kommen ausschließlich aus SMTP_USERNAME/SMTP_PASSWORD (YAML-Referenz auf Umgebungsvariablen). */
    private final AppProperties.Smtp credentials;

    public EmailDispatcher(RuntimeSettings settings, AppProperties properties) {
        this.settings = settings;
        this.credentials = properties.smtp();
    }

    /** Wirksame Server-Einstellungen (Verwaltung vor YAML, ADR 0012). */
    private RuntimeConfig.Smtp smtp() {
        return settings.current().smtp();
    }

    public boolean isEnabled() {
        return smtp().enabled();
    }

    public boolean hasCredentials() {
        return credentials.username() != null && !credentials.username().isBlank();
    }

    /** Versendete Nachricht (Message-ID für das Event). */
    public record SentMail(String messageId, List<String> to, List<String> cc, String subject, List<String> attachmentNames) {
    }

    public SentMail send(List<String> to, List<String> cc, String subject, String body, List<Path> attachments) throws DispatchException {
        RuntimeConfig.Smtp smtp = smtp();
        if (!smtp.enabled()) {
            throw new DispatchException("SMTP-Versand ist deaktiviert (app.smtp.enabled=false)");
        }
        if (smtp.host() == null || smtp.host().isBlank()) {
            throw new DispatchException("SMTP-Host ist nicht konfiguriert (app.smtp.host)");
        }
        if (to == null || to.isEmpty()) {
            throw new DispatchException("Keine Empfänger konfiguriert (postProcess.email.to)");
        }
        if (smtp.from() == null || smtp.from().isBlank()) {
            throw new DispatchException("Absender ist nicht konfiguriert (app.smtp.from)");
        }
        JavaMailSenderImpl sender = createSender(smtp);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(smtp.from());
            helper.setTo(to.toArray(new String[0]));
            if (cc != null && !cc.isEmpty()) {
                helper.setCc(cc.toArray(new String[0]));
            }
            helper.setSubject(subject == null ? "" : subject);
            helper.setText(body == null ? "" : body);
            for (Path attachment : attachments) {
                helper.addAttachment(attachment.getFileName().toString(), attachment.toFile());
            }
            sender.send(message);
            return new SentMail(message.getMessageID(), List.copyOf(to), cc == null ? List.of() : List.copyOf(cc), subject,
                    attachments.stream().map(p -> p.getFileName().toString()).toList());
        } catch (MessagingException | org.springframework.mail.MailException e) {
            throw new DispatchException("SMTP-Versand fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    JavaMailSenderImpl createSender() {
        return createSender(smtp());
    }

    JavaMailSenderImpl createSender(RuntimeConfig.Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        if (smtp.auth()) {
            sender.setUsername(credentials.username());
            sender.setPassword(credentials.password());
        }
        Properties props = sender.getJavaMailProperties();
        long timeoutMs = smtp.timeout() == null ? 30000 : smtp.timeout().toMillis();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(smtp.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(smtp.starttls()));
        props.put("mail.smtp.starttls.required", String.valueOf(smtp.starttls()));
        props.put("mail.smtp.ssl.enable", String.valueOf(smtp.ssl()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
        return sender;
    }
}
