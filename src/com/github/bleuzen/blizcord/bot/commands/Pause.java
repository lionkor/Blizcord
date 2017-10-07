package com.github.bleuzen.blizcord.bot.commands;

import com.github.bleuzen.blizcord.bot.AudioPlayerThread;

import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.User;

class Pause extends Command {

	@Override
	public String getName() {
		return "pause";
	}

	@Override
	public boolean isAdminOnly() {
		return true;
	}

	@Override
	public void execute(String arg, User author, MessageChannel channel, Guild guild) {
		if(AudioPlayerThread.isPaused()) {
			channel.sendMessage("``Continue playback ...``").queue();
			AudioPlayerThread.setPaused(false);
		} else {
			AudioPlayerThread.setPaused(true);
			channel.sendMessage("``Paused.\n"
					+ "Type this command again to resume.``").queue();
		}
	}

}