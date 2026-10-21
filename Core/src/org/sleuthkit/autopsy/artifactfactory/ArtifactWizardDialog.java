/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Native front-end for the artifact-factory parser/template functionality. */
final class ArtifactWizardDialog extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private final JPanel content = new JPanel(new BorderLayout(8, 8));
    private final JLabel status = new JLabel("Vorschau laden und die Felder zuordnen.");
    private final JComboBox<String> type = new JComboBox<>(new String[]{"Geo-Track", "Last-Position", "Geo-Bookmark", "Event", "Mobile", "Bluetooth", "Call"});
    private final JComboBox<String> source = new JComboBox<>(new String[]{"csv", "sqlite", "regex", "dlt"});
    private final JTextField input = new JTextField(45);
    private final JTextField filename = new JTextField(30);
    private final JTextField module = new JTextField("Mein Artifact Parser", 30);
    private final JComboBox<String> separator = new JComboBox<>(new String[]{",", ";", "\\t", "|"});
    private final JComboBox<String> table = new JComboBox<>();
    private final JTextArea query = new JTextArea(3, 45);
    private final JTextArea regex = new JTextArea(3, 45);
    private final JCheckBox multiline = new JCheckBox("Mehrzeilige Treffer (MULTILINE + DOTALL)");
    private final java.util.List<Path> preprocessInputs = new java.util.ArrayList<>();
    private Path toolDirectory;
    private CaseInputStager caseInput;
    private final JLabel toolFolderLabel = new JLabel("Plugin-Ordner (Standard)");
    private final JLabel inputCount = new JLabel("Keine Zusatzdateien ausgewählt");
    private final DltTablePanel dltPreview = new DltTablePanel();
    private final JTable preview = new JTable();
    private final JPanel mappings = new JPanel(new GridBagLayout());
    private final Map<String, JComboBox<String>> mappingBoxes = new LinkedHashMap<>();
    private final Map<String,JsonObject> artifactCatalog=new LinkedHashMap<>();
    private final Map<String,JsonObject> attributeCatalog=new LinkedHashMap<>();
    private JsonObject artifactSchema;
    private final JTextField artifactName=new JTextField("Mein Artefakt",25);
    private final JComboBox<String> timestampUnit=new JComboBox<>(new String[]{"auto","seconds","milliseconds","microseconds","nanoseconds"});
    private final JComboBox<String> timestampPrecision=new JComboBox<>(new String[]{"source","2","3","6","9"});
    private final JTextField timestampFormat=new JTextField(22);
    private final DefaultTableModel steps = new DefaultTableModel(new Object[]{"Name", "Befehl", "Suffix", "Shell"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable pipeline = new JTable(steps);
    private String[] columns = new String[0];
    private String previewSignature;
    private boolean busy;

    ArtifactWizardDialog(Frame owner) {
        super("Artifact Wizard");
        setIconImages(owner.getIconImages());
        setResizable(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (!busy) { dltPreview.reset(); clearCaseInput(); dispose(); } }
        });
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Parser und Plugin", wizard());
        tabs.addTab("Blackboard bereinigen", new ArtifactCleanerPanel(this));
        content.add(tabs, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
        setMinimumSize(new Dimension(760, 650));
        java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1280, screen.width), Math.min(950, screen.height));
        setLocationRelativeTo(owner);
        rebuildMappings();
        loadArtifactCatalog();
    }

    private JPanel wizard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel fields = new JPanel(new GridBagLayout());
        table.setEditable(true);
        JPanel artifactSettings=new JPanel(new GridBagLayout());
        row(artifactSettings, 0, "Artefakttyp", type);
        JPanel artifactOptions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        artifactOptions.add(new JLabel("Name für neue Typen:"));artifactOptions.add(artifactName);
        artifactOptions.add(button("Feld hinzufügen…",this::addField));
        artifactOptions.add(button("Feld entfernen…",this::removeField));
        row(artifactSettings, 1, "Eigene / Fall-Artefakte",artifactOptions);
        JPanel timeOptions=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        timeOptions.add(new JLabel("Unix-Einheit:"));timeOptions.add(timestampUnit);
        timeOptions.add(new JLabel("Nachkommastellen (mind.):"));timeOptions.add(timestampPrecision);
        timeOptions.add(new JLabel("Datumsformat:"));timeOptions.add(timestampFormat);
        timestampPrecision.setToolTipText("source = Quellpräzision; 2 = Hundertstel; 3 = Millisekunden. Vorhandene Genauigkeit bleibt erhalten.");
        timestampFormat.setToolTipText("Leer = automatisch; z.B. %d.%m.%Y %H:%M:%S.%f. Zeiten ohne Offset werden als UTC gelesen.");
        row(artifactSettings, 2, "Zeit (UTC)",timeOptions);
        row(artifactSettings, 3, "Genauigkeit",new JLabel("<html>Normale Autopsy-Zeitfelder verwenden ganze Sekunden.<br>Die vollständige UTC-Zeit, exakte Unix-Sekunden und Originalwerte werden zusätzlich gespeichert.<br>Felder unten den Quellspalten zuordnen; neue eigene Felder sind frei benennbar.</html>"));
        JPanel path = new JPanel(new BorderLayout(6, 0)); path.add(input);
        JPanel inputButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        inputButtons.add(button("Datei…", this::chooseInput));
        inputButtons.add(button("Input-Ordner…", this::chooseInputFolder));
        inputButtons.add(button("Aus Fall wählen…", this::chooseCaseInput));
        path.add(inputButtons, BorderLayout.EAST);
        row(fields, 1, "Preview-Input", path);
        JPanel kind = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        kind.add(source); kind.add(new JLabel("CSV-Trenner:")); kind.add(separator);
        kind.add(button("SQLite-Tabellen laden", this::loadTables));
        row(fields, 2, "Quelle", kind);
        row(fields, 3, "SQLite-Tabelle", table);
        row(fields, 4, "SQL-Abfrage (optional)", new JScrollPane(query));
        row(fields, 5, "Regex mit benannten Gruppen", new JScrollPane(regex));
        row(fields, 6, "Regex-Modus", multiline);
        row(fields, 7, "Autopsy-Dateiname / Muster", filename);
        row(fields, 8, "Freier Modulname", module);
        JPanel preprocessing = new JPanel(new BorderLayout());
        JScrollPane stepScroll = new JScrollPane(pipeline); stepScroll.setPreferredSize(new Dimension(500, 75));
        preprocessing.add(stepScroll);
        JPanel stepButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        stepButtons.add(button("Schritt hinzufügen", () -> editStep(-1)));
        stepButtons.add(button("Bearbeiten", () -> { if (pipeline.getSelectedRow() >= 0) editStep(pipeline.getSelectedRow()); }));
        stepButtons.add(button("Entfernen", () -> { if (pipeline.getSelectedRow() >= 0) steps.removeRow(pipeline.getSelectedRow()); }));
        stepButtons.add(button("↑", () -> moveStep(-1))); stepButtons.add(button("↓", () -> moveStep(1)));
        JPanel resourceButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,4,1));
        resourceButtons.add(button("Preprocessing-Dateien auswählen…",this::choosePreprocessInputs));resourceButtons.add(inputCount);
        JPanel folderButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,4,1));
        folderButtons.add(button("Skript-/Tool-Ordner…",this::chooseToolFolder));
        folderButtons.add(button("Plugin-Ordner verwenden",()->{toolDirectory=null;toolFolderLabel.setText("Plugin-Ordner (Standard)");previewSignature=null;}));
        toolFolderLabel.setPreferredSize(new Dimension(200,22));folderButtons.add(toolFolderLabel);
        JPanel buttonRows=new JPanel();buttonRows.setLayout(new javax.swing.BoxLayout(buttonRows,javax.swing.BoxLayout.Y_AXIS));
        buttonRows.add(stepButtons);buttonRows.add(resourceButtons);buttonRows.add(folderButtons);
        preprocessing.add(buttonRows,BorderLayout.SOUTH);
        row(fields, 9, "Preprocessing (optional)", preprocessing);
        row(fields, 10, "Platzhalter", new JLabel("<html>{input}, {output}, {script_dir}, {tool_dir}, {input_dir}, {inputs}, {input1} …<br>Einzelne Pfade in Anführungszeichen setzen; Ordner-Input benötigt einen Preprocessing-Schritt.</html>"));
        JPanel top = new JPanel(new BorderLayout()); top.add(new JScrollPane(fields));
        top.add(button("Vorschau laden (bis 50 Zeilen)", this::loadPreview), BorderLayout.SOUTH);
        JTabbedPane options=new JTabbedPane();options.addTab("Quelle & Preprocessing",top);
        options.addTab("Artefakttyp & Zeit",new JScrollPane(artifactSettings));
        options.setMinimumSize(new Dimension(300, 100));
        preview.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        preview.setModel(readOnlyModel());
        JTabbedPane previewTabs = new JTabbedPane();
        previewTabs.addTab("Tabelle", new JScrollPane(preview));
        previewTabs.addTab("DLT Viewer", dltPreview);
        JPanel mappingContent = new JPanel(new BorderLayout());
        mappingContent.add(mappings, BorderLayout.NORTH);
        JScrollPane mappingScroll = new JScrollPane(mappingContent);
        mappingScroll.setBorder(BorderFactory.createTitledBorder("Artefaktfelder / Quellspalten"));
        mappingScroll.setMinimumSize(new Dimension(300, 150));
        previewTabs.setMinimumSize(new Dimension(300, 60));
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewTabs, mappingScroll);
        center.setContinuousLayout(true); center.setOneTouchExpandable(true);
        center.setResizeWeight(0.15); center.setDividerLocation(110);
        JSplitPane workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, options, center);
        workspace.setContinuousLayout(true); workspace.setOneTouchExpandable(true);
        workspace.setResizeWeight(0.0); workspace.setDividerLocation(270);
        panel.add(workspace, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(button("Parser testen", () -> parse(false)));
        actions.add(button("Normalisierte CSV exportieren…", () -> parse(true)));
        actions.add(button("Autopsy-Ingest-Modul exportieren…", this::exportTemplate));
        panel.add(actions, BorderLayout.SOUTH);
        type.addActionListener(e -> selectArtifactType());
        return panel;
    }

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dltPreview.reset(); clearCaseInput(); input.setText(chooser.getSelectedFile().getAbsolutePath());
            if (chooser.getSelectedFile().getName().toLowerCase(java.util.Locale.ROOT).endsWith(".dlt")) source.setSelectedItem("dlt");
            if (filename.getText().isBlank()) filename.setText(chooser.getSelectedFile().getName());
            previewSignature = null;
        }
    }

    private void clearCaseInput() {
        if(caseInput!=null){try{caseInput.close();}catch(Exception ex){caseInput.input().toFile().deleteOnExit();}caseInput=null;}
    }

    private void chooseInputFolder() {
        JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){dltPreview.reset();clearCaseInput();input.setText(chooser.getSelectedFile().getAbsolutePath());filename.setText(chooser.getSelectedFile().getName());previewSignature=null;}
    }

    private void chooseToolFolder() {
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Ordner mit Preprocessing-Skripten, EXEs und Konfigurationen");chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){toolDirectory=chooser.getSelectedFile().toPath().toAbsolutePath();toolFolderLabel.setText(toolDirectory.toString());toolFolderLabel.setToolTipText(toolDirectory.toString());previewSignature=null;}
    }

    private void chooseCaseInput() {
        try {
            org.sleuthkit.datamodel.AbstractFile selected=CaseInputChooser.choose(this);
            if(selected==null)return;
            java.util.concurrent.atomic.AtomicReference<CaseInputStager> prepared=new java.util.concurrent.atomic.AtomicReference<>();
            runAsync(() -> {
                CaseInputStager staged=new CaseInputStager(selected);prepared.set(staged);
                JsonObject value=new JsonObject();value.addProperty("path",staged.input().toString());
                return value;
            }, value -> {
                dltPreview.reset();clearCaseInput();caseInput=prepared.get();
                input.setText(value.get("path").getAsString());input.setToolTipText("Fallobjekt "+selected.getId()+": "+selected.getName());filename.setText(selected.getName());
                if(selected.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".dlt"))source.setSelectedItem("dlt");
                previewSignature=null;status.setText("Falldatei intern bereitgestellt; kein manueller Export erforderlich.");
            });
        } catch(Exception ex){error(ex);}
    }

    private void choosePreprocessInputs() {
        JFileChooser chooser = new JFileChooser(); chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Alle Preprocessing-Dateien auswählen (Skripte, Konfigurationen, Zusatzdaten)");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            preprocessInputs.clear();
            for (java.io.File file : chooser.getSelectedFiles()) preprocessInputs.add(file.toPath().toAbsolutePath());
            inputCount.setText(preprocessInputs.size()+" Dateien");
            inputCount.setToolTipText(preprocessInputs.toString()); previewSignature = null; dltPreview.reset();
        }
    }

    private JsonObject meta() {
        JsonObject m = new JsonObject();
        m.addProperty("path", input.getText().trim()); m.addProperty("source_type", (String) source.getSelectedItem());
        m.addProperty("script_dir", org.sleuthkit.autopsy.coreutils.PlatformUtil.getUserPythonModulesPath());
        m.addProperty("tool_dir", toolDirectory == null ? "" : toolDirectory.toString());
        m.addProperty("input_is_directory", !input.getText().isBlank() && Files.isDirectory(Path.of(input.getText())));
        m.addProperty("sep", (String) separator.getSelectedItem());
        m.addProperty("table", table.getEditor().getItem() == null ? "" : table.getEditor().getItem().toString());
        m.addProperty("query", query.getText().trim());
        m.addProperty("regex", (multiline.isSelected() ? "(?ms)" : "") + regex.getText());
        JsonArray list = new JsonArray();
        for (int i = 0; i < steps.getRowCount(); i++) {
            JsonObject s = new JsonObject(); s.addProperty("name", steps.getValueAt(i, 0).toString());
            s.addProperty("command", steps.getValueAt(i, 1).toString()); s.addProperty("output_suffix", steps.getValueAt(i, 2).toString());
            s.addProperty("shell", Boolean.TRUE.equals(steps.getValueAt(i, 3))); list.add(s);
        }
        JsonArray inputs = new JsonArray();
        for (Path path : preprocessInputs) inputs.add(path.toString());
        m.add("preprocess_inputs", inputs);
        m.add("preprocess_steps", list);
        return m;
    }

    private JsonObject request(String action, boolean requirePreview) {
        JsonObject m = meta();
        if (requirePreview && (previewSignature == null || !previewSignature.equals(previewKey(m)))) {
            throw new IllegalStateException("Bitte die Vorschau für die aktuellen Quelldaten und Einstellungen neu laden.");
        }
        JsonObject r = new JsonObject(); r.addProperty("action", action); r.add("meta", m);
        m.addProperty("timestamp_unit",timestampUnit.getSelectedItem().toString());
        m.addProperty("timestamp_precision",timestampPrecision.getSelectedItem().toString());
        m.addProperty("timestamp_format",timestampFormat.getText().trim());
        if(artifactSchema!=null) {
            JsonObject schema=artifactSchema.deepCopy();
            if(schema.has("icon")) {
                String name=artifactName.getText().trim();if(name.isBlank())throw new IllegalStateException("Bitte einen Artefaktnamen eingeben.");
                schema.addProperty("display_name",name);
                try {
                    byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((name+schema.get("attributes").toString()).getBytes(StandardCharsets.UTF_8));
                    schema.addProperty("type_name","VLEAPP_"+schema.get("icon").getAsString()+"_"+java.util.HexFormat.of().formatHex(hash).substring(0,24));
                }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
            }
            m.add("artifact_schema",schema);r.addProperty("plugin_type","Generic");
        } else r.addProperty("plugin_type", type.getSelectedItem().toString());
        r.addProperty("filename", filename.getText().trim()); r.addProperty("module_name", module.getText().trim());
        JsonObject map = new JsonObject();
        mappingBoxes.forEach((field, box) -> map.addProperty(field, box.getSelectedIndex() <= 0 ? "" : box.getSelectedItem().toString()));
        r.add("mapping", map); return r;
    }

    private void loadTables() {
        source.setSelectedItem("sqlite");
        JsonObject r = request("tables", false);
        runAsync(() -> ArtifactFactoryBackend.run("factory.py", r), result -> {
            table.removeAllItems();
            for (JsonElement value : result.getAsJsonArray("tables")) table.addItem(value.getAsString());
            previewSignature = null;
        });
    }

    private void loadPreview() {
        JsonObject r = request("preview", false); previewSignature = null; dltPreview.reset();
        runAsync(() -> ArtifactFactoryBackend.run("factory.py", r), result -> {
            JsonArray names = result.getAsJsonArray("columns"); columns = new String[names.size()];
            for (int i = 0; i < columns.length; i++) columns[i] = names.get(i).getAsString();
            DefaultTableModel model = readOnlyModel();
            for (String column : columns) model.addColumn(column);
            for (JsonElement item : result.getAsJsonArray("rows")) {
                Object[] cells = new Object[columns.length]; JsonObject record = item.getAsJsonObject();
                for (int i = 0; i < columns.length; i++) {
                    JsonElement value = record.get(columns[i]); cells[i] = value == null || value.isJsonNull() ? "" : value.getAsString();
                }
                model.addRow(cells);
            }
            if ("dlt".equals(source.getSelectedItem())) dltPreview.open(r.getAsJsonObject("meta"));
            preview.setModel(model); previewSignature = previewKey(r.getAsJsonObject("meta")); rebuildMappings();
            status.setText(model.getRowCount() + " Zeilen in der Vorschau.");
        });
    }

    private void rebuildMappings() {
        Map<String, String> previous = new LinkedHashMap<>();
        mappingBoxes.forEach((k, v) -> { if(v.getItemCount()>1)previous.put(k, v.getSelectedItem() == null ? "" : v.getSelectedItem().toString()); });
        mappings.removeAll(); mappingBoxes.clear();
        String[][] all = {{"Timestamp","Longitude","Latitude","Geschwindigkeit"}, {"Kommentar","Timestamp","Longitude","Latitude"},
            {"Kommentar","Longitude","Latitude"}, {"Timestamp","Eventname","Kommentar"}, {"Nachname","Vorname","Telefonnummer","BluetoothAdresse"},
            {"Geraetename","BluetoothAdresse"}, {"Timestamp","Anrufername","MACAdresse","Angerufener","Nummer","Dauer"}};
        java.util.List<String> fieldNames=new java.util.ArrayList<>();
        if(artifactSchema==null)java.util.Collections.addAll(fieldNames,all[Math.max(0,Math.min(6,type.getSelectedIndex()))]);
        else for(JsonElement element:artifactSchema.getAsJsonArray("attributes"))fieldNames.add(element.getAsJsonObject().get("name").getAsString());
        for (String field : fieldNames) {
            JComboBox<String> box = new JComboBox<>(); box.addItem("(keine)");
            for (String column : columns) box.addItem(column);
            String selected = previous.getOrDefault(field, field);
            for (String column : columns) if (column.equalsIgnoreCase(selected)) box.setSelectedItem(column);
            String label=field;
            if(artifactSchema!=null)for(JsonElement item:artifactSchema.getAsJsonArray("attributes")) {
                JsonObject f=item.getAsJsonObject();if(f.get("name").getAsString().equals(field))label=f.get("label").getAsString()+" · "+f.get("value_type").getAsString()+" ["+field+"]";
            }
            if(!previous.containsKey(field)) {
                String simple=field.replaceFirst("^TSK_(GEO_)?","");
                for(String column:columns)if(column.equalsIgnoreCase(simple) || (simple.equals("DATETIME") && column.toLowerCase(java.util.Locale.ROOT).matches("timestamp|time.?stamp.?utc|date.?time")))box.setSelectedItem(column);
                if(artifactSchema!=null)for(JsonElement item:artifactSchema.getAsJsonArray("attributes")) {
                    JsonObject f=item.getAsJsonObject();if(!f.get("name").getAsString().equals(field))continue;
                    String hint=f.get("label").getAsString();hint=hint.substring(hint.lastIndexOf(':')+1).trim();
                    for(String column:columns)if(column.equalsIgnoreCase(hint))box.setSelectedItem(column);
                }
            }
            mappingBoxes.put(field, box); row(mappings, mappingBoxes.size()-1, label, box);
        }
        mappings.revalidate(); mappings.repaint();
    }

    private String previewKey(JsonObject meta) {
        JsonObject copy=meta.deepCopy();
        for(String key:new String[]{"artifact_schema","timestamp_unit","timestamp_precision","timestamp_format"})copy.remove(key);
        return copy.toString();
    }

    private org.sleuthkit.datamodel.SleuthkitCase caseDb()throws Exception {
        return org.sleuthkit.autopsy.casemodule.Case.getCurrentCaseThrows().getSleuthkitCase();
    }

    private JsonObject standardField(String name) {
        return attributeCatalog.values().stream().filter(f->f.get("name").getAsString().equals(name)).findFirst().orElseThrow().deepCopy();
    }

    private void loadArtifactCatalog() {
        runAsync(()->{
            JsonObject result=new JsonObject();result.add("types",PRETTY.toJsonTree(ArtifactWizardCatalog.types(caseDb())));
            result.add("attributes",PRETTY.toJsonTree(ArtifactWizardCatalog.attributes(caseDb())));return result;
        },result->{
            result.getAsJsonObject("attributes").entrySet().forEach(e->attributeCatalog.put(e.getKey(),e.getValue().getAsJsonObject()));
            for(String icon:new String[]{"CONTACT","CALL","MESSAGE","GEO","BOOKMARK","SEARCH","BLUETOOTH","WIFI","MEDIA","PHOTO","VIDEO","AUDIO","PHONE","USB","DEVICE","SOFTWARE","ACCOUNT","NETWORK","DOWNLOAD","LOG","KEY","WARNING","VEHICLE","GEAR","DOOR","POWER","BATTERY","SETTINGS","DIAGNOSTIC","ACTIVITY","TEMPERATURE","RADIO","CHIP","TIME","NAVIGATION","DATABASE","WEATHER","RECORD"}) {
                JsonObject spec=new JsonObject();spec.addProperty("type_name","WIZARD_NEW");spec.addProperty("display_name",icon);spec.addProperty("icon",icon);
                JsonArray fields=new JsonArray();for(String field:new String[]{"TSK_DATETIME","TSK_NAME","TSK_COMMENT"})fields.add(standardField(field));
                if(java.util.Set.of("GEO","BOOKMARK","SEARCH","NAVIGATION").contains(icon)) {
                    fields.add(standardField("TSK_GEO_LATITUDE"));fields.add(standardField("TSK_GEO_LONGITUDE"));
                }
                String[] extra;
                switch(icon) {
                    case "CONTACT":extra=new String[]{"TSK_NAME_PERSON","TSK_PHONE_NUMBER","TSK_EMAIL"};break;
                    case "CALL":extra=new String[]{"TSK_PHONE_NUMBER_FROM","TSK_PHONE_NUMBER_TO","TSK_DIRECTION"};break;
                    case "MESSAGE":extra=new String[]{"TSK_TEXT","TSK_SUBJECT","TSK_PHONE_NUMBER_FROM","TSK_PHONE_NUMBER_TO"};break;
                    case "BLUETOOTH":case "DEVICE":case "PHONE":case "USB":extra=new String[]{"TSK_DEVICE_NAME","TSK_MAC_ADDRESS","TSK_DEVICE_ID"};break;
                    case "WIFI":extra=new String[]{"TSK_SSID","TSK_BSSID"};break;
                    case "ACCOUNT":extra=new String[]{"TSK_USER_NAME","TSK_EMAIL"};break;
                    case "NETWORK":extra=new String[]{"TSK_URL","TSK_IP_ADDRESS","TSK_DOMAIN"};break;
                    default:extra=new String[0];
                }
                for(String name:extra)attributeCatalog.values().stream().filter(f->f.get("name").getAsString().equals(name)).findFirst().ifPresent(f->fields.add(f.deepCopy()));
                spec.add("attributes",fields);String label="Neuer Typ · "+categoryLabel(icon);artifactCatalog.put(label,spec);type.addItem(label);
            }
            result.getAsJsonObject("types").entrySet().forEach(e->{String label="Fall · "+e.getKey();artifactCatalog.put(label,e.getValue().getAsJsonObject());type.addItem(label);});
            type.setPrototypeDisplayValue("Fall · Navigation / Fahrzeug-Artefakt [VLEAPP_…]");
            type.setRenderer(new javax.swing.DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(javax.swing.JList<?> list,Object value,int index,boolean selected,boolean focus) {
                    JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                    JsonObject spec=artifactCatalog.get(value);if(spec!=null) {
                        String path=spec.has("icon_path")?spec.get("icon_path").getAsString():org.sleuthkit.autopsy.datamodel.utils.IconsUtil.getIconFilePath("VLEAPP_"+spec.get("icon").getAsString()+"_0123456789abcdef01234567",-1);
                        java.net.URL resource=getClass().getResource(path);if(resource!=null)label.setIcon(new javax.swing.ImageIcon(resource));
                    }
                    return label;
                }
            });
            status.setText("Artefaktkatalog geladen: bestehende Data Artifacts und frei erweiterbare Typen.");
        });
    }

    private void selectArtifactType() {
        JsonObject selected=artifactCatalog.get(type.getSelectedItem());
        artifactSchema=selected==null?null:selected.deepCopy();
        if(artifactSchema!=null && !artifactSchema.has("icon")) {
            String name=artifactSchema.get("type_name").getAsString();
            runAsync(()->{JsonObject r=new JsonObject();r.add("fields",ArtifactWizardCatalog.fields(caseDb(),name));return r;},r->{
                artifactSchema.add("attributes",r.getAsJsonArray("fields"));rebuildMappings();
            });
        } else rebuildMappings();
    }

    private String categoryLabel(String icon) {
        switch(icon) {
            case "GEAR":return "Gang / Wählhebel";case "DOOR":return "Türstatus";
            case "VEHICLE":return "Fahrzeugdaten";case "POWER":return "Ein-/Ausschalten";case "BATTERY":return "Batterie / Spannung";
            case "SETTINGS":return "Einstellungen";case "DIAGNOSTIC":return "Diagnose";case "ACTIVITY":return "Fahrsignale / Aktivität";
            case "TEMPERATURE":return "Temperatur";case "RADIO":return "Radio";case "CHIP":return "Steuergerät / CAN";
            case "TIME":return "Zeitereignisse";case "NAVIGATION":return "Navigation";case "DATABASE":return "Datenbank";
            case "WEATHER":return "Wetter";case "CONTACT":return "Kontakte";case "CALL":return "Anrufe";case "MESSAGE":return "Nachrichten";
            case "GEO":return "Positionen";case "BOOKMARK":return "Favoriten";case "SEARCH":return "Suchen / Ziele";
            case "BLUETOOTH":return "Bluetooth";case "WIFI":return "WLAN";case "MEDIA":return "Medien";case "PHOTO":return "Fotos";
            case "VIDEO":return "Videos";case "AUDIO":return "Audio";case "PHONE":return "Telefon";case "USB":return "USB";
            case "DEVICE":return "Geräte";case "SOFTWARE":return "Software";case "ACCOUNT":return "Konten / Profile";
            case "NETWORK":return "Netzwerk";case "DOWNLOAD":return "Downloads";case "LOG":return "Protokolle";case "KEY":return "Schlüssel";
            case "WARNING":return "Warnungen";default:return "Freier Artefakttyp";
        }
    }

    private void addField() {
        if(artifactSchema==null){JOptionPane.showMessageDialog(this,"Bitte zunächst einen neuen Typ oder einen Fall-Artefakttyp wählen.");return;}
        String[] choices=new String[attributeCatalog.size()+1];choices[0]="Eigenes Feld…";int i=1;for(String label:attributeCatalog.keySet())choices[i++]=label;
        Object choice=JOptionPane.showInputDialog(this,"Zielfeld wählen","Artefaktfeld hinzufügen",JOptionPane.PLAIN_MESSAGE,null,choices,choices[0]);if(choice==null)return;
        JsonObject field;
        if(choice.equals(choices[0])) {
            JTextField label=new JTextField(25);JComboBox<String> kind=new JComboBox<>(new String[]{"STRING","DATETIME","DOUBLE","INTEGER","LONG","BYTE","JSON"});
            JPanel editor=new JPanel(new GridBagLayout());row(editor,0,"Feldname",label);row(editor,1,"Datentyp",kind);
            if(JOptionPane.showConfirmDialog(this,editor,"Eigenes Feld",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            String text=label.getText().trim();if(text.isBlank())return;
            field=new JsonObject();field.addProperty("name","WIZARD_FIELD_"+java.util.UUID.nameUUIDFromBytes((text+kind.getSelectedItem()).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""));
            field.addProperty("label",text);field.addProperty("value_type",kind.getSelectedItem().toString());
        } else field=attributeCatalog.get(choice.toString()).deepCopy();
        JsonArray fields=artifactSchema.getAsJsonArray("attributes");
        for(JsonElement current:fields)if(current.getAsJsonObject().get("name").equals(field.get("name")))return;
        fields.add(field);rebuildMappings();
    }

    private void removeField() {
        if(artifactSchema==null)return;JsonArray fields=artifactSchema.getAsJsonArray("attributes");if(fields.isEmpty())return;
        String[] labels=new String[fields.size()];for(int i=0;i<labels.length;i++)labels[i]=fields.get(i).getAsJsonObject().get("label").getAsString()+" ["+fields.get(i).getAsJsonObject().get("name").getAsString()+"]";
        Object selected=JOptionPane.showInputDialog(this,"Feld entfernen","Artefaktfelder",JOptionPane.PLAIN_MESSAGE,null,labels,labels[0]);if(selected==null)return;
        for(int i=0;i<labels.length;i++)if(labels[i].equals(selected)){fields.remove(i);break;}rebuildMappings();
    }

    private void parse(boolean export) {
        try {
            JsonObject r = request(export ? "export_csv" : "test", true);
            if (export) {
                Path output = chooseOutput("normalisiert.csv"); if (output == null) return;
                if (Files.exists(output)) throw new IllegalStateException("Bitte für den CSV-Export einen neuen Dateinamen wählen.");
                r.addProperty("output", output.toString());
            }
            runAsync(() -> ArtifactFactoryBackend.run("factory.py", r), result -> {
                JTextArea text = new JTextArea(PRETTY.toJson(result), 18, 75); text.setEditable(false);
                JOptionPane.showMessageDialog(this, new JScrollPane(text), "Parser-Ergebnis", JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (Exception ex) { error(ex); }
    }

    private void exportTemplate() {
        try {
            JsonObject r = request("template", true); Path output = chooseOutput("artifact_parser.py");
            if (output == null) return;
            Path evidence = Path.of(input.getText()).toAbsolutePath().normalize();
            if (output.toAbsolutePath().normalize().equals(evidence) || (Files.exists(output) && Files.isSameFile(output, evidence))) {
                throw new IllegalStateException("Die Preview-/Beweisdatei darf nicht überschrieben werden.");
            }
            for (Path resource : preprocessInputs) {
                if (output.toAbsolutePath().normalize().equals(resource.toAbsolutePath().normalize()) || (Files.exists(output) && Files.isSameFile(output, resource))) throw new IllegalStateException("Preprocessing-Dateien dürfen nicht überschrieben werden.");
            }
            String resourceName = output.getFileName().toString()+"_inputs_"+java.util.UUID.randomUUID().toString();
            Path resourceDir = output.toAbsolutePath().getParent().resolve(resourceName);
            r.addProperty("resource_directory", resourceName);
            r.addProperty("tool_directory", toolDirectory == null ? "" : resourceName+"/tools");
            if (Files.exists(output) && JOptionPane.showConfirmDialog(this, "Vorhandenes Modul überschreiben?", "Modul-Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            runAsync(() -> {
                JsonObject result = ArtifactFactoryBackend.run("factory.py", r);
                boolean saved = false;
                try {
                    if (!preprocessInputs.isEmpty() || toolDirectory != null) {
                        Files.createDirectory(resourceDir);
                        for (Path resource : preprocessInputs) Files.copy(resource, resourceDir.resolve(resource.getFileName()), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        if(toolDirectory!=null) {
                            if(resourceDir.startsWith(toolDirectory))throw new IllegalStateException("Exportziel muss außerhalb des Skript-/Tool-Ordners liegen.");
                            try(java.util.stream.Stream<Path> entries=Files.walk(toolDirectory)) {
                                for(Path entry:entries.toArray(Path[]::new)) {
                                    if(Files.isSymbolicLink(entry))throw new IllegalStateException("Bitte einen Tool-Ordner ohne symbolische Links verwenden: "+entry);
                                    Path destination=resourceDir.resolve("tools").resolve(toolDirectory.relativize(entry));
                                    if(Files.isDirectory(entry))Files.createDirectory(destination);else Files.copy(entry,destination,java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                                }
                            }
                        }
                    }
                    Files.writeString(output, result.get("code").getAsString(), StandardCharsets.UTF_8);
                    saved = true;
                } finally {
                    if (!saved && Files.isDirectory(resourceDir)) {
                        try (java.util.stream.Stream<Path> entries = Files.walk(resourceDir)) { for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(entry); }
                    }
                }
                return result;
            }, result -> JOptionPane.showMessageDialog(this, "Ingest-Modul gespeichert:\n"+output));
        } catch (Exception ex) { error(ex); }
    }

    private Path chooseOutput(String suggested) {
        JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File(suggested));
        return chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }

    private void editStep(int index) {
        JTextField name = new JTextField(index < 0 ? "Schritt " + (steps.getRowCount()+1) : steps.getValueAt(index, 0).toString(), 30);
        JTextField command = new JTextField(index < 0 ? "" : steps.getValueAt(index, 1).toString(), 55);
        JTextField suffix = new JTextField(index < 0 ? ".txt" : steps.getValueAt(index, 2).toString(), 10);
        JCheckBox shell = new JCheckBox("Shell verwenden (nur für bewusst eingegebene Shell-Befehle)", index >= 0 && Boolean.TRUE.equals(steps.getValueAt(index, 3)));
        JPanel form = new JPanel(new GridBagLayout()); row(form,0,"Name",name); row(form,1,"Befehl",command); row(form,2,"Output-Suffix",suffix); row(form,3,"Modus",shell);
        if (JOptionPane.showConfirmDialog(this, form, "Preprocessing-Schritt", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            if (command.getText().isBlank()) { error(new IllegalArgumentException("Bitte einen Befehl eingeben.")); return; }
            Object[] values = {name.getText(), command.getText(), suffix.getText(), shell.isSelected()};
            if (index < 0) steps.addRow(values); else for (int c=0;c<4;c++) steps.setValueAt(values[c],index,c);
        }
    }

    private void moveStep(int delta) {
        int row = pipeline.getSelectedRow(), next = row+delta;
        if (row >= 0 && next >= 0 && next < steps.getRowCount()) { steps.moveRow(row,row,next); pipeline.setRowSelectionInterval(next,next); }
    }

    void runAsync(Callable<JsonObject> task, Consumer<JsonObject> completed) {
        if (busy) return;
        busy = true; enableTree(content, false); status.setText("Vorgang läuft…");
        new SwingWorker<JsonObject, Void>() {
            @Override protected JsonObject doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false; enableTree(content, true); status.setText("Bereit.");
                try { completed.accept(get()); } catch (Exception ex) { error(ex.getCause() == null ? ex : ex.getCause()); }
            }
        }.execute();
    }

    void error(Throwable ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Artifact Wizard", JOptionPane.ERROR_MESSAGE); }
    static JButton button(String name, Runnable action) { JButton b = new JButton(name); b.addActionListener(e -> action.run()); return b; }
    static DefaultTableModel readOnlyModel() { return new DefaultTableModel() { @Override public boolean isCellEditable(int r,int c) { return false; } }; }
    static void enableTree(Component c, boolean enabled) { c.setEnabled(enabled); if (c instanceof Container) for (Component child : ((Container)c).getComponents()) enableTree(child, enabled); }
    static void row(JPanel panel, int row, String label, Component field) {
        GridBagConstraints c = new GridBagConstraints(); c.gridx=0; c.gridy=row; c.insets=new Insets(3,4,3,8); c.anchor=GridBagConstraints.NORTHWEST;
        panel.add(new JLabel(label),c); c.gridx=1; c.weightx=1; c.fill=GridBagConstraints.HORIZONTAL; panel.add(field,c);
    }
}

