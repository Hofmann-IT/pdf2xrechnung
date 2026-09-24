# Architecture Decision Records

Format je ADR: Status, Kontext, Entscheidung, Alternativen, Konsequenzen, Offene Punkte.
Ein ADR wird nie umgeschrieben; Änderungen erfolgen durch ein neues ADR, das das alte ablöst.

| Nr.  | Titel                                                    | Status      |
|------|----------------------------------------------------------|-------------|
| 0001 | [Extraktionsstrategie](0001-extraktionsstrategie.md)     | Akzeptiert  |
| 0002 | [Validator-Einbindung](0002-validator-einbindung.md)     | Akzeptiert  |
| 0003 | [Persistenz- und Ledger-Modell](0003-persistenz-ledger-modell.md) | Akzeptiert |
| 0004 | [Idempotenz und Reprocess](0004-idempotenz-reprocess.md) | Akzeptiert  |
| 0005 | [Inbound-Validierung](0005-inbound-validierung.md)       | Akzeptiert  |
| 0006 | [Archivierungsmodell](0006-archivierungsmodell.md)       | Akzeptiert  |
| 0007 | [Postprozess, Reprocess und CLI](0007-postprozess-reprocess-cli.md) | Akzeptiert |

## Modulabhängigkeiten (verbindlich, geprüft durch `ModuleDependencyTest`)

```
model, security                      (Basis, keine Abhängigkeiten)
configuration     -> model, security
extraction        -> configuration, model, security
mapping           -> extraction, configuration, model, security
plausibility      -> mapping, configuration, model
generation        -> mapping, configuration, model, security
validation        -> configuration, model, security
inboundvalidation -> validation, configuration, model, security
ledger            -> configuration, model, security
archive           -> ledger, configuration, model, security
dispatch          -> ledger, configuration, model, security
watcher           -> extraction, mapping, plausibility, generation, validation,
                     ledger, archive, dispatch, configuration, model, security
web               -> alle außer cli
cli               -> alle außer web
```
