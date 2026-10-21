# -*- coding: utf-8 -*-
"""Native Blackboard emission for exported Jython parsers; no case-global type IDs."""


def _wizard_string(sk, name, label, value, module):
    from org.sleuthkit.datamodel import BlackboardAttribute
    kind = sk.getBlackboard().getOrAddAttributeType(name, BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.STRING, label)
    return BlackboardAttribute(kind, module, _bb_text(value))


def _precision_attributes(sk, name, label, parsed, module):
    attrs = []
    if parsed.get('timestamp_raw') is not None:
        attrs.append(_wizard_string(sk, 'WIZARD_RAW_' + name, label + ' (original)', parsed['timestamp_raw'], module))
    if parsed.get('timestamp_epoch') is not None:
        attrs.append(_wizard_string(sk, 'WIZARD_UTC_' + name, label + ' (UTC, full precision)', parsed['timestamp_str'], module))
        attrs.append(_wizard_string(sk, 'WIZARD_EXACT_' + name, label + ' (exact Unix seconds)', parsed['timestamp_epoch_decimal'], module))
    return attrs


def _post_wizard_artifact(artifact, sk, meta, row, module):
    from java.util import ArrayList, Collections
    attrs = ArrayList()
    if row.get('timestamp_raw') is not None:
        for attr in _precision_attributes(sk, 'TSK_DATETIME', 'Timestamp', row, module): attrs.add(attr)
    attrs.add(_wizard_string(sk, 'WIZARD_ORIGINAL_ROW', 'Original source row (JSON)', json.dumps(row.get('original_row', {}), ensure_ascii=True), module))
    artifact.addAttributes(attrs)
    sk.getBlackboard().postArtifacts(Collections.singletonList(artifact), module, None)


def _write_schema_artifact(file, sk, meta, row, module):
    from org.sleuthkit.datamodel import BlackboardAttribute
    from java.util import ArrayList
    from jarray import array
    bb = sk.getBlackboard();schema = meta['artifact_schema'];validate_schema(schema)
    artifact_type = sk.getArtifactType(schema['type_name'])
    if artifact_type is None:
        if schema['type_name'].startswith('TSK_'): raise ValueError('Unknown standard artifact type')
        artifact_type = bb.getOrAddArtifactType(schema['type_name'], schema['display_name'])
    if artifact_type.getCategory().name() != 'DATA_ARTIFACT': raise ValueError('Select a Data Artifact type')
    attrs = ArrayList()
    for field in schema['attributes']:
        name = field['name'];value = row['fields'].get(name)
        if value is None: continue
        kind = field['value_type'];attribute_type = sk.getAttributeType(name)
        if attribute_type is None:
            if name.startswith('TSK_'): raise ValueError('Unknown standard attribute: ' + name)
            attribute_type = bb.getOrAddAttributeType(name, BlackboardAttribute.TSK_BLACKBOARD_ATTRIBUTE_VALUE_TYPE.valueOf(kind), field['label'])
        if attribute_type.getValueType().name() != kind: raise ValueError('Existing field has a different value type: ' + name)
        if kind == 'DATETIME':
            native = long(value['timestamp_epoch'])
            for attr in _precision_attributes(sk, name, field['label'], value, module): attrs.add(attr)
        elif kind in ('LONG', 'INTEGER'): native = long(value) if kind == 'LONG' else int(value)
        elif kind == 'DOUBLE': native = float(value)
        elif kind == 'BYTE':
            text = value[2:] if value.startswith('0x') else value
            native = array([int(text[i:i+2], 16) - (256 if int(text[i:i+2], 16)>127 else 0) for i in range(0, len(text), 2)], 'b')
        else: native = _bb_text(value)
        attrs.add(BlackboardAttribute(attribute_type, module, native))
    artifact = file.newDataArtifact(artifact_type, attrs)
    _post_wizard_artifact(artifact, sk, meta, row, module)
    if (not schema['type_name'].startswith('TSK_') and
            row['fields'].get('TSK_GEO_LATITUDE') is not None and row['fields'].get('TSK_GEO_LONGITUDE') is not None):
        from org.sleuthkit.datamodel import BlackboardArtifact
        copied = ArrayList()
        for attr in artifact.getAttributes():
            kind = attr.getAttributeType().getValueType().name()
            value = (attr.getValueLong() if kind in ('LONG','DATETIME') else
                     attr.getValueInt() if kind == 'INTEGER' else attr.getValueDouble() if kind == 'DOUBLE' else
                     attr.getValueBytes() if kind == 'BYTE' else attr.getValueString())
            copied.add(BlackboardAttribute(attr.getAttributeType(), module, value))
        copied.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT, module, long(artifact.getArtifactID())))
        category = schema.get('icon') or (schema['type_name'].split('_')[1] if schema['type_name'].startswith('VLEAPP_') else 'SEARCH')
        gps = ('TSK_GPS_BOOKMARK' if category == 'BOOKMARK' else
               'TSK_GPS_TRACKPOINT' if category in ('GEO','NAVIGATION') else 'TSK_GPS_SEARCH')
        point = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.valueOf(gps)), copied)
        from java.util import Collections
        bb.postArtifacts(Collections.singletonList(point), module, None)
    return artifact
