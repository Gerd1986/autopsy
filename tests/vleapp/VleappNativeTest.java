/* Licensed under the Apache License, Version 2.0. */
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.*;
import org.sleuthkit.datamodel.*;
import org.sleuthkit.autopsy.datasourceprocessors.vleapp.VleappImporter;
import org.sleuthkit.autopsy.datamodel.utils.IconsUtil;

public final class VleappNativeTest {
    private static void addFiles(SleuthkitCase sk, SpecialDirectory parent, Path local,
            SleuthkitCase.CaseDbTransaction tx)throws Exception {
        if(Files.isDirectory(local)) {
            LocalDirectory directory=sk.addLocalDirectory(parent.getId(),local.getFileName().toString(),tx);
            try(java.util.stream.Stream<Path> children=Files.list(local)) {
                for(Path child:(Iterable<Path>)children::iterator)addFiles(sk,directory,child,tx);
            }
        } else {
            sk.addLocalFile(local.getFileName().toString(),local.toAbsolutePath().toString(),Files.size(local),
                0,0,0,0,true,TskData.EncodingType.NONE,parent,tx);
        }
    }
    public static void main(String[] args)throws Exception {
        Path work=Path.of(args[0]);String mode=args[1];Path db=work.resolve("native-"+mode+".db");
        JsonObject manifest=JsonParser.parseString(Files.readString(work.resolve("manifest.json"))).getAsJsonObject();
        int expected=manifest.get("rows").getAsInt();
        SleuthkitCase sk=SleuthkitCase.newCase(db.toString());
        try {
            SleuthkitCase.CaseDbTransaction tx=sk.beginTransaction();LocalFilesDataSource ds;
            try {ds=sk.addLocalFilesDataSource("vleapp-test-"+mode,"VLEAPP test","UTC",tx);tx.commit();}catch(Exception ex){tx.rollback();throw ex;}
            // Same persisted directory/file layout as FileManager.addLocalFilesDataSource.
            // No hand-injected source map: the production resolver must discover actual files.
            tx=sk.beginTransaction();
            try {addFiles(sk,ds,work.resolve("report"),tx);tx.commit();}catch(Exception ex){tx.rollback();throw ex;}
            Map<Path,AbstractFile> files=VleappImporter.resolveSources(ds,work,()->false);
            if(files.isEmpty() || files.values().stream().anyMatch(f->!f.isFile()))throw new AssertionError("Missing real report files");
            AtomicBoolean cancel=new AtomicBoolean(mode.equals("cancel"));
            try {
                long count=VleappImporter.importRecords(sk,ds.getId(),work,files,cancel::get,s->{if(mode.equals("partial"))cancel.set(true);});
                if(!mode.equals("normal") || count!=expected)throw new AssertionError("Unexpected import result "+count);
            } catch(InterruptedException ex){if(mode.equals("normal"))throw ex;}
        }finally{sk.close();}
        sk=SleuthkitCase.openCase(db.toString());
        try {
            long count=0;
            for(JsonElement e:manifest.getAsJsonArray("tables")) {
                JsonObject t=e.getAsJsonObject();BlackboardArtifact.Type type=sk.getBlackboard().getArtifactType(t.get("type_name").getAsString());
                if(type==null)continue;
                List<BlackboardArtifact> records=sk.getBlackboardArtifacts(type.getTypeID());count+=records.size();
                if(!IconsUtil.getIconFilePath(type.getTypeName(),type.getTypeID()).endsWith("gps_trackpoint.png"))throw new AssertionError("Persistent category icon lost");
                if(!records.isEmpty()) {
                    BlackboardArtifact a=records.get(0);boolean unicode=false,json=false;
                    for(BlackboardAttribute attr:a.getAttributes()) {
                        if(attr.getValueString().contains("Müller"))unicode=true;
                        if(attr.getAttributeType().getTypeName().equals("VLEAPP_VALUES_JSON") && attr.getValueString().contains("base64") && attr.getValueString().contains("null"))json=true;
                    }
                    if(!unicode||!json)throw new AssertionError("Source values lost");
                }
            }
            long target=mode.equals("normal")?expected:mode.equals("partial")?500:0;
            if(count!=target)throw new AssertionError("Expected "+target+" committed rows, got "+count);
        }finally{sk.close();}
        System.out.println("PASS: native VLEAPP "+mode+", reopen, values and persistent icons");
    }
}
