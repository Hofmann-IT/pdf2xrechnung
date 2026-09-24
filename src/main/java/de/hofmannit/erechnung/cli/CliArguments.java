package de.hofmannit.erechnung.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Einfache Auswertung von {@code befehl --name wert --flag}. Keine externe Bibliothek.
 */
public final class CliArguments {

    private final String command;
    private final Map<String, String> options = new LinkedHashMap<>();
    private final List<String> flags = new ArrayList<>();

    private CliArguments(String command) {
        this.command = command;
    }

    public static CliArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            return new CliArguments(null);
        }
        CliArguments result = new CliArguments(args[0]);
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String name = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    result.options.put(name, args[++i]);
                } else {
                    result.flags.add(name);
                }
            }
        }
        return result;
    }

    public String command() {
        return command;
    }

    public Optional<String> option(String name) {
        return Optional.ofNullable(options.get(name));
    }

    public String require(String name) {
        return option(name).orElseThrow(() -> new IllegalArgumentException("Option --" + name + " fehlt"));
    }

    public boolean flag(String name) {
        return flags.contains(name);
    }

    /** Bekannte CLI-Befehle; alles andere startet die Anwendung normal. */
    public static boolean isCliCommand(String[] args) {
        return args != null && args.length > 0 && (args[0].equals("calibrate") || args[0].equals("reprocess"));
    }
}
