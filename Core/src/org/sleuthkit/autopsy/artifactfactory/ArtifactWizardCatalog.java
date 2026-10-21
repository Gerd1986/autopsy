/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import com.google.gson.*;
import java.util.*;
import org.sleuthkit.datamodel.*;

/** Metadata only: persisted names instead of case-specific numeric IDs in exported plugins. */
public final class ArtifactWizardCatalog {
    private ArtifactWizardCatalog() { }
    public static JsonObject attribute(BlackboardAttribute.Type type) {
        JsonObject f=new JsonObject();f.addProperty("name",type.getTypeName());
        f.addProperty("label",type.getDisplayName());f.addProperty("value_type",type.getValueType().name());return f;
    }
    public static Map<String,JsonObject> types(SleuthkitCase sk)throws Exception {
        Map<String,JsonObject> out=new TreeMap<>();
        for(BlackboardArtifact.Type type:sk.getArtifactTypes()) {
            if(type.getCategory()!=BlackboardArtifact.Category.DATA_ARTIFACT)continue;
            JsonObject spec=new JsonObject();spec.addProperty("type_name",type.getTypeName());spec.addProperty("display_name",type.getDisplayName());
            spec.addProperty("icon_path",org.sleuthkit.autopsy.datamodel.utils.IconsUtil.getIconFilePath(type.getTypeName(),type.getTypeID()));
            spec.add("attributes",new JsonArray());out.put(type.getDisplayName()+" ["+type.getTypeName()+"]",spec);
        }
        return out;
    }
    public static Map<String,JsonObject> attributes(SleuthkitCase sk)throws Exception {
        Map<String,JsonObject> out=new TreeMap<>();
        for(BlackboardAttribute.Type type:sk.getAttributeTypes())out.put(type.getDisplayName()+" ["+type.getTypeName()+"]",attribute(type));
        return out;
    }
    public static JsonArray fields(SleuthkitCase sk,String name)throws Exception {
        BlackboardArtifact.Type type=sk.getArtifactType(name);JsonArray out=new JsonArray();
        if(type==null)return out;
        Set<Integer> ids=new HashSet<>();
        try(SleuthkitCase.CaseDbQuery query=sk.executeQuery("SELECT DISTINCT attribute_type_id FROM blackboard_attributes WHERE artifact_type_id="+type.getTypeID())) {
            java.sql.ResultSet rs=query.getResultSet();while(rs.next())ids.add(rs.getInt(1));
        }
        for(BlackboardAttribute.Type field:sk.getAttributeTypes())if(ids.contains(field.getTypeID()))out.add(attribute(field));
        if(out.isEmpty())for(String standard:List.of("TSK_DATETIME","TSK_NAME","TSK_COMMENT"))out.add(attribute(sk.getAttributeType(standard)));
        return out;
    }
}
