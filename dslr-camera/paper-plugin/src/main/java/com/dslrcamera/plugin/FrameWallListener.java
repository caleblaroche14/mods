package com.dslrcamera.plugin;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/** Right-clicking the top-left frame of a wall of empty item frames with a multi-tile photo hangs the whole photo. */
final class FrameWallListener implements Listener {
	private final DslrCameraPlugin plugin;

	FrameWallListener(DslrCameraPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onFrameClick(PlayerInteractEntityEvent event) {
		if (!(event.getRightClicked() instanceof ItemFrame clicked)) {
			return;
		}
		Player player = event.getPlayer();
		ItemStack held = player.getInventory().getItem(event.getHand());
		PhotoItems.FramedPhoto photo = plugin.photoItems().readFramed(held);
		if (photo == null) {
			return;
		}
		// Never let the preview map itself go into a frame.
		event.setCancelled(true);

		BlockFace facing = clicked.getFacing();
		BlockFace right = rightOf(facing);
		if (right == null) {
			player.sendActionBar(Component.text("Big photos can only be hung on walls.", NamedTextColor.RED));
			return;
		}

		List<ItemFrame> frames = new ArrayList<>(photo.cols() * photo.rows());
		Block topLeft = clicked.getLocation().getBlock();
		for (int row = 0; row < photo.rows(); row++) {
			for (int col = 0; col < photo.cols(); col++) {
				Block block = topLeft.getRelative(right, col).getRelative(BlockFace.DOWN, row);
				ItemFrame frame = frameAt(block, facing);
				if (frame == null || !frame.getItem().getType().isAir()) {
					player.sendActionBar(Component.text("Need a " + photo.cols() + "×" + photo.rows()
						+ " wall of empty item frames, starting from the top-left one you clicked.", NamedTextColor.RED));
					return;
				}
				frames.add(frame);
			}
		}

		for (int i = 0; i < frames.size(); i++) {
			MapView view = plugin.getServer().getMap(photo.tileMapIds()[i]);
			if (view == null) {
				player.sendActionBar(Component.text("This photo's maps are missing.", NamedTextColor.RED));
				return;
			}
			ItemFrame frame = frames.get(i);
			frame.setItem(plugin.photoItems().tile(view, photo.cols(), photo.rows()), false);
			frame.setRotation(Rotation.NONE);
		}
		if (player.getGameMode() != GameMode.CREATIVE) {
			held.subtract();
		}
		player.sendActionBar(Component.text("Photo hung!", NamedTextColor.GREEN));
		plugin.getLogger().info(player.getName() + " hung a " + photo.cols() + "x" + photo.rows() + " photo at "
			+ topLeft.getX() + "," + topLeft.getY() + "," + topLeft.getZ());
	}

	/** The viewer's right-hand direction when looking at a wall frame that faces {@code facing}. */
	private static BlockFace rightOf(BlockFace facing) {
		return switch (facing) {
			case NORTH -> BlockFace.WEST;
			case SOUTH -> BlockFace.EAST;
			case EAST -> BlockFace.NORTH;
			case WEST -> BlockFace.SOUTH;
			default -> null;
		};
	}

	private static ItemFrame frameAt(Block block, BlockFace facing) {
		Location center = block.getLocation().add(0.5, 0.5, 0.5);
		for (ItemFrame frame : block.getWorld().getNearbyEntitiesByType(ItemFrame.class, center, 0.75)) {
			if (frame.getFacing() == facing && frame.getLocation().getBlock().equals(block)) {
				return frame;
			}
		}
		return null;
	}
}
