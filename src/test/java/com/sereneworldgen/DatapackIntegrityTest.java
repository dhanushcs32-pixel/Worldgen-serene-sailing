package com.sereneworldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Static checks on the shipped data pack. No Minecraft bootstrap needed. */
public class DatapackIntegrityTest {
    private static final String DENSITY_DIR = "/data/sereneworldgen/worldgen/density_function";
    private static final Pattern MOD_REF = Pattern.compile("\"sereneworldgen:([a-z0-9_/]+)\"");

    private static Path densityDir() throws URISyntaxException {
        URL url = DatapackIntegrityTest.class.getResource(DENSITY_DIR);
        assertNotNull(url, "mod density_function directory must be on the classpath");
        return Path.of(url.toURI());
    }

    @Test
    @DisplayName("Every sereneworldgen:<name> reference in the density functions resolves to a shipped file")
    void allReferencesResolve() throws IOException, URISyntaxException {
        Path dir = densityDir();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                Matcher m = MOD_REF.matcher(Files.readString(file));
                while (m.find()) {
                    // type ids (e.g. spawn_island_bias) share their name with a shipped file, so this covers both
                    if (!Files.exists(dir.resolve(m.group(1) + ".json"))) {
                        problems.add(file.getFileName() + " -> sereneworldgen:" + m.group(1));
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "dangling references: " + problems);
    }

    @Test
    @DisplayName("The mod must not override vanilla overworld density functions (that changes every default world)")
    void doesNotOverrideVanillaTerrain() throws URISyntaxException {
        Path modDataDir = densityDir().getParent().getParent().getParent();
        for (String vanilla : List.of("depth", "sloped_cheese", "offset", "factor", "jaggedness", "continents", "erosion")) {
            Path override = modDataDir.resolve("minecraft/worldgen/density_function/overworld/" + vanilla + ".json");
            assertFalse(Files.exists(override), "mod must not ship a vanilla override for overworld/" + vanilla);
        }
    }

    @Test
    @DisplayName("The starter island stays small: configured outer radius is at most 200 blocks")
    void starterIslandRadiusIsSmall() throws IOException, URISyntaxException {
        String json = Files.readString(densityDir().resolve("spawn_island_bias.json"));
        Matcher m = Pattern.compile("\"radius\"\\s*:\\s*([0-9.]+)").matcher(json);
        assertTrue(m.find(), "spawn_island_bias.json must set an explicit radius");
        double radius = Double.parseDouble(m.group(1));
        assertTrue(radius > 0 && radius <= 200.0,
                "radius " + radius + " would recreate the oversized (~900 block) landmass");
    }
}
