/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openide.modules.InstalledFileLocator;

/** Runs the bundled stdlib-only tools without writing into the installation. */
public final class ArtifactFactoryBackend {
    private static final Gson GSON = new Gson();
    private ArtifactFactoryBackend() { }

    public static JsonObject run(String tool, JsonObject request) throws Exception {
        return run(tool, request, () -> false);
    }

    public static JsonObject run(String tool, JsonObject request, java.util.function.BooleanSupplier cancelled) throws Exception {
        if (!tool.equals("factory.py") && !tool.equals("cleaner.py") && !tool.equals("geotime.py") && !tool.equals("vleapp.py")) {
            throw new IOException("Unknown Artifact Wizard tool");
        }
        File script = InstalledFileLocator.getDefault().locate("artifact_factory/" + tool, null, false);
        if (script == null) {
            throw new IOException("Artifact Wizard files are missing. Reinstall this Autopsy build.");
        }
        File runtime = InstalledFileLocator.getDefault().locate("nand/python/python.exe", null, false);
        String python = runtime != null ? runtime.getAbsolutePath()
                : System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
        return run(List.of(python), script.toPath(), request, cancelled);
    }

    static JsonObject run(List<String> python, Path script, JsonObject request) throws Exception {
        return run(python, script, request, () -> false);
    }

    private static JsonObject run(List<String> python, Path script, JsonObject request, java.util.function.BooleanSupplier cancelled) throws Exception {
        Path response = Files.createTempFile("autopsy-artifact-wizard-", ".json");
        Process process = null;
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>(python);
            command.add(script.toAbsolutePath().toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true).redirectOutput(response.toFile());
            builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            try (java.io.OutputStream input = process.getOutputStream()) {
                input.write(GSON.toJson(request).getBytes(StandardCharsets.UTF_8));
            }
            int waits = 0;
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new InterruptedException("Operation cancelled");
                if (++waits >= 9000) { // bounded long-running preview/pipeline/backup
                    throw new IOException("Artifact Wizard operation timed out");
                }
            }
            String text = Files.readString(response, StandardCharsets.UTF_8);
            JsonObject result;
            try {
                result = GSON.fromJson(text, JsonObject.class);
            } catch (RuntimeException ex) {
                throw new IOException("Artifact Wizard returned an invalid response: " + text.substring(0, Math.min(2000, text.length())), ex);
            }
            if (result == null || !result.has("ok") || !result.get("ok").getAsBoolean() || process.exitValue() != 0) {
                throw new IOException(result != null && result.has("error") ? result.get("error").getAsString() : "Artifact Wizard failed");
            }
            return result;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
            Files.deleteIfExists(response);
        }
    }
}
