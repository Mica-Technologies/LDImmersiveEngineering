/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

/**
 * The junction box's decisions that do not need a world.
 * <p>
 * The bucket brigade itself already lives in {@code ConduitTransfer} and is tested there; this is
 * what was left inline on the tile entity. Each is a choice rather than arithmetic: what a
 * comparator reads off a bundle, how much a channel will take, how the redstone channels resolve
 * when several boxes on one run drive the same conductor, which conductor an unasked-for breakout
 * takes, and which of a box's six faces will accept a wire.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public final class JunctionBoxLogic
{
	private JunctionBoxLogic()
	{
	}

	/**
	 * What a comparator reads off a whole bundle.
	 * <p>
	 * <strong>The busiest conductor, not the total and not the average.</strong> A bundle where one
	 * channel is saturated and fifteen are idle is a bundle with a problem; a total would read the
	 * same as sixteen channels ticking over, and an average would hide it entirely. Floored at 1 for
	 * any non-empty bundle so "carrying something" and "carrying nothing" are distinguishable.
	 *
	 * @param held     per-channel contents
	 * @param capacity one channel's capacity
	 */
	public static int comparatorLevel(int[] held, int capacity)
	{
		if(held==null||capacity <= 0)
			return 0;
		int busiest = 0;
		for(int value : held)
			busiest = Math.max(busiest, value);
		if(busiest <= 0)
			return 0;
		return Math.max(1, Math.min(15, (int)((long)busiest*15/capacity)));
	}

	/**
	 * How much a channel will accept, given what it already holds.
	 *
	 * @return the amount actually taken, never negative and never past the capacity
	 */
	public static int credit(int held, int amount, int capacity)
	{
		if(amount <= 0)
			return 0;
		return Math.max(0, Math.min(capacity-held, amount));
	}

	/**
	 * Resolve the redstone channels across every box on a run.
	 * <p>
	 * <strong>Strongest wins, per channel.</strong> A conductor reaches every box on the run, so
	 * several boxes may be driving the same colour at once -- a lever at one end and a comparator at
	 * the other. Taking the maximum makes that behave the way a redstone wire does, which is the
	 * behaviour a player already has in their hands; summing would let two weak inputs forge a
	 * strong one, and last-writer-wins would make the answer depend on iteration order.
	 *
	 * @param channelCount  how many conductors a bundle carries
	 * @param contributions each {channelIndex, signal}, in any order
	 *
	 * @return the resolved signal per channel
	 */
	public static int[] strongestPerChannel(int channelCount, int[][] contributions)
	{
		int[] strongest = new int[Math.max(0, channelCount)];
		if(contributions==null)
			return strongest;
		for(int[] one : contributions)
		{
			if(one==null||one.length < 2)
				continue;
			int channel = one[0];
			if(channel < 0||channel >= strongest.length)
				continue;
			//Clamped to a redstone level rather than trusted: this comes off a neighbouring block,
			//and a mod is free to answer with whatever it likes.
			int signal = Math.max(0, Math.min(15, one[1]));
			strongest[channel] = Math.max(strongest[channel], signal);
		}
		return strongest;
	}

	/**
	 * Which conductor a face should break out when a connector turns up against it and nobody has
	 * said which.
	 * <p>
	 * <strong>The lowest free one.</strong> Dyeing a face is a real choice and it stays available,
	 * but it is a choice almost nobody wants to make the first time: what a player putting an LV
	 * connector on a junction box means is "power, here", and answering that with silence until they
	 * find out about dyes is the awkwardness this whole change is about. Lowest-first rather than
	 * random so a run wired left to right comes out white, orange, magenta in that order and reads
	 * as deliberate.
	 * <p>
	 * A conductor already patched somewhere on this box is never handed out again -- the same
	 * conductor arriving at two connectors is a short, not a feature -- which is why this takes the
	 * box's whole used mask rather than one face.
	 *
	 * @param usedMask     one bit per conductor already patched on this box
	 * @param channelCount how many conductors a bundle carries
	 *
	 * @return the index of the conductor to patch, or -1 if the box has none left
	 */
	public static int firstFreeChannel(int usedMask, int channelCount)
	{
		for(int i = 0; i < channelCount; i++)
			if((usedMask&(1 << i))==0)
				return i;
		return -1;
	}

	//	=================================
	//		WIRES STRUNG STRAIGHT TO A BOX
	//	=================================

	/** How many faces a box has, and therefore the most wires one can carry. */
	public static final int FACES = 6;

	/**
	 * Why a face will not take the wire being offered to it, or {@link WireRefusal#NONE}.
	 * <p>
	 * An enum rather than a boolean because the answer is the message the player gets. Being told
	 * "wrong cable" while holding the right cable -- which is what a bare no came out as -- is how a
	 * rule becomes a bug report, and this box has three separate rules that can say no.
	 */
	public enum WireRefusal
	{
		/** Nothing wrong: the wire may be attached. */
		NONE,
		/** Not an LV, MV or HV wire. Structural cable holds things up; redstone carries no flux. */
		WRONG_KIND,
		/** The face is the one the housing is bolted to, so a wire there would start inside a block. */
		MOUNT_FACE,
		/** Something is already strung to that face. One face, one wire, one circuit. */
		FACE_TAKEN,
		/** Every face this box can take a wire on already has one. */
		BOX_FULL
	}

	/**
	 * Whether a wire may be strung to one face of a junction box, and if not, why not.
	 * <p>
	 * <strong>One wire per face, six faces, minus the one it is bolted to.</strong> The face is the
	 * face the player clicked, which is the whole reason a box can hold six independent circuits
	 * without a single extra click of configuration: the gesture already said which one was meant.
	 * <p>
	 * The mount face is refused because the housing lies flush against it -- a wire attached there
	 * would leave from inside the block the box is screwed to. In practice that face is usually not
	 * even clickable, so this mostly guards the case where it is: a box hanging in open air, whose
	 * mount is whichever surface its runs put it on. A box with no runs at all is drawn standing on
	 * the floor of its cell, so {@code down} is the face it refuses.
	 * <p>
	 * <strong>The mount is never allowed to take a wire away.</strong> A box's mount moves when the
	 * runs reaching it move, and yanking a wire because somebody laid conduit on the far side would
	 * be a circuit broken by an unrelated action. This is asked when a wire is attached and never
	 * afterwards.
	 *
	 * @param wiredMask one bit per face already carrying a wire, as {@link JunctionWires#mask}
	 * @param face      the face the player clicked, by {@code EnumFacing.ordinal()}
	 * @param mount     the face the box is bolted to, by {@code EnumFacing.ordinal()}
	 * @param tierWire  whether the offered wire is one of the three power tiers
	 */
	public static WireRefusal canTakeWire(int wiredMask, int face, int mount, boolean tierWire)
	{
		if(!tierWire)
			return WireRefusal.WRONG_KIND;
		if(face < 0||face >= FACES)
			return WireRefusal.WRONG_KIND;
		//"Full" is tested before the face's own two rules so that a box with a wire everywhere says
		//so, rather than blaming whichever face happened to be clicked.
		if(freeFaces(wiredMask, mount)==0)
			return WireRefusal.BOX_FULL;
		if(face==mount)
			return WireRefusal.MOUNT_FACE;
		if((wiredMask&(1 << face))!=0)
			return WireRefusal.FACE_TAKEN;
		return WireRefusal.NONE;
	}

	/**
	 * @return how many faces of this box could still take a wire
	 */
	public static int freeFaces(int wiredMask, int mount)
	{
		int free = 0;
		for(int i = 0; i < FACES; i++)
			if(i!=mount&&(wiredMask&(1 << i))==0)
				free++;
		return free;
	}
}
