/* Licensed under the Apache License, Version 2.0. */
import java.util.ArrayList;
import java.util.Collections;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.LocalFilesDataSource;
import org.sleuthkit.datamodel.Score;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.VirtualDirectory;
public final class NativeCaseFixture {
    public static void main(String[] args) throws Exception {
        if(args[0].equals("create")) {
            SleuthkitCase sk=SleuthkitCase.newCase(args[1]);
            try {
                SleuthkitCase.CaseDbTransaction tx=sk.beginTransaction();
                LocalFilesDataSource ds;
                try { ds=sk.addLocalFilesDataSource("artifact-wizard-test", "Synthetic evidence", "UTC", tx); tx.commit(); }
                catch(Exception ex) { tx.rollback(); throw ex; }
                VirtualDirectory file=sk.addVirtualDirectory(ds.getId(),"evidence");
                ArrayList<BlackboardAttribute> attrs=new ArrayList<>();
                attrs.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_NAME_PERSON,"Artifact Wizard Test","Müller"));
                file.newDataArtifact(new BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_CONTACT),attrs);
                file.newAnalysisResult(new BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT),
                    Score.SCORE_NOTABLE,"Preserved analysis result","","",Collections.emptyList());
            } finally { sk.close(); }
        } else {
            SleuthkitCase sk=SleuthkitCase.openCase(args[1]);
            try {
                if(!sk.getBlackboardArtifacts(BlackboardArtifact.ARTIFACT_TYPE.TSK_CONTACT.getTypeID()).isEmpty()) throw new AssertionError("Data Artifact remains");
                if(sk.getBlackboardArtifacts(BlackboardArtifact.ARTIFACT_TYPE.TSK_INTERESTING_FILE_HIT.getTypeID()).size()!=1) throw new AssertionError("Analysis Result lost");
            } finally { sk.close(); }
        }
        System.out.println("PASS: native SleuthkitCase "+args[0]);
    }
}
