/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;
import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.datamodel.*;

/** Lazy case-content browser: no user export needed, no all-case SQL scan. */
final class CaseInputChooser extends JDialog {
    private static final long serialVersionUID=1L;
    private final DefaultMutableTreeNode root=new DefaultMutableTreeNode("Datenquellen");
    private final DefaultTreeModel model=new DefaultTreeModel(root);
    private final JTree tree=new JTree(model);
    private final JLabel status=new JLabel("Datei oder Ordner im Fall auswählen.");
    private final java.util.List<SwingWorker<?,?>> workers=new ArrayList<>();
    private AbstractFile result;
    private final Case target;
    private CaseInputChooser(Window owner)throws Exception{
        super(owner,"Input direkt aus dem Autopsy-Fall",ModalityType.APPLICATION_MODAL);target=Case.getCurrentCaseThrows();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);setLayout(new BorderLayout());tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);add(new JScrollPane(tree));
        tree.addTreeWillExpandListener(new TreeWillExpandListener(){public void treeWillExpand(TreeExpansionEvent e){Object node=e.getPath().getLastPathComponent();if(node instanceof Entry)load((Entry)node);}public void treeWillCollapse(TreeExpansionEvent e){}});
        JPanel bottom=new JPanel(new BorderLayout());bottom.add(status);JPanel buttons=new JPanel();JButton ok=new JButton("Als Input übernehmen"),cancel=new JButton("Abbrechen");buttons.add(ok);buttons.add(cancel);bottom.add(buttons,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        ok.addActionListener(e->{Object node=tree.getLastSelectedPathComponent();if(node instanceof Entry&&((Entry)node).content instanceof AbstractFile){result=(AbstractFile)((Entry)node).content;dispose();}else status.setText("Bitte eine Datei oder einen Ordner innerhalb einer Datenquelle auswählen.");});cancel.addActionListener(e->dispose());
        setSize(800,600);setLocationRelativeTo(owner);
        SwingWorker<java.util.List<Content>,Void> task=new SwingWorker<java.util.List<Content>,Void>(){
            protected java.util.List<Content> doInBackground()throws Exception{return target.getSleuthkitCase().getRootObjects();}
            protected void done(){if(!isDisplayable())return;try{for(Content c:get())root.add(new Entry(c));model.reload(root);tree.expandRow(0);}catch(Exception ex){status.setText(ex.getMessage());}}
        };workers.add(task);task.execute();
    }
    private void load(Entry node){if(node.loaded)return;node.loaded=true;
        SwingWorker<java.util.List<Content>,Void> task=new SwingWorker<java.util.List<Content>,Void>(){
            protected java.util.List<Content> doInBackground()throws Exception{return node.content.getChildren();}
            protected void done(){if(!isDisplayable())return;try{node.removeAllChildren();for(Content c:get())if(!c.getName().equals(".")&&!c.getName().equals(".."))node.add(new Entry(c));model.reload(node);tree.expandPath(new TreePath(node.getPath()));}catch(Exception ex){node.loaded=false;status.setText("Lesefehler: "+ex.getMessage());}}
        };workers.add(task);task.execute();
    }
    static AbstractFile choose(Window owner)throws Exception{CaseInputChooser dialog=new CaseInputChooser(owner);dialog.setVisible(true);return dialog.result;}
    @Override public void dispose(){for(SwingWorker<?,?> worker:workers)worker.cancel(true);super.dispose();}
    private static final class Entry extends DefaultMutableTreeNode{
        private static final long serialVersionUID=1L;private final Content content;private boolean loaded;
        Entry(Content content){super(content.getName()+" ["+content.getId()+"]");this.content=content;if(!(content instanceof AbstractFile)||((AbstractFile)content).isDir())add(new DefaultMutableTreeNode("Laden…"));else loaded=true;}
    }
}
