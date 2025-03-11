package com.example.musicbot.commands;

import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.entities.Guild;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.example.musicbot.MusicManager;
import com.example.musicbot.AudioPlayerSendHandler;
import com.example.musicbot.Bot;
import java.io.File;

public class LibraryMenuListener extends ListenerAdapter {
    private final MusicManager musicManager;
    
    public LibraryMenuListener(MusicManager musicManager) {
        this.musicManager = musicManager;
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals("library_menu")) {
            return; // Not our menu
        }
        
        String selectedFilePath = event.getValues().get(0);
        Guild guild = event.getGuild();
        
        if (guild == null || selectedFilePath == null || selectedFilePath.isEmpty()) {
            event.reply("Could not process selection.").setEphemeral(true).queue();
            return;
        }
        
        event.deferReply().queue();
        
        // Handle the file selection
        playLibraryFile(event, guild, selectedFilePath);
    }
    
    private void playLibraryFile(StringSelectInteractionEvent event, Guild guild, String filePath) {
        File fileToPlay = new File(filePath);
        if (!fileToPlay.exists()) {
            event.getHook().sendMessage("⚠️ Selected file no longer exists.").queue();
            return;
        }
        
        // Open audio connection if member is in a voice channel
        if (event.getMember().getVoiceState().getChannel() == null) {
            event.getHook().sendMessage("❌ You must be in a voice channel to play music!").queue();
            return;
        }
        
        guild.getAudioManager().openAudioConnection(event.getMember().getVoiceState().getChannel());
        var player = musicManager.getPlayer(guild);
        
        if (guild.getAudioManager().getSendingHandler() == null) {
            guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
        }
        
        // Load and play the file
        musicManager.getPlayerManager().loadItem(filePath, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (player.getPlayingTrack() != null) {
                    musicManager.queueTrack(guild, track);
                    event.getHook().sendMessage("🎵 Track queued: `" + track.getInfo().title + "`").queue();
                } else {
                    player.playTrack(track);
                    event.getHook().sendMessage("🎵 Now playing: `" + track.getInfo().title + "`").queue();
                }
            }
            
            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack track = playlist.getTracks().get(0);
                player.playTrack(track);
                event.getHook().sendMessage("🎵 Now playing: `" + track.getInfo().title + "` (from playlist)").queue();
            }
            
            @Override
            public void noMatches() {
                event.getHook().sendMessage("⚠️ No playable media found in the selected file.").queue();
            }
            
            @Override
            public void loadFailed(FriendlyException exception) {
                event.getHook().sendMessage("❌ Failed to load the selected file: " + exception.getMessage()).queue();
            }
        });
    }
}