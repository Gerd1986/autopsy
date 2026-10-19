/* Licensed under the Apache License, Version 2.0. */
package org.sleuthkit.autopsy.artifactfactory;

import java.awt.Component;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.HelpCtx;
import org.openide.util.NbBundle;
import org.openide.util.actions.CallableSystemAction;
import org.openide.util.actions.Presenter;
import org.openide.windows.WindowManager;
import org.sleuthkit.autopsy.casemodule.Case;

@ActionID(category = "Tools", id = "org.sleuthkit.autopsy.artifactfactory.ArtifactWizardAction")
@ActionReferences({@ActionReference(path = "Menu/Tools", position = 107),
    @ActionReference(path = "Toolbars/Case", position = 107)})
@ActionRegistration(displayName = "#CTL_ArtifactWizard", lazy = false)
@NbBundle.Messages("CTL_ArtifactWizard=Artifact Wizard")
public final class ArtifactWizardAction extends CallableSystemAction implements Presenter.Toolbar {
    private static final long serialVersionUID = 1L;
    private final JButton toolbarButton = new JButton();
    public ArtifactWizardAction() {
        toolbarButton.addActionListener(this::actionPerformed);
        setEnabled(false);
    }
    @Override public boolean isEnabled() { return Case.isCaseOpen(); }
    @Override public void performAction() {
        if (Case.isCaseOpen()) {
            new ArtifactWizardDialog(WindowManager.getDefault().getMainWindow()).setVisible(true);
        }
    }
    @Override public Component getToolbarPresenter() {
        toolbarButton.setText(getName());
        toolbarButton.setIcon(new ImageIcon(getClass().getResource("/org/sleuthkit/autopsy/images/artifact-icon.png")));
        toolbarButton.setToolTipText("Parser erstellen und Blackboard Data Artifacts bereinigen");
        return toolbarButton;
    }
    @Override public void setEnabled(boolean value) { super.setEnabled(value); toolbarButton.setEnabled(value); }
    @Override public String getName() { return Bundle.CTL_ArtifactWizard(); }
    @Override public HelpCtx getHelpCtx() { return HelpCtx.DEFAULT_HELP; }
    @Override public boolean asynchronous() { return false; }
}
