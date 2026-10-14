/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.casemodule;

import java.nio.file.Path;
import java.util.List;

/** Standalone integration driver; intentionally runs the production bridge. */
public final class BridgeHarness {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        boolean cancel = args[5].equals("cancel");
        try {
            Path result = NandPreprocessor.preprocess(Path.of(args[0]), Path.of(args[1]),
                    Path.of(args[2]), Path.of(args[3]), List.of(args[4]),
                    () -> cancel && System.nanoTime() - start > 500_000_000L);
            System.out.println(result);
        } catch (InterruptedException ex) {
            System.err.println(ex.getMessage());
            System.exit(3);
        }
    }
}
