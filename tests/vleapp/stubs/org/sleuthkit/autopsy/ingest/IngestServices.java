/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.ingest;
/** Test boundary only: avoid starting application ingest threads in a standalone JVM. */
public final class IngestServices {
    private static final IngestServices INSTANCE=new IngestServices();
    public static IngestServices getInstance(){return INSTANCE;}
    public void fireModuleContentEvent(ModuleContentEvent event){ }
}
