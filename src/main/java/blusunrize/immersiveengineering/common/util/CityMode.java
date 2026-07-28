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
 * These read plain config booleans and are cheap enough to call from a tick body.
 */
public final class CityMode
{
	private CityMode()
	{
	}

	/**
	 * @return true if the master switch is on, regardless of any subsystem opt-out
	 */
	public static boolean enabled()
	{
		return IEConfig.cityMode;
	}

	/**
	 * Lossless, unweighted power distribution across the wire network.
	 */
	public static boolean wires()
	{
		return IEConfig.cityMode&&IEConfig.cityModeWires;
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
		return IEConfig.cityMode&&IEConfig.cityModePipes;
	}

	/**
	 * Floodlights skip their periodic beam re-scan and cap how many light blocks they place.
	 */
	public static boolean floodlights()
	{
		return IEConfig.cityMode&&IEConfig.cityModeFloodlights;
	}

	/**
	 * Generators treat fuel as cosmetic and produce a flat output.
	 */
	public static boolean generators()
	{
		return IEConfig.cityMode&&IEConfig.cityModeGenerators;
	}

	/**
	 * Machines scan for recipes less often and skip cosmetic neighbour updates.
	 */
	public static boolean machines()
	{
		return IEConfig.cityMode&&IEConfig.cityModeMachines;
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
		return IEConfig.cityMode&&IEConfig.cityModeVirtualGrid;
	}

	/**
	 * Petroleum reservoirs stop depleting. A well still has to be prospected and drilled and
	 * still holds the flavour of a finite field, but its remaining capacity is never decremented
	 * and its flow rate never decays, so it delivers at peak, free-flowing rate forever.
	 */
	public static boolean petroleum()
	{
		return IEConfig.cityMode&&IEConfig.cityModePetroleum;
	}
}
