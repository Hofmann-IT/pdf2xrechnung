package de.hofmannit.erechnung.web;

import java.nio.file.NoSuchFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Einheitliche, verständliche Fehlerseiten; technische Details nur im Log. */
@ControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NotFoundException e, Model model) {
        model.addAttribute("title", "Nicht gefunden");
        model.addAttribute("message", e.getMessage());
        return "error";
    }

    @ExceptionHandler(NoSuchFileException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String missingFile(NoSuchFileException e, Model model) {
        model.addAttribute("title", "Datei nicht vorhanden");
        model.addAttribute("message", "Die angeforderte Datei existiert nicht mehr.");
        return "error";
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public String tooLarge(MaxUploadSizeExceededException e, Model model) {
        model.addAttribute("title", "Datei zu groß");
        model.addAttribute("message", "Die hochgeladene Datei überschreitet die zulässige Größe.");
        return "error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String generic(Exception e, Model model) {
        log.error("Unbehandelter Fehler in der Oberfläche", e);
        model.addAttribute("title", "Unerwarteter Fehler");
        model.addAttribute("message", "Der Vorgang konnte nicht abgeschlossen werden. Details stehen im Anwendungsprotokoll.");
        return "error";
    }

    /** Ressource existiert nicht (Run, Artefakt, Prüfvorgang). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
