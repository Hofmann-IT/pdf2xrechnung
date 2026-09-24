package de.hofmannit.erechnung.ledger;

/** Auslöser eines Processing-Runs (Vorgabe Abschnitt 15.2, ADR 0004). */
public enum RunTrigger {
    /** Automatisch durch den Watcher. */
    AUTO,
    /** Manuell ausgelöste technische Neuverarbeitung desselben Quelldokuments. */
    MANUAL_REPROCESS,
    /**
     * Wiederanlauf nach Neustart: Der vorherige Run wurde durch einen Absturz/Neustart
     * abgebrochen und mit {@code PROCESSING_FAILED} abgeschlossen; dieser Run referenziert ihn
     * über {@code parent_run_id}. Kein Benutzer, keine Begründung, kein fachlicher Reprocess.
     */
    RESTART_RECOVERY
}
