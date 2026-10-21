/* Licensed under the Apache License, Version 2.0. */
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.sleuthkit.autopsy.casemodule.services.FileManager;
import org.sleuthkit.autopsy.datasourceprocessors.vleapp.VleappImporter;
import org.sleuthkit.autopsy.datamodel.utils.IconsUtil;
import org.sleuthkit.autopsy.geolocation.datamodel.WaypointBuilder;
import org.sleuthkit.autopsy.geolocation.datamodel.Waypoint;
import org.sleuthkit.datamodel.*;

/** Exercise the real FileManager, native case, persisted values and map's waypoint builder. */
public final class VleappSemanticsTest {
    private static String value(BlackboardArtifact a,String name)throws Exception {
        for(BlackboardAttribute attr:a.getAttributes())if(attr.getAttributeType().getTypeName().equals(name))return attr.getValueString();
        throw new AssertionError("Attribute missing: "+name);
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path work=Path.of(args[0]);Path user=work.resolve("semantic-user");Files.createDirectories(user.resolve("var/log"));System.setProperty("netbeans.user",user.toString());
        JsonObject expected=JsonParser.parseString(Files.readString(work.resolve("semantics-expectations.json"))).getAsJsonObject();
        JsonObject manifest=JsonParser.parseString(Files.readString(work.resolve("manifest.json"))).getAsJsonObject();
        Path db=work.resolve("semantics.db");long dsId;
        SleuthkitCase sk=SleuthkitCase.newCase(db.toString());
        try {
            LocalFilesDataSource ds=new FileManager(sk).addLocalFilesDataSource(UUID.randomUUID().toString(),"Semantic test","UTC",null,List.of(work.resolve("report").toString()),f->{});dsId=ds.getId();
            check(VleappImporter.importRecords(sk,dsId,work,VleappImporter.resolveSources(ds,work,()->false),()->false,s->{})==expected.get("rows").getAsLong(),"Wrong row count");
        }finally{sk.close();}
        sk=SleuthkitCase.openCase(db.toString());
        try {
            int tableIndex=0;
            for(JsonElement entry:manifest.getAsJsonArray("tables")) {
                JsonObject table=entry.getAsJsonObject();String key=table.get("key").getAsString();
                BlackboardArtifact.Type type=sk.getBlackboard().getArtifactType(table.get("type_name").getAsString());
                check(type.getTypeName().startsWith("VLEAPP_"+expected.getAsJsonArray("types").get(tableIndex++).getAsString()+"_"),"Specific icon category lost");
                String icon=IconsUtil.getIconFilePath(type.getTypeName(),type.getTypeID());
                check(VleappSemanticsTest.class.getResource(icon)!=null,"Packaged icon missing: "+icon);
                List<String> originals=Files.readAllLines(work.resolve(table.get("rows_file").getAsString()));
                List<BlackboardArtifact> records=sk.getBlackboardArtifacts(type.getTypeID());
                records.sort(Comparator.comparingLong(a->a.getArtifactID()));
                for(BlackboardArtifact record:records) {
                    int row=Integer.parseInt(value(record,"VLEAPP_ROW"))-1;
                    check(JsonParser.parseString(value(record,"VLEAPP_VALUES_JSON")).equals(JsonParser.parseString(originals.get(row))),"Original row altered");
                    for(JsonElement col:table.getAsJsonArray("time_columns")) {
                        int i=col.getAsJsonObject().get("index").getAsInt();
                        JsonElement raw=JsonParser.parseString(originals.get(row)).getAsJsonArray().get(i);
                        check(value(record,"VLEAPP_RAW_"+key+"_"+i).equals(raw.isJsonNull()?"":raw.getAsString()),"Original time lost");
                    }
                    if(tableIndex==1 && row<expected.getAsJsonArray("display").size()) {
                        check(value(record,"VLEAPP_C_"+key+"_3").equals(expected.getAsJsonArray("display").get(row).getAsString()),"Wrong UTC presentation for row "+row+": "+value(record,"VLEAPP_C_"+key+"_3"));
                    }
                }
            }
            check(WaypointBuilder.getAllWaypoints(sk).size()==expected.get("geo").getAsInt(),"Map cannot parse all expected points");
            List<Waypoint> filtered=new ArrayList<>();
            List<BlackboardArtifact.ARTIFACT_TYPE> mapTypes=List.of(BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_TRACKPOINT,BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_SEARCH,BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_BOOKMARK);
            // This is the actual source/type/time filtering API used by the map UI.
            WaypointBuilder.getAllWaypoints(sk,List.of((DataSource)sk.getContentById(dsId)),mapTypes,true,0,true,result->{check(result.isSuccessfullyParsed(),"Map parsing failed");filtered.addAll(result.getItems());});
            check(filtered.size()==expected.get("geo").getAsInt(),"Map source/type filters hide imported points");
            filtered.clear();
            WaypointBuilder.getAllWaypoints(sk,List.of((DataSource)sk.getContentById(dsId)),mapTypes,false,10,false,result->filtered.addAll(result.getItems()));
            check(filtered.stream().anyMatch(p->Objects.equals(p.getTimestamp(),expected.get("utc_seconds").getAsLong())),"UTC map time filter lost timestamp");
            Set<Long> linked=new HashSet<>();
            for(BlackboardArtifact.ARTIFACT_TYPE type:mapTypes) {
                List<BlackboardArtifact> points=sk.getBlackboardArtifacts(type);
                String countKey=type==BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_TRACKPOINT?"trackpoints":type==BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_SEARCH?"searches":"bookmarks";
                check(points.size()==(expected.has(countKey)?expected.get(countKey).getAsInt():0),"Wrong map semantics: "+type);
                for(BlackboardArtifact point:points) {
                    check(point.getDataSource().getId()==dsId,"Wrong map source");
                    BlackboardAttribute assoc=point.getAttribute(new BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT));
                    BlackboardArtifact original=sk.getBlackboardArtifact(assoc.getValueLong());
                    check(original.getArtifactTypeName().startsWith("VLEAPP_"),"Map link lost");linked.add(original.getArtifactID());
                    check(value(point,"VLEAPP_VALUES_JSON").equals(value(original,"VLEAPP_VALUES_JSON")),"Map provenance lost");
                }
            }
            for(String category:List.of("VEHICLE","GEAR","DOOR","POWER","BATTERY","SETTINGS","DIAGNOSTIC","ACTIVITY","TEMPERATURE","RADIO","CHIP","TIME","NAVIGATION","DATABASE","WEATHER")) {
                String path=IconsUtil.getIconFilePath("VLEAPP_"+category+"_0123456789abcdef01234567",99999);
                check(VleappSemanticsTest.class.getResource(path)!=null,"Icon resource missing: "+category);
                javax.swing.ImageIcon image=new javax.swing.ImageIcon(VleappSemanticsTest.class.getResource(path));
                check(image.getIconWidth()==16 && image.getIconHeight()==16,"Invalid icon: "+category);
            }
            check(IconsUtil.getIconFilePath("VLEAPP_ACTIVITY_0123456789abcdef01234567",99999,"Gear Status").endsWith("vleapp-gear.png"),"Existing gear type icon lost");
            check(IconsUtil.getIconFilePath("VLEAPP_RECORD_0123456789abcdef01234567",99999,"Door Status").endsWith("vleapp-door.png"),"Existing door type icon lost");
            check(!IconsUtil.getIconFilePath("VLEAPP_TEMPERATURE_0123456789abcdef01234567",99999,"Outdoor Temperature").endsWith("vleapp-door.png"),"Door substring false positive");
            check(linked.size()==expected.get("geo").getAsInt(),"Unexpected duplicate map points");
            System.out.println("PASS: real map waypoints, row linkage, reopened semantic icons, UTC display and preserved raw values");
        }finally{sk.close();}
    }
}

