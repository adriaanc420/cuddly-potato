package com.example.musicbot;

import com.example.musicbot.commands.NowPlayingCommand;
import com.example.musicbot.commands.PauseCommand;
import com.example.musicbot.commands.PlayCommand;
import com.example.musicbot.commands.QueueCommand;
import com.example.musicbot.commands.ResumeCommand;
import com.example.musicbot.commands.SkipCommand;
import com.example.musicbot.commands.StopCommand;
import com.example.musicbot.commands.VolumeCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import com.example.musicbot.BotLogger;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class CommandHandler {
    private final String prefix;
    private final MusicManager musicManager;
    private final BotConfig config;
    
    // Use a HashMap for O(1) command lookup instead of if-else chains
    private final Map<String, Object> commandMap = new HashMap<>();
    
    // Command rate limiting with more efficient map
    private final Map<Long, Long> lastCommandTime = new ConcurrentHashMap<>();
    private static final long COMMAND_COOLDOWN_MS = 1000; // 1 second cooldown
    
    // Message cache for control panels with size limit
    private final Map<Long, Long> controlPanels = new ConcurrentHashMap<>();
    private static final int MAX_CONTROL_PANELS = 100;

    public CommandHandler(MusicManager musicManager, String prefix) {
        this.prefix = prefix;
        this.musicManager = musicManager;
        this.config = new BotConfig();
        
        // Initialize commands more efficiently
        initializeCommands();
    }
    
    private void initializeCommands() {
        // Create shared command instances to reduce memory usage
        PlayCommand playCommand = new PlayCommand(musicManager);
        StopCommand stopCommand = new StopCommand(musicManager);
        PauseCommand pauseCommand = new PauseCommand(musicManager);
        ResumeCommand resumeCommand = new ResumeCommand(musicManager);
        SkipCommand skipCommand = new SkipCommand(musicManager);
        QueueCommand queueCommand = new QueueCommand(musicManager);
        NowPlayingCommand nowPlayingCommand = new NowPlayingCommand(musicManager);
        VolumeCommand volumeCommand = new VolumeCommand(musicManager);
        
        // Register commands with their names (and aliases)
        commandMap.put("play", playCommand);
        commandMap.put("stop", stopCommand);
        commandMap.put("pause", pauseCommand);
        commandMap.put("resume", resumeCommand);
        commandMap.put("skip", skipCommand);
        commandMap.put("queue", queueCommand);
        commandMap.put("q", queueCommand);  // Alias
        commandMap.put("nowplaying", nowPlayingCommand);
        commandMap.put("np", nowPlayingCommand);  // Alias
        commandMap.put("volume", volumeCommand);
        commandMap.put("vol", volumeCommand);  // Alias
        
        // Register method references for special commands
        commandMap.put("controls", (BiConsumer<MessageReceivedEvent, String>) this::sendMusicControlPanel);
        commandMap.put("panel", (BiConsumer<MessageReceivedEvent, String>) this::sendMusicControlPanel);  // Alias
        commandMap.put("shuffle", (BiConsumer<MessageReceivedEvent, String>) this::handleShuffle);
        commandMap.put("remove", (BiConsumer<MessageReceivedEvent, String>) this::handleRemove);
        commandMap.put("help", (BiConsumer<MessageReceivedEvent, String>) this::sendHelpMessage);
        commandMap.put("commands", (BiConsumer<MessageReceivedEvent, String>) this::sendHelpMessage);  // Alias
        commandMap.put("invite", (BiConsumer<MessageReceivedEvent, String>) this::sendInviteLink);
    }

    public void handle(MessageReceivedEvent event, String message) {
        if (!message.startsWith(prefix)) return;
        
        // Extract command and arguments
        String content = message.substring(prefix.length()).trim();
        int spaceIndex = content.indexOf(' ');
        
        final String command;
        final String args;
        
        if (spaceIndex == -1) {
            command = content.toLowerCase();
            args = "";
        } else {
            command = content.substring(0, spaceIndex).toLowerCase();
            args = content.substring(spaceIndex + 1).trim();
        }
        
        // Log command execution
        BotLogger.command(
            event.getGuild().getId(),
            event.getAuthor().getId(),
            command,
            args
        );
        
        // Track performance for this command
        String opId = BotLogger.startOperation("cmd_" + command);
        
        try {
            // Command execution logic
            Object commandHandler = commandMap.get(command);
            
            if (commandHandler instanceof Command) {
                ((Command) commandHandler).execute(event, args);
            } else if (commandHandler instanceof BiConsumer) {
                ((BiConsumer<MessageReceivedEvent, String>) commandHandler).accept(event, args);
            }
            
            BotLogger.stopOperation(opId, "cmd_" + command);
            
        } catch (Exception e) {
            BotLogger.error("Error processing command: " + command, e);
        }
    }
    
    private void sendMusicControlPanel(MessageReceivedEvent event, String args) {
        // Delete existing control panel if there is one
        Long existingPanelId = controlPanels.get(event.getGuild().getIdLong());
        if (existingPanelId != null) {
            event.getChannel().deleteMessageById(existingPanelId).queue(
                null, 
                error -> {} // Ignore errors if message is already gone
            );
        }
        
        // Limit control panel cache size
        if (controlPanels.size() > MAX_CONTROL_PANELS) {
            // Clear oldest entries
            controlPanels.clear();
        }
    
        // Create buttons
        Button playPauseButton = Button.primary("play_pause", "⏯️ Play/Pause");
        Button skipButton = Button.secondary("skip", "⏭️ Skip");
        Button stopButton = Button.danger("stop", "⏹️ Stop");
        Button queueButton = Button.secondary("queue", "📋 Queue");
        Button shuffleButton = Button.secondary("shuffle", "🔀 Shuffle");
    
        // Create embed
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎛️ Music Controls");
        embed.setDescription("Use the buttons below to control the music player.");
        embed.setColor(Color.decode(config.getEmbedColor()));
        embed.setFooter("Music Bot | Control panel expires in 5 minutes", null);
    
        // Send message with buttons and store the ID
        event.getChannel().sendMessageEmbeds(embed.build())
            .setActionRow(playPauseButton, skipButton, stopButton, queueButton, shuffleButton)
            .queue(message -> {
                controlPanels.put(event.getGuild().getIdLong(), message.getIdLong());
                
                // Delete control panel after 5 minutes of inactivity
                message.delete().queueAfter(5, TimeUnit.MINUTES, 
                    null, 
                    error -> {} // Ignore errors if already deleted
                );
            });
    }
    
    private void handleShuffle(MessageReceivedEvent event, String args) {
        try {
            musicManager.shuffle(event.getGuild());
            
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("🔀 Queue Shuffled");
            embed.setDescription("The music queue has been shuffled!");
            embed.setColor(Color.decode(config.getEmbedColor()));
            
            event.getChannel().sendMessageEmbeds(embed.build()).queue();
        } catch (Exception e) {
            handleError(event, "shuffling queue", e);
        }
    }
    
    private void handleRemove(MessageReceivedEvent event, String args) {
        try {
            if (args.isEmpty()) {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("❌ Missing Parameter");
                embed.setDescription("Please provide a track number to remove.");
                embed.setColor(Color.RED);
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
                return;
            }
            
            int index = Integer.parseInt(args) - 1; // Convert to 0-based index
            boolean removed = musicManager.remove(event.getGuild(), index);
            
            EmbedBuilder embed = new EmbedBuilder();
            
            if (removed) {
                embed.setTitle("✅ Track Removed");
                embed.setDescription("Removed track at position " + (index + 1));
                embed.setColor(Color.decode(config.getEmbedColor()));
            } else {
                embed.setTitle("❌ Error");
                embed.setDescription("Could not remove track. Make sure the position is valid.");
                embed.setColor(Color.RED);
            }
            
            event.getChannel().sendMessageEmbeds(embed.build()).queue();
        } catch (NumberFormatException e) {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("❌ Invalid Input");
            embed.setDescription("Please provide a valid track number to remove.");
            embed.setColor(Color.RED);
            event.getChannel().sendMessageEmbeds(embed.build()).queue();
        } catch (Exception e) {
            handleError(event, "removing track", e);
        }
    }
    
    private void sendInviteLink(MessageReceivedEvent event, String args) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🔗 Bot Invite Link");
        embed.setDescription("You can invite this bot to your server using the link below:");
        
        // Try to get the bot's application ID for the invite link
        String botId = event.getJDA().getSelfUser().getId();
        String inviteLink = "https://discord.com/oauth2/authorize?client_id=" + botId + "&scope=bot%20applications.commands&permissions=3165184";
        
        embed.addField("Invite Link", "[Click here to invite the bot](" + inviteLink + ")", false);
        embed.setColor(Color.decode(config.getEmbedColor()));
        
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
    }
    
    private void sendDetailedHelp(MessageReceivedEvent event, String topic) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(Color.decode(config.getEmbedColor()));
        
        switch (topic) {
            case "play":
                embed.setTitle("🎵 Play Command Help");
                embed.setDescription("The play command lets you play music from various sources.");
                embed.addField("Usage", "`" + prefix + "play <url or search terms>`", false);
                embed.addField("Examples", 
                              "`" + prefix + "play https://www.youtube.com/watch?v=dQw4w9WgXcQ`\n" +
                              "`" + prefix + "play lofi hip hop`", false);
                embed.addField("Sources", "YouTube, SoundCloud, Bandcamp, and more", false);
                embed.addField("Tips", "For YouTube, songs can be cached for faster playback next time.", false);
                break;
                
            case "queue":
                embed.setTitle("📋 Queue Command Help");
                embed.setDescription("The queue command shows the current playlist.");
                embed.addField("Usage", "`" + prefix + "queue`", false);
                embed.addField("Related Commands", 
                              "`" + prefix + "shuffle` - Randomize the queue\n" +
                              "`" + prefix + "remove <position>` - Remove a track from the queue", false);
                break;
                
            case "controls":
            case "panel":
                embed.setTitle("🎛️ Controls Command Help");
                embed.setDescription("The controls command displays interactive buttons for controlling playback.");
                embed.addField("Usage", "`" + prefix + "controls` or `" + prefix + "panel`", false);
                embed.addField("Buttons", 
                              "⏯️ - Play/Pause\n" +
                              "⏭️ - Skip\n" +
                              "⏹️ - Stop\n" +
                              "📋 - Queue\n" +
                              "🔀 - Shuffle", false);
                embed.addField("Note", "The control panel will disappear after 5 minutes.", false);
                break;
                
            default:
                embed.setTitle("❓ Unknown Topic");
                embed.setDescription("No detailed help found for topic `" + topic + "`");
                embed.addField("Available Topics", 
                              "play, queue, controls, panel, shuffle, remove, volume, skip, nowplaying", false);
                embed.addField("General Help", "Type `" + prefix + "help` for a command list", false);
                break;
        }
        
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
    }

    private void sendHelpMessage(MessageReceivedEvent event, String args) {
        if (args != null && !args.isEmpty()) {
            sendDetailedHelp(event, args.toLowerCase());
            return;
        }
        
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📋 Music Bot Commands");
        embed.setDescription("Here are all available commands:");
        embed.setColor(Color.decode(config.getEmbedColor()));
        
        // Music commands
        embed.addField("🎵 Music Commands", 
            "`" + prefix + "play <url/search>` - Play a song or add to queue\n" +
            "`" + prefix + "pause` - Pause the current playback\n" +
            "`" + prefix + "resume` - Resume playback\n" +
            "`" + prefix + "skip` - Skip the current song\n" +
            "`" + prefix + "stop` - Stop playback and clear queue\n" +
            "`" + prefix + "nowplaying` or `" + prefix + "np` - Show current song\n" +
            "`" + prefix + "volume <0-150>` - Set the volume", 
            false);
        
        // Queue management
        embed.addField("📋 Queue Management", 
            "`" + prefix + "queue` - Show the current queue\n" +
            "`" + prefix + "shuffle` - Shuffle the queue\n" +
            "`" + prefix + "remove <position>` - Remove a song from the queue", 
            false);
        
        // Utility commands
        embed.addField("🔧 Utility Commands", 
            "`" + prefix + "controls` or `" + prefix + "panel` - Show music control panel\n" +
            "`" + prefix + "ping` - Check bot latency\n" +
            "`" + prefix + "help [command]` - Show this help or detail on a command\n" +
            "`" + prefix + "invite` - Get a link to add this bot to your server", 
            false);
        
        // Additional information
        embed.addField("ℹ️ More Information", 
            "For detailed help on a command, type `" + prefix + "help [command]`\n" +
            "Example: `" + prefix + "help play`\n\n" +
            "You can also use slash commands like `/play` and `/queue`",
            false);
        
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
    }
    
    private void handleError(MessageReceivedEvent event, String operation, Exception e) {
        String errorMessage;
        
        if (e.getMessage() != null && !e.getMessage().isEmpty()) {
            errorMessage = "❌ Error " + operation + ": " + e.getMessage();
        } else {
            errorMessage = "❌ An unexpected error occurred while " + operation + ". Please try again later.";
        }
        
        // Create an embed for the error
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("❌ Error");
        embed.setDescription(errorMessage);
        embed.setColor(Color.RED);
        
        // Send error message
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
        
        // Log the error
        System.err.println("Error during " + operation + ": " + e.getMessage());
        e.printStackTrace();
    }
}