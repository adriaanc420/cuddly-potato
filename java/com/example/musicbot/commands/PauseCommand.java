package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.Command;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class PauseCommand implements Command {
    private final MusicManager musicManager;

    public PauseCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        if (player.isPaused()) {
            event.getChannel().sendMessage("Player is already paused.").queue();
        } else {
            player.setPaused(true);
            event.getChannel().sendMessage("Player paused.").queue();
        }
    }
}
