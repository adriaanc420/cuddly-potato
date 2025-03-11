package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.AudioPlayerSendHandler;
import com.example.musicbot.BotConfig;
import com.example.musicbot.SpotifyManager;
import com.example.musicbot.SpotifyManager.TrackInfo;
import com.example.musicbot.SpotifyManager.SpotifyUrlType;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;  // Add this import
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SlashCommandHandler {
    private final MusicManager musicManager;
    private final SpotifyManager spotifyManager;
    private final BotConfig config;
    
    public SlashCommandHandler(MusicManager musicManager) {
        this.musicManager = musicManager;
        this.config = new BotConfig();
        this.spotifyManager = new SpotifyManager(config);
    }
    
    public void handleSlashCommand(SlashCommandInteractionEvent event) {
        String commandName = event.getName();
        
        try {
            switch (commandName) {
                case "play":
                    handlePlayCommand(event);
                    break;
                case "stop":
                    handleStopCommand(event);
                    break;
                case "pause":
                    handlePauseCommand(event);
                    break;
                case "resume":
                    handleResumeCommand(event);
                    break;
                case "skip":
                    handleSkipCommand(event);
                    break;
                case "queue":
                    handleQueueCommand(event);
                    break;
                case "nowplaying":
                    handleNowPlayingCommand(event);
                    break;
                case "volume":
                    handleVolumeCommand(event);
                    break;
                case "shuffle":
                    handleShuffleCommand(event);
                    break;
                case "controls":
                    handleControlsCommand(event);
                    break;
                case "help":
                    handleHelpCommand(event);
                    break;
                default:
                    event.reply("Unknown command: " + commandName).setEphemeral(true).queue();
            }
        } catch (Exception e) {
            // Handle any exceptions
            String errorMessage = "An error occurred while processing command: " + e.getMessage();
            if (event.isAcknowledged()) {
                event.getHook().sendMessage(errorMessage).setEphemeral(true).queue();
            } else {
                event.reply(errorMessage).setEphemeral(true).queue();
            }
            e.printStackTrace();
        }
    }
    
    private void handlePlayCommand(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        GuildVoiceState voiceState = member.getVoiceState();
        
        if (voiceState == null || !voiceState.inAudioChannel()) {
            event.reply("❌ You must be in a voice channel to use this command!").setEphemeral(true).queue();
            return;
        }
        
        String query = event.getOption("query").getAsString();
        
        // Defer reply to allow time for processing
        event.deferReply().queue();
        InteractionHook hook = event.getHook();
        
        Guild guild = event.getGuild();
        guild.getAudioManager().openAudioConnection(voiceState.getChannel());
        var player = musicManager.getPlayer(guild);
        
        if (guild.getAudioManager().getSendingHandler() == null) {
            guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
        }
        
        // Check if it's a Spotify URL
        if (config.isSpotifyEnabled() && spotifyManager.isSpotifyUrl(query)) {
            handleSpotifyUrl(hook, guild, query, player);
            return;
        }
        
        // Check if it's a YouTube URL
        if (isYouTubeUrl(query)) {
            hook.sendMessage("YouTube link detected. Processing...").queue();
        }
        
        // Continue with the existing code for non-Spotify URLs
        musicManager.getPlayerManager().loadItem(query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (player.getPlayingTrack() != null) {
                    musicManager.queueTrack(guild, track);
                    hook.sendMessage("🎵 Track queued: `" + track.getInfo().title + "`").queue();
                } else {
                    player.playTrack(track);
                    hook.sendMessage("🎵 Now playing: `" + track.getInfo().title + "`").queue();
                }
            }
            
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (player.getPlayingTrack() == null) {
                    AudioTrack track = playlist.getTracks().get(0);
                    player.playTrack(track);
                    hook.sendMessage("🎵 Now playing: `" + track.getInfo().title + "` (from playlist)").queue();
                    
                    for (int i = 1; i < playlist.getTracks().size(); i++) {
                        musicManager.queueTrack(guild, playlist.getTracks().get(i));
                    }
                } else {
                    for (AudioTrack track : playlist.getTracks()) {
                        musicManager.queueTrack(guild, track);
                    }
                    hook.sendMessage("📋 Playlist queued: `" + playlist.getName() + "` (" + 
                            playlist.getTracks().size() + " tracks)").queue();
                }
            }
            
            @Override
            public void noMatches() {
                hook.sendMessage("❌ No matches found for: `" + query + "`").queue();
            }
            
            @Override
            public void loadFailed(FriendlyException exception) {
                hook.sendMessage("❌ Failed to load track: " + exception.getMessage()).queue();
            }
        });
    }
    
    // Methods to handle Spotify URLs
    private void handleSpotifyUrl(InteractionHook hook, Guild guild, String url, AudioPlayer player) {
        // Store the original URL so we can extract info from it later
        config.setLastSpotifyUrl(url);
        
        hook.sendMessage("🔍 Processing Spotify link...").queue(message -> {
            try {
                SpotifyUrlType urlType = spotifyManager.getSpotifyUrlType(url);
                String id = spotifyManager.extractId(url, urlType);
                
                if (id == null) {
                    hook.editOriginal("❌ Invalid Spotify URL: Could not extract Spotify ID.").queue();
                    return;
                }
                
                switch (urlType) {
                    case TRACK:
                        handleSpotifyTrack(hook, guild, id, player);
                        break;
                    case ALBUM:
                        handleSpotifyAlbum(hook, guild, id, player);
                        break;
                    case PLAYLIST:
                        handleSpotifyPlaylist(hook, guild, id, player);
                        break;
                    default:
                        hook.editOriginal("❌ Unsupported Spotify link type.").queue();
                }
            } catch (Exception e) {
                hook.editOriginal("❌ Error processing Spotify link: " + e.getMessage()).queue();
                e.printStackTrace();
            }
        });
    }

    private void handleSpotifyTrack(InteractionHook hook, Guild guild, String trackId, AudioPlayer player) {
        TrackInfo trackInfo = spotifyManager.getTrackInfo(trackId);
        
        if (trackInfo == null) {
            hook.editOriginal("❌ Track not found on Spotify.").queue();
            return;
        }
        
        // Update message
        hook.editOriginal("🔍 Found track: `" + trackInfo.toString() + "`\nSearching for playable version...").queue();
        
        // Search on YouTube
        String searchQuery = trackInfo.getSearchQuery();
        playSpotifyQuery(hook, guild, searchQuery, trackInfo, player);
    }

    private void handleSpotifyAlbum(InteractionHook hook, Guild guild, String albumId, AudioPlayer player) {
        List<TrackInfo> tracks = spotifyManager.getAlbumTracks(albumId);
        
        if (tracks.isEmpty()) {
            hook.editOriginal("❌ Album not found or contains no tracks.").queue();
            return;
        }
        
        // Update message
        hook.editOriginal("🔍 Found album with " + tracks.size() + " tracks. Adding to queue...").queue();
        
        // Queue tracks
        queueSpotifyTracks(hook, guild, tracks, player);
    }

    private void handleSpotifyPlaylist(InteractionHook hook, Guild guild, String playlistId, AudioPlayer player) {
        List<TrackInfo> tracks = spotifyManager.getPlaylistTracks(playlistId);
        
        if (tracks.isEmpty()) {
            hook.editOriginal("❌ Playlist not found or contains no tracks.").queue();
            return;
        }
        
        // Update message
        hook.editOriginal("🔍 Found playlist with " + tracks.size() + " tracks. Adding to queue...").queue();
        
        // Queue tracks
        queueSpotifyTracks(hook, guild, tracks, player);
    }

    private void playSpotifyQuery(InteractionHook hook, Guild guild, String searchQuery, TrackInfo trackInfo, AudioPlayer player) {
        // Search on YouTube
        musicManager.getPlayerManager().loadItem("ytsearch:" + searchQuery, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                handleTrackLoaded(track, player, guild, trackInfo, hook);
            }
            
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // For search results, use the first result
                if (playlist.getTracks().isEmpty()) {
                    noMatches();
                    return;
                }
                
                AudioTrack track = playlist.getTracks().get(0);
                handleTrackLoaded(track, player, guild, trackInfo, hook);
            }
            
            @Override
            public void noMatches() {
                hook.editOriginal("❌ No matches found for Spotify track: `" + trackInfo.toString() + "`").queue();
            }
            
            @Override
            public void loadFailed(FriendlyException exception) {
                hook.editOriginal("❌ Failed to load track: " + exception.getMessage()).queue();
            }
        });
    }

    private void handleTrackLoaded(AudioTrack track, AudioPlayer player, Guild guild, 
                                  TrackInfo trackInfo, InteractionHook hook) {
        EmbedBuilder resultEmbed = new EmbedBuilder();
        resultEmbed.setColor(Color.decode(config.getEmbedColor()));
        
        if (player.getPlayingTrack() != null) {
            musicManager.queueTrack(guild, track);
            resultEmbed.setTitle("🎵 Spotify Track Queued");
            resultEmbed.setDescription("`" + trackInfo.getName() + "` by `" + trackInfo.getArtists() + "`");
            resultEmbed.setFooter("From Spotify • Duration: " + formatTime(track.getDuration()));
        } else {
            player.playTrack(track);
            resultEmbed.setTitle("🎵 Now Playing Spotify Track");
            resultEmbed.setDescription("`" + trackInfo.getName() + "` by `" + trackInfo.getArtists() + "`");
            resultEmbed.setFooter("From Spotify • Duration: " + formatTime(track.getDuration()));
        }
        
        // Add album info if available
        if (trackInfo.getAlbum() != null && !trackInfo.getAlbum().isEmpty()) {
            resultEmbed.addField("Album", "`" + trackInfo.getAlbum() + "`", false);
        }
        
        hook.editOriginalEmbeds(resultEmbed.build()).queue();
    }

    private void queueSpotifyTracks(InteractionHook hook, Guild guild, List<TrackInfo> tracks, AudioPlayer player) {
        // Keep track of loaded tracks for the final message
        AtomicInteger loadedTracks = new AtomicInteger(0);
        AtomicInteger failedTracks = new AtomicInteger(0);
        
        // Queue up to 100 tracks for performance reasons
        int trackLimit = Math.min(tracks.size(), 100);
        AtomicInteger remainingTracks = new AtomicInteger(trackLimit);
        
        // Update the loading message periodically
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger progress = new AtomicInteger(0);
        
        // Schedule periodic updates
        scheduler.scheduleAtFixedRate(() -> {
            if (remainingTracks.get() <= 0) {
                scheduler.shutdown();
                return;
            }
            
            int currentProgress = (trackLimit - remainingTracks.get()) * 100 / trackLimit;
            if (currentProgress > progress.get()) {
                progress.set(currentProgress);
                
                EmbedBuilder progressEmbed = new EmbedBuilder();
                progressEmbed.setTitle("🔄 Loading Spotify Tracks");
                progressEmbed.setDescription("Progress: " + progress.get() + "% complete");
                progressEmbed.setColor(Color.decode(config.getEmbedColor()));
                progressEmbed.setFooter("Loaded " + (trackLimit - remainingTracks.get()) + "/" + trackLimit + " tracks");
                
                hook.editOriginalEmbeds(progressEmbed.build()).queue();
            }
        }, 1, 2, TimeUnit.SECONDS);
        
        // Process each track one by one
        for (int i = 0; i < trackLimit; i++) {
            final int trackIndex = i;
            TrackInfo trackInfo = tracks.get(i);
            
            musicManager.getPlayerManager().loadItem("ytsearch:" + trackInfo.getSearchQuery(), new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    processTrack(track, trackInfo);
                }
                
                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (!playlist.getTracks().isEmpty()) {
                        AudioTrack track = playlist.getTracks().get(0);
                        processTrack(track, trackInfo);
                    } else {
                        failedTracks.incrementAndGet();
                        checkComplete();
                    }
                }
                
                @Override
                public void noMatches() {
                    failedTracks.incrementAndGet();
                    checkComplete();
                }
                
                @Override
                public void loadFailed(FriendlyException exception) {
                    failedTracks.incrementAndGet();
                    checkComplete();
                }
                
                private void processTrack(AudioTrack track, TrackInfo trackInfo) {
                    if (trackIndex == 0 && player.getPlayingTrack() == null) {
                        player.playTrack(track);
                    } else {
                        musicManager.queueTrack(guild, track);
                    }
                    
                    loadedTracks.incrementAndGet();
                    checkComplete();
                }
                
                private void checkComplete() {
                    remainingTracks.decrementAndGet();
                    
                    // If all tracks have been processed
                    if (remainingTracks.get() <= 0) {
                        scheduler.shutdown();
                        
                        EmbedBuilder resultEmbed = new EmbedBuilder();
                        resultEmbed.setTitle("✅ Spotify Import Complete");
                        resultEmbed.setDescription("Successfully loaded " + loadedTracks.get() + "/" + trackLimit + " tracks");
                        
                        if (failedTracks.get() > 0) {
                            resultEmbed.addField("Failed Tracks", failedTracks.get() + " tracks could not be loaded", false);
                        }
                        
                        if (tracks.size() > trackLimit) {
                            resultEmbed.addField("Note", "Only the first " + trackLimit + " tracks were loaded due to performance reasons", false);
                        }
                        
                        resultEmbed.setColor(Color.decode(config.getEmbedColor()));
                        
                        hook.editOriginalEmbeds(resultEmbed.build()).queue();
                    }
                }
            });
        }
    }
    
    // Utility method to check if a URL is a YouTube URL
    private boolean isYouTubeUrl(String url) {
        return url.contains("youtube.com") || url.contains("youtu.be");
    }
    
    // Rest of the existing methods
    private void handleStopCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        
        if (player.getPlayingTrack() == null) {
            event.reply("❌ Nothing is playing to stop.").setEphemeral(true).queue();
            return;
        }
        
        musicManager.clearQueue(guild);
        player.stopTrack();
        guild.getAudioManager().closeAudioConnection();
        event.reply("⏹️ Playback stopped and disconnected.").queue();
    }
    
    private void handlePauseCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        
        if (player.getPlayingTrack() == null) {
            event.reply("❌ Nothing is playing to pause.").setEphemeral(true).queue();
            return;
        }
        
        if (player.isPaused()) {
            event.reply("⚠️ Player is already paused.").setEphemeral(true).queue();
        } else {
            player.setPaused(true);
            event.reply("⏸️ Player paused.").queue();
        }
    }
    
    private void handleResumeCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        
        if (player.getPlayingTrack() == null) {
            event.reply("❌ Nothing is loaded to resume.").setEphemeral(true).queue();
            return;
        }
        
        if (!player.isPaused()) {
            event.reply("⚠️ Player is not paused.").setEphemeral(true).queue();
        } else {
            player.setPaused(false);
            event.reply("▶️ Player resumed.").queue();
        }
    }
    
    private void handleSkipCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        
        if (player.getPlayingTrack() == null) {
            event.reply("❌ Nothing is playing to skip.").setEphemeral(true).queue();
            return;
        }
        
        boolean hasQueue = !musicManager.getQueue(guild).isEmpty();
        String currentTrackTitle = player.getPlayingTrack().getInfo().title;
        
        player.stopTrack();
        musicManager.playNext(guild);
        
        if (hasQueue && player.getPlayingTrack() != null) {
            event.reply("⏭️ Skipped: `" + currentTrackTitle + "`. Now playing: `" + 
                    player.getPlayingTrack().getInfo().title + "`").queue();
        } else {
            event.reply("⏭️ Skipped: `" + currentTrackTitle + "`. Queue is now empty.").queue();
        }
    }
    
    private void handleQueueCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        AudioTrack currentTrack = musicManager.getPlayer(guild).getPlayingTrack();
        Queue<AudioTrack> queue = musicManager.getQueue(guild);
        
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎵 Music Queue");
        embed.setColor(new Color(29, 185, 84)); // Spotify green
        
        if (currentTrack == null) {
            embed.setDescription("No track is currently playing.");
        } else {
            long position = currentTrack.getPosition();
            long duration = currentTrack.getDuration();
            
            // Create a progress bar
            StringBuilder progressBar = new StringBuilder();
            int progressBarLength = 20;
            int progressPosition = (int) ((float) position / duration * progressBarLength);
            
            for (int i = 0; i < progressBarLength; i++) {
                if (i == progressPosition) {
                    progressBar.append("🔘");
                } else {
                    progressBar.append("▬");
                }
            }
            
            embed.addField("Now Playing", 
                     "`" + currentTrack.getInfo().title + "`", 
                     false);
            
            embed.addField("Progress", 
                     progressBar.toString() + "\n" +
                     "`" + formatTime(position) + " / " + formatTime(duration) + "`", 
                     false);
        }
        
        if (queue == null || queue.isEmpty()) {
            embed.addField("Queue", "Queue is empty.", false);
        } else {
            List<AudioTrack> trackList = new ArrayList<>(queue);
            StringBuilder sb = new StringBuilder();
            
            // Calculate total duration
            long totalDuration = 0;
            for (AudioTrack track : trackList) {
                totalDuration += track.getDuration();
            }
            
            // Show first 10 tracks
            int tracksToShow = Math.min(10, trackList.size());
            for (int i = 0; i < tracksToShow; i++) {
                AudioTrack track = trackList.get(i);
                sb.append("`").append(i + 1).append(".` ");
                sb.append("`").append(track.getInfo().title).append("`");
                sb.append(" [`").append(formatTime(track.getDuration())).append("`]\n");
            }
            
            // If there are more tracks
            if (trackList.size() > 10) {
                sb.append("\n... and ").append(trackList.size() - 10).append(" more tracks.");
            }
            
            embed.setFooter(trackList.size() + " songs in queue • Total duration: " + formatTime(totalDuration));
            embed.addField("Queue", sb.toString(), false);
        }
        
        event.replyEmbeds(embed.build()).queue();
    }
    
    private void handleNowPlayingCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        AudioTrack track = musicManager.getPlayer(guild).getPlayingTrack();
        
        if (track == null) {
            event.reply("Nothing is playing right now.").setEphemeral(true).queue();
            return;
        }
        
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎵 Now Playing");
        embed.setColor(new Color(29, 185, 84)); // Spotify green
        
        long position = track.getPosition();
        long duration = track.getDuration();
        
        // Create a progress bar
        StringBuilder progressBar = new StringBuilder();
        int progressBarLength = 20;
        int progressPosition = (int) ((float) position / duration * progressBarLength);
        
        for (int i = 0; i < progressBarLength; i++) {
            if (i == progressPosition) {
                progressBar.append("🔘");
            } else {
                progressBar.append("▬");
            }
        }
        
        embed.addField("Track", "`" + track.getInfo().title + "`", false);
        embed.addField("Progress", 
                progressBar.toString() + "\n" +
                "`" + formatTime(position) + " / " + formatTime(duration) + "`", 
                false);
        
        event.replyEmbeds(embed.build()).queue();
    }
    
    private void handleVolumeCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        int volume = event.getOption("level").getAsInt();
        
        if (volume < 0 || volume > 150) {
            event.reply("❌ Volume must be between 0 and 150.").setEphemeral(true).queue();
            return;
        }
        
        var player = musicManager.getPlayer(guild);
        player.setVolume(volume);
        event.reply("🔊 Volume set to " + volume).queue();
    }
    
    private void handleShuffleCommand(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        Queue<AudioTrack> queue = musicManager.getQueue(guild);
        
        if (queue == null || queue.isEmpty()) {
            event.reply("❌ Queue is empty, nothing to shuffle.").setEphemeral(true).queue();
            return;
        }
        
        musicManager.shuffle(guild);
        event.reply("🔀 Queue has been shuffled!").queue();
    }
    
    private void handleControlsCommand(SlashCommandInteractionEvent event) {
        // Create buttons
        Button playPauseButton = Button.primary("play_pause", "⏯️ Play/Pause");
        Button skipButton = Button.secondary("skip", "⏭️ Skip");
        Button stopButton = Button.danger("stop", "⏹️ Stop");
        Button queueButton = Button.secondary("queue", "📋 Queue");
        Button shuffleButton = Button.secondary("shuffle", "🔀 Shuffle");
    
        // Create embed
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎵 Music Controls");
        embed.setDescription("Use the buttons below to control the music player.");
        embed.setColor(new Color(114, 137, 218)); // Discord blue
        embed.setFooter("Music Bot", null);
    
        // Send message with buttons
        event.replyEmbeds(embed.build())
            .addActionRow(playPauseButton, skipButton, stopButton, queueButton, shuffleButton)
            .queue(message -> {
                // Delete control panel after 5 minutes
                message.deleteOriginal().queueAfter(5, TimeUnit.MINUTES, 
                    null, 
                    error -> {} // Ignore errors if already deleted
                );
            });
    }
    
    private void handleHelpCommand(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📋 Music Bot Commands");
        embed.setDescription("Here are all available commands:");
        embed.setColor(new Color(29, 185, 84)); // Spotify green
        
        // Music commands
        embed.addField("🎵 Music Commands", 
            "`/play <url/search>` - Play a song or add to queue\n" +
            "`/pause` - Pause the current playback\n" +
            "`/resume` - Resume playback\n" +
            "`/skip` - Skip the current song\n" +
            "`/stop` - Stop playback and clear queue\n" +
            "`/nowplaying` - Show current song\n" +
            "`/volume <0-150>` - Set the volume", 
            false);
        
        // Queue management
        embed.addField("📋 Queue Management", 
            "`/queue` - Show the current queue\n" +
            "`/shuffle` - Shuffle the queue", 
            false);
        
        // Utility commands
        embed.addField("🔧 Utility Commands", 
            "`/controls` - Show music control panel\n" +
            "`/help` - Show this help message", 
            false);
        
        event.replyEmbeds(embed.build()).queue();
    }
    
    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        return (hours > 0 ? hours + ":" : "") + 
               (minutes < 10 ? "0" + minutes : minutes) + ":" + 
               (seconds < 10 ? "0" + seconds : seconds);
    }
}