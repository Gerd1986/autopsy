/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.casemodule;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Native front-end for the supplied mergeFiles block interleaving tool. */
final class MergeFilesDialog extends JDialog {
    private static final long serialVersionUID=1L;
    private final DefaultListModel<Path> files=new DefaultListModel<>();
    private final JList<Path> list=new JList<>(files);
    private final JTextField block=new JTextField("1",12),order=new JTextField(25),output=new JTextField(45),offset=new JTextField("0",12);
    private final JCheckBox reverse=new JCheckBox("Bytes innerhalb jedes Blocks umkehren"),afterSwap=new JCheckBox("Nach Block-Swap");
    private final List<JComboBox<Path>> selectors=List.of(new JComboBox<>(),new JComboBox<>());
    private final List<JTextArea> views=List.of(new JTextArea(16,46),new JTextArea(16,46));
    private final JLabel status=new JLabel("Bereit."),previewStatus=new JLabel("Gleicher Byte-Offset in beiden Dateien; Mausrad: 16 Bytes.");
    private final JProgressBar progress=new JProgressBar(0,1000);
    private final JPanel root=new JPanel(new BorderLayout(6,6));
    private final JButton start=new JButton("Verarbeiten"),stop=new JButton("Abbrechen"),use=new JButton("Ergebnis als Image übernehmen");
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private boolean running,closeWhenDone;
    private Path result;
    private int previewGeneration;
    private SwingWorker<List<String>,Void> previewWorker;
    private MergeFilesDialog(Window owner,String initial){
        super(owner,"MergeFiles – Dateien verschachteln",ModalityType.APPLICATION_MODAL);setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        root.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));setContentPane(root);
        JPanel top=new JPanel(new BorderLayout(4,4));top.add(new JLabel("Eingabedateien in gewünschter Listenreihenfolge"),BorderLayout.NORTH);
        list.setVisibleRowCount(4);list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(l,(index+1)+".  "+value,index,selected,focus);}});
        top.add(new JScrollPane(list));
        JPanel options=new JPanel();options.setLayout(new BoxLayout(options,BoxLayout.Y_AXIS));
        JPanel buttons=flow();buttons.add(button("Hinzufügen…",this::add));buttons.add(button("Entfernen",this::remove));buttons.add(button("Nach oben",()->move(-1)));buttons.add(button("Nach unten",()->move(1)));options.add(buttons);
        JPanel settings=flow();settings.add(new JLabel("Blockgröße (Bytes):"));settings.add(block);settings.add(new JLabel("Reihenfolge:"));settings.add(order);settings.add(reverse);options.add(settings);
        JPanel destination=flow();destination.add(new JLabel("Ausgabe:"));destination.add(output);destination.add(button("Wählen…",this::chooseOutput));options.add(destination);
        options.add(new JLabel("Leer = Listenreihenfolge. Wiederholungen möglich: 3,3,1,2,2. Nur vollständige Blöcke bis zur kürzesten Datei."));top.add(options,BorderLayout.SOUTH);root.add(top,BorderLayout.NORTH);
        JPanel preview=new JPanel(new BorderLayout(4,4));preview.setBorder(BorderFactory.createTitledBorder("Synchronisierte Hex-/ASCII-Vorschau (keine Merge-Ausgabe)"));
        JPanel nav=flow();nav.add(new JLabel("Offset (dezimal oder 0x…):"));nav.add(offset);nav.add(button("Anzeigen",this::refreshPreview));nav.add(button("◀ 256",()->step(-256)));nav.add(button("256 ▶",()->step(256)));nav.add(afterSwap);preview.add(nav,BorderLayout.NORTH);
        JPanel panes=new JPanel(new GridLayout(1,2,6,0));
        for(int i=0;i<2;i++){JPanel pane=new JPanel(new BorderLayout(2,2));JComboBox<Path> selector=selectors.get(i);selector.addActionListener(e->refreshPreview());pane.add(selector,BorderLayout.NORTH);JTextArea view=views.get(i);view.setEditable(false);view.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));JScrollPane scroll=new JScrollPane(view);scroll.setWheelScrollingEnabled(false);scroll.addMouseWheelListener(e->{step(e.getWheelRotation()>0?16:-16);e.consume();});pane.add(scroll);panes.add(pane);}
        preview.add(panes);preview.add(previewStatus,BorderLayout.SOUTH);root.add(preview);
        JPanel bottom=new JPanel();bottom.setLayout(new BoxLayout(bottom,BoxLayout.Y_AXIS));progress.setStringPainted(true);bottom.add(progress);bottom.add(status);
        JPanel actions=flow();actions.add(start);actions.add(stop);actions.add(use);actions.add(button("Schließen",this::close));bottom.add(actions);root.add(bottom,BorderLayout.SOUTH);
        start.addActionListener(e->start());stop.addActionListener(e->{cancelled.set(true);stop.setEnabled(false);status.setText("Abbruch läuft…");});use.addActionListener(e->{if(result!=null)dispose();});stop.setEnabled(false);use.setEnabled(false);
        offset.addActionListener(e->refreshPreview());afterSwap.addActionListener(e->refreshPreview());reverse.addActionListener(e->refreshPreview());
        block.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){refreshPreview();}public void removeUpdate(DocumentEvent e){refreshPreview();}public void changedUpdate(DocumentEvent e){refreshPreview();}});
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){close();}});
        if(initial!=null&&!initial.isBlank()){Path path=Path.of(initial);if(Files.isRegularFile(path))files.addElement(path.toAbsolutePath());}
        try{output.setText(Path.of(Case.getCurrentCaseThrows().getExportDirectory(),"merged-"+UUID.randomUUID()+".img").toString());}catch(Exception ex){output.setText("");}
        refreshSelectors();Dimension screen=Toolkit.getDefaultToolkit().getScreenSize();setSize(Math.min(1180,screen.width-60),Math.min(850,screen.height-90));setLocationRelativeTo(owner);
    }
    static Path show(Window owner,String initial){MergeFilesDialog dialog=new MergeFilesDialog(owner,initial);dialog.setVisible(true);return dialog.result;}
    private static JPanel flow(){return new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));}
    private static JButton button(String name,Runnable task){JButton b=new JButton(name);b.addActionListener(e->task.run());return b;}
    private static long number(String text){String value=text.trim();return value.toLowerCase(Locale.ROOT).startsWith("0x")?Long.parseLong(value.substring(2),16):Long.parseLong(value);}
    private void add(){JFileChooser chooser=new JFileChooser();chooser.setMultiSelectionEnabled(true);if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){for(java.io.File f:chooser.getSelectedFiles())files.addElement(f.toPath().toAbsolutePath());refreshSelectors();}}
    private void remove(){int[] selected=list.getSelectedIndices();for(int i=selected.length-1;i>=0;i--)files.remove(selected[i]);refreshSelectors();}
    private void move(int delta){int selected=list.getSelectedIndex(),next=selected+delta;if(list.getSelectedIndices().length==1&&next>=0&&next<files.size()){Path item=files.get(selected);files.set(selected,files.get(next));files.set(next,item);list.setSelectedIndex(next);refreshSelectors();}}
    private void refreshSelectors(){for(int i=0;i<selectors.size();i++){JComboBox<Path> selector=selectors.get(i);selector.removeAllItems();for(int j=0;j<files.size();j++)selector.addItem(files.get(j));if(files.size()>0)selector.setSelectedIndex(Math.min(i,files.size()-1));}refreshPreview();}
    private void chooseOutput(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(output.getText().isBlank()?"merged.img":output.getText()));if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION)output.setText(chooser.getSelectedFile().getAbsolutePath());}
    private void step(long delta){if(running)return;try{offset.setText(Long.toString(Math.max(0,Math.addExact(number(offset.getText()),delta))));refreshPreview();}catch(Exception ex){previewStatus.setText("Ungültiger Offset.");}}
    private void refreshPreview(){
        if(running)return;int ticket=++previewGeneration;if(previewWorker!=null)previewWorker.cancel(true);
        try{
            long pos=number(offset.getText()),b=number(block.getText());if(pos<0||b<1)throw new IllegalArgumentException("Offset >= 0, Blockgröße >= 1 erforderlich.");
            List<Path> chosen=new ArrayList<>();for(JComboBox<Path> selector:selectors)chosen.add((Path)selector.getSelectedItem());boolean swapped=afterSwap.isSelected(),rev=reverse.isSelected();
            previewWorker=new SwingWorker<List<String>,Void>(){
                protected List<String> doInBackground()throws Exception{List<String> texts=new ArrayList<>();for(Path path:chosen){if(path==null){texts.add("Keine Datei ausgewählt.");continue;}byte[] bytes=MergeFiles.preview(path,pos,b,swapped,rev);StringBuilder text=new StringBuilder();for(int row=0;row<bytes.length;row+=16){text.append(String.format(Locale.ROOT,"%08X  ",pos+row));StringBuilder ascii=new StringBuilder();for(int i=0;i<16;i++){if(row+i<bytes.length){int v=Byte.toUnsignedInt(bytes[row+i]);text.append(String.format(Locale.ROOT,"%02X ",v));ascii.append(v>=32&&v<127?(char)v:'.');}else text.append("   ");}text.append(" ").append(ascii).append('\n');}texts.add(bytes.length==0?"Ende der Datei / kein vollständiger Block.":text.toString());}return texts;}
                protected void done(){if(ticket!=previewGeneration||!isDisplayable())return;try{List<String> texts=get();for(int i=0;i<views.size();i++){views.get(i).setText(texts.get(i));views.get(i).setCaretPosition(0);}previewStatus.setText("Gleicher Byte-Offset. Nach Block-Swap zeigt vollständige Blöcke mit aktueller Swap-Einstellung.");}catch(Exception ex){previewStatus.setText("Vorschau: "+(ex.getCause()==null?ex.getMessage():ex.getCause().getMessage()));}}
            };previewWorker.execute();
        }catch(Exception ex){previewStatus.setText("Vorschau: "+ex.getMessage());}
    }
    private void start(){
        if(running)return;
        try{
            List<Path> inputs=Collections.list(files.elements());long b=number(block.getText()),expected=MergeFiles.estimate(inputs,b,order.getText());String sequence=order.getText();boolean rev=reverse.isSelected();
            if(output.getText().isBlank())throw new IllegalArgumentException("Bitte eine Ausgabedatei wählen.");Path destination=Path.of(output.getText()).toAbsolutePath().normalize();boolean overwrite=Files.exists(destination);
            if(overwrite&&JOptionPane.showConfirmDialog(this,"Vorhandene Ausgabedatei ersetzen?\n"+destination,"MergeFiles",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            if(expected==0&&JOptionPane.showConfirmDialog(this,"Es gibt keinen gemeinsamen vollständigen Block. Leere Ausgabe erzeugen?","MergeFiles",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            result=null;running=true;cancelled.set(false);enable(root,false);stop.setEnabled(true);status.setText("Verarbeitung läuft: "+expected+" Bytes erwartet.");
            new SwingWorker<Long,long[]>(){
                private long last;
                protected Long doInBackground()throws Exception{return MergeFiles.merge(inputs,destination,b,sequence,rev,overwrite,(done,total)->{long now=System.nanoTime();if(done==total||now-last>100_000_000L){publish(new long[]{done,total});last=now;}},cancelled::get);}
                protected void process(List<long[]> chunks){long[] p=chunks.get(chunks.size()-1);progress.setValue(p[1]==0?1000:(int)(1000.0*p[0]/p[1]));progress.setString(p[0]+" / "+p[1]+" Bytes");}
                protected void done(){running=false;enable(root,true);stop.setEnabled(false);use.setEnabled(false);try{long written=get();result=written>0?destination:null;status.setText("Fertig: "+written+" Bytes — "+destination);use.setEnabled(result!=null);}catch(Exception ex){Throwable cause=ex.getCause()==null?ex:ex.getCause();status.setText(cause instanceof InterruptedException?"Abgebrochen; Ziel unverändert.":"Fehler: "+cause.getMessage());if(!(cause instanceof InterruptedException))JOptionPane.showMessageDialog(MergeFilesDialog.this,cause.getMessage(),"MergeFiles",JOptionPane.ERROR_MESSAGE);}if(closeWhenDone){result=null;dispose();}}
            }.execute();
        }catch(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"MergeFiles",JOptionPane.ERROR_MESSAGE);}
    }
    private static void enable(Component component,boolean enabled){component.setEnabled(enabled);if(component instanceof Container)for(Component c:((Container)component).getComponents())enable(c,enabled);}
    private void close(){if(running){closeWhenDone=true;cancelled.set(true);status.setText("Abbruch läuft…");}else{result=null;dispose();}}
    @Override public void dispose(){previewGeneration++;if(previewWorker!=null)previewWorker.cancel(true);super.dispose();}
}
