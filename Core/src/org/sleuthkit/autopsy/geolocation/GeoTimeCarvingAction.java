/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.geolocation;
import java.awt.Component;
import javax.swing.*;
import org.openide.awt.*;
import org.openide.util.*;
import org.openide.util.actions.*;
import org.openide.windows.WindowManager;
import org.sleuthkit.autopsy.casemodule.Case;

@ActionID(category="Tools",id="org.sleuthkit.autopsy.geolocation.GeoTimeCarvingAction")
@ActionRegistration(displayName="#CTL_GeoTimeCarving",lazy=false)
@ActionReferences({@ActionReference(path="Toolbars/Case",position=104),@ActionReference(path="Menu/Tools",position=104)})
@NbBundle.Messages("CTL_GeoTimeCarving=GeoTime Carving")
public final class GeoTimeCarvingAction extends CallableSystemAction implements Presenter.Toolbar {
    private static final long serialVersionUID=1L;
    private final JButton button=new JButton("GeoTime Carving",new ImageIcon(getClass().getResource("/org/sleuthkit/autopsy/images/blueGeo24.png")));
    private GeoTimeCarvingDialog dialog;
    public GeoTimeCarvingAction(){
        button.addActionListener(e->performAction());setEnabled(Case.isCaseOpen());
        Case.addPropertyChangeListener(e->{if(Case.Events.CURRENT_CASE.toString().equals(e.getPropertyName()))SwingUtilities.invokeLater(()->{setEnabled(Case.isCaseOpen());if(dialog!=null){dialog.dispose();dialog=null;}});});
    }
    @Override public void performAction(){if(!Case.isCaseOpen())return;if(dialog==null||!dialog.isDisplayable())dialog=new GeoTimeCarvingDialog(WindowManager.getDefault().getMainWindow());dialog.setVisible(true);dialog.toFront();}
    @Override public String getName(){return Bundle.CTL_GeoTimeCarving();}
    @Override public HelpCtx getHelpCtx(){return HelpCtx.DEFAULT_HELP;}
    @Override public Component getToolbarPresenter(){return button;}
    @Override public void setEnabled(boolean value){super.setEnabled(value);if(button!=null)button.setEnabled(value);}
    @Override public boolean asynchronous(){return false;}
}
