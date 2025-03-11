package com.example.musicbot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import com.example.musicbot.SpotifyURLDecoder;


/**
 * A simplified version of SpotifyManager that doesn't rely on external Spotify API libraries
 * Instead, it extracts information from Spotify URLs to create better search queries
 */
public class SpotifyManager {
    private final BotConfig config;

    // Patterns for different types of Spotify URLs
    private static final Pattern TRACK_PATTERN = Pattern.compile("spotify\\.com/track/([a-zA-Z0-9]+)");
    private static final Pattern ALBUM_PATTERN = Pattern.compile("spotify\\.com/album/([a-zA-Z0-9]+)");
    private static final Pattern PLAYLIST_PATTERN = Pattern.compile("spotify\\.com/playlist/([a-zA-Z0-9]+)");
    
    // Pattern to extract title from URL path
    private static final Pattern TITLE_PATTERN = Pattern.compile("/track/[a-zA-Z0-9]+/([^?/]+)");

    public SpotifyManager(BotConfig config) {
        this.config = config;
    }

    /**
     * Checks if a URL is a Spotify URL
     */
    public boolean isSpotifyUrl(String url) {
        return url.contains("open.spotify.com") || url.contains("spotify:track") || 
               url.contains("spotify:album") || url.contains("spotify:playlist");
    }

    public String getDebugInfo(String trackId) {
        try {
            TrackInfo info = getTrackInfo(trackId);
            return "Track ID: " + trackId + "\n" +
                   "URL: " + config.getLastSpotifyUrl() + "\n" +
                   "Title: " + info.getName() + "\n" +
                   "Artist: " + info.getArtists() + "\n" +
                   "Search Query: " + info.getSearchQuery();
        } catch (Exception e) {
            return "Error getting debug info: " + e.getMessage();
        }
    }

    /**
     * Gets the type of Spotify URL (track, album, playlist)
     */
    public SpotifyUrlType getSpotifyUrlType(String url) {
        if (TRACK_PATTERN.matcher(url).find() || url.contains("spotify:track:")) {
            return SpotifyUrlType.TRACK;
        } else if (ALBUM_PATTERN.matcher(url).find() || url.contains("spotify:album:")) {
            return SpotifyUrlType.ALBUM;
        } else if (PLAYLIST_PATTERN.matcher(url).find() || url.contains("spotify:playlist:")) {
            return SpotifyUrlType.PLAYLIST;
        }
        return SpotifyUrlType.UNKNOWN;
    }

    /**
     * Extracts Spotify ID from a URL
     */
    public String extractId(String url, SpotifyUrlType type) {
        Pattern pattern;
        
        switch (type) {
            case TRACK:
                pattern = TRACK_PATTERN;
                if (url.contains("spotify:track:")) {
                    return url.split("spotify:track:")[1];
                }
                break;
            case ALBUM:
                pattern = ALBUM_PATTERN;
                if (url.contains("spotify:album:")) {
                    return url.split("spotify:album:")[1];
                }
                break;
            case PLAYLIST:
                pattern = PLAYLIST_PATTERN;
                if (url.contains("spotify:playlist:")) {
                    return url.split("spotify:playlist:")[1];
                }
                break;
            default:
                return null;
        }
        
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }

    /**
     * Try to extract a title from the Spotify URL if present
     */
    private String[] extractInfoFromUrl(String url) {
        // Default values
        String title = "Unknown Track";
        String artist = "Unknown Artist";
        
        try {
            // First, check for title in URL path format
            Matcher titleMatcher = TITLE_PATTERN.matcher(url);
            if (titleMatcher.find()) {
                String slugTitle = titleMatcher.group(1);
                
                // Convert slug format to title format
                String[] words = slugTitle.split("-");
                StringBuilder titleBuilder = new StringBuilder();
                for (String word : words) {
                    if (!word.isEmpty()) {
                        if (titleBuilder.length() > 0) {
                            titleBuilder.append(" ");
                        }
                        titleBuilder.append(Character.toUpperCase(word.charAt(0)));
                        if (word.length() > 1) {
                            titleBuilder.append(word.substring(1));
                        }
                    }
                }
                title = titleBuilder.toString();
            }
            
            // Look for artist name in the URL
            // Example pattern: /artist/123456/track/ or /artist/name/
            Pattern artistPattern = Pattern.compile("artist/([a-zA-Z0-9_-]+)");
            Matcher artistMatcher = artistPattern.matcher(url);
            if (artistMatcher.find()) {
                String slugArtist = artistMatcher.group(1);
                if (slugArtist.matches("[a-zA-Z0-9]+")) {
                    // If it's just an ID, we can't determine the name
                    artist = "Unknown Artist";
                } else {
                    // Convert slug format to name format
                    String[] words = slugArtist.split("-");
                    StringBuilder artistBuilder = new StringBuilder();
                    for (String word : words) {
                        if (!word.isEmpty()) {
                            if (artistBuilder.length() > 0) {
                                artistBuilder.append(" ");
                            }
                            artistBuilder.append(Character.toUpperCase(word.charAt(0)));
                            if (word.length() > 1) {
                                artistBuilder.append(word.substring(1));
                            }
                        }
                    }
                    artist = artistBuilder.toString();
                }
            }
        } catch (Exception e) {
            System.err.println("Error extracting info from URL: " + e.getMessage());
        }
        
        return new String[]{title, artist};
    }

    /**
     * Gets track info from a Spotify track URL
     * Extracts meaningful information from the URL when possible
     */
    public TrackInfo getTrackInfo(String trackId) {
        String title = null;
        String artists = null;
        String album = "";
        
        // First try to get the info by downloading the page content
        String originalUrl = config.getLastSpotifyUrl();
        if (originalUrl != null && !originalUrl.isEmpty()) {
            String[] info = SpotifyURLDecoder.extractFromURL(originalUrl);
            
            if (info[0] != null && !info[0].equals("Unknown Track")) {
                title = info[0];
            }
            
            if (info[1] != null && !info[1].equals("Unknown Artist")) {
                artists = info[1];
            }
        }
        
        // If we still don't have a title or artist, use fallbacks
        if (title == null || title.equals("Unknown Track")) {
            // Try the last user query
            String userQuery = config.getLastUserQuery();
            if (userQuery != null && !userQuery.trim().isEmpty()) {
                // Extract search terms from the query
                String searchTerms = userQuery.trim();
                
                if (searchTerms.startsWith(config.getPrefix())) {
                    searchTerms = searchTerms.substring(config.getPrefix().length()).trim();
                }
                
                if (searchTerms.toLowerCase().startsWith("play")) {
                    searchTerms = searchTerms.substring(4).trim();
                }
                
                // Remove the URL
                searchTerms = searchTerms.replaceAll("https?://\\S+", "").trim();
                
                if (!searchTerms.isEmpty()) {
                    title = searchTerms;
                    // We already have the full search, no need for separate artists
                    artists = "";
                } else {
                    title = "Spotify song";
                }
            } else {
                title = "Spotify song";
            }
        }
        
        // If we have no artist info, use generic terms
        if (artists == null || artists.isEmpty() || artists.equals("Unknown Artist")) {
            artists = "official audio";
        }
        
        return new TrackInfo(
                title,
                artists,
                album,
                180 // default duration of 3 minutes
        );
    }

    /**
     * Gets all tracks from a Spotify album ID
     * Creates mock track info with better search terms
     */
    public List<TrackInfo> getAlbumTracks(String albumId) {
        List<TrackInfo> tracks = new ArrayList<>();
        
        // Create 5 mock tracks for the album with better search terms
        for (int i = 1; i <= 5; i++) {
            tracks.add(new TrackInfo(
                    "Spotify album song " + i,
                    "popular album track official",
                    "Album",
                    180 // 3 minute default duration
            ));
        }
        
        return tracks;
    }

    /**
     * Gets all tracks from a Spotify playlist ID
     * Creates mock track info with better search terms
     */
    public List<TrackInfo> getPlaylistTracks(String playlistId) {
        List<TrackInfo> tracks = new ArrayList<>();
        
        // Create 10 mock tracks for the playlist with better search terms
        for (int i = 1; i <= 10; i++) {
            tracks.add(new TrackInfo(
                    "Spotify playlist song " + i,
                    "popular track official audio",
                    "Playlist",
                    180 // 3 minute default duration
            ));
        }
        
        return tracks;
    }

    /**
     * Data class to store track information
     */
    public static class TrackInfo {
        private final String name;
        private final String artists;
        private final String album;
        private final long durationSeconds;

        public TrackInfo(String name, String artists, String album, long durationSeconds) {
            this.name = name;
            this.artists = artists;
            this.album = album;
            this.durationSeconds = durationSeconds;
        }

        public String getName() {
            return name;
        }

        public String getArtists() {
            return artists;
        }

        public String getAlbum() {
            return album;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }
        
        public String getSearchQuery() {
            // If the name already has the artist (from user query), just use it directly
            if (name != null && !name.equals("Spotify song") && !name.equals("Unknown Track")) {
                if (name.toLowerCase().contains(artists.toLowerCase()) || artists.isEmpty()) {
                    return name + " official audio";
                } else {
                    return name + " " + artists + " official audio";
                }
            }
            
            // Fallback to a more generic search
            if (artists != null && !artists.equals("official audio") && !artists.equals("Unknown Artist")) {
                return "best songs by " + artists + " official audio";
            }
            
            // Last resort
            return "popular songs official audio";
        }
        
        @Override
        public String toString() {
            if (album != null && !album.isEmpty()) {
                return name + " by " + artists + " [" + album + "]";
            } else {
                return name + " by " + artists;
            }
        }
    }

    /**
     * Enum for Spotify URL types
     */
    public enum SpotifyUrlType {
        TRACK,
        ALBUM,
        PLAYLIST,
        UNKNOWN
    }
}