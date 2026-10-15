package com.dslrcamera.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class DslrCameraPlugin extends JavaPlugin implements Listener {
	public static final String CHANNEL = "dslrcamera:photo";
	public static final String EDITOR_CHANNEL = "dslrcamera:editor";

	private CameraItem cameraItem;
	private PhotoItems photoItems;
	private NamespacedKey recipeKey;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		cameraItem = new CameraItem(this);
		photoItems = new PhotoItems(this);
		recipeKey = new NamespacedKey(this, "camera");

		PhotoStore store = new PhotoStore(this);
		EditorSessions editors = new EditorSessions(this, store);
		getServer().getPluginManager().registerEvents(store, this);
		getServer().getPluginManager().registerEvents(editors, this);
		getServer().getPluginManager().registerEvents(new FrameWallListener(this), this);
		getServer().getPluginManager().registerEvents(this, this);
		getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, new PhotoReceiver(this, store, editors));
		getServer().getMessenger().registerOutgoingPluginChannel(this, EDITOR_CHANNEL);

		if (getConfig().getBoolean("camera-recipe", true)) {
			Bukkit.removeRecipe(recipeKey);
			ShapedRecipe recipe = new ShapedRecipe(recipeKey, cameraItem.create());
			recipe.shape(" I ", "ISI", " R ");
			recipe.setIngredient('I', Material.IRON_INGOT);
			recipe.setIngredient('S', Material.SPYGLASS);
			recipe.setIngredient('R', Material.REDSTONE);
			Bukkit.addRecipe(recipe);
		}
	}

	@Override
	public void onDisable() {
		Bukkit.removeRecipe(recipeKey);
		getServer().getMessenger().unregisterIncomingPluginChannel(this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this);
	}

	CameraItem cameraItem() {
		return cameraItem;
	}

	PhotoItems photoItems() {
		return photoItems;
	}

	/** The item consumed when printing, or null if printing is free. */
	Material printCostItem() {
		return Material.matchMaterial(getConfig().getString("consume-item", ""));
	}

	int printCost(int tiles) {
		return getConfig().getInt("consume-per-tile", 1) * tiles;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		if (Bukkit.getRecipe(recipeKey) != null) {
			event.getPlayer().discoverRecipe(recipeKey);
		}
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
		if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
			sender.sendMessage("Usage: /" + label + " give [player]");
			return true;
		}
		Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
		if (target == null) {
			sender.sendMessage("Player not found.");
			return true;
		}
		target.getInventory().addItem(cameraItem.create()).values()
			.forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
		sender.sendMessage("Gave a camera to " + target.getName() + ".");
		return true;
	}
}
