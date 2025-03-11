package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.Command;
import com.example.musicbot.commands.PlayCommand;
import com.example.musicbot.BotConfig;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.awt.Color;

public class SkipCommand implements Command {
    private final MusicManager musicManager;
    private final PlayCommand playCommand;
    private final BotConfig config;

    public SkipCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
        this.playCommand = new PlayCommand(musicManager);
        this.config = new BotConfig();
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        
        // Check if there's something to skip
        if (player.getPlayingTrack() == null) {
            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("❌ Nothing Playing");
            embed.setDescription("Nothing is playing to skip.");
            embed.setColor(Color.RED);
            event.getChannel().sendMessageEmbeds(embed.build()).queue();
            return;
        }
        
        // Store queue status before skipping
        boolean hasQueue = !musicManager.getQueue(guild).isEmpty();
        String currentTrackTitle = player.getPlayingTrack().getInfo().title;
        
        // Store current track for cleanup
        AudioTrack currentTrack = player.getPlayingTrack();
        
        // Stop the current track
        player.stopTrack();
        
        // Clean up temp file if needed
        playCommand.cleanupTempFileForTrack(currentTrack);
        
        // Manually play the next track
        musicManager.playNext(guild);
        
        // Create embed for response
        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(Color.decode(config.getEmbedColor()));
        
        // Better feedback based on queue status
        if (hasQueue && player.getPlayingTrack() != null) {
            embed.setTitle("⏭️ Track Skipped");
            embed.addField("Skipped", "`" + currentTrackTitle + "`", false);
            embed.addField("Now Playing", "`" + player.getPlayingTrack().getInfo().title + "`", false);
        } else {
            embed.setTitle("⏭️ Track Skipped");
            embed.addField("Skipped", "`" + currentTrackTitle + "`", false);
            embed.setDescription("Queue is now empty.");
        }
        
        event.getChannel().sendMessageEmbeds(embed.build()).queue();
    }
}