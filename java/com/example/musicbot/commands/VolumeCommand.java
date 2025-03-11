package com.example.musicbot.commands;

import com.example.musicbot.MusicManager;
import com.example.musicbot.Command;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class VolumeCommand implements Command {
    private final MusicManager musicManager;

    public VolumeCommand(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @Override
    public void execute(MessageReceivedEvent event, String args) {
        Guild guild = event.getGuild();
        if (args.isEmpty()) {
            event.getChannel().sendMessage("Please specify a volume (0-150).").queue();
            return;
        }
        try {
            int vol = Integer.parseInt(args);
            if (vol < 0 || vol > 150) {
                event.getChannel().sendMessage("Volume must be between 0 and 150.").queue();
                return;
            }
            var player = musicManager.getPlayer(guild);
            player.setVolume(vol);
            event.getChannel().sendMessage("Volume set to " + vol).queue();
        } catch (NumberFormatException e) {
            event.getChannel().sendMessage("Invalid volume. Please specify a number between 0 and 150.").queue();
        }
    }
}
