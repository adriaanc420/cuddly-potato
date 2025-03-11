package com.example.musicbot;

import java.io.*;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class BotConfig {
    private final Properties properties = new Properties();
    private final File configFile = new File("config.txt");
    private boolean configInitialized = false;

    // Logging levels
    public static final String LOG_LEVEL_CLEAN = "clean";     // Only important messages
    public static final String LOG_LEVEL_INFO = "info";       // General information
    public static final String LOG_LEVEL_DEBUG = "debug";     // Detailed debugging info
    public static final String LOG_LEVEL_TRACE = "trace";     // Everything, including lavaplayer internals

    public BotConfig() {
        load();
    }

    

    private void load() {
        if (configFile.exists() && configFile.length() < 10_000_000) { // Max 10MB to prevent corrupt files
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                configInitialized = true;
                System.out.println("Config loaded successfully.");
            } catch (IOException e) {
                System.err.println("Error loading config: " + e.getMessage());
                createDefaultConfig();
            }
        } else {
            if (configFile.exists() && configFile.length() >= 10_000_000) {
                // File exists but is too large - likely corrupted
                System.err.println("Config file is too large (" + (configFile.length() / 1024 / 1024) + "MB). Creating backup and new config.");
                try {
                    File backupFile = new File("config_backup_" + System.currentTimeMillis() + ".txt");
                    Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    configFile.delete();
                } catch (IOException e) {
                    System.err.println("Error creating backup: " + e.getMessage());
                }
            }
            createDefaultConfig();
        }
        
        // Set defaults for properties that don't exist
        if (properties.getProperty("prefix") == null)
            properties.setProperty("prefix", "!");
        if (properties.getProperty("bot_status") == null)
            properties.setProperty("bot_status", "Music Bot");
        if (properties.getProperty("now_playing_format") == null)
            properties.setProperty("now_playing_format", "Now Playing: %s");
        if (properties.getProperty("log_level") == null)
            properties.setProperty("log_level", LOG_LEVEL_CLEAN);
        if (properties.getProperty("cache_dir") == null)
            properties.setProperty("cache_dir", "cache");
        if (properties.getProperty("embed_color") == null)
            properties.setProperty("embed_color", "#1DB954");
        if (properties.getProperty("max_volume") == null)
            properties.setProperty("max_volume", "150");
        if (properties.getProperty("default_volume") == null)
            properties.setProperty("default_volume", "100");
        if (properties.getProperty("max_queue_display") == null)
            properties.setProperty("max_queue_display", "10");
        if (properties.getProperty("reaction_timeout") == null)
            properties.setProperty("reaction_timeout", "15");
        if (properties.getProperty("log_to_file") == null)
            properties.setProperty("log_to_file", "false");
        if (properties.getProperty("log_file") == null)
            properties.setProperty("log_file", "logs/musicbot.log");
        if (properties.getProperty("spotify_enabled") == null)
            properties.setProperty("spotify_enabled", "false");
        if (properties.getProperty("spotify_client_id") == null)
            properties.setProperty("spotify_client_id", "");
        if (properties.getProperty("spotify_client_secret") == null)
            properties.setProperty("spotify_client_secret", "");
        if (properties.getProperty("last_spotify_url") == null)
            properties.setProperty("last_spotify_url", "");
        
        // Interactive console for required values
        Scanner scanner = new Scanner(System.in);
        boolean tokenUpdated = false;
        
        if (properties.getProperty("token") == null || properties.getProperty("token").isEmpty() ||
            properties.getProperty("token").equals("BOT_TOKEN_HERE")) {
            System.out.print("Please enter your Discord bot token: ");
            String token = scanner.nextLine().trim();
            if (!token.isEmpty()) {
                properties.setProperty("token", token);
                tokenUpdated = true;
            }
        }
        
        if (properties.getProperty("owner") == null || properties.getProperty("owner").isEmpty()) {
            System.out.print("Please enter your Discord owner ID: ");
            String owner = scanner.nextLine().trim();
            if (!owner.isEmpty()) {
                properties.setProperty("owner", owner);
                tokenUpdated = true;
            }
        }
        
        // Only save if we've updated critical values or the config was just created
        if (tokenUpdated || !configInitialized) {
            saveConfig();
        }
    }

    private void createDefaultConfig() {
        System.out.println("Creating default configuration file...");
        
        StringBuilder sb = new StringBuilder();
        
        // ASCII Art Header
        sb.append("╭━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╮\n");
        sb.append("│                                                  │\n");
        sb.append("│   ███╗   ███╗██╗   ██╗███████╗██╗ ██████╗       │\n");
        sb.append("│   ████╗ ████║██║   ██║██╔════╝██║██╔════╝       │\n");
        sb.append("│   ██╔████╔██║██║   ██║███████╗██║██║            │\n");
        sb.append("│   ██║╚██╔╝██║██║   ██║╚════██║██║██║            │\n");
        sb.append("│   ██║ ╚═╝ ██║╚██████╔╝███████║██║╚██████╗       │\n");
        sb.append("│   ╚═╝     ╚═╝ ╚═════╝ ╚══════╝╚═╝ ╚═════╝       │\n");
        sb.append("│                                                  │\n");
        sb.append("│   ██████╗  ██████╗ ████████╗                    │\n");
        sb.append("│   ██╔══██╗██╔═══██╗╚══██╔══╝                    │\n");
        sb.append("│   ██████╔╝██║   ██║   ██║                       │\n");
        sb.append("│   ██╔══██╗██║   ██║   ██║                       │\n");
        sb.append("│   ██████╔╝╚██████╔╝   ██║                       │\n");
        sb.append("│   ╚═════╝  ╚═════╝    ╚═╝                       │\n");
        sb.append("│                                                  │\n");
        sb.append("╰━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━╯\n\n");
        
        // Config file header
        sb.append("# ═══════════════════════════════════════════════════\n");
        sb.append("# Discord Music Bot Configuration File\n");
        sb.append("# ═══════════════════════════════════════════════════\n\n");
    
        // Required settings
        sb.append("# ┌─────────────────────────────────────────────────┐\n");
        sb.append("# │               REQUIRED SETTINGS                 │\n");
        sb.append("# └─────────────────────────────────────────────────┘\n\n");
        
        sb.append("# Discord Bot Token (Get from https://discord.com/developers/applications)\n");
        sb.append("# REQUIRED: Your bot won't work without this!\n");
        sb.append("token = BOT_TOKEN_HERE\n\n");
        
        sb.append("# Discord Owner ID (Your user ID, right-click your name and Copy ID)\n");
        sb.append("# REQUIRED: Used for admin commands and startup notification\n");
        sb.append("owner = \n\n");
    
        // General settings
        sb.append("# ┌─────────────────────────────────────────────────┐\n");
        sb.append("# │               GENERAL SETTINGS                  │\n");
        sb.append("# └─────────────────────────────────────────────────┘\n\n");
        
        sb.append("# Command prefix for text commands (e.g., !play, !skip)\n");
        sb.append("# Possible values: Any character or string (!, ?, ., -, $, etc.)\n");
        sb.append("prefix = !\n\n");
        
        sb.append("# Bot status message (shown in Discord member list)\n");
        sb.append("# Possible values: Any text (e.g., \"Playing music\", \"Type !help\")\n");
        sb.append("bot_status = 🎵 Music Bot | !help\n\n");
        
        sb.append("# Now playing format (use %s to insert the track title)\n");
        sb.append("# Possible values: Any text with %s as placeholder for the song title\n");
        sb.append("now_playing_format = Now Playing: %s\n\n");
        
        sb.append("# Embed color for messages (Hex color code)\n");
        sb.append("# Possible values: Any hex color code (#RRGGBB format)\n");
        sb.append("embed_color = #1DB954\n\n");
    
        // Audio settings
        sb.append("# ┌─────────────────────────────────────────────────┐\n");
        sb.append("# │               AUDIO SETTINGS                    │\n");
        sb.append("# └─────────────────────────────────────────────────┘\n\n");
        
        sb.append("# Maximum volume level\n");
        sb.append("# Possible values: 1-1000 (recommended: 100-200)\n");
        sb.append("max_volume = 150\n\n");
        
        sb.append("# Default volume level when bot joins\n");
        sb.append("# Possible values: 1-max_volume\n");
        sb.append("default_volume = 100\n\n");
        
        sb.append("# Maximum number of tracks to display in queue command\n");
        sb.append("# Possible values: Any positive number\n");
        sb.append("max_queue_display = 10\n\n");
        
        sb.append("# Timeout for reaction-based choices (in seconds)\n");
        sb.append("# Possible values: Any positive number\n");
        sb.append("reaction_timeout = 15\n\n");
    
        // Spotify integration
        sb.append("# ┌─────────────────────────────────────────────────┐\n");
        sb.append("# │               SPOTIFY INTEGRATION               │\n");
        sb.append("# └─────────────────────────────────────────────────┘\n\n");
        
        sb.append("# Enable Spotify integration\n");
        sb.append("# Possible values: true, false\n");
        sb.append("spotify_enabled = false\n\n");
        
        sb.append("# Spotify API Client ID (Get from https://developer.spotify.com/dashboard/)\n");
        sb.append("# Required if spotify_enabled is true\n");
        sb.append("spotify_client_id = \n\n");
        
        sb.append("# Spotify API Client Secret\n");
        sb.append("# Required if spotify_enabled is true\n");
        sb.append("spotify_client_secret = \n\n");
    
        // Logging settings
        sb.append("# ┌─────────────────────────────────────────────────┐\n");
        sb.append("# │               LOGGING SETTINGS                  │\n");
        sb.append("# └─────────────────────────────────────────────────┘\n\n");
        
        sb.append("# Logging level\n");
        sb.append("# Possible values:\n");
        sb.append("#   clean - Only important messages (connect/disconnect, errors)\n");
        sb.append("#   info  - General information (track loading, queue management)\n");
        sb.append("#   debug - Detailed debugging information (API calls, data processing)\n");
        sb.append("#   trace - Everything, including all internal library events\n");
        sb.append("log_level = clean\n\n");
        
        sb.append("# Log to file\n");
        sb.append("# Possible values: true, false\n");
        sb.append("log_to_file = false\n\n");
        
        sb.append("# Log file path (relative or absolute)\n");
        sb.append("# Only used if log_to_file is true\n");
        sb.append("log_file = logs/musicbot.log\n\n");
    
        // Technical settings
        sb.append("# ┌─────────────────────────────────────────────────┐\n");
        sb.append("# │               TECHNICAL SETTINGS                │\n");
        sb.append("# └─────────────────────────────────────────────────┘\n\n");
        
        sb.append("# Directory for caching downloaded media (relative or absolute path)\n");
        sb.append("# Possible values: Any valid directory path\n");
        sb.append("cache_dir = cache\n\n");
        
        sb.append("# Internal data storage (DO NOT EDIT MANUALLY)\n");
        sb.append("last_spotify_url = \n\n");
        
        sb.append("# ═══════════════════════════════════════════════════\n");
        sb.append("# End of Configuration\n");
        sb.append("# ═══════════════════════════════════════════════════\n");
        
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(sb.toString());
            writer.flush();
            configInitialized = true;
            
            // Load the properties from the newly created file
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
            }
            
            System.out.println("Default configuration created successfully.");
        } catch (IOException e) {
            System.err.println("Error creating default config: " + e.getMessage());
        }
    }

    // Safely save the config file
    private void saveConfig() {
    try {
        // If this is from a newly created default config, just update properties
        // without overwriting the nice formatting
        if (configInitialized) {
            // Write to a temporary file first
            File tempFile = new File("config.tmp");
            try (FileWriter writer = new FileWriter(tempFile)) {
                // Read the original file to preserve formatting
                List<String> lines = Files.readAllLines(configFile.toPath());
                boolean modified = false;
                
                for (String line : lines) {
                    // Check if line has a property
                    int equalPos = line.indexOf('=');
                    if (equalPos > 0 && !line.trim().startsWith("#")) {
                        String key = line.substring(0, equalPos).trim();
                        // Replace with new value if it exists
                        if (properties.containsKey(key)) {
                            String value = properties.getProperty(key);
                            line = key + " = " + value;
                            modified = true;
                        }
                    }
                    writer.write(line + "\n");
                }
                
                // If no modifications were made (maybe property not found), 
                // add missing properties at the end
                if (!modified) {
                    writer.write("\n# Additional properties\n");
                    for (String key : properties.stringPropertyNames()) {
                        writer.write(key + " = " + properties.getProperty(key) + "\n");
                    }
                }
            }
            
            // If successful, replace the main config file
            Files.move(tempFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            // For a completely new config, we want to use the createDefaultConfig method
            // which has all the nice formatting
            createDefaultConfig();
        }
        System.out.println("Configuration saved successfully.");
    } catch (IOException e) {
        System.err.println("Error saving config: " + e.getMessage());
    }
}


    // Public method to update a single property
    public void setProperty(String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
            saveConfig();
        }
    }

    public void setLastUserQuery(String query) {
        if (query != null) {
            properties.setProperty("last_user_query", query);
            saveConfig();
        }
    }
    
    /**
     * Gets the last user query
     */
    public String getLastUserQuery() {
        return properties.getProperty("last_user_query", "");
    }

    // Standard getters
    public String getToken() {
        return properties.getProperty("token");
    }

    public String getOwner() {
        return properties.getProperty("owner");
    }

    public String getPrefix() {
        return properties.getProperty("prefix");
    }

    public String getBotStatus() {
        return properties.getProperty("bot_status");
    }

    public String getNowPlayingFormat() {
        return properties.getProperty("now_playing_format");
    }
    
    public String getLogLevel() {
        return properties.getProperty("log_level", LOG_LEVEL_CLEAN);
    }
    
    public boolean isDebugLogging() {
        String level = getLogLevel();
        return level.equalsIgnoreCase(LOG_LEVEL_DEBUG) || level.equalsIgnoreCase(LOG_LEVEL_TRACE);
    }
    
    public boolean isTraceLogging() {
        return getLogLevel().equalsIgnoreCase(LOG_LEVEL_TRACE);
    }
    
    public boolean isCleanLogging() {
        return getLogLevel().equalsIgnoreCase(LOG_LEVEL_CLEAN);
    }
    
    public boolean logToFile() {
        return Boolean.parseBoolean(properties.getProperty("log_to_file", "false"));
    }
    
    public String getLogFile() {
        return properties.getProperty("log_file", "logs/musicbot.log");
    }
    
    public String getCacheDir() {
        return properties.getProperty("cache_dir", "cache");
    }
    
    public String getEmbedColor() {
        return properties.getProperty("embed_color", "#1DB954");
    }
    
    public int getMaxVolume() {
        try {
            return Integer.parseInt(properties.getProperty("max_volume", "150"));
        } catch (NumberFormatException e) {
            return 150; // Default if parsing fails
        }
    }
    
    public int getDefaultVolume() {
        try {
            return Integer.parseInt(properties.getProperty("default_volume", "100"));
        } catch (NumberFormatException e) {
            return 100; // Default if parsing fails
        }
    }
    
    public int getMaxQueueDisplay() {
        try {
            return Integer.parseInt(properties.getProperty("max_queue_display", "10"));
        } catch (NumberFormatException e) {
            return 10; // Default if parsing fails
        }
    }
    
    public int getReactionTimeout() {
        try {
            return Integer.parseInt(properties.getProperty("reaction_timeout", "15"));
        } catch (NumberFormatException e) {
            return 15; // Default if parsing fails
        }
    }
    
    // Spotify-related methods
    public boolean isSpotifyEnabled() {
        return Boolean.parseBoolean(properties.getProperty("spotify_enabled", "false"));
    }
    
    public String getSpotifyClientId() {
        return properties.getProperty("spotify_client_id", "");
    }
    
    public String getSpotifyClientSecret() {
        return properties.getProperty("spotify_client_secret", "");
    }
    
    public String getLastSpotifyUrl() {
        return properties.getProperty("last_spotify_url", "");
    }
    
    public void setLastSpotifyUrl(String url) {
        if (url != null) {
            properties.setProperty("last_spotify_url", url);
            saveConfig();
        }
    }
    
    public void setSpotifyEnabled(boolean enabled) {
        properties.setProperty("spotify_enabled", String.valueOf(enabled));
        saveConfig();
    }
    
    public void setSpotifyCredentials(String clientId, String clientSecret) {
        if (clientId != null && clientSecret != null) {
            properties.setProperty("spotify_client_id", clientId);
            properties.setProperty("spotify_client_secret", clientSecret);
            saveConfig();
        }
    }
}