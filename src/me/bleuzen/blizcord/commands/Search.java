package me.bleuzen.blizcord.commands;

import me.bleuzen.blizcord.AudioPlayerThread;
import me.bleuzen.blizcord.Bot;
import me.bleuzen.blizcord.Values;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.User;

class Search extends Command {

	@Override
	public String getName() {
		return "search";
	}

	@Override
	public void execute(String arg, User author, MessageChannel channel, Guild guild) {
		if(arg == null) {
			channel.sendMessage(author.getAsMention() + " ``Please specify a video title. Put it behind this command.``").queue();
			return;
		}

		Bot.joinVoiceChannel(); // try to join if not already

		if(Bot.joined) { // if successfully joined
			AudioPlayerThread.loadAndPlay(channel, (Values.SEARCH_PREFIX_YOUTUBE + arg), true, false); // uses the "ytsearch:" prefix of lavaplayer
		}
	}

}