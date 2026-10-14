/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.casemodule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Runs the bundled, read-only NAND cleanup before registering a derived image. */
final class NandPreprocessor {

    private NandPreprocessor() {
    }

    static Path preprocess(Path input, Path config, Path exportDirectory, Path script,
            List<String> python, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (!Files.isRegularFile(input) || !Files.isReadable(input)
                || !Files.isRegularFile(config) || !Files.isReadable(config)) {
            throw new IOException("NAND preprocessing requires a readable dump and JSON configuration.");
        }
        if (script == null || !Files.isRegularFile(script)) {
            throw new IOException("Bundled NAND preprocessor is missing. Reinstall this Autopsy build.");
        }
        checkCancelled(cancelled);
        Path base = exportDirectory.resolve("nand-preprocessed");
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "dump-");
        Path output = directory.resolve("clean.ubi");
        Path log = directory.resolve("preprocessor.log");
        Path profile = directory.resolve("device-profile.json");
        Files.copy(config, profile);
        List<String> command = new ArrayList<>(python);
        command.add(script.toAbsolutePath().toString());
        command.add(input.toAbsolutePath().toString());
        command.add("--profile");
        command.add(profile.toAbsolutePath().toString());
        command.add("--output");
        command.add(output.toAbsolutePath().toString());
        command.add("--ecc");
        command.add("on");
        command.add("--uncorrectable");
        command.add("fail");
        Process process = null;
        boolean succeeded = false;
        try {
            checkCancelled(cancelled);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            // Never write bytecode to the installed application directory.
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            process = builder.start();
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
            }
            checkCancelled(cancelled);
            if (process.exitValue() != 0) {
                throw new IOException("NAND preprocessing failed (exit " + process.exitValue()
                        + "). No data source was added. Details: " + log);
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0
                    || !Files.isRegularFile(directory.resolve("clean.ubi.report.json"))) {
                throw new IOException("NAND preprocessing did not produce a complete image and report. Details: " + log);
            }
            succeeded = true;
            return output;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                // The child must stop writing before incomplete files are removed.
                process.waitFor();
            }
            if (!succeeded) {
                // Preserve the config and log for diagnosis; never leave an incomplete image.
                Files.deleteIfExists(output);
                Files.deleteIfExists(directory.resolve("clean.ubi.report.json"));
                try (Stream<Path> paths = Files.list(directory)) {
                    for (Path path : (Iterable<Path>) paths::iterator) {
                        if (path.getFileName().toString().startsWith(".clean.ubi.")) {
                            Files.deleteIfExists(path);
                        }
                    }
                }
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("NAND preprocessing cancelled. No data source was added.");
        }
    }
}
