package io.forge.reportforge.jmeter;

import org.apache.jmeter.gui.plugin.MenuCreator;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.event.KeyEvent;

/**
 * Registers "ReportForge: Generate Advanced Report" under JMeter's Tools menu (FR-601).
 * Discovered via META-INF/services/org.apache.jmeter.gui.plugin.MenuCreator.
 *
 * NOTE: this module compiles against ApacheJMeter_core; it is source-only in the
 * sandbox build and compiled by the Maven profile `-P with-jmeter`.
 */
public class ReportForgeMenuCreator implements MenuCreator {

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location != MENU_LOCATION.TOOLS) {
            return new JMenuItem[0];
        }
        JMenuItem item = new JMenuItem("ReportForge: Generate Advanced Report", KeyEvent.VK_R);
        item.setName("reportforge_generate");
        item.addActionListener(e -> ReportForgeDialog.open());
        return new JMenuItem[]{item};
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        return new JMenu[0];
    }

    @Override
    public boolean localeChanged(javax.swing.MenuElement menu) {
        return false;
    }

    @Override
    public void localeChanged() {
        // single-locale v0.5
    }
}
