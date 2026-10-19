/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.ingest.IngestManager;

/** Managed close -> backup -> offline transaction -> reopen for Data Artifacts. */
final class ArtifactCleanerPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final ArtifactWizardDialog dialog;
    private final JTextArea summary = new JTextArea(20, 75);
    private Case previewCase;
    private Long previewCount;

    ArtifactCleanerPanel(ArtifactWizardDialog dialog) {
        super(new BorderLayout(8,8)); this.dialog=dialog;
        setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        JLabel intro = new JLabel("<html><b>Data Artifacts im aktuellen Einzelbenutzer-Fall löschen</b><br>"
                +"Analyseergebnisse und Quelldateien bleiben erhalten. Der Fall wird für die Änderung geschlossen,<br>"
                +"die Datenbank gesichert und anschließend wieder geöffnet. Aktive Ingests müssen vorher beendet sein.<br>"
                +"Der bestehende Keyword-Suchindex wird durch diese Blackboard-Bereinigung nicht neu aufgebaut.</html>");
        add(intro,BorderLayout.NORTH); summary.setEditable(false); add(new JScrollPane(summary));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(ArtifactWizardDialog.button("Vorschau / Anzahl laden",this::preview));
        buttons.add(ArtifactWizardDialog.button("Data Artifacts löschen…",this::delete));
        add(buttons,BorderLayout.SOUTH);
    }

    private Case target() throws Exception {
        if (!Case.isCaseOpen()) throw new IllegalStateException("Kein Fall geöffnet.");
        Case current=Case.getCurrentCaseThrows();
        if (!current.getCaseType().name().equals("SINGLE_USER_CASE")) {
            throw new IllegalStateException("Die Bereinigung unterstützt ausschließlich Einzelbenutzer-Fälle mit SQLite.");
        }
        if (IngestManager.getInstance().isIngestRunning()) {
            throw new IllegalStateException("Bitte zuerst alle Ingests beenden.");
        }
        return current;
    }

    private JsonObject request(Case target,String action) {
        JsonObject r=new JsonObject(); r.addProperty("action",action);
        r.addProperty("database",Path.of(target.getCaseDirectory(),"autopsy.db").toString()); return r;
    }

    private void preview() {
        try {
            Case current=target(); JsonObject r=request(current,"preview"); previewCount=null; previewCase=null;
            dialog.runAsync(() -> ArtifactFactoryBackend.run("cleaner.py",r), result -> {
                if (!Case.isCaseOpen() || Case.getCurrentCase() != current) {
                    dialog.error(new IllegalStateException("Der aktuelle Fall hat sich geändert. Bitte die Vorschau neu laden.")); return;
                }
                previewCase=current; previewCount=result.get("data_artifacts").getAsLong();
                StringBuilder text=new StringBuilder(); text.append("Data Artifacts: ").append(previewCount)
                    .append("\nAnalyseergebnisse bleiben erhalten: ").append(result.get("analysis_results_preserved").getAsLong()).append("\n\n");
                for(JsonElement type:result.getAsJsonArray("types")) {
                    text.append(type.getAsJsonObject().get("name").getAsString()).append(": ")
                        .append(type.getAsJsonObject().get("count").getAsLong()).append('\n');
                }
                summary.setText(text.toString());
            });
        } catch(Exception ex) { dialog.error(ex); }
    }

    private void delete() {
        try {
            Case current=target();
            if (current != previewCase || previewCount == null) throw new IllegalStateException("Zuerst die Vorschau für diesen Fall laden.");
            if (previewCount==0) throw new IllegalStateException("Keine Data Artifacts zu löschen.");
            String confirmation=JOptionPane.showInputDialog(dialog,
                previewCount+" Data Artifacts einschließlich zugehöriger Attribute, Tags und Timeline-Einträge löschen?\n"
                +"Vorher wird eine vollständige SQLite-Sicherung angelegt.\nZur Bestätigung LOESCHEN eingeben:","Blackboard bereinigen",JOptionPane.WARNING_MESSAGE);
            if (!"LOESCHEN".equals(confirmation)) return;
            JsonObject r=request(current,"apply"); r.addProperty("expected_count",previewCount);
            r.addProperty("confirmation",confirmation); r.addProperty("case_closed",true);
            String metadata=current.getMetadata().getFilePath().toString();
            previewCount=null; previewCase=null;
            dialog.runAsync(() -> {
                // Validate again on the worker immediately before closing the case.
                if (target() != current) throw new IllegalStateException("Der Fall hat sich geändert.");
                return ArtifactCleanerOperation.run(
                    () -> { Case.closeCurrentCase(); return null; },
                    () -> Case.isCaseOpen(),
                    () -> ArtifactFactoryBackend.run("cleaner.py",r),
                    () -> {
                        try { Case.openAsCurrentCase(metadata); }
                        catch(Exception ex) {
                            throw new IllegalStateException("Fall bitte manuell wieder öffnen: "+metadata+"\n"+ex.getMessage(),ex);
                        }
                        return null;
                    });
            }, result -> {
                summary.setText("Gelöscht: "+result.get("deleted").getAsLong()+" Data Artifacts.\n"
                    +"Analyseergebnisse erhalten. Fall wieder geöffnet.\nBackup: "+result.get("backup").getAsString());
            });
        } catch(Exception ex) { dialog.error(ex); }
    }
}
