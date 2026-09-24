package de.hofmannit.erechnung.ledger;

/** Auslöser eines Processing-Runs (Vorgabe Abschnitt 15.2). */
public enum RunTrigger {
    /** Automatisch durch den Watcher. */
    AUTO,
    /** Manuell ausgelöste technische Neuverarbeitung desselben Quelldokuments. */
    MANUAL_REPROCESS
}
