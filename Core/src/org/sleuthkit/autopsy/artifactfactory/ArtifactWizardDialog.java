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
import javax.swing.JDialog;
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
final class ArtifactWizardDialog extends JDialog {
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
    private final DefaultTableModel steps = new DefaultTableModel(new Object[]{"Name", "Befehl", "Suffix", "Shell"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable pipeline = new JTable(steps);
    private String[] columns = new String[0];
    private String previewSignature;
    private boolean busy;

    ArtifactWizardDialog(Frame owner) {
        super(owner, "Artifact Wizard", true);
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
        setSize(1180, 950);
        setLocationRelativeTo(owner);
        rebuildMappings();
    }

    private JPanel wizard() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel fields = new JPanel(new GridBagLayout());
        table.setEditable(true);
        row(fields, 0, "Artefakttyp", type);
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
        JPanel top = new JPanel(new BorderLayout()); top.add(fields);
        top.add(button("Vorschau laden (bis 50 Zeilen)", this::loadPreview), BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);
        preview.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        preview.setModel(readOnlyModel());
        JTabbedPane previewTabs = new JTabbedPane();
        previewTabs.addTab("Tabelle", new JScrollPane(preview));
        previewTabs.addTab("DLT Viewer", dltPreview);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewTabs, new JScrollPane(mappings));
        center.setResizeWeight(0.65); center.setDividerLocation(140);
        panel.add(center);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(button("Parser testen", () -> parse(false)));
        actions.add(button("Normalisierte CSV exportieren…", () -> parse(true)));
        actions.add(button("Autopsy-Ingest-Modul exportieren…", this::exportTemplate));
        panel.add(actions, BorderLayout.SOUTH);
        type.addActionListener(e -> rebuildMappings());
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
        if (requirePreview && (previewSignature == null || !previewSignature.equals(m.toString()))) {
            throw new IllegalStateException("Bitte die Vorschau für die aktuellen Quelldaten und Einstellungen neu laden.");
        }
        JsonObject r = new JsonObject(); r.addProperty("action", action); r.add("meta", m);
        r.addProperty("plugin_type", type.getSelectedItem().toString());
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
            preview.setModel(model); previewSignature = r.get("meta").toString(); rebuildMappings();
            status.setText(model.getRowCount() + " Zeilen in der Vorschau.");
        });
    }

    private void rebuildMappings() {
        Map<String, String> previous = new LinkedHashMap<>();
        mappingBoxes.forEach((k, v) -> previous.put(k, v.getSelectedItem() == null ? "" : v.getSelectedItem().toString()));
        mappings.removeAll(); mappingBoxes.clear();
        String[][] all = {{"Timestamp","Longitude","Latitude","Geschwindigkeit"}, {"Kommentar","Timestamp","Longitude","Latitude"},
            {"Kommentar","Longitude","Latitude"}, {"Timestamp","Eventname","Kommentar"}, {"Nachname","Vorname","Telefonnummer","BluetoothAdresse"},
            {"Geraetename","BluetoothAdresse"}, {"Timestamp","Anrufername","MACAdresse","Angerufener","Nummer","Dauer"}};
        for (String field : all[Math.max(0, type.getSelectedIndex())]) {
            JComboBox<String> box = new JComboBox<>(); box.addItem("(keine)");
            for (String column : columns) box.addItem(column);
            String selected = previous.getOrDefault(field, field);
            for (String column : columns) if (column.equalsIgnoreCase(selected)) box.setSelectedItem(column);
            mappingBoxes.put(field, box); row(mappings, mappingBoxes.size()-1, field, box);
        }
        mappings.revalidate(); mappings.repaint();
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
