package com.moneybags.tempfly;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.moneybags.tempfly.fly.FlightResult;
import com.moneybags.tempfly.fly.Flyer;
import com.moneybags.tempfly.hook.region.CompatRegion;
import com.moneybags.tempfly.user.FlightUser;

@SuppressWarnings("deprecation")
public class TempFlyAPI {
	
	private TempFly tempfly;
	
	public TempFlyAPI(TempFly tempfly) {
		this.tempfly = tempfly;
	}
	
	/**
	 * @param player Player uuid
	 * @return The amount of flight in seconds a player has.
	 */
	public double getFlightTime(UUID player) {
		return tempfly.getTimeManager().getTime(player);
	}
	
	/**
	 * Set the flight time of a player in seconds.
	 * @param player Player uuid
	 */
	public void setFlightTime(UUID player, double seconds) {
		tempfly.getTimeManager().setTime(player, seconds);
	}
	
	/**
	 * Add flight time in seconds
	 * @param player Player uuid
	 * @param seconds Seconds to give the player
	 */
	public void addFlightTime(UUID player, double seconds) {
		tempfly.getTimeManager().addTime(player, seconds);
	}
	
	/**
	 * Remove flight time from the player.
	 * @param player Player uuid
	 * @param seconds Seconds to remove from the player
	 */
	public void removeFlightTime(UUID player, double seconds) {
		tempfly.getTimeManager().removeTime(player, seconds);
	}
	
	/**
	 * @deprecated Since 3.0, checking if fly is allowed at a given location should now be done with the
	 * methods that includes the player as a parameter. This is due to the integration of
	 * conditional requirements based on factors about the player other than just the location.
	 * 
	 * The method will still work as it used to by checking disabled regions and worlds,
	 * but that is it.
	 * 
	 * @param loc The location to check
	 * @return True if flight is allowed at the given location.
	 */
	@Deprecated
	public boolean canFlyAt(Location loc) {
		return tempfly.getFlightManager().getFlightEnvironment().flyAllowed(loc);
	}
	
	/**
	 * Check if a player is allowed to fly at the given location. This will check all worlds and regions,
	 * 
	 * @param p The player
	 * @param loc The location to check.
	 * @return
	 */
	public List<FlightResult> canFlyAt(Player p, Location loc) {
		return tempfly.getFlightManager().inquireFlight(getUser(p), loc);
	}
	
	/**
	 * Check if a player is allowed to fly in the given world.
	 * 
	 * @param p The player
	 * @param loc The location to check.
	 * @param invokeHooks Do you want to check flight conditions implemented by internal gameplay hooks?
	 * @return
	 */
	public List<FlightResult> canFlyAy(Player p, World world) {
		return tempfly.getFlightManager().inquireFlight(getUser(p), world);
	}
	
	/**
	 * Check if a player is allowed to fly in the given region.
	 * 
	 * @param p The player
	 * @param loc The location to check.
	 * @param invokeHooks Do you want to check flight conditions implemented by internal gameplay hooks?
	 * @return
	 */
	public List<FlightResult> canFlyAt(Player p, CompatRegion region) {
		return tempfly.getFlightManager().inquireFlight(getUser(p), region);
	}
	
	/**
	 * Check if a player is allowed to fly in the given ApplicableRegionSet.
	 * 
	 * @param p The player
	 * @param loc The location to check.
	 * @param invokeHooks Do you want to check flight conditions implemented by internal gameplay hooks?
	 * @return
	 */
	public List<FlightResult> canFlyAt(Player p, CompatRegion[] regions) {
		return tempfly.getFlightManager().inquireFlight(getUser(p), regions);
	}
	
	/**
	 * 
	 * @param p
	 * @return
	 */
	public boolean canCurrentlyFly(Player p) {
		return getUser(p).hasFlightRequirements();
	}
	
	/**
	 * Deprecated as of TempFly 3.0. You should now use FlightUser.
	 * This method will emulate the old function of the flyer object for legacy hooks with tempfly.
	 * In old tempfly the flyer object was instantiated when flight was enabled then destroyed when flight was disabled.
	 * Now every player has a persistent FlightUser object where flight can be enabled and disabled.
	 * @return All flyer objects for players using TempFly.
	 */
	@Deprecated
	public Flyer[] getAllFlyers() {
		List<Flyer> flyers = new ArrayList<>();
		for (FlightUser user: tempfly.getFlightManager().getUsers()) {
			if (user.getPlayer().isFlying()) {
				flyers.add(new Flyer(user));
			}
		}
		return flyers.toArray(new Flyer[flyers.size()]);
	}
	
	/**
	 * Deprecated as of TempFly 3.0. You should now use FlightUser.
	 * This method will emulate the old function of the flyer object for legacy hooks with tempfly.
	 * In old tempfly the flyer object was instantiated when flight was enabled then destroyed when flight was disabled.
	 * Now every player has a persistent FlightUser object where flight can be enabled and disabled.
	 * @return The flyer object if the player is flying. Null if the player is not flying.
	 */
	@Deprecated
	public Flyer getFlyer(Player p) {
		FlightUser user = getUser(p);
		return user.getPlayer().isFlying() ? new Flyer(user) : null;
	}
	
	/**
	 * Get the FlightUser object for a player.
	 * @param p The player
	 * @return The players FlightUser
	 */
	public FlightUser getUser(Player p) {
		return tempfly.getFlightManager().getUser(p);
	}
	
	/**
	 * Get the FlightUser object for all players.
	 * @return All registered FlightUsers
	 */
	public FlightUser[] getUsers() {
		return tempfly.getFlightManager().getUsers();
	}
	
	
	/**
	 * 
	 * @param p Player to toggle
	 * @param enabled Toggle enabled true or false
	 * @param fallDamage If true the player will take fall damage when their flight is toggled off.
	 */
	public void toggleTempfly(Player p, boolean enabled, boolean fallDamage) {
		FlightUser user = getUser(p);
		if (enabled) {
			user.disableFlight(1, !fallDamage);
			user.setAutoFly(false);
		} else {
			user.enableFlight();
		}
	}
	
	/**
	 * Force TempFly to process a combat tag.
	 * This will use all the settings in the config like normal combat.
	 * Useful for plugins that manually handle entity damage and do not
	 * allow entities to directly harm each other. For instance, if a plugin
	 * has a custom health system and deals damage on combat with .damage(), TempFly
	 * would have no way to know this combat occured unless you use this.
	 * 
	 * @param victim The entity that got attacked
	 * @param actor The attacking entity
	 */
	public void processCombat(Entity victim, Entity actor) {
		tempfly.getFlightManager().getCombatHandler().processCombat(victim, actor);
	}
} 
