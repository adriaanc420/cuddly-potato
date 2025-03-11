package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.Command;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class NowPlayingCommand implements Command {
    private final MusicManager musicManager;

    public NowPlayingCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        AudioTrack track = musicManager.getPlayer(guild).getPlayingTrack();
        if (track == null) {
            event.getChannel().sendMessage("Nothing is playing right now.").queue();
        } else {
            String nowPlayingFormat = "Now Playing: %s [%s/%s]";
            String formatted = String.format(nowPlayingFormat, 
                    track.getInfo().title, 
                    formatTime(track.getPosition()), 
                    formatTime(track.getDuration()));
            event.getChannel().sendMessage(formatted).queue();
        }
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        seconds %= 60;
        minutes %= 60;
        return (hours > 0 ? hours + ":" : "") + (minutes < 10 ? "0" + minutes : minutes) + ":" + (seconds < 10 ? "0" + seconds : seconds);
    }
}
