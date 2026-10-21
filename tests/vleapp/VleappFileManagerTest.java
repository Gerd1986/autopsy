/* Licensed under the Apache License, Version 2.0. */
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.sleuthkit.autopsy.casemodule.services.FileManager;
import org.sleuthkit.autopsy.datasourceprocessors.vleapp.VleappImporter;
import org.sleuthkit.datamodel.*;

/** Real production FileManager registration, source resolution and artifact creation. */
public final class VleappFileManagerTest {
    public static void main(String[] args)throws Exception {
        Path work=Path.of(args[0]);SleuthkitCase sk=SleuthkitCase.newCase(work.resolve("file-manager.db").toString());
        try {
            List<AbstractFile> callbacks=new ArrayList<>();
            LocalFilesDataSource ds=new FileManager(sk).addLocalFilesDataSource(UUID.randomUUID().toString(),
                "VLEAPP integration test","UTC",null,List.of(work.resolve("report").toString()),callbacks::add);
            // FileManager currently reports directories only. Old DSP mistakenly used this as a file index.
            System.out.println("FileManager callbacks for regular files: "+callbacks.stream().filter(AbstractFile::isFile).count());
            Map<Path,AbstractFile> files=VleappImporter.resolveSources(ds,work,()->false);
            JsonObject manifest=JsonParser.parseString(Files.readString(work.resolve("manifest.json"))).getAsJsonObject();
            Set<Long> sourceIds=new HashSet<>();for(AbstractFile file:files.values()){if(!file.isFile())throw new AssertionError("Directory used as report source");sourceIds.add(file.getId());}
            long count=VleappImporter.importRecords(sk,ds.getId(),work,files,()->false,s->{});
            if(count!=manifest.get("rows").getAsLong())throw new AssertionError("Missing artifacts");
            long actual=0;Set<Long> usedSources=new HashSet<>();
            for(JsonElement item:manifest.getAsJsonArray("tables")) {
                BlackboardArtifact.Type type=sk.getBlackboard().getArtifactType(item.getAsJsonObject().get("type_name").getAsString());
                if(!sk.getBlackboard().getArtifactTypesInUse(ds.getId()).contains(type))throw new AssertionError("Type missing from result-tree query");
                for(BlackboardArtifact artifact:sk.getBlackboardArtifacts(type.getTypeID())) {
                    if(!sourceIds.contains(artifact.getObjectID()) || artifact.getDataSource().getId()!=ds.getId())throw new AssertionError("Wrong source linkage");
                    actual++;usedSources.add(artifact.getObjectID());
                }
            }
            if(actual!=count || usedSources.size()!=sourceIds.size())throw new AssertionError("Incomplete source coverage");
            try {VleappImporter.resolveSources(ds,work,()->true);throw new AssertionError("Cancellation ignored");}catch(InterruptedException expected){ }
            System.out.println("PASS: actual FileManager directory-only callback, persisted report sources, result-tree types and "+count+" linked artifacts");
        }finally{sk.close();}
    }
}
