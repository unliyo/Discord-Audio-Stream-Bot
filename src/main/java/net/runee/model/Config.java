package net.runee.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Global application configuration: audio settings shared by every bot, plus the list of bots.
 * <p>
 * The legacy single-bot layout ({@code botToken}, {@code autoLogin} and {@code guildConfigs} at top level)
 * is still understood and migrated into a single {@link BotConfig} entry on load, see {@link #migrate()}.
 */
public class Config {
    // audio (shared by all bots)
    public Boolean speakEnabled;
    public String recordingDevice;
    public Boolean listenEnabled;
    public String playbackDevice;
    public Boolean speakThresholdEnabled;
    public Double speakThreshold;

    // bots
    public List<BotConfig> bots;

    // legacy single-bot fields, only read for migration (null after migration, so gson drops them on save)
    public String botToken;
    public Boolean autoLogin;
    public List<GuildConfig> guildConfigs;

    public Config() {

    }

    /**
     * Converts a legacy single-bot config into the multi-bot layout.
     *
     * @return true if a migration happened (config should be saved)
     */
    public boolean migrate() {
        boolean migrated = false;
        if (bots == null) {
            bots = new ArrayList<>();
            migrated = true;
        }
        if (botToken != null || guildConfigs != null || autoLogin != null) {
            BotConfig bot = toLegacyBotConfig();
            if (bot.botToken != null || bot.guildConfigs != null) {
                bot.name = "Bot " + (bots.size() + 1);
                bots.add(bot);
            }
            botToken = null;
            autoLogin = null;
            guildConfigs = null;
            migrated = true;
        }
        return migrated;
    }

    /**
     * Builds a bot config out of the legacy top-level fields (used for migration and for importing
     * config files of older single-bot installations).
     */
    public BotConfig toLegacyBotConfig() {
        BotConfig bot = new BotConfig();
        bot.botToken = botToken;
        bot.autoLogin = autoLogin;
        if (guildConfigs != null) {
            bot.guildConfigs = new ArrayList<>();
            for (GuildConfig guildConfig : guildConfigs) {
                if (guildConfig != null) {
                    bot.guildConfigs.add(new GuildConfig(guildConfig));
                }
            }
        }
        return bot;
    }

    public List<BotConfig> getBots() {
        if (bots == null) {
            bots = new ArrayList<>();
        }
        return bots;
    }

    public boolean getSpeakEnabled() {
        return speakEnabled != null ? speakEnabled : false;
    }

    public boolean getListenEnabled() {
        return listenEnabled != null ? listenEnabled : false;
    }

    public boolean getSpeakThresholdEnabled() {
        return speakThresholdEnabled != null ? speakThresholdEnabled : false;
    }

    public double getSpeakThreshold() {
        return speakThreshold != null ? speakThreshold : 0.5d;
    }
}
