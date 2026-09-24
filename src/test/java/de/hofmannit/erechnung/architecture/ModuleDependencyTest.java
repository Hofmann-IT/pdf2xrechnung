package de.hofmannit.erechnung.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Sichert die Modulregeln aus docs/adr/README.md ohne zusätzliche Bibliothek ab:
 * erlaubte Abhängigkeitsrichtungen und Zyklenfreiheit, ermittelt aus den import-Anweisungen
 * unter src/main/java.
 */
class ModuleDependencyTest {

    private static final String BASE = "de.hofmannit.erechnung";
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "de", "hofmannit", "erechnung");

    private static final Set<String> MODULES = Set.of(
            "model", "security", "configuration", "extraction", "mapping", "plausibility", "generation",
            "validation", "inboundvalidation", "ledger", "archive", "dispatch", "export", "watcher", "web", "cli");

    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("model", Set.of()),
            Map.entry("security", Set.of()),
            Map.entry("configuration", Set.of("model", "security")),
            Map.entry("extraction", Set.of("configuration", "model", "security")),
            Map.entry("mapping", Set.of("extraction", "configuration", "model", "security")),
            Map.entry("plausibility", Set.of("mapping", "configuration", "model")),
            Map.entry("generation", Set.of("mapping", "configuration", "model", "security")),
            Map.entry("validation", Set.of("configuration", "model", "security")),
            Map.entry("inboundvalidation", Set.of("validation", "configuration", "model", "security")),
            Map.entry("ledger", Set.of("configuration", "model", "security")),
            Map.entry("archive", Set.of("ledger", "configuration", "model", "security")),
            Map.entry("dispatch", Set.of("ledger", "configuration", "model", "security")),
            Map.entry("export", Set.of("ledger", "configuration", "model", "security")),
            Map.entry("watcher", Set.of("extraction", "mapping", "plausibility", "generation", "validation",
                    "ledger", "archive", "dispatch", "configuration", "model", "security")),
            // Das Wurzelpaket (Einstiegsklasse) darf den CLI-Einstieg kennen; es ist kein Modul.
            Map.entry("web", allExcept("web", "cli")),
            Map.entry("cli", allExcept("cli", "web")));

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?" + Pattern.quote(BASE) + "\\.([a-z]+)\\.", Pattern.MULTILINE);

    @Test
    void everyModulePackageExists() {
        for (String module : MODULES) {
            assertThat(SOURCE_ROOT.resolve(module).resolve("package-info.java"))
                    .as("package-info.java für Modul %s", module)
                    .exists();
        }
    }

    @Test
    void onlyAllowedDependenciesBetweenModules() throws IOException {
        Map<String, Set<String>> actual = actualDependencies();
        List<String> violations = new ArrayList<>();
        actual.forEach((from, targets) -> targets.forEach(to -> {
            if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
                violations.add(from + " -> " + to);
            }
        }));
        assertThat(violations).as("unerlaubte Modulabhängigkeiten").isEmpty();
    }

    @Test
    void noCyclicDependencies() throws IOException {
        Map<String, Set<String>> graph = actualDependencies();
        // Zusätzlich die erlaubte Matrix selbst auf Zyklen prüfen, damit ADR und Test konsistent bleiben.
        assertThat(findCycle(ALLOWED)).as("Zyklus in der erlaubten Abhängigkeitsmatrix").isEmpty();
        assertThat(findCycle(graph)).as("Zyklus in den tatsächlichen Abhängigkeiten").isEmpty();
    }

    private static Set<String> allExcept(String self, String... excluded) {
        Set<String> result = new HashSet<>(MODULES);
        result.remove(self);
        for (String e : excluded) {
            result.remove(e);
        }
        return Set.copyOf(result);
    }

    private static Map<String, Set<String>> actualDependencies() throws IOException {
        Map<String, Set<String>> deps = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Path rel = SOURCE_ROOT.relativize(file);
                if (rel.getNameCount() < 2) {
                    continue; // ERechnungApplication im Wurzelpaket
                }
                String module = rel.getName(0).toString();
                assertThat(MODULES).as("unbekanntes Modul-Paket %s (Datei %s)", module, file).contains(module);
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = IMPORT.matcher(source);
                while (m.find()) {
                    String target = m.group(1);
                    if (!target.equals(module)) {
                        deps.computeIfAbsent(module, k -> new TreeSet<>()).add(target);
                    }
                }
            }
        }
        return deps;
    }

    /** Einfache Tiefensuche; liefert den ersten gefundenen Zyklus als Pfad. */
    private static List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> done = new HashSet<>();
        for (String start : new TreeSet<>(graph.keySet())) {
            Deque<String> path = new ArrayDeque<>();
            List<String> cycle = dfs(start, graph, done, new HashSet<>(), path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private static List<String> dfs(String node, Map<String, Set<String>> graph, Set<String> done,
                                    Set<String> onPath, Deque<String> path) {
        if (done.contains(node)) {
            return List.of();
        }
        if (!onPath.add(node)) {
            List<String> cycle = new ArrayList<>(path);
            cycle.add(node);
            return cycle;
        }
        path.addLast(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            List<String> cycle = dfs(next, graph, done, onPath, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.removeLast();
        onPath.remove(node);
        done.add(node);
        return List.of();
    }
}
