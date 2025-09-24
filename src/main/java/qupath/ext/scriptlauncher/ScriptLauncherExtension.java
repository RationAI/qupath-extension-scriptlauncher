package qupath.ext.scriptlauncher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;


public class ScriptLauncherExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ScriptLauncherExtension.class);
    
    @Override
    public void installExtension(QuPathGUI qupath) {
        logger.info("Script Launcher Extension loaded");
    }

    @Override
    public String getName() {
        return "Script Launcher Extension";
    }

    public String getDescription() {
        return "Runs custom actions automatically at startup.";
    }
}
