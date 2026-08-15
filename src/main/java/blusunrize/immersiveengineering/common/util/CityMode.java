/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.common.Config.IEConfig;

/**
 * Resolves the "city mode" configuration: a master switch plus one opt-out per subsystem.
 * <p>
 * City mode trades simulation detail for server tick time. Rather than have each subsystem repeat
 * <code>IEConfig.cityMode &amp;&amp; IEConfig.cityModeSomething</code> -- and inevitably get it wrong
 * somewhere -- every call site asks this class. A subsystem is simplified only when the master
 * switch is on <em>and</em> that subsystem has not been individually turned back off, so switching
 * the master off is always sufficient to restore stock behaviour.
 * <p>
 * <b>Where the answer comes from.</b> City mode is a <em>world</em> setting, not a client
 * preference: whether a machine's buffer follows its lever is decided by the logic running the
 * world, and both sides have to agree about it or the client draws a machine the server is not
 * running. IE's config is per-installation, so a client joining a server has its own copy and no
 * reason for it to match. The server therefore pushes its flags on login
 * ({@code MessageCityModeSync}) and they are installed here as an override; every accessor below
 * consults the override first and falls back to the local config only when there is none -- that
 * is, on a dedicated server, and on a client sitting at the main menu. The override is dropped when
 * the client disconnects, so the local config governs the next single-player world it opens.
 * <p>
 * In single player the packet still flows -- the integrated server sends it to the one player -- and
 * the override it installs is a copy of the very config it was built from, so it changes nothing.
 * That is deliberate: one code path, no "is this the integrated server" special case to get wrong.
 * <p>
 * These read plain booleans and are cheap enough to call from a tick body: nothing here allocates
 * except {@link #effective()}, which exists for the packet and for tests rather than for ticking.
 */
public final class CityMode
{
	/**
	 * Every subsystem city mode is able to simplify, in the order they are packed into
	 * {@link Flags}' bitmask.
	 * <p>
	 * Constants may be appended but never reordered or removed: their ordinals are the wire format
	 * of {@code MessageCityModeSync}.
	 */
	public enum Subsystem
	{
		WIRES,
		PIPES,
		CONDUITS,
		TANKS,
		FLOODLIGHTS,
		GENERATORS,
		MACHINES,
		VIRTUAL_GRID,
		PETROLEUM;

		public static final Subsystem[] VALUES = values();

		/**
		 * This subsystem's bit in a {@link Flags} mask.
		 */
		public int bit()
		{
			return 1<<ordinal();
		}
	}

	/**
	 * An immutable snapshot of the whole city-mode configuration: the master switch and one bit per
	 * {@link Subsystem}.
	 * <p>
	 * This is what travels over the wire and what the resolution rule is written against, so the
	 * rule can be tested as a pure function with no config statics and no Minecraft in sight.
	 */
	public static final class Flags
	{
		/**
		 * Master off, nothing simplified -- stock Immersive Engineering.
		 */
		public static final Flags STOCK = new Flags(false, 0);

		private final boolean master;
		private final int subsystems;

		public Flags(boolean master, int subsystems)
		{
			this.master = master;
			this.subsystems = subsystems;
		}

		/**
		 * @return true if the master switch is on, regardless of any subsystem opt-out
		 */
		public boolean master()
		{
			return master;
		}

		/**
		 * @return the raw subsystem bitmask, keyed by {@link Subsystem#ordinal()}
		 */
		public int subsystemMask()
		{
			return subsystems;
		}

		/**
		 * @return true if this subsystem has not been individually opted out -- which says nothing
		 * on its own, because the master switch still has to be on. See {@link #simplifies}.
		 */
		public boolean subsystemEnabled(Subsystem subsystem)
		{
			return (subsystems&subsystem.bit())!=0;
		}

		/**
		 * The rule this whole class exists to state: a subsystem is simplified only when the master
		 * is on <em>and</em> that subsystem has not been turned back off.
		 */
		public boolean simplifies(Subsystem subsystem)
		{
			return master&&subsystemEnabled(subsystem);
		}

		@Override
		public boolean equals(Object o)
		{
			if(this==o)
				return true;
			if(!(o instanceof Flags))
				return false;
			Flags other = (Flags)o;
			return master==other.master&&subsystems==other.subsystems;
		}

		@Override
		public int hashCode()
		{
			return (master?1: 0)*31+subsystems;
		}

		@Override
		public String toString()
		{
			StringBuilder sb = new StringBuilder("CityMode.Flags[master=").append(master);
			for(Subsystem s : Subsystem.VALUES)
				sb.append(' ').append(s.name().toLowerCase(java.util.Locale.ENGLISH)).append('=').append(subsystemEnabled(s));
			return sb.append(']').toString();
		}
	}

	/**
	 * The flags the server told us to use, or null to use the local config.
	 * <p>
	 * Written by the network thread's scheduled task on the client and read from both the client
	 * and (in single player) the integrated server thread, hence volatile. A single reference swap
	 * of an immutable object is all the synchronisation this needs -- there is no state to tear.
	 */
	private static volatile Flags serverOverride = null;

	private CityMode()
	{
	}

	// ---------------------------------------------------------------- snapshots and the override

	/**
	 * @return a snapshot of the local config, ignoring any server override
	 */
	public static Flags fromConfig()
	{
		int mask = 0;
		for(Subsystem s : Subsystem.VALUES)
			if(configFlag(s))
				mask |= s.bit();
		return new Flags(IEConfig.cityMode, mask);
	}

	/**
	 * @return the flags actually in force: the server's if we have them, otherwise the local config
	 */
	public static Flags effective()
	{
		Flags o = serverOverride;
		return o!=null?o: fromConfig();
	}

	/**
	 * Installs the flags a server sent us. Client side only; see the class javadoc.
	 */
	public static void applyServerOverride(Flags flags)
	{
		serverOverride = flags;
	}

	/**
	 * Drops the server's flags, returning to the local config. Called when the client leaves a
	 * server, so that the next single-player world opened is governed by this installation's own
	 * config rather than by whatever the last server happened to run.
	 */
	public static void clearServerOverride()
	{
		serverOverride = null;
	}

	/**
	 * @return true while a server's flags are in force rather than the local config
	 */
	public static boolean hasServerOverride()
	{
		return serverOverride!=null;
	}

	// ---------------------------------------------------------------- resolution

	/**
	 * The one place the override-or-config choice is made, for a single subsystem.
	 * <p>
	 * Written out rather than routed through {@link #effective()} so that the tick-body callers
	 * below allocate nothing.
	 */
	private static boolean simplifies(Subsystem subsystem)
	{
		Flags o = serverOverride;
		if(o!=null)
			return o.simplifies(subsystem);
		return IEConfig.cityMode&&configFlag(subsystem);
	}

	private static boolean configFlag(Subsystem subsystem)
	{
		switch(subsystem)
		{
			case WIRES:
				return IEConfig.cityModeWires;
			case PIPES:
				return IEConfig.cityModePipes;
			case CONDUITS:
				return IEConfig.cityModeConduits;
			case TANKS:
				return IEConfig.cityModeTanks;
			case FLOODLIGHTS:
				return IEConfig.cityModeFloodlights;
			case GENERATORS:
				return IEConfig.cityModeGenerators;
			case MACHINES:
				return IEConfig.cityModeMachines;
			case VIRTUAL_GRID:
				return IEConfig.cityModeVirtualGrid;
			case PETROLEUM:
				return IEConfig.cityModePetroleum;
			default:
				return false;
		}
	}

	// ---------------------------------------------------------------- accessors

	/**
	 * @return true if the master switch is on, regardless of any subsystem opt-out
	 */
	public static boolean enabled()
	{
		Flags o = serverOverride;
		return o!=null?o.master(): IEConfig.cityMode;
	}

	/**
	 * Lossless, unweighted power distribution across the wire network.
	 */
	public static boolean wires()
	{
		return simplifies(Subsystem.WIRES);
	}

	/**
	 * Fluid pipes stop apportioning and simply hand the fluid out in order.
	 * <p>
	 * The full path walks every endpoint on a network twice per fill -- once simulating to learn
	 * what each would take, then again to split the resource between them in proportion. That is a
	 * fairness property, and fairness between tanks is a simulation detail rather than something a
	 * player sees; in a decorative build it is a hundred capability calls a tick to decide which of
	 * sixty identical tanks gets the water first.
	 * <p>
	 * This is the pipe network's counterpart to {@link #wires()} -- same trade, same reasoning.
	 * Transfer limits still apply and nothing is created or destroyed; the nearest tank simply
	 * fills first.
	 */
	public static boolean pipes()
	{
		return simplifies(Subsystem.PIPES);
	}

	/**
	 * Conduits switch from moving units of flux to presence: a conductor is either energised or it
	 * is not, an energised breakout delivers at full rate, and no line loss is charged.
	 * <p>
	 * The full path keeps a buffer per channel per junction box and moves half the difference
	 * between neighbours each tick. That gradient is what makes energy find its way to a load
	 * without anybody walking the run, and it is also arithmetic on every box on every tick for
	 * every conductor carrying anything. In a decorative build the answer to "is this corridor lit"
	 * is yes, and the sixteen subtractions that established it were spent proving something nobody
	 * was going to look at.
	 * <p>
	 * This is the conduit's counterpart to {@link #grid()} rather than to {@link #wires()} -- the
	 * grid made the same move from accounting to presence, for the same reason. A conductor still
	 * goes dark about a second after its source stops, so a switched circuit still switches.
	 */
	public static boolean conduits()
	{
		return simplifies(Subsystem.CONDUITS);
	}

	/**
	 * A tank holding anything at all becomes an unlimited source of it. Empty is still empty.
	 * <p>
	 * Presence rather than accounting, like {@link #grid()} and {@link #conduits()}. Filling is
	 * untouched -- only the draw side is free -- so a tank still fills at its ordinary rate and
	 * there is still something to build. See {@code CityModeTank}.
	 */
	public static boolean tanks()
	{
		return simplifies(Subsystem.TANKS);
	}

	/**
	 * Floodlights skip their periodic beam re-scan and cap how many light blocks they place.
	 */
	public static boolean floodlights()
	{
		return simplifies(Subsystem.FLOODLIGHTS);
	}

	/**
	 * Generators treat fuel as cosmetic and produce a flat output.
	 */
	public static boolean generators()
	{
		return simplifies(Subsystem.GENERATORS);
	}

	/**
	 * Machines scan for recipes less often and skip cosmetic neighbour updates.
	 */
	public static boolean machines()
	{
		return simplifies(Subsystem.MACHINES);
	}

	/**
	 * The virtual grid switches from real flux accounting to presence semantics: a segment
	 * either has power or it does not, and its service units deliver freely when it does.
	 * Feed units only sip a token amount to prove their source is still live.
	 * <p>
	 * This is the grid's counterpart to {@link #wires()} -- same trade, same reasoning.
	 */
	public static boolean grid()
	{
		return simplifies(Subsystem.VIRTUAL_GRID);
	}

	/**
	 * Petroleum reservoirs stop depleting. A well still has to be prospected and drilled and
	 * still holds the flavour of a finite field, but its remaining capacity is never decremented
	 * and its flow rate never decays, so it delivers at peak, free-flowing rate forever.
	 */
	public static boolean petroleum()
	{
		return simplifies(Subsystem.PETROLEUM);
	}
}
