package com.example.musicbot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager; // Add this import
import com.example.musicbot.BotLogger;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class MusicManager {
    private final AudioPlayerManager playerManager;
    private final Map<Long, AudioPlayer> players;
    private final Map<Long, Queue<AudioTrack>> queues;
    private final Map<Long, ScheduledFuture<?>> disconnectTasks; // For auto-disconnect
    private final ScheduledExecutorService scheduler; // Scheduler for tasks
    private final long AUTO_DISCONNECT_DELAY = 30; // Auto-disconnect delay in seconds
    private final long MAX_CACHE_SIZE = 1_000_000_000; // 1GB cache limit
    private final File cacheDir;
    private final boolean useYouTube = false; // Set to true if playing directly from YouTube

    public MusicManager() {
        // Get cache directory from config
        BotConfig config = new BotConfig();
        String cacheDirPath = config.getCacheDir();
        this.cacheDir = new File(cacheDirPath);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    
        // Initialize fields that were missing initialization
        this.disconnectTasks = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Music-Task-Scheduler");
            t.setDaemon(true); // Allow JVM to exit if only daemon threads remain
            return t;
        });
        
        // Schedule regular cache cleanup
        scheduler.scheduleAtFixedRate(this::cleanupCache, 1, 12, TimeUnit.HOURS);

        // Initialize the audio player manager with optimized settings
        playerManager = new DefaultAudioPlayerManager();
        
        // Optimize player settings if available in this version
        try {
            playerManager.getConfiguration().setResamplingQuality(
                    com.sedmelluq.discord.lavaplayer.player.AudioConfiguration.ResamplingQuality.MEDIUM);
        } catch (Exception e) {
            System.err.println("Could not set resampling quality: " + e.getMessage());
        }
        
        // Register audio sources
        AudioSourceManagers.registerRemoteSources(playerManager);
        AudioSourceManagers.registerLocalSource(playerManager);
        
        players = new ConcurrentHashMap<>();
        queues = new ConcurrentHashMap<>();
    }

    private void cleanupCache() {
        try {
            // Skip if cache directory doesn't exist
            if (!cacheDir.exists()) return;
            
            // Get all files in cache
            File[] files = cacheDir.listFiles();
            if (files == null || files.length == 0) return;
            
            // Sort by last modified (oldest first)
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            
            // Calculate total size
            long totalSize = 0;
            for (File file : files) {
                totalSize += file.length();
            }
            
            // Delete oldest files if over limit
            int i = 0;
            while (totalSize > MAX_CACHE_SIZE && i < files.length) {
                long fileSize = files[i].length();
                if (files[i].delete()) {
                    totalSize -= fileSize;
                    System.out.println("Cache cleanup: Deleted " + files[i].getName());
                }
                i++;
            }
        } catch (Exception e) {
            System.err.println("Error during cache cleanup: " + e.getMessage());
        }
    }

    /**
     * Retrieves or creates an AudioPlayer for the given guild and attaches an event listener.
     */
   public AudioPlayer getPlayer(Guild guild) {
    long guildId = guild.getIdLong();
    // Create a new player if one does not exist.
    if (!players.containsKey(guildId)) {
        AudioPlayer player = playerManager.createPlayer();
        queues.put(guildId, new ConcurrentLinkedQueue<>());
        // Add an event listener to play the next track when the current one finishes.
        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
                if (endReason.mayStartNext) {
                    // Use a thread-safe method to play the next track
                    synchronized (this) {
                        playNext(guildId, player);
                    }
                }
            }
        });
        players.put(guildId, player);
    }
    return players.get(guildId);
}

    /**
     * Schedule a task to disconnect from the voice channel after specified delay
     */
    private void scheduleDisconnect(Guild guild, long guildId) {
        cancelDisconnectTask(guildId); // Cancel any existing task first
        
        ScheduledFuture<?> task = scheduler.schedule(() -> {
            try {
                // Check again if something is playing (could have changed while waiting)
                AudioPlayer player = players.get(guildId);
                if (player != null && player.getPlayingTrack() == null) {
                    // Nothing is playing, so disconnect
                    AudioManager audioManager = guild.getAudioManager();
                    if (audioManager.isConnected()) {
                        System.out.println("Auto-disconnecting from voice channel in guild: " + guild.getName());
                        audioManager.closeAudioConnection();
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in auto-disconnect task: " + e.getMessage());
            }
        }, AUTO_DISCONNECT_DELAY, TimeUnit.SECONDS);
        
        disconnectTasks.put(guildId, task);
        System.out.println("Scheduled auto-disconnect for guild: " + guild.getName() + 
                           " in " + AUTO_DISCONNECT_DELAY + " seconds");
    }

    /**
     * Cancel any scheduled disconnect task
     */
    private void cancelDisconnectTask(long guildId) {
        ScheduledFuture<?> task = disconnectTasks.remove(guildId);
        if (task != null && !task.isDone()) {
            task.cancel(false);
            System.out.println("Cancelled auto-disconnect for guild ID: " + guildId);
        }
    }

    /**
     * Adds a track to the queue for the given guild.
     */
    public void queueTrack(Guild guild, AudioTrack track) {
        long guildId = guild.getIdLong();
        Queue<AudioTrack> queue = queues.computeIfAbsent(guildId, k -> new ConcurrentLinkedQueue<>());
        queue.offer(track);
        
        BotLogger.audio("Track queued in guild " + guild.getName() + ": " + track.getInfo().title);
        
        // Cancel any scheduled disconnect when a track is queued
        cancelDisconnectTask(guildId);
    }

    /**
     * Clears the queue for the given guild.
     */
    public void clearQueue(Guild guild) {
        long guildId = guild.getIdLong();
        Queue<AudioTrack> queue = queues.get(guildId);
        if (queue != null) {
            queue.clear();
        }
    }

    /**
     * Plays the next track in the queue, if available.
     */
    public synchronized void playNext(long guildId, AudioPlayer player) {
        Queue<AudioTrack> queue = queues.get(guildId);
        if (queue != null && !queue.isEmpty()) {
            AudioTrack nextTrack = queue.poll();
            if (nextTrack != null) {
                // Create a copy of the track to avoid concurrent modification
                AudioTrack trackToPlay = nextTrack.makeClone();
                player.playTrack(trackToPlay);
            }
        } else {
            // If queue is empty, schedule auto-disconnect
            Guild guild = Bot.getJDAInstance().getGuildById(guildId);
            if (guild != null) {
                scheduleDisconnect(guild, guildId);
            }
        }
    }

    /**
     * Plays the next track in the queue for the specified guild.
     */
    public void playNext(Guild guild) {
        long guildId = guild.getIdLong();
        AudioPlayer player = players.get(guildId);
        if (player != null) {
            playNext(guildId, player);
        }
    }

    /**
     * Gets the queue for the specified guild.
     */
    public Queue<AudioTrack> getQueue(Guild guild) {
        return queues.get(guild.getIdLong());
    }

    /**
     * Shuffles the queue for the specified guild.
     */
    public void shuffle(Guild guild) {
        long guildId = guild.getIdLong();
        Queue<AudioTrack> queue = queues.get(guildId);
        if (queue != null) {
            List<AudioTrack> tracks = new ArrayList<>(queue);
            Collections.shuffle(tracks);
            queue.clear();
            queue.addAll(tracks);
        }
    }

    /**
     * Removes a track from the queue at the specified index.
     */
    public boolean remove(Guild guild, int index) {
        long guildId = guild.getIdLong();
        Queue<AudioTrack> queue = queues.get(guildId);
        if (queue != null && !queue.isEmpty()) {
            List<AudioTrack> tracks = new ArrayList<>(queue);
            if (index >= 0 && index < tracks.size()) {
                tracks.remove(index);
                queue.clear();
                queue.addAll(tracks);
                return true;
            }
        }
        return false;
    }

    /**
     * Moves a track from one position to another in the queue.
     */
    public boolean move(Guild guild, int fromIndex, int toIndex) {
        long guildId = guild.getIdLong();
        Queue<AudioTrack> queue = queues.get(guildId);
        if (queue != null && !queue.isEmpty()) {
            List<AudioTrack> tracks = new ArrayList<>(queue);
            if (fromIndex >= 0 && fromIndex < tracks.size() && toIndex >= 0 && toIndex < tracks.size()) {
                AudioTrack track = tracks.remove(fromIndex);
                tracks.add(toIndex, track);
                queue.clear();
                queue.addAll(tracks);
                return true;
            }
        }
        return false;
    }

    public AudioPlayerManager getPlayerManager() {
        return playerManager;
    }
}