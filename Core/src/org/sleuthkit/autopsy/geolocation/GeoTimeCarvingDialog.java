/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.geolocation;

import com.google.gson.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.jxmapviewer.viewer.GeoPosition;
import org.openide.nodes.Node;
import org.openide.util.Utilities;
import org.sleuthkit.autopsy.artifactfactory.ArtifactFactoryBackend;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.datamodel.AbstractFile;

/** Native binary coordinate and timestamp carving front-end; no Tk/matplotlib. */
final class GeoTimeCarvingDialog extends JDialog {
    private static final long serialVersionUID=1L;
    private final JTextField path=new JTextField(48),start=new JTextField("2010-01-01",18),end=new JTextField("2035-01-01",18);
    private final JComboBox<String> mode=new JComboBox<>(new String[]{"geo","time"}),format=new JComboBox<>(),fence=new JComboBox<>(new String[]{"off","radius","rectangle"});
    private final JSpinner step=new JSpinner(new SpinnerNumberModel(1,1,65536,1)),maximum=new JSpinner(new SpinnerNumberModel(50000,1,2000000,1000));
    private final JTextField longitude=new JTextField("7.0",8),latitude=new JTextField("51.8",8),radius=new JTextField("50",8),minLon=new JTextField("5",8),maxLon=new JTextField("10",8),minLat=new JTextField("50",8),maxLat=new JTextField("54",8);
    private final JLabel status=new JLabel("Binärdatei oder ausgewählte Falldatei laden. Geo und Zeit werden unabhängig gesucht.");
    private final JTextArea info=new JTextArea(5,70);
    private final MapPanel map=new MapPanel();
    private final JTable results=new JTable();
    private final JPanel controls=new JPanel(new GridBagLayout());
    private final JButton scan=new JButton("Carving starten"),cancel=new JButton("Abbrechen");
    private AbstractFile selected;
    private JsonObject formats;
    private SwingWorker<JsonObject,Void> worker;
    private boolean mapReady;
    GeoTimeCarvingDialog(Frame owner){
        super(owner,"GeoTime Carving",false);setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root=new JPanel(new BorderLayout(6,6));root.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));setContentPane(root);
        path.setEditable(false);JPanel source=new JPanel(new FlowLayout(FlowLayout.LEFT));source.add(path);source.add(button("Datei wählen…",this::choose));source.add(button("Ausgewählte Falldatei",this::useSelected));row(0,"Quelle",source);
        JPanel options=new JPanel(new FlowLayout(FlowLayout.LEFT));options.add(mode);options.add(format);options.add(new JLabel("Stride (Bytes)"));options.add(step);options.add(new JLabel("Max. Treffer"));options.add(maximum);row(1,"Format",options);
        JPanel time=new JPanel(new FlowLayout(FlowLayout.LEFT));time.add(new JLabel("Start UTC"));time.add(start);time.add(new JLabel("Ende UTC"));time.add(end);row(2,"Zeitfenster (inklusive)",time);
        JPanel geo=new JPanel(new FlowLayout(FlowLayout.LEFT));geo.add(fence);geo.add(new JLabel("Lon"));geo.add(longitude);geo.add(new JLabel("Lat"));geo.add(latitude);geo.add(new JLabel("Radius km"));geo.add(radius);row(3,"Geofence",geo);
        JPanel rect=new JPanel(new FlowLayout(FlowLayout.LEFT));rect.add(new JLabel("Lon min/max"));rect.add(minLon);rect.add(maxLon);rect.add(new JLabel("Lat min/max"));rect.add(minLat);rect.add(maxLat);row(4,"Rectangle",rect);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(scan);actions.add(cancel);actions.add(new JLabel("CSV und Geo-GPX werden im Fall-Exportordner gespeichert."));row(5,"",actions);root.add(controls,BorderLayout.NORTH);
        results.getSelectionModel().addListSelectionListener(e -> {
            if (!mapReady || results.getSelectedRow() < 0 || results.getModel().getColumnCount() != 3 || !"lon".equals(results.getModel().getColumnName(1))) return;
            int row = results.convertRowIndexToModel(results.getSelectedRow());
            map.focusCarvedPoint(new GeoPosition(Double.parseDouble(results.getModel().getValueAt(row,2).toString()),Double.parseDouble(results.getModel().getValueAt(row,1).toString())));
        });
        results.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);results.setAutoCreateRowSorter(true);info.setEditable(false);info.setLineWrap(true);info.setWrapStyleWord(true);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,map,new JScrollPane(results));split.setResizeWeight(.6);root.add(split);
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(new JScrollPane(info));bottom.add(status,BorderLayout.SOUTH);root.add(bottom,BorderLayout.SOUTH);
        try{map.setWaypoints(Collections.emptySet());map.initMap();mapReady=true;}catch(Exception ex){info.setText("Karte konnte nicht initialisiert werden: "+ex.getMessage()+". Die Carving-Tabelle bleibt nutzbar.");}
        mode.addActionListener(e->updateFormats());scan.addActionListener(e->scan());cancel.addActionListener(e->{if(worker!=null)worker.cancel(true);});cancel.setEnabled(false);scan.setEnabled(false);
        setSize(1250,850);setMinimumSize(new Dimension(900,650));setLocationRelativeTo(owner);
        new SwingWorker<JsonObject,Void>(){
            @Override protected JsonObject doInBackground()throws Exception{JsonObject req=new JsonObject();req.addProperty("action","formats");return ArtifactFactoryBackend.run("geotime.py",req);}
            @Override protected void done(){if(!isDisplayable())return;try{formats=get();updateFormats();scan.setEnabled(true);}catch(Exception ex){status.setText("GeoTime-Dateien fehlen: "+ex.getMessage());}}
        }.execute();
    }
    private void updateFormats(){if(formats==null)return;format.removeAllItems();for(JsonElement value:formats.getAsJsonArray(mode.getSelectedItem().toString()))format.addItem(value.getAsString());}
    private void choose(){JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){selected=null;path.setText(chooser.getSelectedFile().getAbsolutePath());}}
    private void useSelected(){
        AbstractFile f=Utilities.actionsGlobalContext().lookup(AbstractFile.class);
        if(f==null){Node node=Utilities.actionsGlobalContext().lookup(Node.class);if(node!=null)f=node.getLookup().lookup(AbstractFile.class);}
        if(f==null||f.isDir()){JOptionPane.showMessageDialog(this,"Bitte im Autopsy-Ergebnisfenster eine Datei oder einen Unallocated-Bereich auswählen.");return;}
        selected=f;path.setText("Fallobjekt "+f.getId()+": "+f.getName());
    }
    private void scan(){
        if(worker!=null)return;
        try{
            if(path.getText().isBlank())throw new IllegalArgumentException("Bitte eine Quelldatei auswählen.");
            Case target=Case.getCurrentCaseThrows();AbstractFile evidence=selected;
            JsonObject req=new JsonObject();req.addProperty("mode",mode.getSelectedItem().toString());req.addProperty("format",format.getSelectedItem().toString());req.addProperty("step",((Number)step.getValue()).intValue());req.addProperty("maximum",((Number)maximum.getValue()).intValue());req.addProperty("start",start.getText().trim());req.addProperty("end",end.getText().trim());
            JsonObject gf=new JsonObject();gf.addProperty("mode",fence.getSelectedItem().toString());gf.addProperty("longitude",longitude.getText());gf.addProperty("latitude",latitude.getText());gf.addProperty("radius_km",radius.getText());gf.addProperty("min_lon",minLon.getText());gf.addProperty("max_lon",maxLon.getText());gf.addProperty("min_lat",minLat.getText());gf.addProperty("max_lat",maxLat.getText());req.add("geofence",gf);
            Path external=evidence==null?Path.of(path.getText()):null;
            Path output=Path.of(target.getExportDirectory(),"geotime-carving",UUID.randomUUID().toString());req.addProperty("output_directory",output.toString());req.addProperty("source_description",path.getText());
            enableTree(controls,false);cancel.setEnabled(true);status.setText("Carving läuft… AUTO prüft Formate und Strides; Filter gelten auch für die Erkennung.");
            worker=new SwingWorker<JsonObject,Void>(){
                @Override protected JsonObject doInBackground()throws Exception{
                    Path temp=null;
                    try{
                        Path sourcePath=external;
                        if(evidence!=null){temp=Files.createTempFile("autopsy-geotime-", ".bin");sourcePath=temp;
                            try(java.io.OutputStream out=Files.newOutputStream(temp)){byte[] b=new byte[1024*1024];long pos=0;while(pos<evidence.getSize()){if(isCancelled())throw new InterruptedException();int n=evidence.read(b,pos,Math.min(b.length,evidence.getSize()-pos));if(n<=0)throw new java.io.IOException("Quelle konnte nicht vollständig gelesen werden");out.write(b,0,n);pos+=n;}}}
                        req.addProperty("path",sourcePath.toString());return ArtifactFactoryBackend.run("geotime.py",req);
                    }finally{if(temp!=null)Files.deleteIfExists(temp);}
                }
                @Override protected void done(){
                    worker=null;if(!isDisplayable())return;enableTree(controls,true);cancel.setEnabled(false);
                    try{
                        JsonObject result=get();String[] columns="geo".equals(req.get("mode").getAsString())?new String[]{"offset","lon","lat"}:new String[]{"offset","timestamp_iso_utc","unix_seconds"};
                        DefaultTableModel model=new DefaultTableModel(columns,0){@Override public boolean isCellEditable(int r,int c){return false;}};
                        java.util.List<GeoPosition> points=new ArrayList<>();
                        for(JsonElement row:result.getAsJsonArray("rows")){JsonObject record=row.getAsJsonObject();Object[] cells=new Object[columns.length];for(int i=0;i<columns.length;i++)cells[i]=record.get(columns[i]).getAsString();model.addRow(cells);if(record.has("lon"))points.add(new GeoPosition(record.get("lat").getAsDouble(),record.get("lon").getAsDouble()));}
                        results.setModel(model);if(mapReady)map.setCarvedPoints(points);
                        status.setText(result.get("count")+" Treffer; Format "+result.get("format")+"; Stride "+result.get("step")+"; Exporte: "+(result.has("exports")?result.get("exports"):"keine"));
                        info.setText(new GsonBuilder().setPrettyPrinting().create().toJson(result.get("candidates"))+"\n"+result.get("notice").getAsString()+(result.has("limit_reached")&&result.get("limit_reached").getAsBoolean()?"\nTrefferlimit erreicht; Scan endet am Limit.":"")+"\nTabelle/Karte: maximal 20.000 Treffer; Exporte enthalten alle gefundenen Treffer bis zum eingestellten Limit.");
                    }catch(java.util.concurrent.CancellationException ex){status.setText("Carving abgebrochen.");}catch(Exception ex){status.setText("Carving fehlgeschlagen.");JOptionPane.showMessageDialog(GeoTimeCarvingDialog.this,ex.getCause()==null?ex.getMessage():ex.getCause().getMessage(),"GeoTime Carving",JOptionPane.ERROR_MESSAGE);}
                }
            };worker.execute();
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"GeoTime Carving",JOptionPane.ERROR_MESSAGE);}
    }
    @Override public void dispose(){if(worker!=null)worker.cancel(true);if(mapReady){map.closeCarvingMap();mapReady=false;}super.dispose();}
    private static JButton button(String label,Runnable action){JButton b=new JButton(label);b.addActionListener(e->action.run());return b;}
    private static void enableTree(Component c,boolean enabled){c.setEnabled(enabled);if(c instanceof Container)for(Component child:((Container)c).getComponents())enableTree(child,enabled);}
    private void row(int n,String label,Component field){GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=n;c.anchor=GridBagConstraints.NORTHWEST;c.insets=new Insets(2,3,2,6);controls.add(new JLabel(label),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;controls.add(field,c);}
}
