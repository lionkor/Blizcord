import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Timer;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.output.FileWriterWithEncoding;

import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.JDAInfo;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.entities.VoiceChannel;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import net.dv8tion.jda.core.managers.GuildController;

public class Bot extends ListenerAdapter {

	private static JDA api;
	private static Guild guild;
	private static Role adminRole;
	private static TextChannel controlChannel;

	private static UpdateChecker updateChecker;

	static boolean joined;

	static JDA getApi() {
		return api;
	}

	static Guild getGuild() {
		return guild;
	}

	static TextChannel getControlChannel() {
		return controlChannel;
	}

	public static void start() {

		// config got loaded in a

		if(Config.get(Config.BOT_TOKEN).isEmpty()) {
			a.errExit("You must specify a Token in the config file!");
		}

		Log.print("Starting JDA ...");

		try {
			api = new JDABuilder(AccountType.BOT).setToken(Config.get(Config.BOT_TOKEN))
					//.setEnableShutdownHook(false) // default: true
					.buildBlocking();
			api.addEventListener(new Bot());

			// test for only one server
			int guilds = api.getGuilds().size();

			if(guilds == 0) {
				a.addToServerMessage(api.asBot().getInviteUrl());

			} else if(guilds > 1) {
				a.errExit("The bot is on more than 1 server. This is currently not supported.");
			}

			guild = api.getGuilds().get(0);

			try {
				GuildController controller = guild.getController();
				if(guild.getTextChannelsByName(Config.get(Config.CONTROL_CHANNEL), true).isEmpty()) { // create channel if not exists
					controller.createTextChannel(Config.get(Config.CONTROL_CHANNEL)).complete();
					Log.print("Created control channel.");
				}
				if(guild.getVoiceChannelsByName(Config.get(Config.VOICE_CHANNEL), true).isEmpty()) {
					controller.createVoiceChannel(Config.get(Config.VOICE_CHANNEL)).setBitrate(Values.DISCORD_MAX_BITRATE).complete();
					Log.print("Created music channel.");
				}
			} catch(Exception e) {
				Log.print("Failed to create channels. Give me the permission to manage channels or create them yourself.");
			}

			try {
				controlChannel = guild.getTextChannelsByName(Config.get(Config.CONTROL_CHANNEL), true).get(0); // true for Ignore Case
			} catch(IndexOutOfBoundsException e) {
				a.errExit("There is no '" + Config.get(Config.CONTROL_CHANNEL) + "' Text Channel.");
			}

			String adminsRoleName = Config.get(Config.ADMINS_ROLE);
			if(!adminsRoleName.isEmpty()) {
				try {
					adminRole = guild.getRolesByName(adminsRoleName, true).get(0); // true for Ignore Case
				} catch(IndexOutOfBoundsException e) {
					a.errExit("There is no '" + adminsRoleName + "' Role.");
				}
			}


			// Init Player
			PlayerThread.init();

			// Start game update thread
			if(Boolean.valueOf(Config.get(Config.DISPLAY_SONG_AS_GAME))) {
				new Thread(new PlayerThread()).start();
			}

			// Start checking for updates
			int updateCheckInterval;
			try {
				updateCheckInterval= Integer.parseInt(Config.get(Config.UPDATE_CHECK_INTERVAL_HOURS));
			} catch (NumberFormatException e) {
				updateCheckInterval = 0;
			}
			if(updateCheckInterval > 0) {
				// First update check delayed 5 seconds, then all updateCheckInterval hours
				updateChecker = new UpdateChecker();
				new Timer().schedule(updateChecker, 5000, (1000 * 3600 * updateCheckInterval));
			}

			Log.print("Successfully started.");
		} catch (Exception e) {
			a.errExit(e.getMessage());
		}

		controlChannel.sendMessage(Values.BOT_NAME + " " + Values.BOT_VERSION + " started.\nType ``" + Config.get(Config.COMMAND_PREFIX) + "help`` to see all of my commands.").queue();
	}

	static void shutdown() {
		Log.print("Shutting down ...");
		//api.shutdown(); // done by shutdown hook of JDA
		System.exit(0);
	}

	private static void join() {
		if (!joined) {
			String cName = Config.get(Config.VOICE_CHANNEL);
			VoiceChannel channel = guild.getVoiceChannels().stream().filter(vChan -> vChan.getName().equalsIgnoreCase(cName)).findFirst().orElse(null);
			try {
				// for multi server: GuildMessageReceivedEvent#event.getGuild()
				guild.getAudioManager().openAudioConnection(channel);
				joined = true;

				if(channel.getBitrate() != Values.DISCORD_MAX_BITRATE) {
					controlChannel.sendMessage(guild.getOwner().getAsMention() + " Hint: You should set your channel's bitrate to " + (Values.DISCORD_MAX_BITRATE / 1000) + "kbps (highest) if you want to listen to music.").queue();
				}
			} catch(Exception e) {
				controlChannel.sendMessage("Failed to join voice channel: " + cName + "\n"
						+ "Please check your config and give me the permission to join it.").queue();
			}
		}
	}

	static void leave() {
		// only defined guild, for one server
		guild.getAudioManager().closeAudioConnection();
		joined = false;
	}

	private static boolean isAdmin(User user) {
		return user.getId().equals(guild.getOwner().getUser().getId()) || (adminRole != null && guild.getMember(user).getRoles().contains(adminRole));
	}


	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		String message = event.getMessage().getContent();
		MessageChannel channel = event.getChannel();
		User author = event.getAuthor();

		if ( (channel == controlChannel || channel.getType() == ChannelType.PRIVATE) && message.startsWith(Config.get(Config.COMMAND_PREFIX)) && (!author.getId().equals(api.getSelfUser().getId())) ) {

			String[] cmdarg = message.substring(Config.get(Config.COMMAND_PREFIX).length()).split(" ", 2);
			String cmd = cmdarg[0].toLowerCase();
			String arg;
			try {
				arg = cmdarg[1];
			} catch (IndexOutOfBoundsException e) {
				arg = null;
			}

			switch (cmd) {
			case "help":
				channel.sendMessage(author.getAsMention() + " **Commands:**\n"
						+ "```"
						+ "!list                           (Show the playlist)\n"
						+ "!play <file or link>            (Play given track now)\n"
						+ "!add <file, folder or link>     (Add given track to playlist)\n"
						+ "!save <name>                    (Save the current playlist)\n"
						+ "!load <name>                    (Load a saved playlist)\n"
						+ "!pause                          (Pause or resume the current track)\n"
						+ "!skip (<how many songs>)        (Skip one or more songs from the playlist)\n"
						+ "!goto <hours:minutes:seconds>   (Go to a given time)\n"
						+ "!jump (<how many seconds>)      (Jump forward in the current track)\n"
						+ "!repeat (<how many times>)      (Repeat the current playlist)\n"
						+ "!stop                           (Stop the playback and clear the playlist)\n"
						+ "!version                        (Print versions)\n"
						+ "!kill                           (Kill the bot)"
						+ "```"
						+ (channel.getType() == ChannelType.PRIVATE ? ("\n**Guild:** " + guild.getName()) : "") ).queue();

				break;


			case "kill":
				if(isAdmin(author)) {
					channel.sendMessage("Bye").complete(); // complete(): block this thread (send the message first, than shutdown)
					shutdown();
				} else {
					channel.sendMessage(author.getAsMention() + " ``Only admins can kill me.``").queue();
				}

				break;


			case "skip":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Only admins can skip.``").queue();
					return;
				}

				if(!PlayerThread.isPlaying()) {
					channel.sendMessage(author.getAsMention() + " ``Currently I'm not playing.``").queue();
					return;
				}

				int skips;
				if (arg == null) {
					skips = 1;
				} else {
					try {
						skips = Integer.parseInt(arg);
						if (skips < 1) {
							throw new NumberFormatException();
						}
					} catch (NumberFormatException e) {
						channel.sendMessage(author.getAsMention() + " Invalid number").queue();
						return;
					}
				}

				PlayerThread.getMusicManager().scheduler.nextTrack(skips);

				break;


			case "goto":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Only admins can use this command.``").queue();
					return;
				}

				if(!PlayerThread.isPlaying()) {
					channel.sendMessage(author.getAsMention() + " ``Currently I'm not playing.``").queue();
					return;
				}

				if(arg == null) {
					channel.sendMessage(author.getAsMention() + " ``Please specify a time. Put it behind this command. Split hours, minutes and seconds with ':'. Hours and minutes are optional.``").queue();
					return;
				}

				long ms = -1; // invalid by default
				try {
					int c = arg.length() - arg.replace(":", "").length();
					if(c == 2) {
						// hours, minutes and seconds
						String[] split = arg.split(":");
						ms = timeToMS(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2]));
					} else if(c == 1) {
						// minutes and seconds
						String[] split = arg.split(":");
						ms = timeToMS(0, Integer.parseInt(split[0]), Integer.parseInt(split[1]));
					} else if(c == 0) {
						// only seconds
						ms = timeToMS(0, 0, Integer.parseInt(arg));
					}

					if(ms < 0) {
						throw new NumberFormatException();
					}
				} catch(Exception e) {
					channel.sendMessage(author.getAsMention() +  " Invalid time").queue();
					return;
				}

				PlayerThread.getMusicManager().player.getPlayingTrack().setPosition(ms);

				break;


			case "jump":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Only admins can jump.``").queue();
					return;
				}

				int seconds;
				if(arg == null) {
					seconds = 10;
				} else {
					try {
						seconds = Integer.parseInt(arg);
						if(seconds == 0) {
							throw new NumberFormatException();
						}
					} catch(NumberFormatException e) {
						channel.sendMessage(author.getAsMention() +  " Invalid number").queue();
						return;
					}
				}

				AudioTrack track = PlayerThread.getMusicManager().player.getPlayingTrack();
				track.setPosition(track.getPosition() + (1000*seconds)); // Lavaplayer handles values < 0 or > track length

				break;


			case "repeat":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Sorry, only admins can use the repeat command.``").queue();
					return;
				}

				int repeats;
				if(arg == null) {
					repeats = 1;
				} else {
					try {
						repeats = Integer.parseInt(arg);
						if(repeats < 1) {
							throw new NumberFormatException();
						}
					} catch(NumberFormatException e) {
						channel.sendMessage(author.getAsMention() +  " Invalid number").queue();
						return;
					}
				}

				if(PlayerThread.isPlaying()) {

					ArrayList<AudioTrack> songs = new ArrayList<>();
					songs.add(PlayerThread.getMusicManager().player.getPlayingTrack());
					ArrayList<AudioTrack> upcoming = PlayerThread.getMusicManager().scheduler.getList();
					if(!upcoming.isEmpty()) {
						for(int i = 0; i < upcoming.size(); i++) {
							songs.add(upcoming.get(i));
						}
					}

					for(int i = 0; i < repeats; i++) {
						for(int j = 0; j < songs.size(); j++) {
							PlayerThread.play(songs.get(j).makeClone());
						}
					}

					channel.sendMessage( "``Repeated the playlist" + (repeats == 1 ? ".``" : (" " + repeats + " times.``") )).queue();
				} else {
					channel.sendMessage(author.getAsMention() +  " ``The playlist is empty. There is nothing to repeat.``").queue();
				}

				break;


			case "list":
				PlayerThread.sendPlaylist(author, channel);

				break;


			case "pause":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Only admins can pause me.``").queue();
					return;
				}

				if(PlayerThread.isPaused()) {
					channel.sendMessage("Continue playback ...").queue();
					PlayerThread.setPaused(false);
				} else {
					PlayerThread.setPaused(true);
					channel.sendMessage("Paused").queue();
				}

				break;


			case "stop":
				if(isAdmin(author)) {
					// stop the music
					PlayerThread.stop();
					// leave the channel
					leave();
					// cancel skipping
					PlayerThread.skipping = false;
					// clear the playlist
					PlayerThread.getMusicManager().scheduler.clear();
				} else {
					channel.sendMessage(author.getAsMention() + " ``Only admins can stop me.``").queue();
				}

				break;


			case "version":
				channel.sendMessage(author.getAsMention() + "\n"
						+ "``"
						+ Values.BOT_NAME + ": " + Values.BOT_VERSION
						+ "\n"
						+ "JDA: " + JDAInfo.VERSION
						+ "\n"
						+ "Lavaplayer: " + PlayerLibrary.VERSION
						+ "``").queue();

				if(updateChecker != null && updateChecker.isUpdateAvailable()) {
					sendUpdateMessage();
				}

				break;


			case "add":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Sorry, only admins can add something.``").queue();
					return;
				}

				if(arg == null) {
					channel.sendMessage(author.getAsMention() + " ``Please specify what I should add to the playlist. Put it behind this command.``").queue();
					return;
				}

				join(); // try to join if not already

				if(joined) { // if successfully joined

					File inputFile = new File(arg);

					if(inputFile.isDirectory()) {
						channel.sendMessage("Adding all supported files from folder to queue ...").queue();;
						File[] files = inputFile.listFiles();
						Arrays.sort(files);
						int addesFiles = 0;
						for(File f : files) {
							if(f.isFile()) {
								PlayerThread.loadAndPlay(channel, f.getAbsolutePath(), false, true);
							}
							addesFiles++;
						}
						channel.sendMessage(author.getAsMention() + " ``Added " + addesFiles + " files.``").queue();
					} else {
						PlayerThread.loadAndPlay(channel, arg, false, false);
					}

				}

				break;


			case "play":
				if(!isAdmin(author)) {
					channel.sendMessage(author.getAsMention() + " ``Sorry, only admins can play something.``").queue();
					return;
				}

				if(arg == null) {
					channel.sendMessage(author.getAsMention() + " ``Please specify what I should play. Put it behind this command.``").queue();
					return;
				}

				join(); // try to join if not already

				if(joined) { // if successfully joined
					PlayerThread.loadAndPlay(channel, arg, true, false);
				}

				break;


			case "save":
				// arg = playlist name
				if(arg == null) {
					channel.sendMessage(author.getAsMention() + " ``Please specify a playlist name. Put it behind this command.``").queue();
					break;
				}
				if(!PlayerThread.isPlaying()) {
					channel.sendMessage(author.getAsMention() + " ``The playlist is empty, nothing to save.``").queue();
					break;
				}
				File playlistsFolder = new File("playlists");
				if(!playlistsFolder.isDirectory()) {
					if(!playlistsFolder.mkdir()) {
						channel.sendMessage(author.getAsMention() + " ``Failed to create playlists folder.``").queue();
						break;
					}
				}
				try {
					BufferedWriter writer = new BufferedWriter(new FileWriterWithEncoding(new File(playlistsFolder, arg), Charset.forName("UTF-8"), false));
					// Write currently playing track
					writer.write(PlayerThread.getMusicManager().player.getPlayingTrack().getInfo().uri);
					writer.newLine();
					// Write upcoming tracks
					ArrayList<AudioTrack> upcoming = PlayerThread.getMusicManager().scheduler.getList();
					if(!upcoming.isEmpty()) {
						for(int i = 0; i < upcoming.size(); i++) {
							writer.write(upcoming.get(i).getInfo().uri);
							writer.newLine();
						}
					}
					// Save
					writer.close();

					channel.sendMessage(author.getAsMention() + " Playlist saved: " + arg).queue();
				} catch (Exception e) {
					channel.sendMessage(author.getAsMention() + " ``Failed to save playlist.``").queue();
				}

				break;


			case "load":
				// arg = playlist name
				if(arg == null) {
					channel.sendMessage(author.getAsMention() + " ``Please specify a playlist name. Put it behind this command.``").queue();
					break;
				}
				// Load the playlist
				try {
					File playlistFile = new File(new File("playlists"), arg);
					if(!playlistFile.exists()) {
						channel.sendMessage(author.getAsMention() + " Playlist doesn't exist: " + arg).queue();
						break;
					}

					join(); // try to join if not already

					BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(playlistFile), "UTF-8"));
					String line;
					while((line = bufferedReader.readLine()) != null) {
						PlayerThread.loadAndPlay(channel, line, false, true);
					}
					bufferedReader.close();

					channel.sendMessage(author.getAsMention() + " Playlist loaded: " + arg).queue();
				} catch (Exception e) {
					channel.sendMessage(author.getAsMention() + " Failed to load playlist: " + arg).queue();
				}

				break;


			default:
				channel.sendMessage(author.getAsMention() + " ``Unknown command``").queue();
				break;
			}

		}
	}

	static long timeToMS(int hours, int minutes, int seconds) {
		if(seconds > 59 || seconds < 0) {
			return -1;
		}
		if(minutes > 59 || minutes < 0) {
			return -1;
		}

		long s = (seconds + (60 * (minutes + (hours * 60))));
		return TimeUnit.SECONDS.toMillis(s);
	}

	static void setGame(Game game) {
		api.getPresence().setGame(game);
		//System.out.println("GAME UPDATE: " + game);
	}

	static void sendUpdateMessage() {
		controlChannel.sendMessage("A new version is available!\n"
				+ "https://github.com/" + Values.BOT_GITHUB_REPO + "/releases").queue();
	}

	static String getTrackName(AudioTrack track) {
		String sourceName = track.getSourceManager().getSourceName();
		if(sourceName.equals("local") || sourceName.equals("http")) {
			return track.getIdentifier();
		} else {
			return track.getInfo().title;
		}
	}

}

