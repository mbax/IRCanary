import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IRCanary extends Plugin {
    private class IRCPluginListener extends PluginListener {
        private final IRCanary plugin;

        public IRCPluginListener(IRCanary plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onBan(Player admin, Player player, String reason) {
            if (IRCanary.this.allowKickBanUpdates) {
                this.plugin.sendIRCMessage(IRCanary.this.banMessage.replace("%admin", admin.getName()).replace("%player", player.getName()).replace("%reason", reason));
            }
        }

        @Override
        public boolean onChat(Player player, String message) {
            this.plugin.sendIRCMessage("<" + player.getName() + ">" + " " + message);
            return false;
        }

        @Override
        public boolean onCommand(Player player, String[] split) {
            if (split[0].equalsIgnoreCase("/me")) {
                String message = "";
                for (int $x = 1; $x < split.length; $x++) {
                    message = message + " " + split[$x];
                }
                this.plugin.sendIRCMessage("* " + player.getName() + message);
            }
            if ((split[0].equalsIgnoreCase("/ircreload")) && (player.canUseCommand("/ircreload"))) {
                this.plugin.loadAdmins();
                player.sendMessage("§c[IRCanary] IRC admins reloaded");
                return true;
            }
            return false;
        }

        @Override
        public void onDisconnect(Player player) {
            if (IRCanary.this.allowJoinQuitUpdates) {
                this.plugin.sendIRCMessage(player.getName() + " left the server");
            }
        }

        @Override
        public void onKick(Player admin, Player player, String reason) {
            if (IRCanary.this.allowKickBanUpdates) {
                this.plugin.sendIRCMessage(IRCanary.this.kickMessage.replace("%admin", admin.getName()).replace("%player", player.getName()).replace("%reason", reason));
            }
        }

        @Override
        public void onLogin(Player player) {
            if (IRCanary.this.allowJoinQuitUpdates) {
                this.plugin.sendIRCMessage(player.getName() + " logged in");
            }
        }
    }

    private ArrayList<IRCAdmin> admins;
    private boolean allowJoinQuitUpdates;
    private boolean allowKickBanUpdates;
    private String banMessage;
    private IRCBot bot;
    private String botAuthLine;
    private boolean botEnabled;
    private int characterLimit;
    private char commandPrefix;
    private boolean debugMode;
    private boolean echoIRCMessages;
    private String ircChannel;
    private String[] ircNameSeparator;
    private String ircUserColor;
    private String kickMessage;
    private final IRCPluginListener listener = new IRCPluginListener(this);
    private Logger log;
    private boolean msgCommandRequired;
    private String nickname;
    private String serverHost;
    private int serverPort;
    private final Object syncAdmins = new Object();
    private final String version = "0.9.9";

    @Override
    public void disable() {
        if (this.bot != null) {
            this.botEnabled = false;
            this.bot.disconnect();
        }
    }

    @Override
    public void enable() {
        this.log = Logger.getLogger("Minecraft");
        final File dir = new File("IRCanary");
        if (!dir.exists()) {
            dir.mkdir();
        }
        try {
            final PropertiesFile ircProperties = new PropertiesFile("IRCanary/config.properties");
            this.serverHost = ircProperties.getString("server-host", "localhost");
            this.serverPort = ircProperties.getInt("server-port", 6667);
            this.nickname = ircProperties.getString("bot-nickname", "aMinecraftBot");
            this.ircChannel = ircProperties.getString("irc-channel", "#minecraftbot");
            this.ircUserColor = ircProperties.getString("irc-usercolor", "f");
            this.ircNameSeparator = ircProperties.getString("irc-separator", "<,>").split(",");
            this.characterLimit = ircProperties.getInt("charlimit", 390);
            this.msgCommandRequired = ircProperties.getBoolean("msg-command-required", false);
            this.echoIRCMessages = ircProperties.getBoolean("repeat-relay-back", false);
            this.debugMode = ircProperties.getBoolean("debug-spam-mode", false);
            this.botAuthLine = ircProperties.getString("auth-message", "");
            this.commandPrefix = ircProperties.getString("irc-command-prefix", ".").charAt(0);
            this.allowJoinQuitUpdates = ircProperties.getBoolean("send-join-quit-to-IRC", true);
            this.allowKickBanUpdates = ircProperties.getBoolean("send-kick-ban-to-IRC", true);
            this.kickMessage = ircProperties.getString("kick-formatting", "%player kicked (\"%reason%\")");
            this.banMessage = ircProperties.getString("ban-formatting", "%player banned (\"%reason%\")");
        } catch (final Exception e) {
            this.log.log(Level.SEVERE, "[IRCanary] Exception while reading from irc.properties", e);
        }
        this.botEnabled = true;
        this.bot = new IRCBot(this.nickname, this.msgCommandRequired, this.characterLimit, this.ircUserColor, this.echoIRCMessages, this.ircNameSeparator, this, this.commandPrefix);
        if (this.debugMode) {
            this.bot.setVerbose(true);
        }
        this.resetBot();
        this.loadAdmins();
        this.log.log(Level.INFO, "[IRCanary] Version " + this.version + " enabled!");
    }

    @Override
    public void initialize() {
        etc.getLoader().addListener(PluginLoader.Hook.CHAT, this.listener, this, PluginListener.Priority.MEDIUM);
        etc.getLoader().addListener(PluginLoader.Hook.COMMAND, this.listener, this, PluginListener.Priority.MEDIUM);
        etc.getLoader().addListener(PluginLoader.Hook.LOGIN, this.listener, this, PluginListener.Priority.MEDIUM);
        etc.getLoader().addListener(PluginLoader.Hook.DISCONNECT, this.listener, this, PluginListener.Priority.MEDIUM);
        etc.getLoader().addListener(PluginLoader.Hook.KICK, this.listener, this, PluginListener.Priority.MEDIUM);
        etc.getLoader().addListener(PluginLoader.Hook.BAN, this.listener, this, PluginListener.Priority.MEDIUM);
    }

    private void loadAdmins() {
        final String adminFileName = "IRCanary/IRCAdmins.txt";
        if (!new File(adminFileName).exists()) {
            FileWriter writer = null;
            try {
                writer = new FileWriter(adminFileName);
                writer.write("#Add IRC admins here.\r\n");
                writer.write("#The format is:\r\n");
                writer.write("#NAME:PASSWORD:ACCESSLEVEL\r\n");
                writer.write("#Access levels: 2=kick,ban 3=everything");
                writer.write("#Example:\r\n");
                writer.write("#notch:iminurbox:3\r\n");
            } catch (final Exception e) {
                this.log.log(Level.SEVERE, "[IRCanary] Exception while creating " + adminFileName, e);
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (final IOException e2) {
                    this.log.log(Level.SEVERE, "[IRCanary] Exception while closing writer for " + adminFileName, e);
                }
            } finally {
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (final IOException e) {
                    this.log.log(Level.SEVERE, "[IRCanary] Exception while closing writer for " + adminFileName, e);
                }
            }
        }
        synchronized (this.syncAdmins) {
            this.admins = new ArrayList<IRCAdmin>();
            try {
                final Scanner scanner = new Scanner(new File(adminFileName));
                while (scanner.hasNextLine()) {
                    final String line = scanner.nextLine();
                    if ((line.startsWith("#")) || (line.equals("")) || (line.startsWith("﻿"))) {
                        continue;
                    }
                    final String[] split = line.split(":");
                    if (split.length != 3) {
                        continue;
                    }
                    final IRCAdmin admin = new IRCAdmin(split[0], split[1], Integer.parseInt(split[2]));
                    this.admins.add(admin);
                }
                scanner.close();
            } catch (final Exception e) {
                this.log.log(Level.SEVERE, "[IRCanary] Exception while reading " + adminFileName + " (Are you sure you formatted it correctly?)", e);
            }
        }
    }

    private void sendIRCMessage(String message) {
        this.bot.sendMessage(this.ircChannel, message);
    }

    protected boolean authenticate(String sender, String name, String pass, String host) {
        boolean success = false;
        synchronized (this.syncAdmins) {
            for (final IRCAdmin admin : this.admins) {
                if ((admin != null) && (admin.getUsername().equalsIgnoreCase(name)) && (admin.auth(pass, host))) {
                    this.log.log(Level.INFO, "[IRCanary] IRC admin " + admin.getUsername() + " logged in  (" + host + ")");
                    success = true;
                } else {
                    this.log.log(Level.INFO, "[IRCanary] IRC admin failed login. user[" + name + "] pass[" + pass + "] nick[" + sender + "] host[" + host + "]");
                }
            }
        }
        return success;
    }

    protected boolean ircCommandAttempt(String host, String[] command) {
        int lvl = 0;
        String adminName = "";
        synchronized (this.syncAdmins) {
            for (final IRCAdmin admin : this.admins) {
                if ((admin != null) && (admin.getHostname().equals(host))) {
                    lvl = admin.getAccessLevel();
                    adminName = admin.getUsername();
                }
            }
        }
        if (command[0].charAt(0) == this.commandPrefix) {
            command[0] = command[0].substring(1);
        }
        if ((lvl == 0) || ((lvl == 2) && (!command[0].equalsIgnoreCase("kick")) && (!command[0].equalsIgnoreCase("ban")))) {
            return false;
        }
        final String commands = etc.combineSplit(0, command, " ");
        if (etc.getInstance().parseConsoleCommand(commands, etc.getMCServer())) {
            return true;
        }
        etc.getServer().useConsoleCommand(commands);
        this.log.log(Level.INFO, "[IRCanary] IRC admin " + adminName + "(" + host + ") used command: " + commands);
        return true;
    }

    protected void resetBot() {
        if (this.botEnabled) {
            this.bot.disconnect();
            System.out.println("[IRCanary] Joining " + this.ircChannel + " on " + this.serverHost + ":" + this.serverPort + " as " + this.nickname);
            try {
                this.bot.connect(this.serverHost, this.serverPort);
            } catch (final Exception e) {
                e.printStackTrace();
            }
            if (!this.botAuthLine.equals("")) {
                final String[] split = this.botAuthLine.split(" ");
                if (split.length > 1) {
                    final String to = split[0];
                    final String msg = etc.combineSplit(1, split, " ");
                    this.bot.sendMessage(to, msg);
                }
            }
            this.bot.joinChannel(this.ircChannel);
            this.bot.sendMessage(this.ircChannel, "Never fear, a minecraft bot is here!");
        }
    }

}
