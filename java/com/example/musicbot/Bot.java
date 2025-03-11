package com.example.musicbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.utils.MiscUtil;

import com.example.musicbot.commands.SlashCommandHandler;
import com.example.musicbot.BotLogger;

import com.example.musicbot.commands.PlayCommand.ButtonResponseRegistry;
import com.example.musicbot.commands.PlayCommand.ButtonResponseRegistry.ButtonResponseData;

import javax.security.auth.login.LoginException;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.awt.Color;
import java.io.File;
import java.io.IOException;

public class Bot extends ListenerAdapter {
    private static JDA jdaInstance;
    private final BotConfig config;
    private final MusicManager musicManager;
    private final CommandHandler commandHandler;
    private final SlashCommandHandler slashCommandHandler;
    private long startTime;

    public Bot() {
        this.config = new BotConfig();
        this.musicManager = new MusicManager();
        this.commandHandler = new CommandHandler(musicManager, config.getPrefix());
        this.slashCommandHandler = new SlashCommandHandler(musicManager);
        this.startTime = System.currentTimeMillis();
        
        // Set JVM options for better performance
        System.setProperty("java.awt.headless", "true"); // Disable AWT if not needed
    }
    
    public void start() throws LoginException {
        BotLogger.status("Starting bot...");
        BotLogger.info("Bot initializing");
        
        String token = config.getToken();
        
        // Create optimized JDABuilder with only necessary intents
        JDABuilder builder = JDABuilder.createDefault(token, 
             GatewayIntent.GUILD_MESSAGES,
             GatewayIntent.MESSAGE_CONTENT,
             GatewayIntent.GUILD_VOICE_STATES,
             GatewayIntent.GUILD_EMOJIS_AND_STICKERS,
             GatewayIntent.SCHEDULED_EVENTS);
        
        // Apply optimizations
        builder.setActivity(Activity.playing(config.getBotStatus()))
               .addEventListeners(this)
               .setBulkDeleteSplittingEnabled(false)
               .setAutoReconnect(true);
        
        jdaInstance = builder.build();
        
        // Wait for the bot to be ready, then register slash commands
        jdaInstance.addEventListener(new ListenerAdapter() {
            @Override
            public void onReady(ReadyEvent event) {
                BotLogger.status("Music Bot successfully connected to Discord!");
                
                // Register slash commands
                registerSlashCommands();
                
                // Send startup notification to owner
                sendStartupNotification();
                
                // Set up a ping task to maintain connection
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        jdaInstance.getRestPing().queue();
                    } catch (Exception e) {
                        // Ignore errors
                    }
                }, 5, 5, TimeUnit.MINUTES);
            }
        });
    }
    
    private void sendStartupNotification() {
        String ownerId = config.getOwner();
        if (ownerId == null || ownerId.isEmpty()) {
            BotLogger.info("No owner ID configured, skipping startup notification");
            return;
        }
        
        try {
            jdaInstance.retrieveUserById(ownerId).queue(owner -> {
                if (owner == null) {
                    BotLogger.info("Could not find owner with ID: " + ownerId);
                    return;
                }
                
                // Create a DM channel and send the message
                owner.openPrivateChannel().queue(channel -> {
                    EmbedBuilder embed = new EmbedBuilder();
                    
                    // Set a futuristic blue color
                    embed.setColor(new Color(0, 191, 255));
                    
                    // Current date and time
                    String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
                    
                    embed.setTitle("🤖 Music Bot Online");
                    
                    // Calculate system specs
                    long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
                    long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
                    long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
                    long usedMemory = totalMemory - freeMemory;
                    
                    // Create a progress bar for memory usage
                    int percentage = (int)(((double)usedMemory / (double)maxMemory) * 100);
                    StringBuilder memoryBar = new StringBuilder();
                    for (int i = 0; i < 20; i++) {
                        if (i < (percentage / 5)) {
                            memoryBar.append("█");
                        } else {
                            memoryBar.append("░");
                        }
                    }
                    
                    // CPU cores
                    int processors = Runtime.getRuntime().availableProcessors();
                    
                    StringBuilder description = new StringBuilder();
                    description.append("```yaml\n");
                    description.append("System Status: ONLINE\n");
                    description.append("Boot Time: ").append(timestamp).append("\n");
                    description.append("Memory: [").append(memoryBar).append("] ").append(percentage).append("%\n");
                    description.append("JVM Memory: ").append(usedMemory).append("MB / ").append(maxMemory).append("MB\n");
                    description.append("CPU Cores: ").append(processors).append("\n");
                    description.append("Java Version: ").append(System.getProperty("java.version")).append("\n");
                    description.append("```");
                    
                    embed.setDescription(description.toString());
                    
                    // Connected servers
                    int serverCount = jdaInstance.getGuilds().size();
                    embed.addField("📊 Statistics", "Connected to " + serverCount + " server" + (serverCount == 1 ? "" : "s"), false);
                    
                    // Command information
                    embed.addField("🎮 Commands", 
                        "Use `" + config.getPrefix() + "help` for command list\n" +
                        "Prefix: `" + config.getPrefix() + "`", false);
                    
                    // Footer
                    embed.setFooter("Music Bot • Ready to rock!", jdaInstance.getSelfUser().getAvatarUrl());
                    
                    channel.sendMessageEmbeds(embed.build()).queue(
                        success -> BotLogger.info("Startup notification sent to owner"),
                        error -> BotLogger.info("Failed to send startup notification: " + error.getMessage())
                    );
                });
            });
        } catch (Exception e) {
            BotLogger.info("Error sending startup notification: " + e.getMessage());
        }
    }


    private void registerSlashCommands() {
        try {
            BotLogger.logInfo("Registering slash commands...");
            
            jdaInstance.updateCommands().addCommands(
                Commands.slash("play", "Play a song")
                    .addOption(OptionType.STRING, "query", "URL or search query", true),
                Commands.slash("stop", "Stop playback"),
                Commands.slash("pause", "Pause playback"),
                Commands.slash("resume", "Resume playback"),
                Commands.slash("skip", "Skip the current song"),
                Commands.slash("queue", "Show the current queue"),
                Commands.slash("nowplaying", "Show the currently playing song"),
                Commands.slash("volume", "Set the volume")
                    .addOption(OptionType.INTEGER, "level", "Volume level (0-150)", true),
                Commands.slash("shuffle", "Shuffle the queue"),
                Commands.slash("controls", "Show music control panel"),
                Commands.slash("ping", "Check the bot's response time"),
                Commands.slash("help", "Show help and command list")
            ).queue();
            
            BotLogger.logInfo("Slash commands registered successfully!");
    } catch (Exception e) {
        BotLogger.logClean("❌ Failed to register slash commands: " + e.getMessage());
        e.printStackTrace();
    }
 }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) return;
        if (event.getAuthor().isBot()) return;
        
        String content = event.getMessage().getContentRaw();
        String prefix = config.getPrefix();
        
        if (content.startsWith(prefix)) {
            BotLogger.logInfo("Command received: " + content + " from user " + event.getAuthor().getAsTag());
        }
        
        // Handle ping command outside of the regular command handler
        if (content.equalsIgnoreCase(prefix + "ping")) {
            handlePingCommand(event);
            return;
        }
        
        // Process normal commands through the handler
        commandHandler.handle(event, content);
    }
    
    private void handlePingCommand(MessageReceivedEvent event) {
        BotLogger.logInfo("Handling ping command from " + event.getAuthor().getAsTag());
        
        long gatewayPing = jdaInstance.getGatewayPing();
        long restPing = -1;
        
        event.getChannel().sendMessage("Measuring ping...").queue(message -> {
            long pingStart = System.currentTimeMillis();
            
            message.editMessage("Calculating...").queue(editedMessage -> {
                long pingEnd = System.currentTimeMillis();
                long messagePing = pingEnd - pingStart;
                
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("🏓 Pong!");
                embed.setColor(Color.decode(config.getEmbedColor()));
                embed.addField("Message Latency", messagePing + "ms", true);
                embed.addField("Gateway Latency", gatewayPing + "ms", true);
                
                // Calculate uptime
                long uptime = System.currentTimeMillis() - startTime;
                long seconds = uptime / 1000;
                long minutes = seconds / 60;
                long hours = minutes / 60;
                long days = hours / 24;
                seconds %= 60;
                minutes %= 60;
                hours %= 24;
                
                String uptimeString = (days > 0 ? days + "d " : "") + 
                                     (hours > 0 ? hours + "h " : "") + 
                                     (minutes > 0 ? minutes + "m " : "") + 
                                     seconds + "s";
                
                embed.addField("Uptime", uptimeString, false);
                
                if (config.isDebugLogging() || config.isTraceLogging()) {
                    embed.addField("Log Level", config.getLogLevel(), true);
                    embed.addField("Java Version", System.getProperty("java.version"), true);
                }
                
                editedMessage.editMessageEmbeds(embed.build()).queue();
                
                BotLogger.logInfo("Ping command results: Message: " + messagePing + 
                              "ms, Gateway: " + gatewayPing + "ms");
            });
        });
    }
    
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("Commands can only be used in servers!").setEphemeral(true).queue();
            return;
        }
        
        BotLogger.logInfo("Slash command received: /" + event.getName() + 
                      " from user " + event.getUser().getAsTag());
        
        // Special handler for ping command
        if (event.getName().equals("ping")) {
            handleSlashPingCommand(event);
            return;
        }
        
        // Handle other slash commands
        slashCommandHandler.handleSlashCommand(event);
    }
    
    private void handleSlashPingCommand(SlashCommandInteractionEvent event) {
        BotLogger.logInfo("Handling slash ping command from " + event.getUser().getAsTag());
        
        long gatewayPing = jdaInstance.getGatewayPing();
        
        event.deferReply().queue(hook -> {
            // PROBLEM: Using complete() inside a callback causes deadlocks
            // BEFORE: long restPing = event.getJDA().getRestPing().complete();
            
            // SOLUTION: Use queue() instead of complete() for asynchronous operation
            event.getJDA().getRestPing().queue(restPing -> {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("🏓 Pong!");
                embed.setColor(Color.decode(config.getEmbedColor()));
                embed.addField("API Latency", restPing + "ms", true);
                embed.addField("Gateway Latency", gatewayPing + "ms", true);
                
                // Calculate uptime
                long uptime = System.currentTimeMillis() - startTime;
                long seconds = uptime / 1000;
                long minutes = seconds / 60;
                long hours = minutes / 60;
                long days = hours / 24;
                seconds %= 60;
                minutes %= 60;
                hours %= 24;
                
                String uptimeString = (days > 0 ? days + "d " : "") + 
                                   (hours > 0 ? hours + "h " : "") + 
                                   (minutes > 0 ? minutes + "m " : "") + 
                                   seconds + "s";
                
                embed.addField("Uptime", uptimeString, false);
                
                if (config.isDebugLogging() || config.isTraceLogging()) {
                    embed.addField("Log Level", config.getLogLevel(), true);
                    embed.addField("Java Version", System.getProperty("java.version"), true);
                }
                
                hook.sendMessageEmbeds(embed.build()).queue();
                
                BotLogger.logInfo("Slash ping command results - Gateway: " + gatewayPing + "ms, REST: " + restPing + "ms");
            },
            // Error handling for REST ping
            error -> {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("🏓 Pong!");
                embed.setColor(Color.decode(config.getEmbedColor()));
                embed.addField("REST API", "Failed to measure", true);
                embed.addField("Gateway Latency", gatewayPing + "ms", true);
                
                long uptime = System.currentTimeMillis() - startTime;
                long seconds = uptime / 1000;
                long minutes = seconds / 60;
                long hours = minutes / 60;
                long days = hours / 24;
                seconds %= 60;
                minutes %= 60;
                hours %= 24;
                
                String uptimeString = (days > 0 ? days + "d " : "") + 
                                   (hours > 0 ? hours + "h " : "") + 
                                   (minutes > 0 ? minutes + "m " : "") + 
                                   seconds + "s";
                
                embed.addField("Uptime", uptimeString, false);
                
                hook.sendMessageEmbeds(embed.build()).queue();
                
                BotLogger.logInfo("Slash ping command results - Gateway: " + gatewayPing + "ms, REST: Error");
            });
        });
    }
    
    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String buttonId = event.getComponentId();
        Guild guild = event.getGuild();
        
        BotLogger.logInfo("Button interaction: " + buttonId + " from user " + event.getUser().getAsTag());
        
        // Handle play command cache buttons and search platform buttons
        if (buttonId.equals("cache_yes") || buttonId.equals("cache_no") || 
            buttonId.equals("search_youtube") || buttonId.equals("search_soundcloud")) {
            handleCacheButtons(event);
            return;
        }
        
        if (guild == null) return;
        
        switch (buttonId) {
            case "play_pause":
                var player = musicManager.getPlayer(guild);
                boolean wasPaused = player.isPaused();
                player.setPaused(!wasPaused);
                event.reply(wasPaused ? "▶️ Resumed playback" : "⏸️ Paused playback").setEphemeral(true).queue();
                BotLogger.logInfo("Player " + (wasPaused ? "resumed" : "paused") + " in guild " + guild.getName());
                break;
                
            case "skip":
                if (musicManager.getPlayer(guild).getPlayingTrack() == null) {
                    event.reply("❌ Nothing is playing to skip.").setEphemeral(true).queue();
                } else {
                    boolean hasQueue = !musicManager.getQueue(guild).isEmpty();
                    String currentTrackTitle = musicManager.getPlayer(guild).getPlayingTrack().getInfo().title;
                    
                    musicManager.getPlayer(guild).stopTrack();
                    musicManager.playNext(guild);
                    
                    if (hasQueue && musicManager.getPlayer(guild).getPlayingTrack() != null) {
                        event.reply("⏭️ Skipped: `" + currentTrackTitle + "`. Now playing: `" + 
                                musicManager.getPlayer(guild).getPlayingTrack().getInfo().title + "`").setEphemeral(true).queue();
                    } else {
                        event.reply("⏭️ Skipped: `" + currentTrackTitle + "`. Queue is now empty.").setEphemeral(true).queue();
                    }
                    
                    BotLogger.logInfo("Track skipped in guild " + guild.getName());
                }
                break;
                
                case "stop":
                if (musicManager.getPlayer(guild).getPlayingTrack() == null) {
                    event.reply("❌ Nothing is playing to stop.").setEphemeral(true).queue();
                } else {
                    // Fix for concurrent modification exception
                    // Acknowledge the interaction immediately
                    event.reply("⏹️ Playback stopped and disconnected.").setEphemeral(true).queue();
                    
                    // Safely get a reference to the track that's playing
                    final AudioTrack currentTrack = musicManager.getPlayer(guild).getPlayingTrack();
                    
                    // Small delay to ensure the interaction finishes processing
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // Ignore interruption
                    }
                    
                    // Clear the queue first to prevent playNext from being triggered
                    musicManager.clearQueue(guild);
                    
                    // Then stop the track
                    musicManager.getPlayer(guild).stopTrack();
                    
                    // Finally disconnect from voice
                    guild.getAudioManager().closeAudioConnection();
                    
                    BotLogger.logInfo("Playback stopped in guild " + guild.getName());
                }
                break;   
                
            case "queue":
                // Use QueueCommand logic but reply with ephemeral message
                AudioTrack currentTrack = musicManager.getPlayer(guild).getPlayingTrack();
                if (currentTrack == null) {
                    event.reply("No track is currently playing.").setEphemeral(true).queue();
                } else {
                    // Create a simplified queue listing
                    StringBuilder queueInfo = new StringBuilder();
                    queueInfo.append("**Now Playing**: `").append(currentTrack.getInfo().title).append("`\n\n");
                    
                    Queue<AudioTrack> queue = musicManager.getQueue(guild);
                    if (queue == null || queue.isEmpty()) {
                        queueInfo.append("Queue is empty.");
                    } else {
                        queueInfo.append("**Queue**:\n");
                        int trackNumber = 1;
                        for (AudioTrack track : queue) {
                            if (trackNumber > 5) {
                                queueInfo.append("And ").append(queue.size() - 5).append(" more...");
                                break;
                            }
                            
                            queueInfo.append("`").append(trackNumber++).append(".` ");
                            queueInfo.append("`").append(track.getInfo().title).append("`\n");
                        }
                    }
                    
                    event.reply(queueInfo.toString()).setEphemeral(true).queue();
                    BotLogger.logInfo("Queue displayed in guild " + guild.getName());
                }
                break;
                
            case "shuffle":
                musicManager.shuffle(guild);
                event.reply("🔀 Queue has been shuffled!").setEphemeral(true).queue();
                BotLogger.logInfo("Queue shuffled in guild " + guild.getName());
                break;
        }
    }
    
    private void handleCacheButtons(ButtonInteractionEvent event) {
        // Check if this is a valid cached button interaction
        ButtonResponseData responseData = ButtonResponseRegistry.getResponseData(event.getMessageIdLong());
        
        if (responseData == null) {
            event.reply("This interaction has expired.").setEphemeral(true).queue();
            return;
        }
        
        // Check if the user who clicked is the user who initiated the command
        if (event.getUser().getIdLong() != responseData.getUserId()) {
            event.reply("Only the user who requested the song can choose this option.").setEphemeral(true).queue();
            return;
        }
        
        // Shut down the scheduled timeout
        if (responseData.getScheduler() != null) {
            responseData.getScheduler().shutdownNow();
        }
        
        // Process the choice
        String choiceId = event.getComponentId();
        responseData.getResponseHandler().accept(choiceId);
        
        // Acknowledge the interaction
        event.deferEdit().queue();
        
        // Remove this from the registry
        ButtonResponseRegistry.removeMessage(event.getMessageIdLong());
        
        BotLogger.logInfo("Cache button " + choiceId + " processed for user " + event.getUser().getAsTag());
    }

    public MusicManager getMusicManager() {
        return musicManager;
    }

    public static JDA getJDAInstance() {
        return jdaInstance;
    }
}