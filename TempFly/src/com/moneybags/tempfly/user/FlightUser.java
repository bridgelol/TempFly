package com.moneybags.tempfly.user;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.moneybags.tempfly.aesthetic.ActionBarAPI;
import com.moneybags.tempfly.aesthetic.TitleAPI;
import com.moneybags.tempfly.aesthetic.particle.Particles;
import com.moneybags.tempfly.environment.RelativeTimeRegion;
import com.moneybags.tempfly.fly.FlightManager;
import com.moneybags.tempfly.fly.RequirementProvider;
import com.moneybags.tempfly.fly.RequirementProvider.InquiryType;
import com.moneybags.tempfly.fly.result.FlightResult;
import com.moneybags.tempfly.time.TimeManager;
import com.moneybags.tempfly.util.Console;
import com.moneybags.tempfly.util.U;
import com.moneybags.tempfly.util.V;
import com.moneybags.tempfly.util.data.DataBridge;
import com.moneybags.tempfly.util.data.DataPointer;
import com.moneybags.tempfly.util.data.DataBridge.DataValue;

public class FlightUser {

	
	
	private final FlightManager manager;
	private final TimeManager timeManager;
	private final Player p;
	private final UserEnvironment environment;
	
	//A list of reasons the player cannot currently fly.
	private Map<RequirementProvider, Map<InquiryType, FlightResult>> requirements = new ConcurrentHashMap<>();
	
	private BukkitTask
	initialTask, enforceTask, damageProtection;
	
	private TempFlyTimer timer;
	
	private String
	listName, tagName, particle;
	
	private boolean
	enabled, autoEnable,
	infinite = true,
	bypass = true;
	
	private int
	idle = -1;
	
	private double
	time;
	
	private long
	accumulativeCycle;
	
	
	public FlightUser(Player p, FlightManager manager) {
		this.manager = manager;
		this.timeManager = manager.getTempFly().getTimeManager();
		this.p = p;
		this.environment = new UserEnvironment(this);
		manager.updateLocation(this, p.getLocation(), p.getLocation(), true);

		final DataBridge bridge = manager.getTempFly().getDataBridge();
		
		this.time = timeManager.getTime(p.getUniqueId());
		this.listName = p.getPlayerListName();
		this.tagName = p.getDisplayName();
		this.particle = Particles.loadTrail(p.getUniqueId());
		this.infinite = (boolean) bridge.getOrDefault(DataPointer.of(DataValue.PLAYER_INFINITE, p.getUniqueId().toString()), true); 
		this.bypass = (boolean) bridge.getOrDefault(DataPointer.of(DataValue.PLAYER_BYPASS, p.getUniqueId().toString()), true); 
		
		boolean logged = (boolean) bridge.getOrDefault(DataPointer.of(DataValue.PLAYER_FLIGHT_LOG, p.getUniqueId().toString()), false);
		initialTask = new BukkitRunnable() {
			@Override
			public void run() {
				if (logged && (hasInfiniteFlight() || timeManager.getTime(p.getUniqueId()) > 0)) {
					if (!enableFlight()) {
						sendRequirementMessage();
						enforce(1);
					}
				} else {
					enforce(1);
					if (V.permaTimer) {
						if (timer != null) {
							timer.cancel();
						}
						timer = new FlightTimer();
					}
				}
				bridge.stageChange(DataPointer.of(DataValue.PLAYER_FLIGHT_LOG, p.getUniqueId().toString()), false);
			}
		}.runTaskLater(manager.getTempFly(), 1);
	}
	
	public void save() {
		Console.debug("", "-----< Save FlightUser: (" + p.getUniqueId().toString() + ") >-----");
		DataBridge bridge = manager.getTempFly().getDataBridge();
		UUID u = p.getUniqueId();
		bridge.manualCommit(
				DataPointer.of(DataValue.PLAYER_TIME, u.toString()),
				DataPointer.of(DataValue.PLAYER_DAILY_BONUS, u.toString()),
				DataPointer.of(DataValue.PLAYER_DAMAGE_PROTECTION, u.toString()),
				DataPointer.of(DataValue.PLAYER_FLIGHT_LOG, u.toString()),
				DataPointer.of(DataValue.PLAYER_TRAIL, u.toString()),
				DataPointer.of(DataValue.PLAYER_INFINITE, u.toString()),
				DataPointer.of(DataValue.PLAYER_BYPASS, u.toString()));
	}
	
	
	public FlightManager getFlightManager() {
		return manager;
	}
	
	public double getTime() {
		return time;
	}
	
	public Player getPlayer() {
		return p;
	}
	
	public void setTime(double time) {
		if (time <= 0) {
			time = 0;
		}
		this.time = time;
		manager.getTempFly().getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_TIME, p.getUniqueId().toString()), time);
		if ((timer instanceof FlightTimer) 
				&& !hasInfiniteFlight()
				&& p.isFlying()) {
			if (V.actionBar) {doActionBar();}
		}
		if (time > 0 && hasAutoFlyQueued() && !enabled) {
			enableFlight();
		} else if (time == 0) {
			disableFlight(0, !V.damageTime);
			autoEnable = true;
			if (timer != null) {
				timer.cancel();
			}
		} else if (V.permaTimer) {
			if (timer != null) {
				timer.cancel();
			}
			timer = new FlightTimer();	
		}
	}
	
	public void resetIdleTimer() {
		this.idle = -1;
	}
	
	public boolean isIdle() {
		return V.idleThreshold > -1 && idle >= V.idleThreshold;
	}
	
	public boolean hasFlightEnabled() {
		return enabled;
	}
	
	public boolean hasAutoFlyQueued() {
		return autoEnable && V.autoFly;
	}
	
	public void setAutoFly(boolean auto) {
		this.autoEnable = auto;
	}
	
	public boolean isOpenForSubmission() {
		return hasAutoFlyQueued() || hasFlightEnabled();
	}
	
	public UserEnvironment getEnvironment() {
		return environment;
	}
	
	/**
	 * @return true if the user has infinite flight and it is enabled.
	 */
	public boolean hasInfiniteFlight() {
		return p.hasPermission("tempfly.infinite") && infinite;
	}
	
	/**
	 * Set whether the user has infinite flight enabled. This has no effect if they do not have the permission tempfly.infinite
	 * @param enable enable infinite flight?
	 */
	public void setInfiniteFlight(boolean enable) {
		manager.getTempFly().getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_INFINITE, p.getUniqueId().toString()), enable);
		this.infinite = enable;
		if (!enable && V.actionBar && time > 0) {
			doActionBar();
		} else if (!enable && time <= 0) {
			disableFlight(0, !V.damageCommand);
			setAutoFly(true);
		} else if (enable && hasAutoFlyQueued()) {
			enableFlight();
		}
	} 
	
	/**
	 * @return true if the user has requirement bypass and it is enabled.
	 */
	public boolean hasRequirementBypass() {
		return p.hasPermission("tempfly.bypass") && bypass;
	}
	
	/**
	 * Set whether the user has requirement bypass enabled. This has no effect if they do not have the permission tempfly.bypass
	 * @param enable enable requirement bypass?
	 */
	public void setRequirementBypass(boolean enable) {
		manager.getTempFly().getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_BYPASS, p.getUniqueId().toString()), enable);
		this.bypass = enable;
		if (enable && hasAutoFlyQueued()) {
			enableFlight();
		} else if (!enable && hasFlightEnabled() && hasFlightRequirements()) {
			FlightResult result = getCurrentRequirement();
			U.m(p, result.getMessage());
			disableFlight(0, result.hasDamageProtection());
			setAutoFly(true);
		}
	}
	
	
	/**
	 * 
	 * --=--------------=--
	 *    Flight Control
	 * --=--------------=--
	 * 
	 */
	
	
	
	/**
	 * Internal clean up method called when the player quits or server is reloading.
	 */
	public void onQuit(boolean reload) {
		if (enabled || hasAutoFlyQueued()) {
			manager.getTempFly().getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_FLIGHT_LOG, p.getUniqueId().toString()), true);
			if (!reload) {disableFlight(-1, false);}
		}
		updateList(true);
		updateName(true);
		save();
		if (initialTask != null) {initialTask.cancel();}
		if (enforceTask != null) {enforceTask.cancel();}
		if (timer != null) {timer.cancel();}
		removeDamageProtection();
	}
	
	/**
	 * This is a safety method to make sure a players flight is disabled.
	 * This will only try to remove flight if the user has flight mode disabled but are flying anyway.
	 * 
	 * @param delay The delay in ticks to enforce removal of flight. 1 should suffice.
	 */
	public void enforce(int delay) {
		if (enforceTask != null) {
			enforceTask.cancel();
		}
		enforceTask = new BukkitRunnable() {
			@Override public void run() {
				// If the users flight is enabled again when the task runs we will return.
				if (enabled) return;
				GameMode m = p.getGameMode();
				if (!(m.equals(GameMode.CREATIVE)) && !(m.equals(GameMode.SPECTATOR))) {
					Console.debug("--- Enforcing disabled flight ----");
					p.setFlying(false);
					p.setAllowFlight(false);
				}
			}
		}.runTaskLater(manager.getTempFly(), delay);
	}
	
	/**
	 * Turn off players flight with a safety delay that will enforce proper removal of flight.
	 * @param delay The delay in ticks to enforce removal of flight. 1 should suffice. -1 for no enforcement
	 */
	public void disableFlight(int delay, boolean fallSafely) {
		if (!enabled) {return;}
		enabled = false;
		if (!V.permaTimer) {
			timer.cancel();
			timer = null;
		}
		GameMode m = p.getGameMode();
		updateList(true);
		updateName(true);
		// Fixes a weird bug where fall damage accumulates through flight and damages even when 1 block off the ground.
		if (p.isFlying()) {p.setFallDistance(0);}
		if (m != GameMode.CREATIVE && m != GameMode.SPECTATOR) {
			p.setFlying(false);
			p.setAllowFlight(false);
			if (fallSafely) {addDamageProtection();}
		}
		if (delay > -1) {enforce(delay);}
	}
	
	/**
	 * Enable the users flight
	 * @return false if the users flight can not be enabled due to flight requirements.
	 */
	public boolean enableFlight() {
		if (hasFlightRequirements() && !hasRequirementBypass()) {
			setAutoFly(true);
			return false;
		}
		if (time == 0 && !hasInfiniteFlight()) {
			setAutoFly(true);
			return false;
		}
		enabled = true;
		p.setAllowFlight(true);
		p.setFlying(!p.isOnGround());
		applySpeedCorrect();
		if (timer == null) {
			this.timer = p.isOnGround() && !V.permaTimer && !V.groundTimer ? new GroundTimer() : new FlightTimer();	
		}
		return true;
	}
	
	/**
	 * Methid to make sure a player can fly when they are supposed to.
	 */
	public void applyFlightCorrect() {
		new BukkitRunnable() {
			@Override
			public void run() {
				if (p.isOnline() && hasFlightEnabled()) {
					p.setAllowFlight(true);
					p.setFlying(true);
				}
			}
		}.runTaskLater(manager.getTempFly(), 1);
	}
	
	/**
	 * 
	 * --=-----------=--
	 *    Requirement
	 * --=-----------=--
	 * 
	 */
	
	
	
	public RequirementProvider[] getFlightRequirements() {
		return requirements.keySet().toArray(new RequirementProvider[requirements.size()]);
	}
	
	public boolean hasFlightRequirement(RequirementProvider requirement) {
		return requirements.containsKey(requirement);
	}
	
	public boolean hasFlightRequirement(RequirementProvider requirement, InquiryType type) {
		return requirements.getOrDefault(requirement, new HashMap<>()).containsKey(type);
	}
	
	public boolean hasFlightRequirements() {
		return requirements.size() > 0;
	}
	
	public void submitFlightRequirement(RequirementProvider requirement, FlightResult failedResult) {
		if (V.debug) {Console.debug("", "---- Submitting failed requirement to user ----", "--| Requirement: " + requirement.getClass().toGenericString(), "--| Requirements: " + requirements);}
		Map<InquiryType, FlightResult> types = requirements.getOrDefault(requirement, new HashMap<>());
		InquiryType type = failedResult.getInquiryType();
		if (types.containsKey(type)) {
			types.remove(type);
		}
		types.put(type, failedResult);
		this.requirements.put(requirement, types);
		if (enabled) {
			autoEnable = true;
		}
	}
	
	/**
	 * 
	 * @param requirement
	 * @param type
	 * @return true if there are no more requirements
	 */
	public boolean removeFlightRequirement(RequirementProvider requirement, InquiryType type) {
		if (V.debug) {Console.debug("", "---- Removing flight requirement from user ----", "--| Requirement: " + requirement.getClass().toGenericString(), "--| Requirements: " + requirements);}
		Map<InquiryType, FlightResult> types = requirements.getOrDefault(requirement, new HashMap<>());
		types.remove(type);
		if (types.size() == 0) {
			this.requirements.remove(requirement);
		} else {
			this.requirements.put(requirement, types);
		}
		return !hasFlightRequirements();
	}
	
	/**
	 * 
	 * @param requirement
	 * @param type
	 * @return true if there are no more requirements
	 */
	public boolean removeFlightRequirement(RequirementProvider requirement) {
		if (V.debug) {Console.debug("", "---- Removing flight requirement from user ----", "--| Requirement: " + requirement.getClass().toGenericString(), "--| Requirements: " + requirements);}
		this.requirements.remove(requirement);
		return !hasFlightRequirements();
	}
	
	public void removeFlightRequirements() {
		this.requirements.clear();
	}
	
	public void sendRequirementMessage() {
		if (hasFlightRequirements()) {
			// lmao whats this trash
			U.m(p, requirements.values().iterator().next().values().iterator().next().getMessage());
		}
	}
	
	public FlightResult getCurrentRequirement() {
		if (hasFlightRequirements()) {
			return requirements.values().iterator().next().values().iterator().next();
		}
		return null;
	}
	
	/**
	 * Quality of life method.
	 * Evaluate the overall flight status of the user, checks all flight requirements present on the server.
	 * Used mainly when a player first joins or for some reason is not being tracked and we need to re-check everything.
	 * Flight will be disabled if a requirement fails.
	 * The requirement will then be submitted to the user for the auto flight enable feature.
	 * 
	 * @return false if the user fails.
	 */
	public boolean evaluateFlightRequirements(Location loc, boolean failMessage) {
		List<FlightResult> results = new ArrayList<>();
		results.addAll(manager.inquireFlight(this, loc.getWorld()));
		results.addAll(manager.inquireFlight(this, loc));
		if (manager.getTempFly().getHookManager().hasRegionProvider()) {
			results.addAll(manager.inquireFlight(this, manager.getTempFly().getHookManager().getRegionProvider().getApplicableRegions(p.getLocation())));
		}
		submitFlightResults(results, false);
		if (hasFlightRequirements()) {
			if (!hasRequirementBypass() && failMessage) {
				sendRequirementMessage();
			}
			return false;
		}
		return true;
	}
	
	/**
	 * Evaluate the flight status of the user for the requirements introduced by the provider.
	 * Flight will be disabled if a requirement fails.
	 * The requirement will then be submitted to the user for the auto flight enable feature.
	 *
	 * @param failMessage Do you want the fail message to be sent to the user if they cannot fly? 
	 * @return false if the user fails.
	 */
	
	public boolean evaluateFlightRequirement(RequirementProvider requirement, Location loc) {
		List<FlightResult> results = new ArrayList<>();
		if (!requirement.handles(InquiryType.WORLD) && hasFlightRequirement(requirement, InquiryType.WORLD)) {
			results.add(requirement.handleFlightInquiry(this, loc.getWorld()));
		}
		if (!requirement.handles(InquiryType.LOCATION) && hasFlightRequirement(requirement, InquiryType.LOCATION)) {
			results.add(requirement.handleFlightInquiry(this, loc));
		}
		if (!requirement.handles(InquiryType.REGION) && hasFlightRequirement(requirement, InquiryType.REGION)
				&& manager.getTempFly().getHookManager().hasRegionProvider()) {
			results.add(requirement.handleFlightInquiry(this, environment.getCurrentRegionSet()));
		}
		return submitFlightResults(results, hasFlightEnabled()) && hasFlightRequirement(requirement);
	}
	
	
	/**
	 * Submit a batch of flight results to the FlightUser.
	 * @param results The results to submit
	 * @return false if the results disabled the users flight aka user failed.
	 */
	public boolean submitFlightResult(FlightResult result) {
		RequirementProvider provider = result.getRequirement();
		InquiryType type = result.getInquiryType();
		if (!result.isAllowed()) {
			submitFlightRequirement(provider, result);
			if (!hasRequirementBypass()) {
				if (hasFlightEnabled()) {
					U.m(p, result.getMessage());
				}
				disableFlight(1, result.hasDamageProtection());	
			}
			return false;
		} else {
			if (hasFlightRequirement(provider, type) && removeFlightRequirement(provider, type)) {
				updateRequirements(result.getMessage());
			}	
		}
		return true;
	}
	
	/**
	 * Submit a batch of flight results.
	 * @param results
	 * @return
	 */
	public boolean submitFlightResults(List<FlightResult> results, boolean failMessage) {
		// The result that actually disabled the flight. first come first serve.
		FlightResult disabled = null;
		// The final result that enabled the flight.
		FlightResult enable = null;
		
		for (FlightResult result: results) {
			RequirementProvider provider = result.getRequirement();
			InquiryType type = result.getInquiryType();
			if (!result.isAllowed()) {
				if (disabled == null) {
					disabled = result;
				}
				if (!hasFlightRequirement(provider, type)) {
					submitFlightRequirement(provider, result);
				}
			} else {
				if (hasFlightRequirement(provider, type) && removeFlightRequirement(provider, type)) {
					enable = result;
				}	
			}
		}
		if (disabled != null) {
			if (!hasRequirementBypass()) {
				if (failMessage) {
					U.m(p, disabled.getMessage());
				}
				disableFlight(1, disabled.hasDamageProtection());
			}
			return false;
		} else if (enable != null) {
			updateRequirements(enable.getMessage());
		}
		return true;
	}
	
	/**
	 * Update the flight requirements for the user. Automatically auto enables flight if applicable. 
	 * @return True if there are no more requirements.
	 */
	public boolean updateRequirements(String enableMessage) {
		Console.debug("", "--- updating requirements ---", "--| requirements: " + requirements.toString(),
				"--| flight enabled: " + enabled, "--| auto flight: " + autoEnable, "--| time: " + time);
		
		if (requirements.size() == 0 && !hasFlightEnabled() && hasAutoFlyQueued() && (time > 0 || hasInfiniteFlight())) {
			if (V.debug) Console.debug(hasRequirementBypass() ? "--|> Autofly will not be invoked, User has requirement bypass mode..." : "--|> AutoFly engaged!");
			autoEnable = false;
			if (!hasRequirementBypass()) {
				enableFlight();
				U.m(p, enableMessage);	
			}
			return true;
		}
		return requirements.size() == 0;
	}
	
	
	
	/**
	 * 
	 * --=-----------=--
	 *    Fall Damage
	 * --=-----------=--
	 * 
	 */
	
	
	public void addDamageProtection() {
		removeDamageProtection();
		damageProtection = new BukkitRunnable() {@Override public void run() {removeDamageProtection();}}.runTaskLater(manager.getTempFly(), 120);
	}
	
	public void removeDamageProtection() {
		if (damageProtection != null) {
			damageProtection.cancel();
			damageProtection = null;
		}
	}
	
	public boolean hasDamageProtection() {
		return damageProtection != null;
	}
	
	
	
	/**
	 * 
	 * --=----------=--
	 *    Aesthetics
	 * --=----------=--
	 * 
	 */
	
	
	
	/**
	 * This method returns a string to keep the plugin compatible through versions.
	 * @return The enum string representation of the particle
	 */
	public String getTrail() {
		return particle;
	}
	
	/**
	 *  This method requires a string to keep the plugin compatible through versions.
	 *  The enum value of the particle as a string
	 * @param particle
	 */
	public void setTrail(String particle) {
		this.particle = particle;
		manager.getTempFly().getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_TRAIL, p.getUniqueId().toString()), particle);
	}
	
	public void playTrail() {
		if (particle == null || particle.length() == 0) {return;}
		
		if (V.hideVanish) {
			for (MetadataValue meta : p.getMetadata("vanished")) {
				if (meta.asBoolean()) {
					return;
				}
			}
		}
		Particles.play(p.getLocation(), particle);
	}
	
	private void updateList(boolean reset) {
		if (!V.list) {return;}
		p.setPlayerListName(!p.isFlying() || reset
				? listName : timeManager.regexString(V.listName
						.replaceAll("\\{PLAYER}", p.getName())
						.replaceAll("\\{OLD_TAG}", tagName), time));
	}
	
	private void updateName(boolean reset) {
		if (!V.tag) {return;}
		p.setDisplayName(!p.isFlying() || reset
				? tagName : timeManager.regexString(V.tagName
						.replaceAll("\\{PLAYER}", p.getName())
						.replaceAll("\\{OLD_TAG}", tagName), time));
	}
	
	public void doActionBar() {
		ActionBarAPI.sendActionBar(p, timeManager.regexString(V.actionText, getTime()));
	}
	
	
	/**
	 * 
	 * --=-----------=--
	 *   Speed control
	 * --=-----------=--
	 * 
	 */
	
	
	public void applySpeedCorrect() {
		float maxSpeed = getMaxSpeed();
		if (p.getFlySpeed() >= (maxSpeed * 0.1f)) {
			new BukkitRunnable() {
				@Override
				public void run() {
					if (p.isOnline()) {p.setFlySpeed(maxSpeed * 0.1f);}
				}
			}.runTaskLater(manager.getTempFly(), 10);
		}
	}
	
	public float getMaxSpeed() {
		float maxBase = (float) ((V.defaultSpeed < 0) ? 0f : (p.isOp() || V.defaultSpeed > 10) ? 10f : V.defaultSpeed);
		if (!p.isOp()) {
			float maxFound = maxBase;
			for (PermissionAttachmentInfo info: p.getEffectivePermissions()) {
				String perm = info.getPermission();
				if (perm.startsWith("tempfly.speed")) {
					try {
						float found = Float.parseFloat(perm.split("\\.")[2]);
						maxFound = found > maxFound ? found : maxFound;
					} catch (Exception e) {continue;}
				}
			}
			maxBase = maxFound > maxBase ? maxFound : maxBase;
		}
		return maxBase;
	}
	
	
	/**
	 * 
	 * --=---------=--
	 *     Timers
	 * --=---------=--
	 * 
	 */
	
	public abstract class TempFlyTimer extends BukkitRunnable {
		
	}
	
	/**
	 * Ground timer runs every tick when FlightTimer isnt scheduled and simply checks if the player is flying.
	 * This way the FlightTimer will run as soon as the player starts flying. Otherwise it kinda looks laggy.
	 * @author Kevin
	 *
	 */
	public class GroundTimer extends TempFlyTimer {
		
		public GroundTimer() {
			this.runTaskTimer(manager.getTempFly(), 0, 3);
		}
		
		@Override
		public void run() {
			if (p.isFlying()) {
				if (isIdle() && !V.idleTimer) {
					return;	
				}
				this.cancel();
				timer = new FlightTimer();
			}
		}
		
	}
	
	/**
	 * FlightTimer runs every 20 ticks and is in charge of decrementing time among other things such as
	 * action bar messages.
	 * @author Kevin
	 *
	 */
	public class FlightTimer extends TempFlyTimer {
		
		private int cycle = 10;
		private boolean previouslyFlying;
		private boolean fixInitialCycle = true;
		
		private long localCycle;
		
		public FlightTimer() {
			this.runTaskTimer(manager.getTempFly(), 0, 2);
		}
		
		@Override
		public void run() {
			doIdentifier();
			if (!doCycle()) {
				return;
			}
			// This line fixed an unknown confliction with another plugin on some guys server so i'l just leave it.
			if (enabled) {p.setAllowFlight(true);}
			if (hasInfiniteFlight()) {
				return;
			}
			
			if (!V.permaTimer && ((!p.isFlying() && !V.groundTimer) || !checkIdle())) {
				this.cancel();
				timer = new GroundTimer();
				accumulativeCycle += localCycle;
				return;
			} else {
				localCycle = System.currentTimeMillis();
			}
			
			idle++;
			
			if (time > 0) {
				double cost = 1;
				for (RelativeTimeRegion rtr : environment.getRelativeTimeRegions()) {
					cost *= rtr.getFactor();
				}
				
				if (!fixInitialCycle || accumulativeCycle >= 1000) {
					localCycle = 0;
					if (accumulativeCycle >= 1000) {
						accumulativeCycle = 0;	
					}
					time = time-cost <= 0 ? 0 : time-cost;
					manager.getTempFly().getDataBridge().stageChange(DataPointer.of(DataValue.PLAYER_TIME, p.getUniqueId().toString()), time);	
				}
				fixInitialCycle = false;
				
				if (time == 0) {
					disableFlight(0, !V.damageTime);
					U.m(p, V.invalidTimeSelf);
					autoEnable = true;
				}
				
				if (V.warningTimes.contains((long)time)) {TitleAPI.sendTitle(p, 15, 30, 15, timeManager.regexString(V.warningTitle, time), timeManager.regexString(V.warningSubtitle, time));}
				if (V.actionBar) {doActionBar();}
				
				return;
			}
			if (enabled) {
				disableFlight(-1, !V.damageTime);
				U.m(p, V.invalidTimeSelf);
				autoEnable = true;
			}
		}
		
		@Override
		public void cancel() {
			accumulativeCycle += localCycle;
			super.cancel();
		}
		
		private void doIdentifier() {
			if (!enabled) {
				return;
			}
			if (previouslyFlying && !p.isFlying() || !previouslyFlying && p.isFlying()) {
				updateList(!p.isFlying());
				updateName(!p.isFlying());	
			}
			previouslyFlying = p.isFlying();
		}
		
		private boolean doCycle() {
			if (cycle > 0) {
				localCycle += 100;
			}
			cycle++;
			if (cycle < 10) {return false;}
			cycle = 0;
			return true;
		}
		
		/**
		 * 
		 * @return True if the timer should continue, false if it can switch to ground timer.
		 */
		private boolean checkIdle() {
			if (isIdle()) {
				if (V.idleDrop) {disableFlight(0, !V.damageIdle);} 
				U.m(p, V.idleDrop ? V.disabledIdle : V.consideredIdle);
				return V.idleTimer;
			}
			return true;
		}
		
	}
}
