package net.runee.gui.components;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.requests.CloseCode;
import net.runee.BotManager;
import net.runee.DiscordAudioStreamBot;
import net.runee.gui.MainFrame;
import net.runee.misc.Utils;
import net.runee.misc.gui.DialogResult;
import net.runee.model.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;

/**
 * Main tab: one row per bot with its login / audio state, plus bulk and per-bot actions.
 */
public class HomePanel extends JPanel implements DiscordAudioStreamBot.Listener, BotManager.Listener {
    private static final Logger logger = LoggerFactory.getLogger(HomePanel.class);

    private static final String[] COLUMNS = {"Bot", "Status", "Discord user", "Audio channel", "Gateway", "Audio"};

    /** per-bot GUI state, fed by bot events */
    private static class BotState {
        JDA.Status status = JDA.Status.SHUTDOWN;
        CloseCode closeCode;
        Long gatewayPing;
        final Map<Guild, Long> audioPings = new HashMap<>();
        AudioChannel channel;

        Long getAudioPing() {
            if (audioPings.isEmpty()) {
                return null;
            }
            return audioPings.values().iterator().next();
        }
    }

    private final MainFrame mainFrame;
    private final Map<DiscordAudioStreamBot, BotState> states = new IdentityHashMap<>();
    private final List<DiscordAudioStreamBot> bots = new ArrayList<>();

    private JTable table;
    private BotTableModel tableModel;
    private JButton loginAllButton;
    private JButton logoffAllButton;
    private JButton loginButton;
    private JButton addButton;
    private JButton importButton;
    private JButton editButton;
    private JButton removeButton;
    private JButton inviteButton;
    private JLabel summaryLabel;

    public HomePanel(MainFrame mainFrame) {
        this.mainFrame = mainFrame;
        initComponents();
        layoutComponents();
        BotManager.addListener(this);
        onBotsChanged();
    }

    private void initComponents() {
        tableModel = new BotTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.setAutoCreateRowSorter(false);
        table.getSelectionModel().addListSelectionListener(e -> updateControls());
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(160);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(70);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && getSelectedBot() != null) {
                    editSelected();
                }
            }
        });

        loginAllButton = new JButton("Login all", Utils.getIcon("icomoon/32px/183-switch.png", 16, true));
        loginAllButton.addActionListener(e -> BotManager.loginAll());
        logoffAllButton = new JButton("Logoff all");
        logoffAllButton.addActionListener(e -> BotManager.logoffAll());

        loginButton = new JButton("Login");
        loginButton.addActionListener(e -> toggleSelected());
        addButton = new JButton("Add...");
        addButton.addActionListener(e -> addBot());
        importButton = new JButton("Import config...");
        importButton.setToolTipText("Import bots from config.json files of single-bot installations");
        importButton.addActionListener(e -> importBots());
        editButton = new JButton("Edit...");
        editButton.addActionListener(e -> editSelected());
        removeButton = new JButton("Remove");
        removeButton.addActionListener(e -> removeSelected());
        inviteButton = new JButton("Invite...");
        inviteButton.setToolTipText("Open the invite url of the selected bot in the browser");
        inviteButton.addActionListener(e -> inviteSelected());

        summaryLabel = new JLabel();
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(10f));
    }

    private void layoutComponents() {
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel top = new JPanel(new BorderLayout());
        top.add(Utils.buildFlowPanel(loginAllButton, logoffAllButton), BorderLayout.WEST);
        top.add(summaryLabel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        add(new JScrollPane(table), BorderLayout.CENTER);

        add(Utils.buildFlowPanel(loginButton, editButton, addButton, importButton, removeButton, inviteButton), BorderLayout.SOUTH);
    }

    // bot registry

    @Override
    public void onBotsChanged() {
        Runnable r = () -> {
            for (DiscordAudioStreamBot bot : bots) {
                bot.removeListener(this);
            }
            bots.clear();
            bots.addAll(BotManager.getBots());
            states.keySet().retainAll(new HashSet<>(bots));
            for (DiscordAudioStreamBot bot : bots) {
                bot.addListener(this);
                BotState state = states.computeIfAbsent(bot, b -> new BotState());
                state.status = bot.getStatus();
                state.channel = bot.getConnectedChannel();
            }
            tableModel.fireTableDataChanged();
            updateControls();
        };
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            EventQueue.invokeLater(r);
        }
    }

    private BotState getState(DiscordAudioStreamBot bot) {
        return states.computeIfAbsent(bot, b -> new BotState());
    }

    // bot events (JDA threads)

    @Override
    public void onStatusChange(DiscordAudioStreamBot bot, JDA.Status status, CloseCode closeCode) {
        EventQueue.invokeLater(() -> {
            BotState state = getState(bot);
            state.status = status;
            state.closeCode = closeCode;
            switch (status) {
                case CONNECTED:
                    break;
                default:
                    state.gatewayPing = null;
                    state.audioPings.clear();
                    state.channel = null;
                    break;
            }
            fireBotUpdated(bot);
            updateControls();
        });
    }

    @Override
    public void onGatewayPing(DiscordAudioStreamBot bot, Long ping) {
        EventQueue.invokeLater(() -> {
            getState(bot).gatewayPing = ping;
            fireBotUpdated(bot);
        });
    }

    @Override
    public void onAudioPing(DiscordAudioStreamBot bot, Guild guild, Long ping) {
        EventQueue.invokeLater(() -> {
            BotState state = getState(bot);
            if (ping != null) {
                state.audioPings.put(guild, ping);
            } else {
                state.audioPings.remove(guild);
            }
            fireBotUpdated(bot);
        });
    }

    @Override
    public void onAudioChannelChange(DiscordAudioStreamBot bot, Guild guild, AudioChannel channel) {
        EventQueue.invokeLater(() -> {
            getState(bot).channel = channel;
            fireBotUpdated(bot);
        });
    }

    private void fireBotUpdated(DiscordAudioStreamBot bot) {
        int row = bots.indexOf(bot);
        if (row >= 0) {
            tableModel.fireTableRowsUpdated(row, row);
        }
        updateSummary();
    }

    // actions

    private DiscordAudioStreamBot getSelectedBot() {
        int row = table.getSelectedRow();
        return row >= 0 && row < bots.size() ? bots.get(row) : null;
    }

    private void toggleSelected() {
        DiscordAudioStreamBot bot = getSelectedBot();
        if (bot == null) {
            return;
        }
        if (bot.isActive()) {
            bot.logoff();
        } else {
            if (!bot.getBotConfig().hasToken()) {
                Utils.guiError(this, "A bot token must be set. Use Edit... to set one.");
                return;
            }
            if (!bot.login() && bot.getLastLoginError() != null) {
                Utils.guiError(this, "Failed to log in", bot.getLastLoginError());
            }
        }
        updateControls();
    }

    private void addBot() {
        BotConfig botConfig = new BotConfig();
        botConfig.autoLogin = true;
        // default the guild to the one the other bots use, this fork's use case is 'many bots, one server'
        for (DiscordAudioStreamBot other : bots) {
            if (other.getBotConfig().guildConfigs != null && !other.getBotConfig().guildConfigs.isEmpty()) {
                botConfig.getPrimaryGuildConfig().guildId = other.getBotConfig().guildConfigs.get(0).guildId;
                break;
            }
        }
        BotEditorDialog dialog = new BotEditorDialog(mainFrame, botConfig, true);
        dialog.setVisible(true);
        if (dialog.getDialogResult() == DialogResult.ok) {
            BotManager.addBot(botConfig);
            saveConfig();
            selectBot(botConfig);
        }
    }

    private void importBots() {
        JFileChooser chooser = new JFileChooser(new File("."));
        chooser.setDialogTitle("Import bots from config.json files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON config (*.json)", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        int imported = 0, skipped = 0;
        for (File file : chooser.getSelectedFiles()) {
            try {
                for (BotConfig botConfig : BotManager.readBotsFromFile(file)) {
                    if (findByToken(botConfig.botToken) != null) {
                        skipped++;
                        continue;
                    }
                    if (botConfig.name == null) {
                        File dir = file.getAbsoluteFile().getParentFile();
                        botConfig.name = dir != null ? dir.getName() : "Bot " + (bots.size() + 1);
                    }
                    BotManager.addBot(botConfig);
                    imported++;
                }
            } catch (IOException | RuntimeException ex) {
                logger.error("Failed to import " + file, ex);
                Utils.guiError(this, "Failed to import " + file.getName(), ex);
            }
        }
        if (imported > 0) {
            saveConfig();
        }
        JOptionPane.showMessageDialog(this, "Imported " + imported + " bot(s)" + (skipped > 0 ? ", skipped " + skipped + " already present" : "") + ".", "Import", JOptionPane.INFORMATION_MESSAGE);
    }

    private DiscordAudioStreamBot findByToken(String token) {
        if (token == null) {
            return null;
        }
        for (DiscordAudioStreamBot bot : bots) {
            if (token.trim().equals(Utils.nullToEmptyString(bot.getBotConfig().botToken).trim())) {
                return bot;
            }
        }
        return null;
    }

    private void editSelected() {
        DiscordAudioStreamBot bot = getSelectedBot();
        if (bot == null) {
            return;
        }
        BotConfig draft = new BotConfig(bot.getBotConfig());
        BotEditorDialog dialog = new BotEditorDialog(mainFrame, draft, !bot.isActive());
        dialog.setVisible(true);
        if (dialog.getDialogResult() == DialogResult.ok) {
            BotConfig target = bot.getBotConfig();
            target.name = draft.name;
            target.botToken = draft.botToken;
            target.autoLogin = draft.autoLogin;
            target.guildConfigs = draft.guildConfigs;
            saveConfig();
            fireBotUpdated(bot);
        }
    }

    private void removeSelected() {
        DiscordAudioStreamBot bot = getSelectedBot();
        if (bot == null) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, "Remove bot '" + bot.getName() + "'?" + (bot.isActive() ? "\nIt will be logged off first." : ""), "Remove bot", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        BotManager.removeBot(bot);
        saveConfig();
    }

    private void inviteSelected() {
        DiscordAudioStreamBot bot = getSelectedBot();
        if (bot == null || !bot.isConnected()) {
            return;
        }
        if (!Utils.browseUrl(bot.getInviteUrl())) {
            Utils.guiError(this, "Unable to open invite url in browser.");
        }
    }

    private void selectBot(BotConfig botConfig) {
        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).getBotConfig() == botConfig) {
                table.getSelectionModel().setSelectionInterval(i, i);
                return;
            }
        }
    }

    private void saveConfig() {
        try {
            BotManager.saveConfig();
        } catch (IOException ex) {
            Utils.guiError(this, "Failed to save config", ex);
        }
    }

    // state display

    private void updateControls() {
        DiscordAudioStreamBot bot = getSelectedBot();
        boolean selected = bot != null;
        boolean active = selected && bot.isActive();
        boolean busy = selected && active && !bot.isConnected();
        loginButton.setEnabled(selected && !busy);
        loginButton.setText(active ? "Logoff" : "Login");
        editButton.setEnabled(selected);
        removeButton.setEnabled(selected);
        inviteButton.setEnabled(selected && bot.isConnected());
        boolean anyInactive = false, anyActive = false;
        for (DiscordAudioStreamBot b : bots) {
            if (b.isActive()) {
                anyActive = true;
            } else if (b.getBotConfig().hasToken()) {
                anyInactive = true;
            }
        }
        loginAllButton.setEnabled(anyInactive);
        logoffAllButton.setEnabled(anyActive);
        updateSummary();
    }

    private void updateSummary() {
        int connected = 0, inAudio = 0;
        for (DiscordAudioStreamBot b : bots) {
            BotState state = states.get(b);
            if (state != null && state.status == JDA.Status.CONNECTED) {
                connected++;
                if (state.channel != null) {
                    inAudio++;
                }
            }
        }
        summaryLabel.setText(connected + "/" + bots.size() + " connected, " + inAudio + " in audio");
        mainFrame.updateLoginStatus(connected, bots.size());
    }

    private static String format(JDA.Status status) {
        String[] words = status.name().replace("_", " ").split(" ", -1);
        for (int i = 0; i < words.length; i++) {
            final String word = words[i];
            words[i] = word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
        }
        return String.join(" ", words);
    }

    private static String formatPing(Long ping) {
        return ping != null ? ping + " ms" : "";
    }

    private class BotTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return bots.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DiscordAudioStreamBot bot = bots.get(rowIndex);
            BotState state = getState(bot);
            switch (columnIndex) {
                case 0:
                    return bot.getName();
                case 1:
                    return state;
                case 2: {
                    JDA jda = bot.getJDA();
                    return jda != null && state.status == JDA.Status.CONNECTED ? jda.getSelfUser().getName() : "";
                }
                case 3:
                    return state.channel != null ? state.channel.getName() : "";
                case 4:
                    return formatPing(state.gatewayPing);
                case 5:
                    return formatPing(state.getAudioPing());
                default:
                    return "";
            }
        }
    }

    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            BotState state = (BotState) value;
            String text = state != null ? format(state.status) : "";
            if (state != null && state.closeCode != null && state.status != JDA.Status.CONNECTED) {
                text += " (" + state.closeCode.getCode() + ")";
            }
            Component c = super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
            if (!isSelected && state != null) {
                switch (state.status) {
                    case CONNECTED:
                        c.setForeground(Utils.colorGreen.darker());
                        break;
                    case SHUTDOWN:
                    case FAILED_TO_LOGIN:
                        c.setForeground(Utils.colorRed);
                        break;
                    default:
                        c.setForeground(Utils.colorYellow.darker());
                        break;
                }
            } else if (isSelected) {
                c.setForeground(table.getSelectionForeground());
            }
            if (state != null && state.closeCode != null) {
                setToolTipText("CloseCode " + state.closeCode.getCode() + ": " + state.closeCode.getMeaning());
            } else {
                setToolTipText(null);
            }
            return c;
        }
    }
}
