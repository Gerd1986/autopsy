/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.datasourceprocessors.vleapp;

import com.google.gson.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
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

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) ? object.getAsJsonArray(key) : new JsonArray();
    }

    /** UTC conversion follows LAVA object_columns, not SQLite's generic INTEGER affinity. */
    private static Instant instant(JsonElement value, JsonObject spec) {
        if(value==null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean())return null;
        String input=text(value).trim();if(input.isEmpty())return null;
        try {
            if(input.matches("[+-]?\\d+(?:\\.\\d+)?")) {
                BigDecimal seconds=new BigDecimal(input);
                String unit=text(spec.get("unit"));
                if(unit.equals("auto")) {
                    BigDecimal magnitude=seconds.abs();
                    // Untyped legacy timestamps need an epoch-sized number; counters stay text.
                    if(magnitude.compareTo(new BigDecimal("100000000"))<0)return null;
                    unit=magnitude.compareTo(new BigDecimal("100000000000000000"))>=0?"nanoseconds":
                        magnitude.compareTo(new BigDecimal("100000000000000"))>=0?"microseconds":
                        magnitude.compareTo(new BigDecimal("100000000000"))>=0?"milliseconds":"seconds";
                }
                if(unit.equals("milliseconds"))seconds=seconds.movePointLeft(3);
                else if(unit.equals("microseconds"))seconds=seconds.movePointLeft(6);
                else if(unit.equals("nanoseconds"))seconds=seconds.movePointLeft(9);
                BigDecimal whole=seconds.setScale(0,RoundingMode.FLOOR);
                Instant result=Instant.ofEpochSecond(whole.longValueExact(),seconds.subtract(whole).movePointRight(9).longValue());
                int year=result.atOffset(ZoneOffset.UTC).getYear();
                return year>=1 && year<=9999?result:null;
            }
            String iso=input.replace(' ','T');
            if(iso.endsWith("TUTC"))iso=iso.substring(0,iso.length()-4)+"Z";
            try {return OffsetDateTime.parse(iso).toInstant();}catch(DateTimeException ex) { }
            if(spec.has("naive_utc") && spec.get("naive_utc").getAsBoolean()) {
                return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
            }
        } catch(ArithmeticException | DateTimeException ex) { /* Invalid cells remain verbatim. */ }
        return null;
    }

    private static String formatted(JsonElement value, JsonObject spec) {
        if(text(spec.get("kind")).equals("date")) {
            try {return LocalDate.parse(text(value)).format(DateTimeFormatter.ofPattern("dd.MM.uuuu"));}
            catch(DateTimeException ex){return text(value);}
        }
        Instant stamp=instant(value,spec);if(stamp==null)return text(value);
        String fraction=stamp.getNano()==0?"":"."+String.format(Locale.ROOT,"%09d",stamp.getNano()).replaceFirst("0+$","");
        return DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss",Locale.ROOT).withZone(ZoneOffset.UTC).format(stamp)+fraction+" UTC";
    }

    private static Double coordinate(JsonElement value, double limit) {
        if(value==null || !value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean())return null;
        try {
            double number=Double.parseDouble(text(value).trim().replace(',','.'));
            return Double.isFinite(number) && Math.abs(number)<=limit?number:null;
        } catch(NumberFormatException ex){return null;}
    }

    private static BlackboardArtifact.ARTIFACT_TYPE geoType(JsonObject table) {
        String name=text(table.get("name")).toLowerCase(Locale.ROOT);
        if(name.matches(".*(favorite|favourite|bookmark|saved place).*"))return BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_BOOKMARK;
        if(name.matches(".*last known.*"))return BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_LAST_KNOWN_LOCATION;
        if(name.matches(".*(destination|search|recent|charge location|contact|phonebook|route).*"))return BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_SEARCH;
        return name.matches(".*(position|gps|gnss|dev loc|trackpoint).*" )?
            BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_TRACKPOINT:BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_SEARCH;
    }

    private static void addGeo(Blackboard bb, long dataSourceId, AbstractFile content,
            JsonObject table, JsonArray values, List<BlackboardAttribute> original,
            BlackboardArtifact record, List<BlackboardArtifact> posted, SleuthkitCase.CaseDbTransaction tx) throws Exception {
        for(JsonElement element:array(table,"geo_pairs")) {
            JsonObject pair=element.getAsJsonObject();
            Double latitude=coordinate(values.get(pair.get("latitude").getAsInt()),90);
            Double longitude=coordinate(values.get(pair.get("longitude").getAsInt()),180);
            if(latitude==null || longitude==null)continue;
            // Keep the custom report row and expose a linked standard GPS artifact to Autopsy's map/filter.
            List<BlackboardAttribute> attrs=new ArrayList<>();
            for(BlackboardAttribute attr:original)attrs.add(new BlackboardAttribute(attr.getAttributeType(),MODULE,attr.getValueString()));
            attrs.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LATITUDE,MODULE,latitude));
            attrs.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE,MODULE,longitude));
            String label="VLEAPP · "+text(table.get("name"))+" · "+text(pair.get("label"));
            JsonArray headers=table.getAsJsonArray("headers");
            for(int i=0;i<headers.size();i++) {
                if(text(headers.get(i)).toLowerCase(Locale.ROOT).matches("name|label|address|destination|poi name") && !text(values.get(i)).isBlank()) {
                    label+=" · "+text(values.get(i));break;
                }
            }
            attrs.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_NAME,MODULE,label));
            attrs.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT,MODULE,record.getArtifactID()));
            for(JsonElement column:array(table,"time_columns")) {
                JsonObject spec=column.getAsJsonObject();
                if(!text(spec.get("kind")).equals("datetime"))continue;
                Instant stamp=instant(values.get(spec.get("index").getAsInt()),spec);
                if(stamp!=null){attrs.add(new BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME,MODULE,stamp.getEpochSecond()));break;}
            }
            posted.add(bb.newDataArtifact(new BlackboardArtifact.Type(geoType(table)),content.getId(),dataSourceId,attrs,null,tx));
        }
    }
    /** Resolve persisted report files, never FileManager's directory-only progress callbacks. */
    public static Map<Path, AbstractFile> resolveSources(Content dataSource, Path directory,
            BooleanSupplier cancelled) throws Exception {
        Path base=directory.toAbsolutePath().normalize();
        Path report=base.resolve("report");
        JsonObject manifest;
        try(java.io.Reader reader=Files.newBufferedReader(base.resolve("manifest.json"),StandardCharsets.UTF_8)) {
            manifest=JsonParser.parseReader(reader).getAsJsonObject();
        }
        Set<Path> wanted=new HashSet<>();
        for(JsonElement item:manifest.getAsJsonArray("tables")) {
            Path target=report.resolve(item.getAsJsonObject().get("source").getAsString()).normalize();
            if(!target.startsWith(report) || target.equals(report))throw new IOException("Invalid VLEAPP source path");
            wanted.add(target);
        }
        Map<Path,AbstractFile> result=new HashMap<>();
        Deque<Map.Entry<Content,Path>> pending=new ArrayDeque<>();
        pending.push(new AbstractMap.SimpleImmutableEntry<>(dataSource,base));
        while(!pending.isEmpty()) {
            if(cancelled.getAsBoolean())throw new InterruptedException("VLEAPP source resolution cancelled");
            Map.Entry<Content,Path> parent=pending.pop();
            for(Content child:parent.getKey().getChildren()) {
                Path target=parent.getValue().resolve(child.getName()).normalize();
                if(!(child instanceof AbstractFile))continue;
                AbstractFile file=(AbstractFile)child;
                if(wanted.contains(target) && file.isFile())result.put(target,file);
                if(file.isDir() && wanted.stream().anyMatch(p->p.startsWith(target))) {
                    pending.push(new AbstractMap.SimpleImmutableEntry<>(child,target));
                }
            }
        }
        if(result.size()!=wanted.size()) {
            Set<Path> missing=new HashSet<>(wanted);missing.removeAll(result.keySet());
            throw new IOException("VLEAPP report files not found in added data source: "+missing);
        }
        return result;
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
                columns.add(attribute(bb,"VLEAPP_C_"+key+"_"+col,"VLEAPP field "+(col+1)+": "+text(header)));
                col++;
            }
            Map<Integer,JsonObject> timeColumns=new HashMap<>();
            Map<Integer,BlackboardAttribute.Type> rawColumns=new HashMap<>();
            for(JsonElement item:array(t,"time_columns")) {
                JsonObject spec=item.getAsJsonObject();int index=spec.get("index").getAsInt();
                if(index<0 || index>=columns.size())throw new IOException("Invalid time column");
                timeColumns.put(index,spec);
                rawColumns.put(index,attribute(bb,"VLEAPP_RAW_"+key+"_"+index,"VLEAPP original field "+(index+1)+": "+text(t.getAsJsonArray("headers").get(index))));
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
                            for(int i=0;i<values.size();i++) {
                                String raw=text(values.get(i));JsonObject spec=timeColumns.get(i);
                                add(attrs,columns.get(i),spec==null?raw:formatted(values.get(i),spec));
                                if(spec!=null)add(attrs,rawColumns.get(i),raw);
                            }
                            add(attrs,rowType,Long.toString(++row));add(attrs,tableType,text(t.get("table")));
                            add(attrs,categoryType,text(t.get("category")));add(attrs,moduleType,text(t.get("module")));
                            add(attrs,sourceType,text(t.get("original_source")));add(attrs,jsonType,values.toString());
                            BlackboardArtifact record=bb.newDataArtifact(type,content.getId(),dataSourceId,attrs,null,tx);
                            posted.add(record);
                            addGeo(bb,dataSourceId,content,t,values,attrs,record,posted,tx);
                        }
                        tx.commit();
                    } catch(Exception ex) { tx.rollback();throw ex; }
                    total+=batch.size();bb.postArtifacts(posted,MODULE,null);
                    progress.accept("VLEAPP: "+total+" Datensätze · "+text(t.get("name")));
                }
            }
            if(row!=t.get("rows").getAsLong())throw new IOException("VLEAPP row count mismatch");
        }
        return total;
    }
}
