/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import com.google.gson.JsonObject;
import java.util.concurrent.Callable;

/** Ensures offline cleanup never runs on an open case and reopens after failure. */
final class ArtifactCleanerOperation {
    private ArtifactCleanerOperation() { }
    static JsonObject run(Callable<Void> close, Callable<Boolean> isOpen,
            Callable<JsonObject> apply, Callable<Void> reopen) throws Exception {
        Exception failure = null;
        JsonObject result = null;
        try {
            close.call();
            if (isOpen.call()) throw new IllegalStateException("Der Fall konnte nicht geschlossen werden.");
            result = apply.call();
        } catch (Exception ex) {
            failure = ex;
        } finally {
            if (!isOpen.call()) {
                try { reopen.call(); }
                catch (Exception ex) {
                    if (failure != null) ex.addSuppressed(failure);
                    throw ex;
                }
            }
        }
        if (failure != null) throw failure;
        return result;
    }
}
