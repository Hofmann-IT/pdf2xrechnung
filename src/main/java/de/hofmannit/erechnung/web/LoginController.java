package de.hofmannit.erechnung.web;

import java.security.Principal;

import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.admin.UserAccountService;
import de.hofmannit.erechnung.admin.UserAccountService.Account;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Anmeldeseite und Seite „Mein Konto" zum Ändern des eigenen Passworts (ADR 0015). */
@Controller
public class LoginController {

    private final UserAccountService accounts;

    public LoginController(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/anmelden")
    public String login(Principal principal, @RequestParam(required = false) String fehler, @RequestParam(required = false) String gesperrt,
                        @RequestParam(required = false) String abgemeldet, @RequestParam(required = false) String eingerichtet, Model model) {
        if (principal != null) {
            return "redirect:/";
        }
        if (fehler != null) {
            model.addAttribute("error", "Benutzername oder Passwort ist nicht korrekt.");
        }
        if (gesperrt != null) {
            model.addAttribute("error", "Zu viele Fehlversuche. Dieser Benutzername ist für einige Minuten gesperrt.");
        }
        if (abgemeldet != null) {
            model.addAttribute("notice", "Sie wurden abgemeldet.");
        }
        if (eingerichtet != null) {
            model.addAttribute("notice", "Einrichtung abgeschlossen. Melden Sie sich mit dem vergebenen Benutzernamen und Passwort an.");
        }
        return "anmelden";
    }

    @GetMapping("/konto")
    public String account(Principal principal, Model model) {
        Account account = accounts.find(principal.getName()).orElseThrow();
        model.addAttribute("account", account);
        model.addAttribute("active", "konto");
        return "konto";
    }

    @PostMapping("/konto/passwort")
    public String changeOwnPassword(Principal principal, @RequestParam String currentPassword, @RequestParam String password,
                                    @RequestParam String passwordRepeat, RedirectAttributes redirect) {
        try {
            accounts.changeOwnPassword(principal.getName(), currentPassword, password, passwordRepeat);
            redirect.addFlashAttribute("notice", "Passwort geändert.");
        } catch (SettingsException e) {
            redirect.addFlashAttribute("error", String.join("; ", e.errors()));
        }
        return "redirect:/konto";
    }
}
