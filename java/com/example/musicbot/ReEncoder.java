package com.example.musicbot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ReEncoder {

    /**
     * Re-encodes the given input file to an MP3 file at the specified output path using FFmpeg.
     * Optimized for lower CPU usage and faster encoding.
     *
     * @param inputPath  the path to the input audio file
     * @param outputPath the desired output MP3 file path
     * @return true if re-encoding succeeds (exit code 0), false otherwise
     */
    public static boolean reencodeToMp3(String inputPath, String outputPath) {
        // Build the FFmpeg command with optimized parameters
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", inputPath,
                "-vn",                      // Disable video
                "-ar", "44100",            // Sample rate
                "-ac", "2",                // Audio channels
                "-b:a", "192k",            // Audio bitrate
                "-preset", "ultrafast",    // Ultra fast encoding (lowest CPU usage)
                "-threads", "2",           // Limit thread count to avoid overwhelming the CPU
                "-max_muxing_queue_size", "1024", // Avoid muxing queue errors
                "-af", "dynaudnorm",       // Dynamic audio normalization for consistent volume
                outputPath
        );
        pb.redirectErrorStream(true);
        
        try {
            Process process = pb.start();
            
            // Set up process timeout to avoid hangs
            boolean completed = process.waitFor(5, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                System.err.println("FFmpeg encoding timed out after 5 minutes");
                return false;
            }
            
            // Capture FFmpeg output for debugging
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return true;
            } else {
                System.err.println("FFmpeg re-encode failed with exit code " + exitCode);
                System.err.println("FFmpeg output: " + output.toString());
                return false;
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Optimized method for quickly checking if a file is a valid audio file.
     * This is much faster than trying to load the full file.
     */
    public static boolean isValidAudioFile(String filePath) {
        ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error", "-show_entries", 
                "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", 
                filePath
        );
        pb.redirectErrorStream(true);
        
        try {
            Process process = pb.start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            
            return process.exitValue() == 0 && result != null && !result.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}