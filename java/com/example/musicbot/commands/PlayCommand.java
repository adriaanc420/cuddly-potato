package com.example.musicbot.commands;

import com.example.musicbot.BotConfig;
import com.example.musicbot.Command;
import com.example.musicbot.MusicManager;
import com.example.musicbot.AudioPlayerSendHandler;
import com.example.musicbot.ReEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.RestAction;
import com.example.musicbot.SpotifyManager;
import com.example.musicbot.SpotifyManager.SpotifyUrlType;
import com.example.musicbot.SpotifyManager.TrackInfo;
import com.example.musicbot.BotLogger;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

public class PlayCommand implements Command {
    private final MusicManager musicManager;
    private final BotConfig config;
    
    // Thread pool for downloads instead of creating new threads each time
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
    
    // Map to track audio track to file path for cleanup
    private final ConcurrentHashMap<String, String> tempFilePaths = new ConcurrentHashMap<>();

    public PlayCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
        this.config = new BotConfig();
        
        // Create temp directory if it doesn't exist
        File tempDir = new File("temp");
        if (!tempDir.exists()) tempDir.mkdirs();
    }

    @Override
    public void execute(MessageReceivedEvent event, String query) {
        Guild guild = event.getGuild();
        if (event.getMember().getVoiceState().getChannel() == null) {
            event.getChannel().sendMessage("❌ You must be in a voice channel to play music!").queue();
            return;
        }
        
        try {
            // Store the original user query for better search results
            config.setLastUserQuery(event.getMessage().getContentRaw());
        if (query == null || query.trim().isEmpty()) {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("🎵 Music Playback");
            embed.setDescription("Please provide a search term or URL to play music.");
            embed.setColor(Color.decode(config.getEmbedColor()));
            embed.addField("Examples", 
                "• `" + config.getPrefix() + "play lofi hip hop`\n" +
                "• `" + config.getPrefix() + "play https://www.youtube.com/watch?v=dQw4w9WgXcQ`\n" +
                "• `" + config.getPrefix() + "play https://open.spotify.com/track/123456789`", false);
            embed.setFooter("You can search for YouTube videos, SoundCloud tracks, or use direct links", null);
            
            event.getChannel().sendMessageEmbeds(embed.build()).queue();
            return;
        }

        // Check for Spotify URLs
        if (query.contains("spotify.com")) {
            if (config.isSpotifyEnabled() && 
                !config.getSpotifyClientId().isEmpty() && 
                !config.getSpotifyClientSecret().isEmpty()) {
                // Store the URL for reference
                config.setLastSpotifyUrl(query);
                // Handle the Spotify URL with the existing code
                handleSpotifyUrl(event, query);
            } else {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("⚠️ Spotify URL Detected");
                embed.setDescription("Spotify URL support is not enabled in your configuration.");
                embed.setColor(Color.ORANGE);
                embed.addField("Solution", "Use the command `" + config.getPrefix() + "spotify setup` to configure Spotify integration.", false);
                embed.addField("Alternative", "You can manually search for this song using:\n`" + config.getPrefix() + "play song name artist name`", false);
                
                // Store the URL for debugging
                config.setLastSpotifyUrl(query);
                
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            }
            return;
        }

        // Check if it's a YouTube URL
        if (isYouTubeUrl(query)) {
            final String videoId = extractVideoId(query);
            if (videoId == null) {
                event.getChannel().sendMessage("❌ Could not extract video ID from URL.").queue();
                return;
            }
            
            String cacheDirPath = config.getCacheDir();
            File cacheDir = new File(cacheDirPath);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            
            // First, check if we have this video ID in our cache (any file that contains the ID)
            File[] cachedFiles = cacheDir.listFiles((dir, name) -> name.contains(videoId) && name.endsWith(".mp3"));
            
            if (cachedFiles != null && cachedFiles.length > 0) {
                File cachedFile = cachedFiles[0];
                event.getChannel().sendMessage("🎵 Playing cached file: `" + cachedFile.getName().replace(".mp3", "").replace(videoId + "_", "") + "`").queue();
                playLocalFile(event, cachedFile.getAbsolutePath(), false);
                return;
            }
            
            // Create buttons instead of reactions for faster response
            Button cacheButton = Button.success("cache_yes", "💾 Save for later");
            Button noCacheButton = Button.secondary("cache_no", "⏳ Just this time");
            
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("YouTube Download Options");
            embed.setDescription("Do you want to save this song for future use?");
            embed.setColor(Color.decode(config.getEmbedColor()));
            embed.setFooter("Song will download after selection");
            
            event.getChannel().sendMessageEmbeds(embed.build())
                .setActionRow(cacheButton, noCacheButton)
                .queue(message -> {
                    // Add an event waiter for the button click
                    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                    int timeoutSeconds = config.getReactionTimeout();
                    
                    // Set a timeout to delete the message if no one clicks
                    scheduler.schedule(() -> {
                        // Safe delete with error handling
                        message.delete().queue(
                            null, 
                            error -> System.out.println("Could not delete message: " + error.getMessage())
                        );
                        
                        // Send followup and download without caching
                        event.getChannel().sendMessage("No response received. Downloading without caching.").queue(
                            responseMsg -> {
                                downloadAndPlay(query, videoId, event, false);
                            },
                            error -> System.out.println("Could not send message: " + error.getMessage())
                        );
                    }, timeoutSeconds, TimeUnit.SECONDS);
                    
                    // Store message info for button handling
                    ButtonResponseRegistry.registerMessage(
                        message.getIdLong(), 
                        event.getAuthor().getIdLong(),
                        scheduler,
                        response -> {
                            boolean cache = response.equals("cache_yes");
                            String reply = cache ? "Caching track for future use." : "Not caching track; downloading temporarily.";
                            
                            // Safe delete with error handling
                            message.delete().queue(
                                null, 
                                error -> System.out.println("Could not delete message: " + error.getMessage())
                            );
                            
                            // Send followup and download
                            event.getChannel().sendMessage("📥 " + reply + " Downloading from YouTube, please wait...").queue(
                                responseMsg -> {
                                    downloadAndPlay(query, videoId, event, cache);
                                },
                                error -> System.out.println("Could not send message: " + error.getMessage())
                            );
                        }
                    );
                });
        } else if (query.startsWith("http://") || query.startsWith("https://")) {
            // For other URLs, use the existing playQuery method
            playQuery(event, query);
        } else {
            // For search terms, offer a choice of platforms
            Button youtubeButton = Button.primary("search_youtube", "🔍 Search YouTube");
            Button soundcloudButton = Button.secondary("search_soundcloud", "🔍 Search SoundCloud");
            
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("Search Options");
            embed.setDescription("Where would you like to search for: `" + query + "`?");
            embed.setColor(Color.decode(config.getEmbedColor()));
            embed.setFooter("Choose a platform to search");
            
            event.getChannel().sendMessageEmbeds(embed.build())
                .setActionRow(youtubeButton, soundcloudButton)
                .queue(message -> {
                    // Set up a timeout for the search choice
                    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                    int timeoutSeconds = config.getReactionTimeout();
                    
                    // Set a timeout to delete the message if no one clicks
                    scheduler.schedule(() -> {
                        // Safe delete with error handling
                        message.delete().queue(
                            null, 
                            error -> System.out.println("Could not delete message: " + error.getMessage())
                        );
                        
                        // Default to YouTube search after timeout
                        event.getChannel().sendMessage("No response received. Searching on YouTube...").queue(
                            responseMsg -> {
                                playQuery(event, "ytsearch:" + query);
                            },
                            error -> System.out.println("Could not send message: " + error.getMessage())
                        );
                    }, timeoutSeconds, TimeUnit.SECONDS);
                    
                    // Register message for button handling
                    ButtonResponseRegistry.registerMessage(
                        message.getIdLong(), 
                        event.getAuthor().getIdLong(),
                        scheduler,
                        response -> {
                            String platform = response.equals("search_youtube") ? "YouTube" : "SoundCloud";
                            String searchPrefix = response.equals("search_youtube") ? "ytsearch:" : "scsearch:";
                            
                            // Safe delete with error handling
                            message.delete().queue(
                                null, 
                                error -> System.out.println("Could not delete message: " + error.getMessage())
                            );
                            
                            // Send feedback and perform search
                            event.getChannel().sendMessage("🔍 Searching on " + platform + " for: `" + query + "`").queue(
                                responseMsg -> {
                                    playQuery(event, searchPrefix + query);
                                },
                                error -> System.out.println("Could not send message: " + error.getMessage())
                            );
                        }
                    );
                });
        }
    } catch (Exception e) {
        handleError(event, "processing play command", e);
    }
  }
  private void handleSpotifyUrl(MessageReceivedEvent event, String url) {
    Guild guild = event.getGuild();
    
    // Connect to voice channel
    guild.getAudioManager().openAudioConnection(event.getMember().getVoiceState().getChannel());
    var player = musicManager.getPlayer(guild);
    
    if (guild.getAudioManager().getSendingHandler() == null) {
        guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
    }

    // Create a SpotifyManager instance if needed
    SpotifyManager spotifyManager = new SpotifyManager(config);
    
    event.getChannel().sendMessage("🔍 Processing Spotify link...").queue(message -> {
        try {
            SpotifyUrlType urlType = spotifyManager.getSpotifyUrlType(url);
            String id = spotifyManager.extractId(url, urlType);

            if (config.isDebugLogging()) {
                // Log debug info about what we extracted
                String debugInfo = spotifyManager.getDebugInfo(id);
                System.out.println("Spotify URL Debug Info:\n" + debugInfo);
            }
            
            if (id == null) {
                message.editMessage("❌ Invalid Spotify URL: Could not extract Spotify ID.").queue();
                return;
            }
            
            // Process based on URL type (track, album, playlist)
            switch (urlType) {
                case TRACK:
                    TrackInfo trackInfo = spotifyManager.getTrackInfo(id);
                    String searchQuery = trackInfo.getSearchQuery();
                    
                    // Search on YouTube
                    message.editMessage("🔍 Found track: `" + trackInfo.toString() + "`\nSearching for playable version...").queue();
                    playQuery(event, "ytsearch:" + searchQuery);
                    break;
                    
                case ALBUM:
                case PLAYLIST:
                    List<TrackInfo> tracks = urlType == SpotifyUrlType.ALBUM ? 
                        spotifyManager.getAlbumTracks(id) : spotifyManager.getPlaylistTracks(id);
                    
                    int trackCount = tracks.size();
                    message.editMessage("🔍 Found " + (urlType == SpotifyUrlType.ALBUM ? "album" : "playlist") + 
                                     " with " + trackCount + " tracks. Adding to queue...").queue();
                    
                    // Queue the first 10 tracks to avoid overwhelming the system
                    int tracksToProcess = Math.min(10, trackCount);
                    for (int i = 0; i < tracksToProcess; i++) {
                        final int index = i;
                        final TrackInfo track = tracks.get(i);
                        
                        // Add small delay between requests to avoid rate limiting
                        if (i > 0) {
                            try {
                                Thread.sleep(500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        
                        // Search and queue each track
                        playQuery(event, "ytsearch:" + track.getSearchQuery());
                    }
                    
                    if (trackCount > 10) {
                        event.getChannel().sendMessage("⚠️ Only the first 10 tracks were queued to avoid performance issues.").queue();
                    }
                    break;
                    
                default:
                    message.editMessage("❌ Unsupported Spotify link type.").queue();
            }
            
        } catch (Exception e) {
            message.editMessage("❌ Error processing Spotify link: " + e.getMessage()).queue();
            e.printStackTrace();
        }
    });
   } 
    private void playQuery(MessageReceivedEvent event, String query) {
        Guild guild = event.getGuild();
        guild.getAudioManager().openAudioConnection(event.getMember().getVoiceState().getChannel());
        var player = musicManager.getPlayer(guild);
        
        // Set default volume if it's a new player
        player.setVolume(config.getDefaultVolume());
        
        if (guild.getAudioManager().getSendingHandler() == null)
            guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
        
        // Send loading message
        EmbedBuilder loadingEmbed = new EmbedBuilder();
        loadingEmbed.setTitle("🔍 Searching");
        loadingEmbed.setDescription("Looking for: `" + query + "`");
        loadingEmbed.setColor(Color.decode(config.getEmbedColor()));
        
        event.getChannel().sendMessageEmbeds(loadingEmbed.build()).queue(loadingMsg -> {
            musicManager.getPlayerManager().loadItem(query, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    EmbedBuilder resultEmbed = new EmbedBuilder();
                    resultEmbed.setColor(Color.decode(config.getEmbedColor()));
                    
                    if (player.getPlayingTrack() != null) {
                        musicManager.queueTrack(guild, track);
                        resultEmbed.setTitle("🎵 Track Queued");
                        resultEmbed.setDescription("`" + track.getInfo().title + "`");
                        resultEmbed.setFooter("Duration: " + formatTime(track.getDuration()));
                    } else {
                        player.playTrack(track);
                        resultEmbed.setTitle("🎵 Now Playing");
                        resultEmbed.setDescription("`" + track.getInfo().title + "`");
                        resultEmbed.setFooter("Duration: " + formatTime(track.getDuration()));
                    }
                    
                    // Safe edit with error handling
                    loadingMsg.editMessageEmbeds(resultEmbed.build()).queue(
                        null,
                        error -> System.out.println("Could not edit message: " + error.getMessage())
                    );
                }
                
                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    EmbedBuilder resultEmbed = new EmbedBuilder();
                    resultEmbed.setColor(Color.decode(config.getEmbedColor()));
                    
                    // Check if this is a search result or an actual playlist
                    if (playlist.isSearchResult()) {
                        AudioTrack track = playlist.getTracks().get(0);
                        if (player.getPlayingTrack() != null) {
                            musicManager.queueTrack(guild, track);
                            resultEmbed.setTitle("🎵 Track Queued");
                            resultEmbed.setDescription("`" + track.getInfo().title + "`");
                            resultEmbed.setFooter("Duration: " + formatTime(track.getDuration()));
                        } else {
                            player.playTrack(track);
                            resultEmbed.setTitle("🎵 Now Playing");
                            resultEmbed.setDescription("`" + track.getInfo().title + "`");
                            resultEmbed.setFooter("Duration: " + formatTime(track.getDuration()));
                        }
                    } else {
                        // It's an actual playlist
                        if (player.getPlayingTrack() == null && !playlist.getTracks().isEmpty()) {
                            AudioTrack track = playlist.getTracks().get(0);
                            player.playTrack(track);
                            
                            for (int i = 1; i < playlist.getTracks().size(); i++) {
                                musicManager.queueTrack(guild, playlist.getTracks().get(i));
                            }
                            
                            resultEmbed.setTitle("🎵 Playlist Started");
                            resultEmbed.setDescription("Now playing: `" + track.getInfo().title + "`");
                            resultEmbed.addField("Playlist", "`" + playlist.getName() + "`", false);
                            resultEmbed.addField("Tracks", (playlist.getTracks().size() - 1) + " more tracks queued", false);
                        } else {
                            for (AudioTrack track : playlist.getTracks()) {
                                musicManager.queueTrack(guild, track);
                            }
                            
                            resultEmbed.setTitle("📋 Playlist Queued");
                            resultEmbed.setDescription("`" + playlist.getName() + "`");
                            resultEmbed.addField("Tracks", playlist.getTracks().size() + " tracks added to queue", false);
                        }
                    }
                    
                    // Safe edit with error handling
                    loadingMsg.editMessageEmbeds(resultEmbed.build()).queue(
                        null,
                        error -> System.out.println("Could not edit message: " + error.getMessage())
                    );
                }
                
                @Override
                public void noMatches() {
                    EmbedBuilder resultEmbed = new EmbedBuilder();
                    resultEmbed.setTitle("❌ No Matches");
                    resultEmbed.setDescription("No matches found for: `" + query + "`");
                    resultEmbed.setColor(Color.RED);
                    
                    // Safe edit with error handling
                    loadingMsg.editMessageEmbeds(resultEmbed.build()).queue(
                        null,
                        error -> System.out.println("Could not edit message: " + error.getMessage())
                    );
                }
                
                @Override
                public void loadFailed(FriendlyException exception) {
                    EmbedBuilder resultEmbed = new EmbedBuilder();
                    resultEmbed.setTitle("❌ Load Failed");
                    resultEmbed.setDescription("Error: " + exception.getMessage());
                    resultEmbed.setColor(Color.RED);
                    
                    // Safe edit with error handling
                    loadingMsg.editMessageEmbeds(resultEmbed.build()).queue(
                        null,
                        error -> System.out.println("Could not edit message: " + error.getMessage())
                    );
                }
            });
        });
    }

    private void downloadAndPlay(String query, String videoId, MessageReceivedEvent event, boolean shouldCache) {
        // Use the thread pool instead of creating a new thread
        downloadExecutor.submit(() -> {
            try {
                // Create a progress message with embed
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("⏳ Download Started");
                embed.setDescription("Starting download...");
                embed.setColor(Color.decode(config.getEmbedColor()));
                
                // Create atomic variables to store info across callbacks
                AtomicReference<Message> progressMessageRef = new AtomicReference<>();
                AtomicReference<String> videoTitleRef = new AtomicReference<>("");
                
                // First, get the title of the video for better file naming
                ProcessBuilder titlePb = new ProcessBuilder(
                    "yt-dlp", "--get-title", "--no-playlist", query
                );
                titlePb.redirectErrorStream(true);
                Process titleProcess = titlePb.start();
                
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(titleProcess.getInputStream()))) {
                    String title = reader.readLine();
                    if (title != null) {
                        // Clean the title to make it a valid filename
                        title = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                        videoTitleRef.set(title);
                    }
                }
                
                titleProcess.waitFor();
                
                // If we couldn't get the title, use the video ID
                if (videoTitleRef.get().isEmpty()) {
                    videoTitleRef.set("video_" + videoId);
                }
                
                // Determine file path based on cache preference
                final String fileName;
                final File outputFile;
                
                if (shouldCache) {
                    fileName = videoId + "_" + videoTitleRef.get() + ".mp3";
                    outputFile = new File(config.getCacheDir(), fileName);
                } else {
                    fileName = videoId + "_" + videoTitleRef.get() + "_temp.mp3";
                    outputFile = new File("temp", fileName);
                }
                
                // Send progress message
                event.getChannel().sendMessageEmbeds(embed.build()).queue(progressMessage -> {
                    progressMessageRef.set(progressMessage);
                    
                    try {
                        // Download best audio in original format
                        ProcessBuilder pb = new ProcessBuilder(
                            "yt-dlp", "-f", "bestaudio", "--restrict-filenames", "-o", outputFile.getAbsolutePath(), query
                        );
                        pb.redirectErrorStream(true);
                        Process process = pb.start();
                        
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                // Process download progress info
                                Pattern progressPattern = Pattern.compile("\\[download\\]\\s+(\\d+\\.\\d+)%\\s+of\\s+(\\S+)");
                                Matcher matcher = progressPattern.matcher(line);
                                if (matcher.find()) {
                                    String percentStr = matcher.group(1);
                                    String totalSize = matcher.group(2);
                                    double percent = Double.parseDouble(percentStr);
                                    int segments = (int) (percent / 5); // 20 segments for bar
                                    
                                    // Progress bar
                                    StringBuilder bar = new StringBuilder();
                                    for (int i = 0; i < 20; i++) {
                                        bar.append(i < segments ? "█" : "░");
                                    }
                                    
                                    // Update progress message
                                    EmbedBuilder progressEmbed = new EmbedBuilder();
                                    progressEmbed.setTitle("📥 Downloading");
                                    progressEmbed.setDescription(bar.toString());
                                    progressEmbed.addField("Title", videoTitleRef.get(), false);
                                    progressEmbed.addField("Progress", percentStr + "%", true);
                                    progressEmbed.addField("Size", totalSize, true);
                                    progressEmbed.setColor(Color.decode(config.getEmbedColor()));
                                    
                                    // Safe edit with error handling
                                    progressMessage.editMessageEmbeds(progressEmbed.build()).queue(
                                        null,
                                        error -> System.out.println("Could not update progress: " + error.getMessage())
                                    );
                                }
                            }
                            
                            // Wait for download to complete
                            process.waitFor();
                            
                            // Show encoding message if file exists
                            if (outputFile.exists()) {
                                EmbedBuilder encodingEmbed = new EmbedBuilder();
                                encodingEmbed.setTitle("⚙️ Processing");
                                encodingEmbed.setDescription("Download complete. Now encoding...");
                                encodingEmbed.setColor(Color.decode(config.getEmbedColor()));
                                
                                // Safe edit with error handling
                                progressMessage.editMessageEmbeds(encodingEmbed.build()).queue(
                                    null,
                                    error -> System.out.println("Could not update encoding status: " + error.getMessage())
                                );
                                
                                // Play the file
                                playLocalFile(event, outputFile.getAbsolutePath(), !shouldCache);
                                
                                // Remove progress message
                                progressMessage.delete().queue(
                                    null,
                                    error -> System.out.println("Could not delete progress message: " + error.getMessage())
                                );
                                
                                // Success message
                                EmbedBuilder completeEmbed = new EmbedBuilder();
                                completeEmbed.setTitle("✅ Download Complete");
                                completeEmbed.setDescription("Now playing: `" + videoTitleRef.get() + "`");
                                completeEmbed.setColor(Color.decode(config.getEmbedColor()));
                                
                                // Send success message and delete after a few seconds
                                event.getChannel().sendMessageEmbeds(completeEmbed.build()).queue(
                                    msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS, 
                                        null, 
                                        error -> {}  // Ignore delete errors
                                    ),
                                    error -> {}  // Ignore send errors
                                );
                            } else {
                                // Handle download failure
                                EmbedBuilder errorEmbed = new EmbedBuilder();
                                errorEmbed.setTitle("❌ Download Failed");
                                errorEmbed.setDescription("Failed to download the file.");
                                errorEmbed.setColor(Color.RED);
                                
                                // Try to update progress message first
                                progressMessage.editMessageEmbeds(errorEmbed.build()).queue(
                                    null,
                                    error -> {
                                        // If that fails, send a new message
                                        event.getChannel().sendMessageEmbeds(errorEmbed.build()).queue();
                                    }
                                );
                            }
                        }
                    } catch (Exception e) {
                        // Handle exception
                        EmbedBuilder errorEmbed = new EmbedBuilder();
                        errorEmbed.setTitle("❌ Download Failed");
                        errorEmbed.setDescription("Error: " + e.getMessage());
                        errorEmbed.setColor(Color.RED);
                        
                        // Try to update progress message or send new one
                        progressMessage.editMessageEmbeds(errorEmbed.build()).queue(
                            null,
                            error -> {
                                event.getChannel().sendMessageEmbeds(errorEmbed.build()).queue();
                            }
                        );
                        
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                // Handle any exceptions in the initial part
                EmbedBuilder errorEmbed = new EmbedBuilder();
                errorEmbed.setTitle("❌ Download Failed");
                errorEmbed.setDescription("Error: " + e.getMessage());
                errorEmbed.setColor(Color.RED);
                
                event.getChannel().sendMessageEmbeds(errorEmbed.build()).queue();
                e.printStackTrace();
            }
        });
    }

    private void playLocalFile(MessageReceivedEvent event, String filePath, boolean isTemp) {
        Guild guild = event.getGuild();
        guild.getAudioManager().openAudioConnection(event.getMember().getVoiceState().getChannel());
        var player = musicManager.getPlayer(guild);
        if (guild.getAudioManager().getSendingHandler() == null)
            guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));

        // Extract title from filename for cached files
        final String displayTitle = extractTitleFromFilePath(filePath);

        musicManager.getPlayerManager().loadItem(filePath, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                // If we have a display title from the filename, set it on the track info
                if (displayTitle != null && !displayTitle.isEmpty()) {
                    try {
                        // Try to set the title using reflection since AudioTrackInfo is immutable
                        java.lang.reflect.Field titleField = track.getInfo().getClass().getDeclaredField("title");
                        titleField.setAccessible(true);
                        titleField.set(track.getInfo(), displayTitle);
                    } catch (Exception e) {
                        // If reflection fails, just log it - we'll display the title separately
                        System.err.println("Could not set track title: " + e.getMessage());
                    }
                }
                
                // Store file path if this is a temp file
                if (isTemp) {
                    // Store the track ID and file path for cleanup
                    String trackId = track.getIdentifier();
                    tempFilePaths.put(trackId, filePath);
                    
                    // Add an end of track listener to clean up
                    player.addListener(new AudioEventAdapter() {
                        @Override
                        public void onTrackEnd(AudioPlayer player, AudioTrack endedTrack, AudioTrackEndReason endReason) {
                            if (track.equals(endedTrack)) {
                                // Clean up the temp file
                                cleanupTempFile(trackId);
                                
                                // Remove this listener 
                                player.removeListener(this);
                            }
                        }
                    });
                }
                
                EmbedBuilder embed = new EmbedBuilder();
                embed.setColor(Color.decode(config.getEmbedColor()));
                
                // Use our extracted title if the track title is empty or "Unknown title"
                String title = track.getInfo().title;
                if (title == null || title.isEmpty() || title.equals("Unknown title")) {
                    title = displayTitle != null ? displayTitle : "(No title)";
                }
                
                if (player.getPlayingTrack() != null) {
                    musicManager.queueTrack(guild, track);
                    embed.setTitle("🎵 Track Queued");
                    embed.setDescription("`" + title + "`");
                    embed.setFooter("Duration: " + formatTime(track.getDuration()));
                } else {
                    player.playTrack(track);
                    embed.setTitle("🎵 Now Playing");
                    embed.setDescription("`" + title + "`");
                    embed.setFooter("Duration: " + formatTime(track.getDuration()));
                }
                
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            }
            
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack track = playlist.getTracks().get(0);
                
                // If we have a display title from the filename, set it on the track info
                if (displayTitle != null && !displayTitle.isEmpty()) {
                    try {
                        // Try to set the title using reflection since AudioTrackInfo is immutable
                        java.lang.reflect.Field titleField = track.getInfo().getClass().getDeclaredField("title");
                        titleField.setAccessible(true);
                        titleField.set(track.getInfo(), displayTitle);
                    } catch (Exception e) {
                        System.err.println("Could not set track title: " + e.getMessage());
                    }
                }
                
                // Store file path if this is a temp file
                if (isTemp) {
                    String trackId = track.getIdentifier();
                    tempFilePaths.put(trackId, filePath);
                    
                    // Add cleanup listener
                    player.addListener(new AudioEventAdapter() {
                        @Override
                        public void onTrackEnd(AudioPlayer player, AudioTrack endedTrack, AudioTrackEndReason endReason) {
                            if (track.equals(endedTrack)) {
                                cleanupTempFile(trackId);
                                player.removeListener(this);
                            }
                        }
                    });
                }
                
                player.playTrack(track);
                
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("🎵 Now Playing");
                
                // Use our extracted title if the track title is empty or "Unknown title"
                String title = track.getInfo().title;
                if (title == null || title.isEmpty() || title.equals("Unknown title")) {
                    title = displayTitle != null ? displayTitle : "(No title)";
                }
                
                embed.setDescription("`" + title + "`");
                embed.setFooter("Duration: " + formatTime(track.getDuration()));
                embed.setColor(Color.decode(config.getEmbedColor()));
                
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            }
            
            @Override
            public void noMatches() {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("❌ No Matches");
                embed.setDescription("No playable media found at: `" + filePath + "`");
                embed.setColor(Color.RED);
                
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            }
            
            @Override
            public void loadFailed(FriendlyException exception) {
                EmbedBuilder embed = new EmbedBuilder();
                embed.setTitle("❌ Load Failed");
                embed.setDescription("Error: " + exception.getMessage());
                embed.setColor(Color.RED);
                
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            }
        });
    }

    /**
     * Extracts the title from a file path.
     * For YouTube cached files, the format is: videoId_Title.mp3
     */
    private String extractTitleFromFilePath(String filePath) {
        try {
            File file = new File(filePath);
            String fileName = file.getName();
            
            // For YouTube cache files (videoId_Title.mp3)
            if (fileName.length() > 14 && fileName.contains("_")) {
                // Extract everything after the first underscore and before .mp3
                int underscoreIndex = fileName.indexOf('_');
                int extensionIndex = fileName.lastIndexOf('.');
                
                if (underscoreIndex != -1 && extensionIndex != -1 && underscoreIndex < extensionIndex) {
                    return fileName.substring(underscoreIndex + 1, extensionIndex)
                            .replace("_", " ")  // Replace underscores with spaces
                            .replace(".mp3", "") // Remove extension if it's still there
                            .replace("_temp", ""); // Remove _temp suffix for temp files
                }
            }
            
            // If not in expected format, just return the filename without extension
            int extensionIndex = fileName.lastIndexOf('.');
            if (extensionIndex != -1) {
                return fileName.substring(0, extensionIndex);
            }
            
            return fileName;
        } catch (Exception e) {
            System.err.println("Error extracting title from path: " + e.getMessage());
            return null;
        }
    }

    // Clean up temp file with better error handling
    private void cleanupTempFile(String trackId) {
        String filePath = tempFilePaths.remove(trackId);
        if (filePath != null) {
            try {
                File tempFile = new File(filePath);
                if (tempFile.exists()) {
                    Files.deleteIfExists(tempFile.toPath());
                    System.out.println("Deleted temp file: " + filePath);
                }
            } catch (Exception e) {
                System.err.println("Failed to delete temp file: " + e.getMessage());
            }
        }
    }
    
    // Method to call from SkipCommand and StopCommand
    public void cleanupTempFileForTrack(AudioTrack track) {
        if (track != null) {
            cleanupTempFile(track.getIdentifier());
        }
    }

    private boolean isYouTubeUrl(String url) {
        return url.contains("youtube.com") || url.contains("youtu.be");
    }

    private String extractVideoId(String url) {
        Pattern pattern = Pattern.compile("(?:v=|youtu\\.be/)([a-zA-Z0-9_-]{11})");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
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
    
    private void handleError(MessageReceivedEvent event, String operation, Exception e) {
        String errorMessage;
        
        if (e instanceof FriendlyException) {
            // LavaPlayer exception with a sensible message
            errorMessage = "❌ Error " + operation + ": " + e.getMessage();
        } else if (e instanceof IOException) {
            errorMessage = "❌ Network error while " + operation + ". Please try again.";
        } else if (e instanceof InterruptedException) {
            errorMessage = "❌ Operation was interrupted. Please try again.";
        } else {
            // Generic error, log the full stack trace
            e.printStackTrace();
            errorMessage = "❌ An unexpected error occurred while " + operation + ". Please try again later.";
        }
        
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("❌ Error");
        embed.setDescription(errorMessage);
        embed.setColor(Color.RED);
        
        // Send error message
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
        
        // Log the error
        System.err.println("Error during " + operation + ": " + e.getMessage());
    }
    
    // This will be used by Bot.java to handle button clicks
    public static class ButtonResponseRegistry {
        private static final ConcurrentHashMap<Long, ButtonResponseData> activeMessages = new ConcurrentHashMap<>();
        
        public static void registerMessage(long messageId, long userId, 
                                           ScheduledExecutorService scheduler, 
                                           Consumer<String> responseHandler) {
            activeMessages.put(messageId, new ButtonResponseData(userId, scheduler, responseHandler));
        }
        
        public static ButtonResponseData getResponseData(long messageId) {
            return activeMessages.get(messageId);
        }
        
        public static void removeMessage(long messageId) {
            activeMessages.remove(messageId);
        }
        
        public static class ButtonResponseData {
            private final long userId;
            private final ScheduledExecutorService scheduler;
            private final Consumer<String> responseHandler;
            
            public ButtonResponseData(long userId, ScheduledExecutorService scheduler, 
                                      Consumer<String> responseHandler) {
                this.userId = userId;
                this.scheduler = scheduler;
                this.responseHandler = responseHandler;
            }
            
            public long getUserId() {
                return userId;
            }
            
            public ScheduledExecutorService getScheduler() {
                return scheduler;
            }
            
            public Consumer<String> getResponseHandler() {
                return responseHandler;
            }
        }
    }
}