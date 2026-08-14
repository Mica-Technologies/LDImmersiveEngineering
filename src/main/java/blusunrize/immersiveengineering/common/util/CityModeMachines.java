/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

/**
 * The three decisions city mode makes about a powered multiblock, as pure functions.
 * <p>
 * {@link CityMode#machines()} answers <em>whether</em> the machine subsystem is simplified; this
 * class answers <em>what</em> the simplification is. Everything here is arithmetic on numbers a
 * caller already has, deliberately free of Minecraft types, so the rules can be tested without a
 * world -- {@code TileEntityMultiblockMetal} is a thin wrapper over these.
 * <p>
 * The three rules exist together because they are one behaviour seen from three sides: a decorative
 * machine in a city is <em>on</em>. It looks on ({@link #renderAsActive}), it is not starved of the
 * chance to start work ({@link #idleScanExempt}), and its buffer follows the switch that turns it on
 * ({@link #redstoneEdgeBufferLevel}).
 *
 * @author LDImmersiveEngineering
 */
public final class CityModeMachines
{
	/**
	 * Sentinel for "this machine has never started a process", for {@link #idleScanExempt}.
	 */
	public static final long NEVER = Long.MIN_VALUE;

	/**
	 * How long a machine keeps its every-tick idle recipe scan after it last managed to start
	 * something, in ticks.
	 * <p>
	 * Ten seconds is long enough to cover any gap a working machine leaves between batches -- the
	 * longest stock process is well under it -- and short enough that a machine which has genuinely
	 * run out of work settles back onto the throttle within a few seconds of going quiet.
	 */
	public static final int IDLE_SCAN_GRACE_TICKS = 200;

	private CityModeMachines()
	{
	}

	/**
	 * Whether a machine with an empty queue may look for a new recipe on this tick regardless of the
	 * scan throttle.
	 * <p>
	 * Outside city mode the answer is always yes, which is stock behaviour: an idle machine scans
	 * every tick so newly inserted items start immediately.
	 * <p>
	 * City mode throttles the idle scan as well, because the case worth throttling is a machine
	 * holding input it can never use, re-scanning hundreds of recipes forever. The mistake in the
	 * first version of that was to throttle <em>every</em> idle machine, including one that empties
	 * its queue between batches -- and every caller ANDs the scan opportunity with conditions read
	 * on that same tick (stored energy, feed level, output room), so missing the one tick in 32 costs
	 * another 32, and missing two costs three seconds. That is the stutter, and at short process
	 * times it is most of the duty cycle.
	 * <p>
	 * So the throttle now applies only to a machine that has <em>proved</em> it has nothing to do: one
	 * that has not started a process for {@link #IDLE_SCAN_GRACE_TICKS}. A machine between batches
	 * scans every tick exactly as it does in normal mode; a decorative machine full of the wrong
	 * items falls back onto the throttle within ten seconds and stays there. The expensive case is
	 * still throttled and the working case no longer stalls.
	 *
	 * @param cityMode         whether the machine subsystem is simplified, see {@link CityMode#machines()}
	 * @param now              the current world time
	 * @param lastProcessStart the world time at which this machine last queued a process, or {@link #NEVER}
	 */
	public static boolean idleScanExempt(boolean cityMode, long now, long lastProcessStart)
	{
		return !cityMode||recentlyProductive(now, lastProcessStart);
	}

	/**
	 * Whether a machine has started a process recently enough to still count as running.
	 * <p>
	 * A negative age means the world clock moved backwards under us -- a restored backup, a
	 * {@code /time set} -- which is treated as "not recent" so the machine re-earns its exemption
	 * rather than holding it for the next several million ticks.
	 */
	public static boolean recentlyProductive(long now, long lastProcessStart)
	{
		if(lastProcessStart==NEVER)
			return false;
		long since = now-lastProcessStart;
		return since >= 0&&since <= IDLE_SCAN_GRACE_TICKS;
	}

	/**
	 * Whether the client should draw and sound a machine as running.
	 * <p>
	 * Normally that means it is actually mid-process. In city mode a machine is set dressing, and
	 * set dressing that flickers on and off with the queue reads as broken rather than as busy --
	 * the animation and the looping sound both cut out for the gap between one batch and the next.
	 * So in city mode the queue stops being part of the answer: a machine that is switched on and
	 * has power looks like it is working, whether or not there is anything in it.
	 * <p>
	 * The two conditions that remain are the two a player controls. Redstone still stops a machine
	 * dead, which is the whole point of wiring one up, and an unpowered machine is still still.
	 *
	 * @param cityMode         whether the machine subsystem is simplified
	 * @param redstoneEnabled  whether the machine's redstone control currently allows it to run
	 * @param powered          whether the machine holds any energy at all
	 * @param processing       whether the machine currently has something queued
	 */
	public static boolean renderAsActive(boolean cityMode, boolean redstoneEnabled, boolean powered, boolean processing)
	{
		if(!redstoneEnabled||!powered)
			return false;
		return cityMode||processing;
	}

	/**
	 * The level a machine's energy buffer should jump to because its redstone control just changed
	 * state, or -1 for "leave the buffer alone".
	 * <p>
	 * City mode's contract for a machine is presence rather than accounting, the same trade the grid
	 * and the conduits make: switched on means full, switched off means empty. A player flipping the
	 * lever on a machine expects the whole machine to respond, gauge included, not the process alone.
	 * <p>
	 * This is an <em>edge</em>, not a level. Holding the buffer full every tick would make the gauge
	 * a constant and stop a machine ever visibly drawing power; setting it once per transition leaves
	 * the buffer to behave normally in between, so a running machine still drains and still refills
	 * from whatever is feeding it.
	 *
	 * @param cityMode    whether the machine subsystem is simplified
	 * @param lastEnabled the enable state observed last tick, or null if this machine has not been
	 *                    observed yet (freshly placed, or freshly loaded from disk)
	 * @param enabled     the enable state now
	 * @param capacity    the buffer's capacity
	 * @return the level to set, or -1 to change nothing
	 */
	public static int redstoneEdgeBufferLevel(boolean cityMode, Boolean lastEnabled, boolean enabled, int capacity)
	{
		if(!cityMode)
			return -1;
		//A machine seen for the first time counts as an edge. Otherwise a machine loaded with its
		//lever already thrown would sit at whatever level it was saved with until somebody flipped
		//the lever twice, which is exactly the "only the machine turned on, not the buffer" symptom
		//this is here to fix.
		if(lastEnabled!=null&&lastEnabled==enabled)
			return -1;
		return enabled?Math.max(0, capacity): 0;
	}
}
