package com.example.musicbot.commands;

import com.example.musicbot.Command;
import com.example.musicbot.MusicManager;
import com.example.musicbot.commands.PlayCommand;
import com.example.musicbot.BotConfig;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.awt.Color;
import java.util.Queue;
import java.util.ArrayList;
import java.util.List;

public class StopCommand implements Command {
    private final MusicManager musicManager;
    private final PlayCommand playCommand;
    private final BotConfig config;

    public StopCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
        this.playCommand = new PlayCommand(musicManager);
        this.config = new BotConfig();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        
        // Check if something is playing
        if (player.getPlayingTrack() == null && 
            (musicManager.getQueue(guild) == null || musicManager.getQueue(guild).isEmpty())) {
            
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("❌ Nothing Playing");
            embed.setDescription("Nothing is playing to stop.");
            embed.setColor(Color.RED);
            event.getChannel().sendMessageEmbeds(embed.build()).queue();
            return;
        }
        
        // Store current track for cleanup
        AudioTrack currentTrack = player.getPlayingTrack();
        
        // Get all tracks in the queue for cleanup
        Queue<AudioTrack> queue = musicManager.getQueue(guild);
        List<AudioTrack> tracksToCleanup = new ArrayList<>();
        
        if (queue != null && !queue.isEmpty()) {
            tracksToCleanup.addAll(queue);
        }
        
        // Clear the queue
        musicManager.clearQueue(guild);
        
        // Stop the current track
        player.stopTrack();
        
        // Clean up current track if it exists
        if (currentTrack != null) {
            playCommand.cleanupTempFileForTrack(currentTrack);
        }
        
        // Clean up all queued tracks
        for (AudioTrack track : tracksToCleanup) {
            playCommand.cleanupTempFileForTrack(track);
        }
        
        // Disconnect from voice channel
        guild.getAudioManager().closeAudioConnection();
        
        // Create response embed
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("⏹️ Playback Stopped");
        embed.setDescription("Playback stopped and disconnected from voice channel.");
        
        if (!tracksToCleanup.isEmpty()) {
            embed.addField("Queue Cleared", tracksToCleanup.size() + " tracks removed from queue", false);
        }
        
        embed.setColor(Color.decode(config.getEmbedColor()));
        
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
    }
}