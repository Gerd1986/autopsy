/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
public final class CleanerLifecycleTest {
    public static void main(String[] args) throws Exception {
        for (int mode=0; mode<4; mode++) {
            final int scenario=mode;
            boolean[] open={true}; List<String> calls=new ArrayList<>(); boolean failed=false;
            try {
                ArtifactCleanerOperation.run(
                    () -> { calls.add("close"); if(scenario!=2) open[0]=false; if(scenario==3) throw new Exception("close failed"); return null; },
                    () -> open[0],
                    () -> { if(open[0]) throw new AssertionError("cleanup with an open case"); calls.add("apply"); if(scenario==1) throw new Exception("apply failed"); return new JsonObject(); },
                    () -> { calls.add("reopen"); open[0]=true; return null; });
            } catch (Exception ex) { failed=true; }
            if (!open[0] || failed!=(mode!=0)) throw new AssertionError("case lifecycle "+mode);
            List<String> expected=mode==2 ? List.of("close") : mode==3 ? List.of("close","reopen") : List.of("close","apply","reopen");
            if(!calls.equals(expected)) throw new AssertionError(calls.toString());
        }
        System.out.println("PASS: Cleanup runs only offline; closed case reopens after success, cleanup failure and close failure");
    }
}
