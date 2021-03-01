package org.gestern.gringotts;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.EbeanServerFactory;
import com.avaje.ebean.config.DataSourceConfig;
import com.avaje.ebean.config.ServerConfig;
import com.avaje.ebean.config.dbplatform.SQLitePlatform;
import com.avaje.ebeaninternal.api.SpiEbeanServer;
import com.avaje.ebeaninternal.server.ddl.DdlGenerator;
import com.avaje.ebeaninternal.server.lib.sql.TransactionIsolation;
import net.milkbowl.vault.economy.Economy;
import org.apache.commons.lang.NullArgumentException;
import org.apache.commons.lang.Validate;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.gestern.gringotts.accountholder.AccountHolder;
import org.gestern.gringotts.accountholder.AccountHolderFactory;
import org.gestern.gringotts.accountholder.AccountHolderProvider;
import org.gestern.gringotts.api.Eco;
import org.gestern.gringotts.api.dependency.Dependency;
import org.gestern.gringotts.api.dependency.DependencyProvider;
import org.gestern.gringotts.api.impl.GringottsEco;
import org.gestern.gringotts.api.impl.ReserveConnector;
import org.gestern.gringotts.api.impl.VaultConnector;
import org.gestern.gringotts.commands.GringottsExecutor;
import org.gestern.gringotts.commands.MoneyAdminExecutor;
import org.gestern.gringotts.commands.MoneyExecutor;
import org.gestern.gringotts.currency.Denomination;
import org.gestern.gringotts.data.DAO;
import org.gestern.gringotts.data.DerbyDAO;
import org.gestern.gringotts.data.EBeanDAO;
import org.gestern.gringotts.data.Migration;
import org.gestern.gringotts.dependency.DependencyProviderImpl;
import org.gestern.gringotts.dependency.GenericDependency;
import org.gestern.gringotts.event.AccountListener;
import org.gestern.gringotts.event.PlayerVaultListener;
import org.gestern.gringotts.event.VaultCreator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.gestern.gringotts.Configuration.CONF;
import static org.gestern.gringotts.Language.LANG;

/**
 * The type Gringotts.
 *
 * @editor iavengetheweak
 */
public class Gringotts extends JavaPlugin {
	private static final String MESSAGES_YML = "messages.yml";
	private static Gringotts instance;

	private final AccountHolderFactory accountHolderFactory = new AccountHolderFactory();
	private final DependencyProvider dependencies = new DependencyProviderImpl(this);
	private Accounting accounting;
	private DAO dao;
	private EbeanServer ebean;
	private Eco eco;

	/**
	 * Instantiates a new Gringotts.
	 */
	public Gringotts() {
		ServerConfig dbConfig = new ServerConfig();

		dbConfig.setDefaultServer(false);
		dbConfig.setRegister(false);
		dbConfig.setClasses(getDatabaseClasses());
		dbConfig.setName(getDescription().getName());

		configureDbConfig(dbConfig);

		DataSourceConfig dsConfig = dbConfig.getDataSourceConfig();

		dsConfig.setUrl(replaceDatabaseString(dsConfig.getUrl()));
		getDataFolder().mkdirs();

		ClassLoader previous = Thread.currentThread().getContextClassLoader();

		Thread.currentThread().setContextClassLoader(getClassLoader());
		ebean = EbeanServerFactory.create(dbConfig);
		Thread.currentThread().setContextClassLoader(previous);
	}

	/**
	 * The Gringotts plugin instance.
	 *
	 * @return the instance
	 */
	public static Gringotts getInstance() {
		return instance;
	}

	/**
	 * On enable.
	 */
	@Override
	public void onEnable() {
		instance = this;

		try {
			// just call DAO once to ensure it's loaded before startup is complete
			dao = getDAO();

			// load and init configuration
			saveDefaultConfig(); // saves default configuration if no config.yml exists yet
			reloadConfig();

			accounting = new Accounting();
			eco = new GringottsEco();

			if (!(this.dependencies.hasDependency("vault") ||
					this.dependencies.hasDependency("reserve"))) {
				Bukkit.getPluginManager().disablePlugin(this);

				getLogger().warning(
						"Neither Vault or Reserve was found. Other plugins may not be able to access Gringotts accounts."
				);

				return;
			}

			this.dependencies.onEnable();

			registerCommands();
			registerEvents();

			if (this.dependencies.hasDependency("vault")) {
				getServer().getServicesManager().register(
						Economy.class,
						new VaultConnector(),
						this,
						ServicePriority.Highest
				);

				getLogger().info("Registered Vault interface.");
			}

			if (this.dependencies.hasDependency("reserve")) {
				ReserveConnector.registerProviderSafely();

				getLogger().info("Registered Reserve interface.");
			}

			registerMetrics();
		} catch (GringottsStorageException | GringottsConfigurationException e) {
			getLogger().severe(e.getMessage());
			this.disable();
		} catch (RuntimeException e) {
			this.disable();
			throw e;
		}

		getLogger().fine("enabled");
	}

	private void disable() {
		Bukkit.getPluginManager().disablePlugin(this);
		getLogger().warning("Gringotts disabled due to startup errors.");
	}

	@Override
	public void onLoad() {
		this.registerGenericDependency(
				"vault",
				"Vault",
				"net.milkbowl.vault.Vault",
				"1.5.0"
		);
		this.registerGenericDependency(
				"reserve",
				"Reserve",
				"net.tnemc.core.Reserve",
				"0.1.4.6"
		);

		this.dependencies.onLoad();
	}

	/**
	 * Register generic dependency.
	 *
	 * @param id         the id
	 * @param name       the name
	 * @param classPath  the class path
	 * @param minVersion the min version
	 */
	private void registerGenericDependency(@NotNull String id,
										   @NotNull String name,
										   @NotNull String classPath,
										   @NotNull String minVersion) {
		try {
			if (!this.dependencies.registerDependency(new GenericDependency(
					this.dependencies.hookPlugin(
							name,
							classPath,
							minVersion
					),
					id
			))) {
				getLogger().warning(
						name + " plugin is already assigned into the dependencies."
				);
			}
		} catch (NullArgumentException ignored) {
		} catch (IllegalArgumentException e) {
			getLogger().warning(
					String.format(
							"Looks like %1$s plugin is not compatible with Gringotts's code.",
							name
					)
			);
		}
	}

	/**
	 * Gets dependencies.
	 *
	 * @return the dependencies
	 */
	public DependencyProvider getDependencies() {
		return dependencies;
	}

	/**
	 * On disable.
	 */
	@Override
	public void onDisable() {
		this.dependencies.onDisable();

		// shut down db connection
		try {
			if (dao != null) {
				dao.shutdown();
			}
		} catch (GringottsStorageException e) {
			getLogger().severe(e.toString());
		}

		getLogger().info("disabled");
	}

	private void registerMetrics() {
		// Setup Metrics support.
		Metrics metrics = new Metrics(this, 4998);

		// Tracking how many vaults exists.
		metrics.addCustomChart(new Metrics.SingleLineChart("vaultsChart", () -> {
			int returned = 0;

			for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
				AccountHolder holder = accountHolderFactory.get(player);
				GringottsAccount account = accounting.getAccount(holder);

				returned += Gringotts.getInstance().getDao().retrieveChests(account).size();
			}

			return returned;
		}));

		// Tracking the balance of the users exists.
		metrics.addCustomChart(new Metrics.SingleLineChart("economyChart", () -> {
			int returned = 0;

			for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
				if (!player.hasPlayedBefore()) {
					continue;
				}
				if (player.isOp()) {
					continue;
				}

				AccountHolder holder = accountHolderFactory.get(player);
				GringottsAccount account = accounting.getAccount(holder);

				returned += account.getBalance();
			}

			return returned;
		}));

		// Tracking the exists denominations.
		metrics.addCustomChart(new Metrics.AdvancedPie("denominationsChart", () -> {
			Map<String, Integer> returned = new HashMap<>();

			for (Denomination denomination : CONF.getCurrency().getDenominations()) {
				String name = denomination.getKey().type.getType().name();

				if (!returned.containsKey(name)) {
					returned.put(name, 0);
				}

				returned.put(name, returned.get(name) + 1);
			}

			return returned;
		}));

		metrics.addCustomChart(new Metrics.DrilldownPie("dependencies", () -> {
			Map<String, Map<String, Integer>> returned = new HashMap<>();

			for (Dependency dependency : this.dependencies) {
				if (dependency.isEnabled()) {
					String name = dependency.getName();
					String version = dependency.getVersion();

					if (name != null && version != null) {
						returned.put(name, new HashMap<String, Integer>() {{
							put(version, 1);
						}});
					}
				}
			}

			return returned;
		}));
	}

	private void registerCommands() {
		registerCommand(new String[]{"balance", "money"}, new MoneyExecutor());
		registerCommand("moneyadmin", new MoneyAdminExecutor());
		registerCommand("gringotts", new GringottsExecutor(this));
	}

	private boolean registerCommand(@NotNull String[] names, @NotNull TabExecutor executor) {
		boolean returned = true;

		for (String name : names) {
			if (!registerCommand(name, executor)) {
				returned = false;
			}
		}

		return returned;
	}

	private boolean registerCommand(@NotNull String name, @NotNull TabExecutor executor) {
		PluginCommand pluginCommand = getCommand(name);

		if (pluginCommand == null) {
			getLogger().warning(String.format(
					"Looks like the command '%1$s' is not available. Please be sure that Gringotts is the only plugin using it.",
					name
			));

			return false;
		}

		pluginCommand.setExecutor(executor);
		pluginCommand.setTabCompleter(executor);

		return true;
	}

	private void registerEvents() {
		PluginManager manager = getServer().getPluginManager();

		manager.registerEvents(new AccountListener(), this);
		manager.registerEvents(new PlayerVaultListener(), this);
		manager.registerEvents(new VaultCreator(), this);

		// listeners for other account types are loaded with dependencies
	}

	/**
	 * Register an accountholder provider with Gringotts.
	 * This is necessary for Gringotts to find and create
	 * account holders of any non-player type. Registering
	 * a provider for the same type twice will overwrite
	 * the previously registered provider.
	 *
	 * @param type     type id for an account type
	 * @param provider provider for the account type
	 */
	public void registerAccountHolderProvider(String type, AccountHolderProvider provider) {
		accountHolderFactory.registerAccountHolderProvider(type, provider);
	}

	/**
	 * Get the configured player interaction messages.
	 *
	 * @return the configured player interaction messages
	 */
	public FileConfiguration getMessages() {
		String langPath = String.format("i18n/messages_%s.yml", CONF.language);

		// try configured language first
		InputStream langStream = getResource(langPath);
		FileConfiguration conf;

		if (langStream != null) {
			Reader langReader = new InputStreamReader(langStream, StandardCharsets.UTF_8);
			conf = YamlConfiguration.loadConfiguration(langReader);
		} else {
			// use custom/default
			File langFile = new File(getDataFolder(), MESSAGES_YML);
			conf = YamlConfiguration.loadConfiguration(langFile);
		}

		return conf;
	}

	/**
	 * Reload config.
	 * <p>
	 * override to handle custom config logic and language loading
	 */
	@Override
	public void reloadConfig() {
		super.reloadConfig();
		CONF.readConfig(getConfig());
		LANG.readLanguage(getMessages());
	}

	/**
	 * Save default config.
	 */
	@Override
	public void saveDefaultConfig() {
		super.saveDefaultConfig();
		File defaultMessages = new File(getDataFolder(), MESSAGES_YML);

		if (!defaultMessages.exists()) {
			saveResource(MESSAGES_YML, false);
		}
	}

	private DAO getDAO() {
		setupEBean();

		// legacy support: migrate derby if it hasn't happened yet
		// automatically migrate derby to eBeans if db exists and migration flag hasn't been set
		Migration migration = new Migration();

		DerbyDAO derbyDAO;
		if (!migration.isDerbyMigrated() && (derbyDAO = DerbyDAO.getDao()) != null) {
			getLogger().info("Derby database detected. Migrating to Bukkit-supported database ...");

			EBeanDAO eBeanDAO = EBeanDAO.getDao();
			migration.doDerbyMigration(derbyDAO, eBeanDAO);
		}

		if (!migration.isUUIDMigrated()) {
			getLogger().info("Player database not migrated to UUIDs yet. Attempting migration");

			migration.doUUIDMigration();
		}

		return EBeanDAO.getDao();
	}

	/**
	 * Gets database classes.
	 *
	 * @return the database classes
	 */
	public List<Class<?>> getDatabaseClasses() {
		return EBeanDAO.getDatabaseClasses();
	}

	/**
	 * Some awkward ritual that Bukkit requires to initialize all the DB classes.
	 * Does nothing if they have already been set up.
	 */
	private void setupEBean() {
		try {
			EbeanServer db = getDatabase();

			for (Class<?> c : getDatabaseClasses()) {
				db.find(c).findRowCount();
			}
		} catch (Exception ignored) {
			getLogger().info("Initializing database tables.");

			installDDL();
		}
	}

	/**
	 * Gets the {@link EbeanServer} tied to this plugin.
	 * <p>
	 * <i>For more information on the use of <a href="http://www.avaje.org/">
	 * Avaje Ebeans ORM</a>, see <a href="http://www.avaje.org/ebean/documentation.html">
	 * Avaje Ebeans Documentation
	 * </a></i>
	 * <p>
	 * <i>For an example using Ebeans ORM, see <a
	 * href="https://github.com/Bukkit/HomeBukkit">Bukkit's Homebukkit Plugin
	 * </a></i>
	 *
	 * @return ebean server instance or null if not enabled all EBean related methods has been removed with Minecraft 1.12 - see https://www.spigotmc.org/threads/194144/
	 */
	public EbeanServer getDatabase() {
		return ebean;
	}

	/**
	 * Install ddl.
	 */
	protected void installDDL() {
		SpiEbeanServer server = (SpiEbeanServer) getDatabase();
		DdlGenerator gen = server.getDdlGenerator();

		gen.runScript(false, gen.generateCreateDdl());
	}

//    /**
//     * Remove ddl.
//     */
//    protected void removeDDL() {
//        SpiEbeanServer server = (SpiEbeanServer) getDatabase();
//        DdlGenerator gen = server.getDdlGenerator();
//
//        gen.runScript(true, gen.generateDropDdl());
//    }

	private String replaceDatabaseString(String input) {
		input = input.replaceAll(
				"\\{DIR}",
				getDataFolder().getPath().replaceAll("\\\\", "/") + "/");
		input = input.replaceAll(
				"\\{NAME}",
				getDescription().getName().replaceAll("[^\\w_-]", ""));

		return input;
	}

	/**
	 * Configure db config.
	 *
	 * @param config the config
	 */
	public void configureDbConfig(ServerConfig config) {
		Validate.notNull(config, "Config cannot be null");

		DataSourceConfig ds = new DataSourceConfig();
		ds.setDriver("org.sqlite.JDBC");
		ds.setUrl("jdbc:sqlite:{DIR}{NAME}.db");
		ds.setUsername("bukkit");
		ds.setPassword("walrus");
		ds.setIsolationLevel(TransactionIsolation.getLevel("SERIALIZABLE"));

		if (ds.getDriver().contains("sqlite")) {
			config.setDatabasePlatform(new SQLitePlatform());
			config.getDatabasePlatform().getDbDdlSyntax().setIdentity("");
		}

		config.setDataSourceConfig(ds);
	}

	/**
	 * Gets dao.
	 *
	 * @return the dao
	 */
	public DAO getDao() {
		return dao;
	}

	/**
	 * The account holder factory is the place to go if you need an AccountHolder instance for an id.
	 *
	 * @return the account holder factory
	 */
	public AccountHolderFactory getAccountHolderFactory() {
		return accountHolderFactory;
	}

	/**
	 * Manages accounts.
	 *
	 * @return the accounting
	 */
	public Accounting getAccounting() {
		return accounting;
	}

	/**
	 * Gets eco.
	 *
	 * @return the eco
	 */
	public Eco getEco() {
		return eco;
	}
}
