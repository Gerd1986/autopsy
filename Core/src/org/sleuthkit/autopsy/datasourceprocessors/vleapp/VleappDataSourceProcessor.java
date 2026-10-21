/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.datasourceprocessors.vleapp;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.autopsy.artifactfactory.ArtifactFactoryBackend;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.corecomponentinterfaces.*;
import org.sleuthkit.datamodel.*;

/** Separate Add Data Source input for completed VLEAPP LAVA/TSV export folders. */
@ServiceProvider(service=DataSourceProcessor.class)
public final class VleappDataSourceProcessor implements DataSourceProcessor {
    private final JPanel panel=new JPanel(new BorderLayout(5,5));
    private final JTextField path=new JTextField();
    private final JLabel error=new JLabel(" ");
    private final AtomicBoolean cancelled=new AtomicBoolean();
    public VleappDataSourceProcessor() {
        JPanel selection=new JPanel(new BorderLayout(5,5));selection.add(path);
        JButton browse=new JButton("Exportordner auswählen…");selection.add(browse,BorderLayout.EAST);
        browse.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showOpenDialog(panel)==JFileChooser.APPROVE_OPTION)path.setText(chooser.getSelectedFile().getAbsolutePath());});
        JPanel intro=new JPanel(new GridLayout(0,1));
        intro.add(new JLabel("VLEAPP Export (LAVA oder TSV)"));
        intro.add(new JLabel("LAVA-Report oder Ordner _TSV Exports auswählen."));
        intro.add(new JLabel("Kopie im Fall; eigene Artefakttypen mit allen Quellspalten."));
        panel.add(intro,BorderLayout.NORTH);panel.add(selection);panel.add(error,BorderLayout.SOUTH);
        path.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}
            private void changed(){panel.firePropertyChange(DSP_PANEL_EVENT.UPDATE_UI.toString(),false,true);}
        });
    }
    @Override public String getDataSourceType(){return "VLEAPP Export (LAVA / TSV)";}
    @Override public JPanel getPanel(){return panel;}
    @Override public boolean isPanelValid(){
        try {
            Path p=Path.of(path.getText().trim());
            if(path.getText().isBlank() || !Files.isDirectory(p) || Files.isSymbolicLink(p))throw new Exception("Bitte einen lokalen Exportordner wählen.");
            boolean lava=Files.isRegularFile(p.resolve("_lava_data.lava")) && Files.isRegularFile(p.resolve("_lava_artifacts.db"));
            boolean tsv=Files.isDirectory(p.resolve("_TSV Exports"));
            try(java.util.stream.Stream<Path> entries=Files.list(p)){tsv=tsv || entries.anyMatch(x->x.getFileName().toString().endsWith(".tsv"));}
            if(!lava && !tsv)throw new Exception("Kein LAVA-/TSV-Export gefunden. HTML allein reicht nicht.");
            error.setText(" ");return true;
        }catch(Exception ex){error.setText(ex.getMessage());return false;}
    }
    @Override public void run(DataSourceProcessorProgressMonitor monitor,DataSourceProcessorCallback callback){run(null,monitor,callback);}
    @Override public void run(Host host,DataSourceProcessorProgressMonitor monitor,DataSourceProcessorCallback callback){
        final String selected=path.getText().trim();cancelled.set(false);
        new Thread(()->{
            LocalFilesDataSource ds=null;Path work=null;
            List<String> errors=new ArrayList<>();
            try {
                Case current=Case.getCurrentCaseThrows();monitor.setIndeterminate(true);monitor.setProgressText("VLEAPP-Export kopieren und prüfen…");
                Path base=Path.of(current.getModuleDirectory(),"VLEAPP");Files.createDirectories(base);work=Files.createTempDirectory(base,"import-");
                JsonObject request=new JsonObject();request.addProperty("input",selected);request.addProperty("output",work.toString());
                ArtifactFactoryBackend.run("vleapp.py",request,cancelled::get);
                if(cancelled.get())throw new InterruptedException("VLEAPP-Import abgebrochen.");
                Path report=work.resolve("report");
                ds=current.getServices().getFileManager().addLocalFilesDataSource(UUID.randomUUID().toString(),
                    "VLEAPP Export - "+Path.of(selected).getFileName(),"UTC",host,List.of(report.toString()),f->{ });
                Map<Path,AbstractFile> files=VleappImporter.resolveSources(ds,work,cancelled::get);
                long count=VleappImporter.importRecords(current.getSleuthkitCase(),ds.getId(),work,files,cancelled::get,monitor::setProgressText);
                Files.writeString(work.resolve("import-status.txt"),"Complete: "+count+" VLEAPP records.\n");
                monitor.setProgressText("VLEAPP: "+count+" Datensätze importiert.");
            }catch(Exception ex){
                errors.add("VLEAPP: "+ex.getMessage());
                if(ds!=null)errors.add("Datenquelle und bereits bestätigte Datensätze bleiben erhalten. Details im VLEAPP-Modulordner.");
                if(work!=null)try{Files.writeString(work.resolve("import-status.txt"),String.join("\n",errors));}catch(Exception ignored){ }
            }
            List<Content> sources=new ArrayList<>();if(ds!=null)sources.add(ds);
            callback.done(errors.isEmpty()?DataSourceProcessorCallback.DataSourceProcessorResult.NO_ERRORS:
                ds==null?DataSourceProcessorCallback.DataSourceProcessorResult.CRITICAL_ERRORS:DataSourceProcessorCallback.DataSourceProcessorResult.NONCRITICAL_ERRORS,errors,sources);
        },"VLEAPP export import").start();
    }
    @Override public void cancel(){cancelled.set(true);}
    @Override public void reset(){path.setText("");error.setText(" ");}
}
