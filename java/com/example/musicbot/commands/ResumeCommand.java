package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.Command;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class ResumeCommand implements Command {
    private final MusicManager musicManager;

    public ResumeCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        var player = musicManager.getPlayer(guild);
        if (!player.isPaused()) {
            event.getChannel().sendMessage("Player is not paused.").queue();
        } else {
            player.setPaused(false);
            event.getChannel().sendMessage("Player resumed.").queue();
        }
    }
}
