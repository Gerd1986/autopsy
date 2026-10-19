/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.contentviewers;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.*;
import java.util.Locale;
import javax.swing.*;
import org.openide.nodes.Node;
import org.openide.util.lookup.ServiceProvider;
import org.sleuthkit.autopsy.artifactfactory.DltTablePanel;
import org.sleuthkit.autopsy.corecomponentinterfaces.DataContentViewer;
import org.sleuthkit.datamodel.AbstractFile;

/** Diagnostic Log and Trace viewer in the lower content pane, before ingest too. */
@ServiceProvider(service=DataContentViewer.class,position=4)
public final class DltContentViewer extends JPanel implements DataContentViewer {
    private static final long serialVersionUID=1L;
    private final DltTablePanel viewer=new DltTablePanel();
    private final JLabel status=new JLabel();
    private SwingWorker<Path,Void> extraction;
    private Path local;
    private int generation;
    public DltContentViewer(){super(new BorderLayout());add(viewer);add(status,BorderLayout.NORTH);}
    private AbstractFile file(Node node){return node==null?null:node.getLookup().lookup(AbstractFile.class);}
    @Override public boolean isSupported(Node node){
        AbstractFile f=file(node);if(f==null||f.isDir())return false;
        if("application/x-dlt".equals(f.getMIMEType())||f.getName().toLowerCase(Locale.ROOT).endsWith(".dlt"))return true;
        try{byte[] b=new byte[4];return f.read(b,0,4)==4&&b[0]=='D'&&b[1]=='L'&&((b[2]=='T'&&(b[3]==1||b[3]==2))||(b[2]=='S'&&b[3]==1));}catch(Exception ex){return false;}
    }
    @Override public void setNode(Node node){
        resetComponent();if(!isSupported(node))return;AbstractFile f=file(node);int ticket=generation;status.setText("DLT-Arbeitskopie wird erstellt: "+f.getName());
        extraction=new SwingWorker<Path,Void>(){
            @Override protected Path doInBackground()throws Exception{
                Path temp=Files.createTempFile("autopsy-dlt-", ".dlt");temp.toFile().deleteOnExit();boolean transferred=false;
                try(java.io.OutputStream out=Files.newOutputStream(temp)){
                    byte[] buffer=new byte[1024*1024];long pos=0;
                    while(pos<f.getSize()){if(isCancelled())throw new InterruptedException();int n=f.read(buffer,pos,Math.min(buffer.length,f.getSize()-pos));if(n<=0)throw new java.io.IOException("DLT source could not be read completely");out.write(buffer,0,n);pos+=n;}
                    if(isCancelled())throw new InterruptedException();transferred=true;return temp;
                }finally{if(!transferred || isCancelled())Files.deleteIfExists(temp);}
            }
            @Override protected void done(){
                try{Path temp=get();if(ticket!=generation){Files.deleteIfExists(temp);return;}local=temp;JsonObject meta=new JsonObject();meta.addProperty("path",temp.toString());meta.addProperty("source_type","dlt");viewer.open(meta);status.setText(f.getName()+" — DLT-Tabelle (Arbeitskopie; Quelle unverändert)");}catch(Exception ex){if(ticket==generation)status.setText("DLT: "+ex.getMessage());}
            }
        };extraction.execute();
    }
    @Override public void resetComponent(){generation++;if(extraction!=null)extraction.cancel(true);extraction=null;viewer.reset();if(local!=null){try{Files.deleteIfExists(local);}catch(Exception ex){local.toFile().deleteOnExit();}local=null;}status.setText("");}
    @Override public String getTitle(){return "DLT Table";}
    @Override public String getToolTip(){return "Diagnostic Log and Trace: Header, Payload, Hex und Zeitstempel";}
    @Override public DataContentViewer createInstance(){return new DltContentViewer();}
    @Override public Component getComponent(){return this;}
    @Override public int isPreferred(Node node){return isSupported(node)?5:0;}
}
