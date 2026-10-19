/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import com.google.gson.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.*;
import javax.swing.table.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Shared paged DLT table for case content and the Artifact Wizard. */
public final class DltTablePanel extends JPanel {
    private static final long serialVersionUID=1L;
    private final JTable table=new JTable();
    private final JTextArea details=new JTextArea();
    private final JLabel status=new JLabel("DLT-Datei auswählen.");
    private final JTextField filter=new JTextField(28);
    private final JButton first=new JButton("Erste Seite"), next=new JButton("Weiter (500)");
    private JsonObject meta;
    private long offset;
    private SwingWorker<JsonObject,Void> worker;
    private int generation;
    public DltTablePanel() {
        super(new BorderLayout(4,4));
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(first);buttons.add(next);buttons.add(new JLabel("Filter (aktuelle Seite):"));buttons.add(filter);
        add(buttons,BorderLayout.NORTH);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,new JScrollPane(table),new JScrollPane(details));split.setResizeWeight(.7);add(split);
        add(status,BorderLayout.SOUTH);first.addActionListener(e->load(0));next.addActionListener(e->load(offset));
        table.getSelectionModel().addListSelectionListener(e->{
            if(table.getSelectedRow()<0)return;
            int r=table.convertRowIndexToModel(table.getSelectedRow());StringBuilder text=new StringBuilder();
            for(int c=0;c<table.getModel().getColumnCount();c++)text.append(table.getModel().getColumnName(c)).append(": ").append(table.getModel().getValueAt(r,c)).append('\n');
            details.setText(text.toString());details.setCaretPosition(0);
        });
        filter.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){applyFilter();}public void removeUpdate(DocumentEvent e){applyFilter();}public void changedUpdate(DocumentEvent e){applyFilter();}});
        next.setEnabled(false);first.setEnabled(false);
    }
    private void applyFilter(){
        if(table.getRowSorter() instanceof TableRowSorter){
            @SuppressWarnings("unchecked") TableRowSorter<TableModel> sorter=(TableRowSorter<TableModel>)table.getRowSorter();
            sorter.setRowFilter(filter.getText().isEmpty()?null:RowFilter.regexFilter("(?i)"+java.util.regex.Pattern.quote(filter.getText())));
        }
    }
    public void open(JsonObject source){reset();meta=source.deepCopy();load(0);}
    public void reset(){generation++;if(worker!=null)worker.cancel(true);worker=null;meta=null;offset=0;table.setModel(new DefaultTableModel());details.setText("");status.setText("DLT-Datei auswählen.");next.setEnabled(false);first.setEnabled(false);}
    private void load(long start){
        if(meta==null||worker!=null)return;
        JsonObject req=new JsonObject();req.addProperty("action","dlt_page");req.add("meta",meta.deepCopy());req.addProperty("offset",start);
        int ticket=generation;first.setEnabled(false);next.setEnabled(false);status.setText("DLT wird dekodiert…");
        worker=new SwingWorker<JsonObject,Void>(){
            @Override protected JsonObject doInBackground()throws Exception{return ArtifactFactoryBackend.run("factory.py",req);}
            @Override protected void done(){
                if(ticket!=generation)return;worker=null;first.setEnabled(true);
                try{
                    JsonObject result=get();JsonArray cols=result.getAsJsonArray("columns"),rows=result.getAsJsonArray("rows");
                    DefaultTableModel model=new DefaultTableModel(){@Override public boolean isCellEditable(int r,int c){return false;}};
                    for(JsonElement c:cols)model.addColumn(c.getAsString());
                    for(JsonElement item:rows){Object[] values=new Object[cols.size()];for(int i=0;i<values.length;i++){JsonElement v=item.getAsJsonObject().get(cols.get(i).getAsString());values[i]=v==null||v.isJsonNull()?"":v.getAsString();}model.addRow(values);}
                    table.setModel(model);table.setAutoCreateRowSorter(true);applyFilter();offset=result.get("next_offset").getAsLong();next.setEnabled(rows.size()==500&&offset>start);
                    status.setText(rows.size()+" Nachrichten; nächster Offset "+offset+"; übersprungene Bytes: "+result.get("skipped_bytes")+(result.get("truncated").getAsBoolean()?"; abgeschnittene Nachricht":""));
                    if(model.getRowCount()>0)table.setRowSelectionInterval(0,0);
                }catch(Exception ex){status.setText("DLT: "+(ex.getCause()==null?ex.getMessage():ex.getCause().getMessage()));}
            }
        };worker.execute();
    }
}
