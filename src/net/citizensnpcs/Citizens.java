package net.citizensnpcs;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.exception.NPCException;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.trait.Character;
import net.citizensnpcs.api.npc.trait.DefaultInstanceFactory;
import net.citizensnpcs.api.npc.trait.Trait;
import net.citizensnpcs.api.npc.trait.trait.Inventory;
import net.citizensnpcs.api.npc.trait.trait.Owner;
import net.citizensnpcs.api.npc.trait.trait.SpawnLocation;
import net.citizensnpcs.api.npc.trait.trait.Spawned;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.DatabaseStorage;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.command.CommandManager;
import net.citizensnpcs.command.Injector;
import net.citizensnpcs.command.command.AdminCommands;
import net.citizensnpcs.command.command.HelpCommands;
import net.citizensnpcs.command.command.NPCCommands;
import net.citizensnpcs.command.exception.CommandUsageException;
import net.citizensnpcs.command.exception.NoPermissionsException;
import net.citizensnpcs.command.exception.RequirementMissingException;
import net.citizensnpcs.command.exception.ServerCommandException;
import net.citizensnpcs.command.exception.UnhandledCommandException;
import net.citizensnpcs.command.exception.WrappedCommandException;
import net.citizensnpcs.npc.CitizensNPCManager;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.Sneak;
import net.citizensnpcs.util.Messaging;
import net.citizensnpcs.util.Metrics;
import net.citizensnpcs.util.StringHelper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;

public class Citizens extends JavaPlugin {
    private static final String COMPATIBLE_MC_VERSION = "1.1";

    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Trait>> defaultTraits = Lists.newArrayList(Owner.class, Spawned.class,
            LookClose.class, SpawnLocation.class, Inventory.class, Sneak.class);

    private volatile CitizensNPCManager npcManager;
    private final DefaultInstanceFactory<Character> characterManager = new DefaultInstanceFactory<Character>();
    private final DefaultInstanceFactory<Trait> traitManager = new DefaultInstanceFactory<Trait>();
    private final CommandManager commands = new CommandManager();
    private Settings config;
    private Storage saves;
    private boolean compatible;
    private File installDirectory = new File("");
    private File savesFile = new File("saves");

    private boolean suggestClosestModifier(CommandSender sender, String command, String modifier) {
        int minDist = Integer.MAX_VALUE;
        String closest = "";
        for (String string : commands.getAllCommandModifiers(command)) {
            int distance = StringHelper.getLevenshteinDistance(modifier, string);
            if (minDist > distance) {
                minDist = distance;
                closest = string;
            }
        }
        if (!closest.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Unknown command. Did you mean:");
            sender.sendMessage(StringHelper.wrap(" /") + command + " " + StringHelper.wrap(closest));
            return true;
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String cmdName, String[] args) {
        Player player = null;
        if (sender instanceof Player)
            player = (Player) sender;

        try {
            // must put command into split.
            String[] split = new String[args.length + 1];
            System.arraycopy(args, 0, split, 1, args.length);
            split[0] = cmd.getName().toLowerCase();

            String modifier = args.length > 0 ? args[0] : "";

            if (!commands.hasCommand(split[0], modifier) && !modifier.isEmpty()) {
                return suggestClosestModifier(sender, split[0], modifier);
            }

            NPC npc = null;
            if (player != null)
                npc = npcManager.getSelectedNPC(player);

            try {
                commands.execute(split, player, player == null ? sender : player, npc);
            } catch (ServerCommandException ex) {
                sender.sendMessage("You must be in-game to execute that command.");
            } catch (NoPermissionsException ex) {
                Messaging.sendError(player, "You don't have permission to execute that command.");
            } catch (CommandUsageException ex) {
                Messaging.sendError(player, ex.getMessage());
                Messaging.sendError(player, ex.getUsage());
            } catch (RequirementMissingException ex) {
                Messaging.sendError(player, ex.getMessage());
            } catch (WrappedCommandException e) {
                throw e.getCause();
            } catch (UnhandledCommandException e) {
                return false;
            }
        } catch (NumberFormatException e) {
            Messaging.sendError(player, "That is not a valid number.");
        } catch (Throwable excp) {
            excp.printStackTrace();
            Messaging.sendError(player, "Please report this error: [See console]");
            Messaging.sendError(player, excp.getClass().getName() + ": " + excp.getMessage());
        }
        return true;
    }

    @Override
    public void onDisable() {
        // Don't bother with this part if MC versions are not compatible
        if (compatible) {
            config.save();
            saveNPCs();
            for (NPC npc : npcManager)
                npc.despawn();
            Bukkit.getScheduler().cancelTasks(this);
        }

        Messaging.log("v" + getDescription().getVersion() + " disabled.");
    }

    @Override
    public void onEnable() {
        // Disable if the server is not using the compatible Minecraft version
        compatible = ((CraftServer) getServer()).getServer().getVersion().startsWith(COMPATIBLE_MC_VERSION);
        String mcVersion = ((CraftServer) getServer()).getServer().getVersion();
        if (!compatible) {
            Messaging.log(Level.SEVERE, "v" + getDescription().getVersion() + " is not compatible with Minecraft v"
                    + mcVersion + ". Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Set the root folder for Citizens.
        setInstallDirectoty(getDataFolder());

        // Configuration file
        config = new Settings(this);
        config.load();

        // NPC storage
        if (Setting.USE_DATABASE.asBoolean()) {
            try {
                saves = new DatabaseStorage(Setting.DATABASE_DRIVER.asString(), Setting.DATABASE_URL.asString(),
                        Setting.DATABASE_USERNAME.asString(), Setting.DATABASE_PASSWORD.asString());
            } catch (SQLException e) {
                Messaging.log("Unable to connect to database, falling back to YAML");
                saves = new YamlStorage(savesFile.getAbsolutePath(), "Citizens NPC Storage");
            }
        } else {
            saves = new YamlStorage(savesFile.getAbsolutePath(), "Citizens NPC Storage");
        }

        // Register API managers
        npcManager = new CitizensNPCManager(saves);
        CitizensAPI.setNPCManager(npcManager);
        CitizensAPI.setCharacterManager(characterManager);
        CitizensAPI.setTraitManager(traitManager);

        // Register events
        getServer().getPluginManager().registerEvents(new EventListen(npcManager), this);

        // Register commands
        registerCommands();

        // Register default traits
        traitManager.registerAll(defaultTraits);

        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new NPCUpdater(npcManager), 0, 1);

        Messaging.log("v" + getDescription().getVersion() + " enabled.");

        // Setup NPCs after all plugins have been enabled (allows for multiworld
        // support and for NPCs to properly register external settings)
        if (Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            @Override
            public void run() {
                try {
                    setupNPCs();
                } catch (NPCLoadException ex) {
                    Messaging.log(Level.SEVERE, "Issue when loading NPCs: " + ex.getMessage());
                }
            }
        }) == -1) {
            Messaging.log(Level.SEVERE, "Issue enabling plugin. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
        }

        // Run metrics last
        new Thread() {
            @Override
            public void run() {
                try {
                    Metrics metrics = new Metrics();
                    metrics.addCustomData(Citizens.this, new Metrics.Plotter() {
                        @Override
                        public int getValue() {
                            return Iterators.size(npcManager.iterator());
                        }

                        @Override
                        public String getColumnName() {
                            return "Total NPCs";
                        }
                    });
                    metrics.beginMeasuringPlugin(Citizens.this);
                } catch (IOException ex) {
                    Messaging.log("Unable to load metrics");
                }
            }
        }.start();
    }

    public CitizensNPCManager getNPCManager() {
        return npcManager;
    }

    public DefaultInstanceFactory<Character> getCharacterManager() {
        return characterManager;
    }

    public CommandManager getCommandManager() {
        return commands;
    }

    public Storage getStorage() {
        return saves;
    }

    private void registerCommands() {
        commands.setInjector(new Injector(this));

        // Register command classes
        commands.register(AdminCommands.class);
        commands.register(NPCCommands.class);
        commands.register(HelpCommands.class);
    }

    private void saveNPCs() {
        for (NPC npc : npcManager)
            npc.save(saves.getKey("npc." + npc.getId()));
        saves.save();
    }

    private void setupNPCs() throws NPCLoadException {
        int created = 0, spawned = 0;
        for (DataKey key : saves.getKey("npc").getIntegerSubKeys()) {
            int id = Integer.parseInt(key.name());
            if (!key.keyExists("name"))
                throw new NPCLoadException("Could not find a name for the NPC with ID '" + id + "'.");

            String type = key.getString("traits.type").toUpperCase();
            NPC npc = npcManager.createNPC(type.equalsIgnoreCase("DEFAULT") ? null : CreatureType.valueOf(type), id,
                    key.getString("name"), null);
            try {
                npc.load(key);
            } catch (NPCException ex) {
                Messaging.log(ex.getMessage());
            }

            ++created;
            if (npc.isSpawned())
                ++spawned;
        }
        Messaging.log("Loaded " + created + " NPCs (" + spawned + " spawned).");
    }

    /**
     * Set the root folder Citizens uses.
     * @return 
     */
    public File getInstallDirectory() {
        return installDirectory;
    }

    /**
     * Set the root folder Citizens uses.
     * @param installDirectory 
     */
    public void setInstallDirectoty(File installDirectory) {
        this.installDirectory = installDirectory;
        this.savesFile = new File(installDirectory, "saves.yml");
    }
}