package qupath.ext.scriptlauncher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.File;
import java.io.IOException;


public class ScriptLauncherExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ScriptLauncherExtension.class);
    
    @Override
    public void installExtension(QuPathGUI qupath) {
        String imagePath = System.getenv("QUPATH_IMAGE");
        if (imagePath == null) {
            logger.warn("QUPATH_IMAGE not set");
            return;
        }
        File f = new File(imagePath);
        if (!f.exists()) {
            logger.error("Image file not found: " + imagePath);
            return;
        }
        try {
            qupath.openImage(qupath.getViewer(), imagePath);
            logger.info("Opened image via OpenSlide: " + imagePath);
        } catch (IOException e) {
            logger.error("Failed to open image with OpenSlide", e);
            return;
        }

        String scriptPath = System.getenv("QUPATH_SCRIPT");
         if (scriptPath == null) {
            logger.warn("QUPATH_SCRIPT not set");
            return;
        }

        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            logger.error("Script file not found: " + scriptPath);
            return;
        }

        try {
            // Run the script inside QuPath
            qupath.runScript(scriptFile, null);
            logger.info("Executed script: " + scriptPath);
        } catch (Exception e) {
            logger.error("Failed to execute script", e);
        }
    }

    @Override
    public String getName() {
        return "Script Launcher Extension";
    }

    public String getDescription() {
        return "Runs custom actions automatically at startup.";
    }
}
