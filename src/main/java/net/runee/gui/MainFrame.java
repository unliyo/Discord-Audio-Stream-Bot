package net.runee.gui;

import jouvieje.bass.BassInit;
import net.runee.BotManager;
import net.runee.DiscordAudioStreamBot;
import net.runee.gui.components.HomePanel;
import net.runee.gui.components.SettingsPanel;
import net.runee.misc.Utils;
import net.runee.misc.gui.BorderPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

public class MainFrame extends JFrame implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(MainFrame.class);
    private static final File logFile = new File("app.log");
    private static MainFrame instance;

    public static void main(String[] args) {
        // add shutdown hook
        Thread shutdownThread = new Thread(MainFrame::onRuntimeShutdown);
        shutdownThread.setName("DASB Shutdown Hook");
        shutdownThread.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(shutdownThread);

        Thread.setDefaultUncaughtExceptionHandler(MainFrame::uncaughtException);

        logger.info("Hello World!");
        Utils.printSystemInfo();

        // set L&F
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            logger.warn("Failed to set L&F", ex);
        }

        // load bass natives
        BassInit.loadLibraries();

        // load config (and bot instances)
        BotManager.getConfig();

        // run app
        EventQueue.invokeLater(getInstance());
    }

    public static MainFrame getInstance() {
        if (instance == null) {
            instance = new MainFrame();
        }
        return instance;
    }

    public static boolean hasInstance() {
        return instance != null;
    }

    private static void uncaughtException(Thread t, Throwable e) {
        logger.error("Uncaught exception in thread " + t.getName(), e);
        JOptionPane.showMessageDialog(instance, "A fatal error occurred and the application will be closed.\nThe logs can be found at " + logFile.getAbsolutePath(), "Error", JOptionPane.ERROR_MESSAGE);
        BotManager.exit(-1);
    }

    private static void onRuntimeShutdown() {
        logger.info("Goodbye!");
    }

    private JTabbedPane tabs;
    public HomePanel tabHome;
    public SettingsPanel tabSettings;

    private MainFrame() {
        updateTitle(0, 0);
        setIconImage(Utils.getIcon("icomoon/32px/017-headphones.png", 32, true).getImage());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                BotManager.autoLoginAll();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                BotManager.shutdownAllNow();
                MainFrame.this.dispose();
                System.exit(0);
            }
        });

        initComponents();
        layoutComponents();

        setMinimumSize(new Dimension(800, 500));
        pack();
    }

    private void initComponents() {
        tabs = new JTabbedPane();
        tabHome = new HomePanel(this);
        tabSettings = new SettingsPanel();

        // home
        tabs.addTab("Bots", getTabIcon("001-home"), new BorderPanel(tabHome));

        // settings
        tabs.addTab("Audio", getTabIcon("190-menu"), new BorderPanel(tabSettings));
    }

    private Icon getTabIcon(String file) {
        return Utils.getIcon("icomoon/32px/" + file + ".png", 24, true);
    }

    private void layoutComponents() {
        setContentPane(tabs);
    }

    public void updateLoginStatus(int connected, int total) {
        updateTitle(connected, total);
    }

    @Override
    public void run() {
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private void updateTitle(int connected, int total) {
        setTitle(DiscordAudioStreamBot.NAME + " - " + connected + "/" + total + " bots connected");
    }
}
