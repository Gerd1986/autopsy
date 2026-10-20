/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.datasourceprocessors.vleapp;

import com.google.gson.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.sleuthkit.datamodel.*;

/** Streams normalized records into case-scoped custom Data Artifact types. */
public final class VleappImporter {
    private static final String MODULE = "VLEAPP Export Import";
    private VleappImporter() { }

    private static BlackboardAttribute.Type attribute(Blackboard bb, String name, String label) throws Exception {
        return bb.getOrAddAttributeType(name, BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.STRING, label);
    }
    private static String text(JsonElement value) {
        return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }
    private static void add(List<BlackboardAttribute> out, BlackboardAttribute.Type type, String value) {
        out.add(new BlackboardAttribute(type, MODULE, value));
    }
    public static long importRecords(SleuthkitCase sk, long dataSourceId, Path directory,
            Map<Path, AbstractFile> files, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        JsonObject manifest;
        try (java.io.Reader reader=Files.newBufferedReader(directory.resolve("manifest.json"), StandardCharsets.UTF_8)) {
            manifest=JsonParser.parseReader(reader).getAsJsonObject();
        }
        Blackboard bb=sk.getBlackboard();
        BlackboardAttribute.Type rowType=attribute(bb,"VLEAPP_ROW","VLEAPP record number");
        BlackboardAttribute.Type tableType=attribute(bb,"VLEAPP_TABLE","VLEAPP table");
        BlackboardAttribute.Type categoryType=attribute(bb,"VLEAPP_CATEGORY","VLEAPP category");
        BlackboardAttribute.Type moduleType=attribute(bb,"VLEAPP_MODULE","VLEAPP parser module");
        BlackboardAttribute.Type sourceType=attribute(bb,"VLEAPP_ORIGINAL_SOURCE","VLEAPP original source (report metadata)");
        BlackboardAttribute.Type jsonType=attribute(bb,"VLEAPP_VALUES_JSON","VLEAPP original row (JSON array)");
        long total=0;
        for (JsonElement entry:manifest.getAsJsonArray("tables")) {
            if(cancelled.getAsBoolean())throw new InterruptedException("VLEAPP import cancelled; committed records remain visible.");
            JsonObject t=entry.getAsJsonObject();String key=t.get("key").getAsString();
            String typeName=t.get("type_name").getAsString();
            if(!key.matches("[a-f0-9]{24}") || !typeName.matches("VLEAPP_[A-Z]+_[a-f0-9]{24}"))throw new IOException("Invalid VLEAPP manifest type");
            Path report=directory.resolve("report").toAbsolutePath().normalize();
            Path source=report.resolve(t.get("source").getAsString()).normalize();
            if(!source.startsWith(report))throw new IOException("Invalid VLEAPP source path");
            AbstractFile content=files.get(source);
            if(content==null)throw new IOException("Imported source missing: "+source);
            BlackboardArtifact.Type type=bb.getOrAddArtifactType(typeName,"VLEAPP · "+text(t.get("category"))+" · "+text(t.get("name")));
            List<BlackboardAttribute.Type> columns=new ArrayList<>();int col=0;
            for(JsonElement header:t.getAsJsonArray("headers")) {
                columns.add(attribute(bb,"VLEAPP_C_"+key+"_"+col++,text(header)));
            }
            String rowsName=t.get("rows_file").getAsString();
            if(!rowsName.equals(key+".jsonl"))throw new IOException("Invalid row file");
            long row=0;
            try(BufferedReader reader=Files.newBufferedReader(directory.resolve(rowsName),StandardCharsets.UTF_8)) {
                String line;
                while((line=reader.readLine())!=null) {
                    List<JsonArray> batch=new ArrayList<>();
                    batch.add(JsonParser.parseString(line).getAsJsonArray());
                    while(batch.size()<500 && (line=reader.readLine())!=null)batch.add(JsonParser.parseString(line).getAsJsonArray());
                    List<BlackboardArtifact> posted=new ArrayList<>();
                    SleuthkitCase.CaseDbTransaction tx=sk.beginTransaction();
                    try {
                        for(JsonArray values:batch) {
                            if(cancelled.getAsBoolean())throw new InterruptedException("VLEAPP import cancelled; committed records remain visible.");
                            if(values.size()!=columns.size())throw new IOException("VLEAPP row width mismatch");
                            List<BlackboardAttribute> attrs=new ArrayList<>();
                            for(int i=0;i<values.size();i++)add(attrs,columns.get(i),text(values.get(i)));
                            add(attrs,rowType,Long.toString(++row));add(attrs,tableType,text(t.get("table")));
                            add(attrs,categoryType,text(t.get("category")));add(attrs,moduleType,text(t.get("module")));
                            add(attrs,sourceType,text(t.get("original_source")));add(attrs,jsonType,values.toString());
                            posted.add(bb.newDataArtifact(type,content.getId(),dataSourceId,attrs,null,tx));
                        }
                        tx.commit();
                    } catch(Exception ex) { tx.rollback();throw ex; }
                    total+=posted.size();bb.postArtifacts(posted,MODULE,null);
                    progress.accept("VLEAPP: "+total+" Datensätze · "+text(t.get("name")));
                }
            }
            if(row!=t.get("rows").getAsLong())throw new IOException("VLEAPP row count mismatch");
        }
        return total;
    }
}
