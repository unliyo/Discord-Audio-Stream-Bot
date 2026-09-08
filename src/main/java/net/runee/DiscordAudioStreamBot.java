package net.runee;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.GatewayPingEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.runee.commands.bot.*;
import net.runee.commands.settings.AutoJoinAudioCommand;
import net.runee.commands.settings.BindCommand;
import net.runee.commands.settings.FollowAudioCommand;
import net.runee.commands.user.*;
import net.runee.errors.BassException;
import net.runee.errors.CommandException;
import net.runee.misc.Utils;
import net.runee.misc.discord.Command;
import net.runee.model.BotConfig;
import net.runee.model.Config;
import net.runee.model.GuildConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.util.List;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * One discord bot account. Several instances live side by side in the same process, all of them fed by the
 * same (shared) recording device, see {@link SpeakHandler}. Global state lives in {@link BotManager}.
 */
public class DiscordAudioStreamBot extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordAudioStreamBot.class);
    public static final String NAME = "Discord Audio Stream Bot";
    public static final String GITHUB_URL = "https://github.com/unliyo/Discord-Audio-Stream-Bot";

    /**
     * Callbacks for the GUI. All methods are invoked from JDA threads.
     */
    public interface Listener {
        void onStatusChange(DiscordAudioStreamBot bot, JDA.Status status, CloseCode closeCode);

        void onGatewayPing(DiscordAudioStreamBot bot, Long ping);

        void onAudioPing(DiscordAudioStreamBot bot, Guild guild, Long ping);

        void onAudioChannelChange(DiscordAudioStreamBot bot, Guild guild, AudioChannel channel);
    }

    // data
    private final BotConfig botConfig;
    private JDA jda;
    private Throwable lastLoginError;

    // convenience
    private Map<String, Command> commands;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public DiscordAudioStreamBot(BotConfig botConfig) {
        this.botConfig = Objects.requireNonNull(botConfig);
    }

    public BotConfig getBotConfig() {
        return botConfig;
    }

    public String getName() {
        return botConfig.getDisplayName();
    }

    @Override
    public String toString() {
        return getName();
    }

    private String logPrefix() {
        return "[" + getName() + "] ";
    }

    public static Config getConfig() {
        return BotManager.getConfig();
    }

    // listeners

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    // login

    /**
     * Starts the login procedure (asynchronously). Progress is reported through {@link Listener}.
     *
     * @return false if the login could not even be started (e.g. invalid token)
     */
    public boolean login() {
        if (!botConfig.hasToken()) {
            logger.warn(logPrefix() + "No token set, can't log in");
            return false;
        }
        if (isActive()) {
            logger.warn(logPrefix() + "Already logged in");
            return true;
        }
        logger.info(logPrefix() + "Logging in...");
        lastLoginError = null;
        try {
            jda = JDABuilder.create(botConfig.botToken.trim(),
                            GatewayIntent.GUILD_MEMBERS,
                            GatewayIntent.GUILD_VOICE_STATES,
                            GatewayIntent.GUILD_MESSAGES,
                            //GatewayIntent.GUILD_MESSAGE_REACTIONS,
                            GatewayIntent.DIRECT_MESSAGES
                            //GatewayIntent.DIRECT_MESSAGE_REACTIONS
                    )
                    .addEventListeners(this)
                    .setAudioModuleConfig(new AudioModuleConfig()
                            .withDaveSessionFactory(new JDaveSessionFactory()))
                    .setEnableShutdownHook(false)
                    .build()
            ;
        } catch (Exception ex) {
            logger.error(logPrefix() + "Failed to log in", ex);
            lastLoginError = ex;
            jda = null;
            fireStatusChange(JDA.Status.FAILED_TO_LOGIN, null);
            return false;
        }
        jda.setRequiredScopes("applications.commands"); // necessary for invite url which enables /command interactivity within discord client

        jda.updateCommands()
                .addCommands(getCommands().values()
                        .stream()
                        .map(Command::getData)
                        .collect(Collectors.toList())
                )
                .queue();
        return true;
    }

    public void logoff() {
        if (jda != null) {
            logger.info(logPrefix() + "Logging off...");
            jda.shutdown();
        }
    }

    public void shutdownNow() {
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    public JDA getJDA() {
        return jda;
    }

    public JDA.Status getStatus() {
        return jda != null ? jda.getStatus() : JDA.Status.SHUTDOWN;
    }

    public Throwable getLastLoginError() {
        return lastLoginError;
    }

    /**
     * @return true while a JDA instance exists that is neither shut down nor failed
     */
    public boolean isActive() {
        switch (getStatus()) {
            case SHUTDOWN:
            case FAILED_TO_LOGIN:
                return false;
            default:
                return true;
        }
    }

    public boolean isConnected() {
        return getStatus() == JDA.Status.CONNECTED;
    }

    public String getInviteUrl() {
        return jda.getInviteUrl(Permission.EMPTY_PERMISSIONS);
    }

    /**
     * @return the audio channel this bot is currently connected to (first guild found), or null
     */
    public AudioChannel getConnectedChannel() {
        if (jda != null) {
            for (AudioManager audioManager : jda.getAudioManagers()) {
                if (audioManager.isConnected()) {
                    return audioManager.getConnectedChannel();
                }
            }
        }
        return null;
    }

    public Map<String, Command> getCommands() {
        if (commands == null) {
            List<Command> commands = Arrays.asList(
                    // bot
                    new AboutCommand(),
                    new ExitCommand(),
                    new InviteCommand(),
                    new LeaveAudioAllCommand(),
                    new StopCommand(),
                    // bot user
                    new ActivityCommand(),
                    new JoinAudioCommand(),
                    new LeaveGuildCommand(),
                    new LeaveAudioCommand(),
                    new StatusCommand(),
                    new StageCommand(),
                    // settings
                    new AutoJoinAudioCommand(),
                    new BindCommand(),
                    new FollowAudioCommand()
            );

            this.commands = new HashMap<>();
            for (Command cmd : commands) {
                this.commands.put(cmd.getData().getName(), cmd);
            }
        }
        return commands;
    }

    @Override
    public void onReady(@Nonnull ReadyEvent e) {
        autoJoin();
    }

    private void autoJoin() {
        for (GuildConfig guildConfig : Utils.nullListToEmpty(botConfig.guildConfigs)) {
            if (guildConfig == null || guildConfig.guildId == null) {
                continue;
            }
            for (int step = 0; true; step++) {
                switch (step) {
                    case 0:
                        if (guildConfig.followedUserId != null) {
                            Guild guild = jda.getGuildById(guildConfig.guildId);
                            if (guild == null) {
                                logger.warn(logPrefix() + "Failed to retrieve guild with id '" + guildConfig.guildId + "' to follow voice");
                                continue;
                            }
                            Member target = guild.getMemberById(guildConfig.followedUserId);
                            if (target == null) {
                                logger.warn(logPrefix() + "User with id '" + guildConfig.followedUserId + "' not found in guild " + guild.getName());
                                continue;
                            }
                            AudioChannel target_channel = target.getVoiceState().getChannel();
                            if (target_channel != null) {
                                joinAudio(target_channel);
                                return;
                            }
                        }
                        continue;
                    case 1:
                        if (guildConfig.autoJoinAudioChannelId != null) {
                            Guild guild = jda.getGuildById(guildConfig.guildId);
                            if (guild == null) {
                                logger.warn(logPrefix() + "Failed to retrieve guild with id '" + guildConfig.guildId + "' to auto-join voice");
                                continue;
                            }
                            AudioChannel channel;
                            channel = guild.getVoiceChannelById(guildConfig.autoJoinAudioChannelId);
                            if (channel == null) {
                                channel = guild.getStageChannelById(guildConfig.autoJoinAudioChannelId);
                            }
                            if (channel == null) {
                                logger.warn(logPrefix() + "Voice channel with id '" + guildConfig.autoJoinAudioChannelId + "' not found in guild " + guild.getName());
                                continue;
                            }
                            joinAudio(channel);
                            return;
                        }
                        continue;
                    default:
                        return;
                }
            }
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        if (event.getMember().getUser().equals(jda.getSelfUser())) {
            // our own voice state changed - tell the GUI
            fireAudioChannelChange(event.getGuild(), event.getChannelJoined());
            return;
        }
        if (!isFollowedVoiceTarget(event.getMember())) {
            return;
        }

        if (event.getChannelJoined() != null && event.getChannelLeft() != null) {
            // audio channel moved
            if (!Objects.equals(event.getChannelJoined().getGuild(), event.getChannelLeft().getGuild())) {
                // moved to another guild's audio channel, disconnect audio manager of left guild
                leaveAudio(event.getChannelLeft().getGuild());
            }
            joinAudio(event.getChannelJoined());
        } else if (event.getChannelJoined() != null) {
            // audio channel joined
            joinAudio(event.getChannelJoined());
        } else if (event.getChannelLeft() != null) {
            // audio channel left
            leaveAudio(event.getChannelLeft().getGuild());
        }
    }

    private boolean isFollowedVoiceTarget(Member member) {
        GuildConfig guildConfig = botConfig.getGuildConfig(member.getGuild());
        return guildConfig.followedUserId != null && Objects.equals(member.getId(), guildConfig.followedUserId);
    }

    @Override
    public void onShutdown(@Nonnull ShutdownEvent e) {
        fireGatewayPing(null);
        fireStatusChange(JDA.Status.SHUTDOWN, e.getCloseCode());
    }

    @Override
    public void onGatewayPing(@NotNull GatewayPingEvent e) {
        fireGatewayPing(e.getNewPing());
    }

    @Override
    public void onStatusChange(@Nonnull StatusChangeEvent e) {
        switch (e.getNewValue()) {
            case CONNECTED:
                logger.info(logPrefix() + "Logged in as " + e.getJDA().getSelfUser().getName());
                break;
            case SHUTDOWN:
                logger.info(logPrefix() + "Logged off");
                break;
            case FAILED_TO_LOGIN:
                logger.info(logPrefix() + "Failed to login");
                break;
            default:
                break;
        }
        if (e.getNewValue() != JDA.Status.SHUTDOWN) { // shutdown is reported by onShutdown (with close code)
            fireStatusChange(e.getNewValue(), null);
        }
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent e) {
        Command cmd = getCommands().get(e.getName());
        if (cmd != null) {
            try {
                cmd.run(e);
            } catch (CommandException ex) {
                e.replyEmbeds(new EmbedBuilder()
                        .setDescription(ex.getReplyMessage())
                        .setColor(Utils.colorRed)
                        .build()
                ).setEphemeral(!cmd.isPublic()).queue();
            } catch (Exception ex) {
                logger.error(logPrefix() + "Failed to execute command " + e.getName(), ex);
                e.replyEmbeds(new EmbedBuilder()
                        .setDescription("Failed to execute command; details are in the log.")
                        .setColor(Utils.colorRed)
                        .build()
                ).setEphemeral(!cmd.isPublic()).queue();
            }
        } else {
            e.replyEmbeds(new EmbedBuilder()
                    .setDescription("Unrecognized command: `" + e.getName() + "`!")
                    .setColor(Utils.colorRed)
                    .build()
            ).setEphemeral(true).queue();
        }
    }

    public void sendDirect(User user, MessageCreateData message) {
        user.openPrivateChannel().queue(chan -> {
            chan.sendMessage(message).queue();
        });
    }

    public void sendDirect(User user, String message) {
        sendDirect(user, MessageCreateData.fromContent(message));
    }

    public void sendDirect(User user, MessageEmbed embed) {
        sendDirect(user, MessageCreateData.fromEmbeds(embed));
    }

    // audio

    public void joinAudio(AudioChannel channel) {
        AudioManager audioManager = channel.getGuild().getAudioManager();
        updateSpeakState(audioManager, null, null);
        updateListenState(audioManager, null, null);
        audioManager.setConnectionListener(new ConnectionListener() {
            @Override
            public void onPing(long ping) {
                fireAudioPing(channel.getGuild(), ping);
            }

            @Override
            public void onStatusChange(@Nonnull ConnectionStatus status) {
                try {
                    switch (status) {
                        case CONNECTED: {
                            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
                            if (sendingHandler instanceof SpeakHandler) {
                                ((SpeakHandler) sendingHandler).setPlaying(true);
                            }
                            break;
                        }
                        default: {
                            AudioSendHandler sendingHandler = audioManager.getSendingHandler();
                            if (sendingHandler instanceof SpeakHandler) {
                                ((SpeakHandler) sendingHandler).setPlaying(false);
                            }
                            fireAudioPing(channel.getGuild(), null);
                            break;
                        }
                    }
                } catch (BassException ex) {
                    logger.error(logPrefix() + "Failed to pause/unpause speak handler for guild " + audioManager.getGuild().getName(), ex);
                }
            }
        });
        audioManager.openAudioConnection(channel);
    }

    public void leaveAudio(Guild guild) {
        AudioManager audioManager = guild.getAudioManager();
        if (audioManager.isConnected()) {
            updateSpeakState(audioManager, false, null);
            updateListenState(audioManager, false, null);
            audioManager.closeAudioConnection();
        }
    }

    public void leaveVoiceAll() {
        if (jda == null) {
            return;
        }
        for (AudioManager audioManager : jda.getAudioManagers()) {
            leaveAudio(audioManager.getGuild());
        }
    }

    public void updateSpeakState(AudioManager audioManager, Boolean speakEnabled, String recordingDevice) {
        final Config config = getConfig();
        speakEnabled = speakEnabled != null ? speakEnabled : config.getSpeakEnabled();
        recordingDevice = recordingDevice != null ? recordingDevice : config.recordingDevice;

        // audio send handler
        AudioSendHandler sendingHandler = audioManager.getSendingHandler();
        if (speakEnabled) {
            if (sendingHandler == null) {
                sendingHandler = new SpeakHandler();
            }
            if (sendingHandler instanceof SpeakHandler) {
                try {
                    ((SpeakHandler) sendingHandler).openRecordingDevice(Utils.getRecordingDeviceHandle(recordingDevice), audioManager.isConnected());
                } catch (BassException | RuntimeException ex) {
                    logger.error(logPrefix() + "Failed to open recording device '" + recordingDevice + "'", ex);
                    sendingHandler = null;
                    speakEnabled = false;
                }
            }
        } else {
            if (sendingHandler != null) {
                if (sendingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) sendingHandler);
                }
                sendingHandler = null;
            }
        }
        audioManager.setSendingHandler(sendingHandler);
        audioManager.setSelfMuted(!speakEnabled);
    }

    public void updateListenState(AudioManager audioManager, Boolean listenEnabled, String playbackDevice) {
        final Config config = getConfig();
        listenEnabled = listenEnabled != null ? listenEnabled : config.getListenEnabled();
        playbackDevice = playbackDevice != null ? playbackDevice : config.playbackDevice;

        // audio receive handler
        AudioReceiveHandler receivingHandler = audioManager.getReceivingHandler();
        if (listenEnabled) {
            if (receivingHandler == null) {
                receivingHandler = new ListenHandler();
            }
            if (receivingHandler instanceof ListenHandler) {
                try {
                    ((ListenHandler) receivingHandler).openPlaybackDevice(Utils.getPlaybackDeviceHandle(playbackDevice));
                } catch (BassException | RuntimeException ex) {
                    logger.error(logPrefix() + "Failed to open playback device '" + playbackDevice + "'", ex);
                    receivingHandler = null;
                    listenEnabled = false;
                }
            }
        } else {
            if (receivingHandler != null) {
                if (receivingHandler instanceof Closeable) {
                    Utils.closeQuiet((Closeable) receivingHandler);
                }
                receivingHandler = null;
            }
        }
        audioManager.setReceivingHandler(receivingHandler);
        audioManager.setSelfDeafened(!listenEnabled);
    }

    public void setSpeakEnabled(boolean speakEnabled) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateSpeakState(audioManager, speakEnabled, null);
        }
    }

    public void setListenEnabled(boolean listenEnabled) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateListenState(audioManager, listenEnabled, null);
        }
    }

    public void setRecordingDevice(String recordingDevice) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateSpeakState(audioManager, null, recordingDevice);
        }
    }

    public void setPlaybackDevice(String playbackDevice) {
        for (AudioManager audioManager : getConnectedAudioManagers()) {
            updateListenState(audioManager, null, playbackDevice);
        }
    }

    private List<AudioManager> getConnectedAudioManagers() {
        if (jda != null) {
            List<AudioManager> result = new ArrayList<>();
            for (AudioManager audioManager : jda.getAudioManagers()) {
                if (audioManager.isConnected()) {
                    result.add(audioManager);
                }
            }
            return result;
        } else {
            return Collections.emptyList();
        }
    }

    // listener dispatch

    private void fireStatusChange(JDA.Status status, CloseCode closeCode) {
        for (Listener listener : listeners) {
            listener.onStatusChange(this, status, closeCode);
        }
    }

    private void fireGatewayPing(Long ping) {
        for (Listener listener : listeners) {
            listener.onGatewayPing(this, ping);
        }
    }

    private void fireAudioPing(Guild guild, Long ping) {
        for (Listener listener : listeners) {
            listener.onAudioPing(this, guild, ping);
        }
    }

    private void fireAudioChannelChange(Guild guild, AudioChannel channel) {
        for (Listener listener : listeners) {
            listener.onAudioChannelChange(this, guild, channel);
        }
    }
}
