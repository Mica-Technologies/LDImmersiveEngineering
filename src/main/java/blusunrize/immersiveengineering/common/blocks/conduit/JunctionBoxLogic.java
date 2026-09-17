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
	 * What being lit costs the sender under city mode's presence rule, per credit: a token.
	 * <p>
	 * Any credit at all sets a city-mode conductor to full, and the decay then measures how long
	 * it stays lit -- so the amount is a time, not a quantity the source has to keep up with. The
	 * box used to debit the sender by what it had actually taken anyway, which after the decay was a
	 * twentieth of a channel every tick: more than any LV or MV connector delivers, so on a line it
	 * shared with anything else it was a bottomless sink that either starved the rest of the line
	 * or, if the line served the rest first, was starved by it and read dead (private issue #4).
	 */
	public static final int PRESENCE_DRAW = 1;

	/**
	 * How much of a credit to charge the sender.
	 *
	 * @param taken    what {@link #credit} said the channel took
	 * @param presence true under city mode's presence rule, where the charge is {@link #PRESENCE_DRAW}
	 * @return the debit, never more than was taken and never negative
	 */
	public static int debit(int taken, boolean presence)
	{
		if(taken <= 0)
			return 0;
		return presence?Math.min(taken, PRESENCE_DRAW): taken;
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

	/**
	 * Which conductor a breakout on that face should take, before falling back to the lowest free
	 * one.
	 * <p>
	 * <strong>Red on the left, blue in the middle, green on the right.</strong> A box on a pole
	 * wears three breakouts far more often than it wears sixteen, and until now which colour landed
	 * where depended on the order somebody happened to bolt the hardware on -- so two boxes built
	 * the same way came out different, and a lineman reading a pole from the ground could not tell
	 * one circuit from another at a glance. Handing each face a fixed colour is what makes a row of
	 * poles read as a row of poles.
	 * <p>
	 * The three are the phase colours anybody would reach for, laid out the way they are on the
	 * reference photograph: looking north at a box, west is on the left and east is on the right,
	 * and up is between them -- which is also "on top" for a box on a pole and "in the centre" for
	 * one on the ground, the two cases the report names. North and south take the same pair, so a
	 * line running the other way is laid out the same way round.
	 * <p>
	 * A preference, not a rule: the colour is only taken if it is free, because the same conductor
	 * arriving at two connectors is a short. A fourth breakout, or a second one on a face whose
	 * colour has already gone somewhere, falls through to {@link #firstFreeChannel} exactly as
	 * every breakout used to.
	 *
	 * @param face         the face wanting a breakout, as an {@code EnumFacing} ordinal
	 * @param usedMask     one bit per conductor already patched on this box
	 * @param channelCount how many conductors a bundle carries
	 *
	 * @return the index of the conductor to patch, or -1 if the box has none left
	 */
	public static int preferredChannel(int face, int usedMask, int channelCount)
	{
		int wanted = face >= 0&&face < DEFAULT_CHANNEL.length?DEFAULT_CHANNEL[face]: -1;
		if(wanted >= 0&&wanted < channelCount&&(usedMask&(1 << wanted))==0)
			return wanted;
		return firstFreeChannel(usedMask, channelCount);
	}

	/**
	 * Which conductor a breakout should take when the box is on a run that already carries some.
	 * <p>
	 * <strong>A box joining a run takes the run's circuit; a box on its own takes its face's
	 * colour.</strong> The face colours above are a layout, not a wiring plan, and on their own they
	 * make the plainest thing anybody builds fail silently: feed a run on one end and tap it on the
	 * other, and the two boxes auto-patch onto opposite faces, which are opposite colours. The
	 * energy crosses the run on red the whole way and the far box has only a green breakout, so
	 * nothing leaves it. Both ends read 0 and the run looks dead -- which is the report this exists
	 * to answer (private issue #4).
	 * <p>
	 * The face colour still wins whenever the run is already carrying it, which is what keeps a row
	 * of linked poles reading as a row of poles: the second box's west face wants red, the run's
	 * first box put red on its own west face, so red it is. Only a face whose colour is nowhere on
	 * the run gives way, and then to the lowest conductor the run does carry.
	 * <p>
	 * A box with no run, or one whose run carries nothing this box has not already broken out, is
	 * exactly {@link #preferredChannel} and nothing here applies -- so a standalone box, a pole box
	 * and the first box of a run are all unchanged.
	 *
	 * @param face         the face wanting a breakout, as an {@code EnumFacing} ordinal
	 * @param usedMask     one bit per conductor already patched on this box
	 * @param runMask      one bit per conductor patched on some <em>other</em> box of this run, or 0
	 *                     for a box that is on no run or whose run has not been walked
	 * @param channelCount how many conductors a bundle carries
	 *
	 * @return the index of the conductor to patch, or -1 if the box has none left
	 */
	public static int channelForNewBreakout(int face, int usedMask, int runMask, int channelCount)
	{
		//Conductors the run carries that this box has not already broken out. Masked to the bundle's
		//width so a caller cannot widen the choice with stray high bits.
		int candidates = runMask&~usedMask&((1 << channelCount)-1);
		if(candidates==0)
			return preferredChannel(face, usedMask, channelCount);
		int wanted = face >= 0&&face < DEFAULT_CHANNEL.length?DEFAULT_CHANNEL[face]: -1;
		if(wanted >= 0&&wanted < channelCount&&(candidates&(1 << wanted))!=0)
			return wanted;
		return Integer.numberOfTrailingZeros(candidates);
	}

	/**
	 * The colour each face reaches for first, indexed by {@code EnumFacing.ordinal()} -- down, up,
	 * north, south, west, east.
	 * <p>
	 * Written as indices into {@code WireChannel} rather than as the enum, so this class stays what
	 * the rest of it is: arithmetic with no game behind it. The three used are BLUE, GREEN and RED,
	 * which are 11, 13 and 14 in {@code EnumDyeColor} order -- the order {@code WireChannel}
	 * deliberately matches, and the order a dye's ore dictionary name resolves through.
	 */
	private static final int[] DEFAULT_CHANNEL = {11, 11, 14, 13, 14, 13};

	/**
	 * One step of the Engineer's Hammer's cycle on a face: which conductor it lands on next.
	 * <p>
	 * The cycle is every conductor this box has not already spent on some <em>other</em> face, in
	 * order, and then bare. Conductors spoken for elsewhere are stepped over rather than refused:
	 * the same conductor arriving at two connectors is a short, and a click that visibly does
	 * nothing reads as a broken block rather than as a rule.
	 * <p>
	 * Bare is a stop in the cycle rather than a separate gesture, so one tool both recolours a
	 * breakout and takes it away -- which is what the report asked for, and what makes the hammer a
	 * complete answer rather than a shortcut with a hole in it. It is skipped when a wire is strung
	 * to the face, for the reason the box refuses to unpatch under one: hardware left live on no
	 * conductor is the worst of the ways for this to be wrong.
	 *
	 * @param current      the conductor on that face now, or -1 for a bare face
	 * @param takenMask    one bit per conductor patched on <em>another</em> face of this box
	 * @param mayGoBare    whether "no breakout" is one of the stops
	 * @param channelCount how many conductors a bundle carries
	 *
	 * @return the conductor to patch, or -1 to leave the face bare. Answers {@code current} when
	 * there is nowhere else to go, so a caller can treat "no change" as "nothing happened".
	 */
	public static int nextBreakout(int current, int takenMask, boolean mayGoBare, int channelCount)
	{
		int[] stops = new int[channelCount];
		int count = 0;
		for(int i = 0; i < channelCount; i++)
			if((takenMask&(1 << i))==0)
				stops[count++] = i;
		int size = count+(mayGoBare?1: 0);
		if(size==0)
			return current;
		int index = -1;
		if(current < 0)
			//A bare face steps to the first colour when bare is not a stop at all, which is a face a
			//wire was adopted onto before anything patched it.
			index = mayGoBare?count: -1;
		else
			for(int i = 0; i < count; i++)
				if(stops[i]==current)
					index = i;
		int next = (index+1)%size;
		return next < count?stops[next]: -1;
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
