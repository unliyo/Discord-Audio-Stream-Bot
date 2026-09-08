package net.runee.gui.components;

import com.jgoodies.forms.builder.FormBuilder;
import net.runee.misc.Utils;
import net.runee.misc.gui.DialogResult;
import net.runee.misc.gui.EditorDialog;
import net.runee.misc.gui.SpecBuilder;
import net.runee.model.BotConfig;
import net.runee.model.GuildConfig;

import javax.swing.*;
import java.awt.*;

/**
 * Dialog to create or edit a single bot entry (name, token, auto login, guild + auto-join channel).
 */
public class BotEditorDialog extends EditorDialog<JPanel> {
    private final BotConfig target;

    private JTextField name;
    private JPasswordField token;
    private JCheckBox showToken;
    private JCheckBox autoLogin;
    private JTextField guildId;
    private JTextField autoJoinChannelId;

    public BotEditorDialog(Frame owner, BotConfig target, boolean tokenEditable) {
        super(owner, new JPanel());
        this.target = target;
        setTitle(target.hasToken() ? "Edit bot" : "New bot");
        initComponents(tokenEditable);
        layoutComponents();
        loadFromTarget();
        pack();
        setMinimumSize(new Dimension(520, getHeight()));
        setLocationRelativeTo(owner);
    }

    private void initComponents(boolean tokenEditable) {
        name = new JTextField(24);
        token = new JPasswordField(48);
        token.setEnabled(tokenEditable);
        token.setToolTipText(tokenEditable ? "Bot token from the discord developer portal" : "Log the bot off to change its token");
        showToken = new JCheckBox("Show");
        showToken.addActionListener(e -> token.setEchoChar(showToken.isSelected() ? (char) 0 : '•'));
        autoLogin = new JCheckBox("Log in automatically on startup");
        guildId = new JTextField(24);
        guildId.setToolTipText("Discord server (guild) id");
        autoJoinChannelId = new JTextField(24);
        autoJoinChannelId.setToolTipText("Voice channel id to join right after login (optional). Can also be set with /autojoin from discord.");
    }

    private void layoutComponents() {
        int row = 1;
        JPanel tokenRow = new JPanel(new BorderLayout(5, 0));
        tokenRow.add(token, BorderLayout.CENTER);
        tokenRow.add(showToken, BorderLayout.EAST);

        FormBuilder
                .create()
                .columns(SpecBuilder
                        .create()
                        .add("r:p")
                        .add("f:p:g")
                        .build()
                )
                .rows(SpecBuilder
                        .create()
                        .add("c:p") // bot
                        .add("c:p")
                        .add("c:p")
                        .add("c:p")
                        .gapUnrelated().add("c:p") // guild
                        .add("c:p")
                        .add("c:p")
                        .build()
                )
                .panel(getEditor())
                .border(BorderFactory.createEmptyBorder(5, 5, 5, 5))
                .addSeparator("Bot").xyw(1, row, 3)
                .add("Name").xy(1, row += 2)
                /**/.add(name).xy(3, row)
                .add("Token").xy(1, row += 2)
                /**/.add(tokenRow).xy(3, row)
                .add("").xy(1, row += 2)
                /**/.add(autoLogin).xy(3, row)
                .addSeparator("Server").xyw(1, row += 2, 3)
                .add("Guild id").xy(1, row += 2)
                /**/.add(guildId).xy(3, row)
                .add("Auto-join channel id").xy(1, row += 2)
                /**/.add(autoJoinChannelId).xy(3, row)
                .build();
    }

    private void loadFromTarget() {
        name.setText(Utils.nullToEmptyString(target.name));
        token.setText(Utils.nullToEmptyString(target.botToken));
        autoLogin.setSelected(target.isAutoLogin());
        GuildConfig guildConfig = firstGuildConfig();
        guildId.setText(guildConfig != null ? Utils.nullToEmptyString(guildConfig.guildId) : "");
        autoJoinChannelId.setText(guildConfig != null ? Utils.nullToEmptyString(guildConfig.autoJoinAudioChannelId) : "");
    }

    private GuildConfig firstGuildConfig() {
        if (target.guildConfigs != null) {
            for (GuildConfig guildConfig : target.guildConfigs) {
                if (guildConfig != null) {
                    return guildConfig;
                }
            }
        }
        return null;
    }

    private boolean applyToTarget() {
        String tokenValue = Utils.emptyStringToNull(new String(token.getPassword()).trim());
        if (token.isEnabled() && tokenValue == null) {
            Utils.guiError(this, "A bot token must be set.");
            return false;
        }
        String guildIdValue = Utils.emptyStringToNull(guildId.getText().trim());
        String channelIdValue = Utils.emptyStringToNull(autoJoinChannelId.getText().trim());
        if (!isSnowflake(guildIdValue) || !isSnowflake(channelIdValue)) {
            Utils.guiError(this, "Guild and channel ids must be numeric discord ids.");
            return false;
        }
        if (channelIdValue != null && guildIdValue == null) {
            Utils.guiError(this, "An auto-join channel requires a guild id.");
            return false;
        }

        target.name = Utils.emptyStringToNull(name.getText().trim());
        if (token.isEnabled()) {
            target.botToken = tokenValue;
        }
        target.autoLogin = autoLogin.isSelected();
        GuildConfig guildConfig = firstGuildConfig();
        if (guildIdValue != null) {
            if (guildConfig == null) {
                guildConfig = target.getPrimaryGuildConfig();
            }
            guildConfig.guildId = guildIdValue;
            guildConfig.autoJoinAudioChannelId = channelIdValue;
        } else if (guildConfig != null) {
            target.guildConfigs.remove(guildConfig);
        }
        return true;
    }

    private static boolean isSnowflake(String value) {
        if (value == null) {
            return true;
        }
        if (value.length() < 15 || value.length() > 22) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close(DialogResult dialogResult) {
        if (dialogResult == DialogResult.ok && !applyToTarget()) {
            return; // keep dialog open
        }
        super.close(dialogResult);
    }
}
