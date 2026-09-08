package net.runee.model;

import net.dv8tion.jda.api.entities.Guild;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration of a single discord bot account (token + per-guild settings).
 * Audio settings are global, see {@link Config}.
 */
public class BotConfig {
    public String name;
    public String botToken;
    public Boolean autoLogin;
    public List<GuildConfig> guildConfigs;

    public BotConfig() {

    }

    public BotConfig(BotConfig copy) {
        this.name = copy.name;
        this.botToken = copy.botToken;
        this.autoLogin = copy.autoLogin;
        if (copy.guildConfigs != null) {
            this.guildConfigs = new ArrayList<>();
            for (GuildConfig guildConfig : copy.guildConfigs) {
                if (guildConfig != null) {
                    this.guildConfigs.add(new GuildConfig(guildConfig));
                }
            }
        }
    }

    public boolean isAutoLogin() {
        return autoLogin != null ? autoLogin : false;
    }

    public boolean hasToken() {
        return botToken != null && !botToken.trim().isEmpty();
    }

    public String getDisplayName() {
        return name != null && !name.trim().isEmpty() ? name : "(unnamed bot)";
    }

    public GuildConfig getGuildConfig(Guild guild) {
        if (guild != null && guildConfigs != null) {
            for (GuildConfig guildConfig : guildConfigs) {
                if (guildConfig == null) {
                    continue; // what?
                }
                if (Objects.equals(guildConfig.guildId, guild.getId())) {
                    return guildConfig;
                }
            }
        }
        GuildConfig result = new GuildConfig(guild);
        if (guildConfigs == null) {
            guildConfigs = new ArrayList<>();
        }
        guildConfigs.add(result);
        return result;
    }

    /**
     * Returns the first guild config, creating an empty one if needed. Used by the GUI editor, which
     * (for this fork's use case) manages a single guild per bot.
     */
    public GuildConfig getPrimaryGuildConfig() {
        if (guildConfigs == null) {
            guildConfigs = new ArrayList<>();
        }
        for (GuildConfig guildConfig : guildConfigs) {
            if (guildConfig != null) {
                return guildConfig;
            }
        }
        GuildConfig result = new GuildConfig();
        guildConfigs.add(result);
        return result;
    }
}
