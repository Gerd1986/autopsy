/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
public final class BackendHarness {
    public static void main(String[] args) throws Exception {
        JsonObject request=new Gson().fromJson(Files.readString(Path.of(args[2]),StandardCharsets.UTF_8),JsonObject.class);
        System.out.println(ArtifactFactoryBackend.run(List.of(args[0]),Path.of(args[1]),request));
    }
}
