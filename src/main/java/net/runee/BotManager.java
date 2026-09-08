package net.runee;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.dv8tion.jda.api.JDA;
import net.runee.misc.Utils;
import net.runee.model.BotConfig;
import net.runee.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-wide registry: owns the config file and the list of bot instances (one per token).
 */
public final class BotManager {
    private static final Logger logger = LoggerFactory.getLogger(BotManager.class);
    public static final File configPath = new File("config.json");
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setLenient()
            .create();

    private static Config config;
    private static final List<DiscordAudioStreamBot> bots = new CopyOnWriteArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        void onBotsChanged();
    }

    private BotManager() {

    }

    // config

    public static synchronized Config getConfig() {
        if (config == null) {
            try {
                if (configPath.exists()) {
                    config = gson.fromJson(Utils.readAllText(configPath), Config.class);
                }
                if (config == null) {
                    config = new Config();
                }
                if (config.migrate()) {
                    logger.info("Config migrated to multi-bot layout");
                    saveConfig();
                }
            } catch (IOException ex) {
                logger.warn("Failed to load or create new config file", ex);
                config = new Config();
                config.migrate();
            }
            for (BotConfig botConfig : config.getBots()) {
                bots.add(new DiscordAudioStreamBot(botConfig));
            }
        }
        return config;
    }

    public static synchronized void saveConfig() throws IOException {
        Utils.writeAllText(configPath, gson.toJson(config));
    }

    /**
     * Parses a config file of any supported layout and returns the bots defined in it (used for importing
     * config files of older single-bot installations).
     */
    public static List<BotConfig> readBotsFromFile(File file) throws IOException {
        Config other = gson.fromJson(Utils.readAllText(file), Config.class);
        List<BotConfig> result = new ArrayList<>();
        if (other == null) {
            return result;
        }
        if (other.bots != null) {
            for (BotConfig bot : other.bots) {
                if (bot != null) {
                    result.add(new BotConfig(bot));
                }
            }
        }
        BotConfig legacy = other.toLegacyBotConfig();
        if (legacy.botToken != null) {
            result.add(legacy);
        }
        return result;
    }

    // bots

    public static List<DiscordAudioStreamBot> getBots() {
        getConfig();
        return Collections.unmodifiableList(bots);
    }

    public static DiscordAudioStreamBot getBot(JDA jda) {
        if (jda != null) {
            for (DiscordAudioStreamBot bot : bots) {
                if (bot.getJDA() == jda) {
                    return bot;
                }
            }
        }
        return null;
    }

    public static DiscordAudioStreamBot addBot(BotConfig botConfig) {
        getConfig().getBots().add(botConfig);
        DiscordAudioStreamBot bot = new DiscordAudioStreamBot(botConfig);
        bots.add(bot);
        fireBotsChanged();
        return bot;
    }

    public static void removeBot(DiscordAudioStreamBot bot) {
        bot.shutdownNow();
        bots.remove(bot);
        getConfig().getBots().remove(bot.getBotConfig());
        fireBotsChanged();
    }

    public static void loginAll() {
        for (DiscordAudioStreamBot bot : bots) {
            if (bot.getBotConfig().hasToken() && !bot.isActive()) {
                bot.login();
            }
        }
    }

    public static void autoLoginAll() {
        for (DiscordAudioStreamBot bot : bots) {
            if (bot.getBotConfig().hasToken() && bot.getBotConfig().isAutoLogin() && !bot.isActive()) {
                bot.login();
            }
        }
    }

    public static void logoffAll() {
        for (DiscordAudioStreamBot bot : bots) {
            bot.logoff();
        }
    }

    public static void shutdownAllNow() {
        for (DiscordAudioStreamBot bot : bots) {
            bot.shutdownNow();
        }
    }

    // global audio settings, applied to every connected bot

    public static void setSpeakEnabled(boolean speakEnabled) {
        for (DiscordAudioStreamBot bot : bots) {
            bot.setSpeakEnabled(speakEnabled);
        }
    }

    public static void setListenEnabled(boolean listenEnabled) {
        for (DiscordAudioStreamBot bot : bots) {
            bot.setListenEnabled(listenEnabled);
        }
    }

    public static void setRecordingDevice(String recordingDevice) {
        for (DiscordAudioStreamBot bot : bots) {
            bot.setRecordingDevice(recordingDevice);
        }
    }

    public static void setPlaybackDevice(String playbackDevice) {
        for (DiscordAudioStreamBot bot : bots) {
            bot.setPlaybackDevice(playbackDevice);
        }
    }

    // listeners

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void fireBotsChanged() {
        for (Listener listener : listeners) {
            listener.onBotsChanged();
        }
    }

    /**
     * Terminates the whole application (all bots).
     */
    public static void exit(int status) {
        shutdownAllNow();
        System.exit(status);
    }
}
