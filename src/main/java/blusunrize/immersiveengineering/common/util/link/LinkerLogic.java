/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.link;

/**
 * What a linking tool does with one click, decided without a world.
 * <p>
 * The two linkers -- one for the power grid, one for the fluid network -- ask exactly the same
 * questions in exactly the same order, and both have to re-check a lock on <em>both</em> ends of
 * the move: the network the tool is loaded with, and the one the box is leaving. A tool in hand
 * must not be a way around a lock, and that rule is easy to get subtly wrong in two places at
 * once, so it is written down once, here, where it can be asserted against without a server.
 * <p>
 * Nothing in this class knows what a segment or a main <em>is</em>. It takes the handful of
 * booleans that describe the situation and answers with what should happen; the caller does the
 * happening and says so in chat. That is the same split {@code GridEngine} makes against
 * {@code IGridEndpoint}, and for the same reason.
 *
 * @author LDImmersiveEngineering -- network linkers
 */
public final class LinkerLogic
{
	private LinkerLogic()
	{
	}

	/**
	 * What the caller should do about a click.
	 */
	public enum Outcome
	{
		/** The block clicked is not a device of this network. Let the click fall through. */
		NOT_A_DEVICE,
		/** The tool is empty, so there is nothing to paste. Show the chooser instead. */
		OPEN_CHOOSER,
		/** The stored selection has been deleted since it was picked up. Clear the tool. */
		SELECTION_GONE,
		/** The stored selection is locked against this player. Change nothing. */
		SELECTION_LOCKED,
		/** The device's current network is locked against this player. Change nothing. */
		CURRENT_LOCKED,
		/** The device is already on the selection. Change nothing, and say so. */
		ALREADY_LINKED,
		/** Everything checks out: perform the assignment. */
		ASSIGN
	}

	/**
	 * Decides what one click on a device does.
	 *
	 * @param deviceExists      whether the block clicked is a registered device of this network
	 * @param hasSelection      whether the tool is carrying a selection at all
	 * @param selectionExists   whether that selection is still a live network
	 * @param selectionEditable whether the player may edit the selected network
	 * @param currentEditable   whether the player may edit the network the device is on now
	 *                          (true when the device is unlinked -- there is nothing to be locked out of)
	 * @param alreadyLinked     whether the device is already on the selected network
	 */
	public static Outcome decide(boolean deviceExists, boolean hasSelection, boolean selectionExists,
								 boolean selectionEditable, boolean currentEditable,
								 boolean alreadyLinked)
	{
		if(!deviceExists)
			return Outcome.NOT_A_DEVICE;
		if(!hasSelection)
			return Outcome.OPEN_CHOOSER;
		if(!selectionExists)
			return Outcome.SELECTION_GONE;
		if(!selectionEditable)
			return Outcome.SELECTION_LOCKED;
		//"Already there" is answered before the *current* lock is examined on purpose: a device
		//sitting on a locked network that the tool is also holding is not being moved anywhere, and
		//reporting a permission failure for a no-op would send somebody hunting for an owner they do
		//not need.
		if(alreadyLinked)
			return Outcome.ALREADY_LINKED;
		if(!currentEditable)
			return Outcome.CURRENT_LOCKED;
		return Outcome.ASSIGN;
	}

	/**
	 * Whether an outcome left the tool's stored selection unusable, and so should empty it.
	 * <p>
	 * Only a selection that has actually ceased to exist clears the tool. A lock is a temporary
	 * refusal by somebody else and emptying the tool over one would mean re-picking a segment every
	 * time a click landed on a box belonging to a neighbour.
	 */
	public static boolean clearsTool(Outcome outcome)
	{
		return outcome==Outcome.SELECTION_GONE;
	}

	/**
	 * Whether the click should be treated as handled, so it does not also open a device panel or
	 * place a block.
	 * <p>
	 * Everything except {@link Outcome#NOT_A_DEVICE} is: a refusal that let the click fall through
	 * would open the per-device panel on top of the message explaining why nothing happened.
	 */
	public static boolean consumesClick(Outcome outcome)
	{
		return outcome!=Outcome.NOT_A_DEVICE;
	}
}
