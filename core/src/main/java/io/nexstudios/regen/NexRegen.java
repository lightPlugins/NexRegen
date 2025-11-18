package io.nexstudios.regen;

import io.nexstudios.nexus.bukkit.database.api.DbAsyncHelper;
import io.nexstudios.nexus.bukkit.database.api.NexusDatabaseService;
import io.nexstudios.nexus.bukkit.files.NexusFile;
import io.nexstudios.nexus.bukkit.files.NexusFileReader;
import io.nexstudios.nexus.bukkit.handler.MessageSender;
import io.nexstudios.nexus.bukkit.language.NexusLanguage;
import io.nexstudios.nexus.bukkit.utils.NexusLogger;
import io.nexstudios.nexus.libs.commands.PaperCommandManager;
import io.nexstudios.regen.regen.RegenFactory;
import io.nexstudios.regen.regen.RegenReader;
import io.nexstudios.regen.regen.commands.ReloadCommand;
import io.nexstudios.regen.regen.impl.*;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.DatabaseMetaData;
import java.util.List;

@Getter
public class NexRegen extends JavaPlugin {

    @Getter
    public static NexRegen instance;
    public static NexusLogger nexusLogger;
    public PaperCommandManager commandManager;
    public NexusFile settingsFile;
    public NexusFileReader languageFiles;
    public NexusFileReader regenFiles;
    public NexusLanguage nexusLanguage;
    public MessageSender messageSender;
    private DbAsyncHelper dbHelper;
    private NexusDatabaseService db;

    public RegenReader regenReader;
    public RegenFactory regenFactory;

    @Override
    public void onLoad() {
        instance = this;
        if (!checkPluginRequirements()) {
            return;
        }
        nexusLogger = new NexusLogger("<reset>[<green>NexRegen<reset>]", false, 99, "<green>");
        nexusLogger.info("Loading <green>NexRegen <reset>...");
    }

    @Override
    public void onEnable() {
        nexusLogger.info("Starting up ...");
        nexusLogger.info("Register commands ...");
        commandManager = new PaperCommandManager(this);
        nexusLogger.info("Load files and drop tables ...");
        reload();
        initDatabase();
        initDatabaseSchema();
        registerListeners();
        registerCommands();
        nexusLogger.info("Register events ...");
        nexusLogger.info("NexRegen has been enabled!");
    }

    @Override
    public void onDisable() {
        nexusLogger.info("Shutting down ...");

        if (dbHelper != null) {
            dbHelper.shutdown();
            nexusLogger.info("Database successfully shut down.");
        }
    }

    public void reload() {
        loadNexusFiles();
        messageSender = new MessageSender(nexusLanguage);
        regenReader.read();
    }

    private void registerCommands() {
        commandManager.registerCommand(new ReloadCommand());
        int size = commandManager.getRegisteredRootCommands().size();
        nexusLogger.info("Successfully registered " + size + " command(s).");
    }

    private void registerListeners() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        regenFactory = new RegenFactory(pluginManager, dbHelper);
        regenFactory.registerVanillaRegenHandler(new DefaultRegen());
        regenFactory.registerVanillaRegenHandler(new AgeableRegen());
        regenFactory.registerVanillaRegenHandler(new CocoaRegen());
        regenFactory.registerVanillaRegenHandler(new SugarCaneRegen());
        regenFactory.registerVanillaRegenHandler(new CactusRegen());
        regenFactory.registerVanillaRegenHandler(new BambooRegen());
        regenFactory.registerVanillaRegenHandler(new DoubleHeightRegen());
    }

    private void loadNexusFiles() {
        settingsFile = new NexusFile(this, "settings.yml", nexusLogger, true);
        // preload the default english language file.
        new NexusFile(this, "languages/english.yml", nexusLogger, true);
        nexusLogger.setDebugEnabled(settingsFile.getBoolean("logging.debug.enable", true));
        nexusLogger.setDebugLevel(settingsFile.getInt("logging.debug.level", 3));
        // initialize the NexusFileReader for languages.
        languageFiles = new NexusFileReader("languages", this);
        // Load all language files as FileConfigurations.
        nexusLanguage = new NexusLanguage(languageFiles, nexusLogger);
        regenFiles = new NexusFileReader("regenerators", this);
        // Load all regen files as FileConfigurations.
        regenReader = new RegenReader(regenFiles.getFiles(), nexusLogger);
        nexusLogger.info("All Nexus files have been (re)loaded successfully.");
    }

    private void initDatabase() {
        var reg = getServer().getServicesManager().getRegistration(NexusDatabaseService.class);
        if (reg == null) {
            nexusLogger.error("Could not find NexusDatabaseService. Regenerators will be disabled.");
            return;
        }
        this.db = reg.getProvider();
        if (!db.isHealthy()) {
            nexusLogger.error("Database unhealthy. Feature disabled.");
            return;
        }
        try {
            db.isHealthy();
        } catch (Exception e) {
            nexusLogger.error("Database health check failed: " + e.getMessage());
            return;
        }

        this.dbHelper = new DbAsyncHelper(db);
    }

    private void initDatabaseSchema() {
        // Dialekt ermitteln
        boolean sqlite = isSqlite();

        String sql;
        if (sqlite) {
            sql = "CREATE TABLE IF NOT EXISTS nexregen_active_regen (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "world TEXT NOT NULL," +
                    "x INTEGER NOT NULL," +
                    "y INTEGER NOT NULL," +
                    "z INTEGER NOT NULL," +
                    "original_material TEXT NOT NULL," +
                    "original_block_data TEXT," +
                    "replacement_material TEXT," +
                    "replacement_block_data TEXT," +
                    "regen_at INTEGER NOT NULL," +
                    "regen_id TEXT" +
                    ")";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS nexregen_active_regen (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "world VARCHAR(64) NOT NULL," +
                    "x INT NOT NULL," +
                    "y INT NOT NULL," +
                    "z INT NOT NULL," +
                    "original_material VARCHAR(64) NOT NULL," +
                    "original_block_data TEXT NULL," +
                    "replacement_material VARCHAR(64) NULL," +
                    "replacement_block_data TEXT NULL," +
                    "regen_at BIGINT NOT NULL," +
                    "regen_id VARCHAR(128) NULL" +
                    ")";
        }

        try {
            // einmalig, darf blockierend sein
            dbHelper.updateAsync(sql).get();
        } catch (Exception e) {
            nexusLogger.error(List.of(
                    "Failed to initialize database schema for NexRegen.",
                    e.getMessage()
            ));
        }
    }

    private boolean isSqlite() {
        try {
            // Analog zu deinem SqlDialectResolver
            return db.withConnection(connection -> {
                try {
                    DatabaseMetaData md = connection.getMetaData();
                    String product = safeLower(md.getDatabaseProductName());
                    String url = safeLower(md.getURL());
                    return product.contains("sqlite") || url.startsWith("jdbc:sqlite");
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            // Fallback: treat as MySQL/MariaDB
            return false;
        }
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private boolean checkPluginRequirements() {
        if (Bukkit.getPluginManager().getPlugin("Nexus") == null) {
            getLogger().severe("NexRegen requires the Nexus plugin to be installed and enabled.");
            getLogger().severe("Please download the plugin from https://www.spigotmc.org/resources/nexus.10000/");
            getLogger().severe("Disabling NexRegen ...");
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }
}