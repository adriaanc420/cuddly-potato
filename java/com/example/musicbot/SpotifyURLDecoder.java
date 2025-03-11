package com.example.musicbot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to extract meaningful information from Spotify URLs by fetching their HTML content
 */
public class SpotifyURLDecoder {
    // Patterns to extract information from Spotify URLs
    private static final Pattern TRACK_ID_PATTERN = Pattern.compile("spotify\\.com/track/([a-zA-Z0-9]+)");
    private static final Pattern TITLE_META_PATTERN = Pattern.compile("<meta property=\"og:title\" content=\"([^\"]+)\"");
    private static final Pattern DESCRIPTION_META_PATTERN = Pattern.compile("<meta name=\"description\" content=\"([^\"]+)\"");
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("<title>([^<]+)</title>");
    
    /**
     * Extract information from a Spotify URL by fetching its content
     * @param spotifyUrl The Spotify URL
     * @return String array with [title, artist]
     */
    public static String[] extractFromURL(String spotifyUrl) {
        String title = "Unknown Track";
        String artist = "Unknown Artist";
        
        try {
            // First get the track ID
            Matcher trackMatcher = TRACK_ID_PATTERN.matcher(spotifyUrl);
            if (!trackMatcher.find()) {
                return new String[]{title, artist};
            }
            
            // Create a GET request to the Spotify URL
            URL url = new URL(spotifyUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            // Set a browser-like user agent to avoid being blocked
            connection.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            
            // Set a timeout
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            
            // Get the response
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                // Read the HTML content
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder content = new StringBuilder();
                String line;
                
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                    
                    // Look for meta tags - we don't need to read the whole thing
                    if (content.length() > 10000) break;
                }
                reader.close();
                
                // Extract title from meta tag
                Matcher titleMatcher = TITLE_META_PATTERN.matcher(content);
                if (titleMatcher.find()) {
                    String fullTitle = titleMatcher.group(1);
                    
                    // Spotify meta title is usually "Song Name - Artist Name"
                    if (fullTitle.contains(" - ")) {
                        String[] parts = fullTitle.split(" - ", 2);
                        title = parts[0].trim();
                        artist = parts[1].trim();
                    } else {
                        title = fullTitle;
                    }
                } else {
                    // Try HTML title as fallback
                    Matcher htmlTitleMatcher = HTML_TITLE_PATTERN.matcher(content);
                    if (htmlTitleMatcher.find()) {
                        String htmlTitle = htmlTitleMatcher.group(1);
                        if (htmlTitle.contains(" - ")) {
                            String[] parts = htmlTitle.split(" - ", 2);
                            title = parts[0].trim();
                            if (!parts[1].toLowerCase().contains("spotify")) {
                                artist = parts[1].trim();
                            }
                        } else {
                            title = htmlTitle;
                        }
                    }
                }
                
                // Try description meta for more info
                Matcher descMatcher = DESCRIPTION_META_PATTERN.matcher(content);
                if (descMatcher.find()) {
                    String description = descMatcher.group(1);
                    if (description.contains("by") && artist.equals("Unknown Artist")) {
                        int byIndex = description.indexOf("by");
                        if (byIndex > 0 && byIndex + 3 < description.length()) {
                            String possibleArtist = description.substring(byIndex + 3).trim();
                            if (possibleArtist.contains(".")) {
                                possibleArtist = possibleArtist.substring(0, possibleArtist.indexOf("."));
                            }
                            if (!possibleArtist.isEmpty()) {
                                artist = possibleArtist;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching Spotify URL: " + e.getMessage());
        }
        
        return new String[]{title, artist};
    }
}