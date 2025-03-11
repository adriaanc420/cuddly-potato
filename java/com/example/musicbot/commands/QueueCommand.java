package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.Command;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class QueueCommand implements Command {
    private final MusicManager musicManager;
    private final static int TRACKS_PER_PAGE = 10;

    public QueueCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        AudioTrack currentTrack = musicManager.getPlayer(guild).getPlayingTrack();
        Queue<AudioTrack> queue = musicManager.getQueue(guild);
        
        // Parse page number if provided
        int page = 1;
        if (args != null && !args.isEmpty()) {
            try {
                page = Integer.parseInt(args);
                if (page < 1) page = 1;
            } catch (NumberFormatException e) {
                // Ignore invalid page numbers
            }
        }
        
        // Create an embed
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎵 Music Queue");
        embed.setColor(new Color(29, 185, 84)); // Spotify green
        
        // Add current track info
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
        
        // Add queue info
        if (queue == null || queue.isEmpty()) {
            embed.addField("Queue", "Queue is empty.", false);
        } else {
            List<AudioTrack> trackList = new ArrayList<>(queue);
            int totalPages = (int) Math.ceil((double) trackList.size() / TRACKS_PER_PAGE);
            
            // Adjust page if out of bounds
            if (page > totalPages) page = totalPages;
            
            int startIndex = (page - 1) * TRACKS_PER_PAGE;
            int endIndex = Math.min(startIndex + TRACKS_PER_PAGE, trackList.size());
            
            StringBuilder sb = new StringBuilder();
            
            // Calculate total duration
            long totalDuration = 0;
            for (AudioTrack track : trackList) {
                totalDuration += track.getDuration();
            }
            
            // Add page info
            embed.setFooter("Page " + page + "/" + totalPages + " • " + 
                           trackList.size() + " songs • Total duration: " + formatTime(totalDuration));
            
            // Add tracks for this page
            for (int i = startIndex; i < endIndex; i++) {
                AudioTrack track = trackList.get(i);
                sb.append("`").append(i + 1).append(".` ");
                sb.append("`").append(track.getInfo().title).append("`");
                sb.append(" [`").append(formatTime(track.getDuration())).append("`]\n");
            }
            
            // If there are more pages, add navigation hint
            if (totalPages > 1) {
                sb.append("\nUse `!queue <page>` to navigate pages.");
            }
            
            embed.addField("Queue - " + trackList.size() + " tracks", sb.toString(), false);
        }
        
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
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