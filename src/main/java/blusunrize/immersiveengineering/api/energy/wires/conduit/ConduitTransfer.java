/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

/**
 * How much energy moves down one channel of one hop of a bundle, and what arrives.
 * <p>
 * A bundle is a bucket brigade rather than a routed network. Each box, each tick, hands a share of
 * what it holds to whichever neighbour is holding less, and drains into any connector hung on the
 * matching breakout. That makes energy flow toward whatever is drawing it without anybody walking
 * the run: a box with a machine on it empties, which opens a gradient, which pulls from next door.
 * <p>
 * The alternative -- find every reachable sink for every live channel every tick and divide the
 * supply among them -- is what IE's wires do, and it is the reason the wire network has the
 * profiling history it has. A bundle is up to sixteen channels, so doing it that way would be
 * sixteen path walks per box per tick. This is a subtraction and a comparison.
 * <p>
 * The cost of the choice, stated plainly: energy takes one tick per hop to travel, so a long run
 * with boxes at every corner has a visible ramp-up when a load comes on. Boxes only exist at
 * corners and ends, so in practice that is a handful of ticks, and it is the honest behaviour for
 * something being described as a cable rather than a teleport.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitTransfer
{
	private ConduitTransfer()
	{
	}

	/**
	 * What one hop moves.
	 * <p>
	 * {@link #taken} leaves the source and {@link #delivered} arrives at the destination; the
	 * difference is line loss and is destroyed. They are separate numbers precisely so that nobody
	 * can accidentally credit the loss to somebody.
	 */
	public static final class Moved
	{
		public final int taken;
		public final int delivered;

		Moved(int taken, int delivered)
		{
			this.taken = taken;
			this.delivered = delivered;
		}

		public boolean isNothing()
		{
			return taken <= 0;
		}
	}

	public static final Moved NOTHING = new Moved(0, 0);

	/**
	 * One hop, one channel.
	 * <p>
	 * Half the difference rather than everything: handing over the whole gradient makes two boxes
	 * swap their contents back and forth forever, which shows up as a run that flickers between
	 * full and empty and never settles. Half converges.
	 * <p>
	 * A consequence worth stating: a difference of one moves nothing, so up to one unit of flux can
	 * sit a block further back along a run than it strictly needs to. Rounding that up instead
	 * would have a single unit hopping between two boxes for as long as the world is loaded, which
	 * is the worse of the two.
	 *
	 * @param from     what the source is holding on this channel
	 * @param to       what the destination is holding
	 * @param capacity the most either may hold on one channel
	 * @param rate     the most this channel may move in a tick -- the channel's own wire, not a
	 *                 share of some bundle-wide allowance. Sixteen conductors in a sleeve each
	 *                 carry what a conductor carries.
	 * @param loss     fraction destroyed in transit, 0..1
	 */
	public static Moved hop(int from, int to, int capacity, int rate, double loss)
	{
		if(from <= 0||rate <= 0||capacity <= 0)
			return NOTHING;
		int gradient = from-to;
		if(gradient <= 0)
			return NOTHING;
		int room = capacity-to;
		if(room <= 0)
			return NOTHING;
		int taken = Math.min(Math.min(gradient/2, rate), Math.min(from, room));
		if(taken <= 0)
			return NOTHING;
		//Rounded down, so loss never exceeds what was sent and a hop can never create energy. A
		//very small packet therefore travels loss-free, which is the right way round to be wrong:
		//the alternative rounds trickles away to nothing and a nearly-idle run silently eats them.
		int lost = (int)(taken*clamp(loss));
		return new Moved(taken, taken-lost);
	}

	/**
	 * What a breakout hands to a connector: everything it can, up to the rate.
	 * <p>
	 * No gradient here -- a connector is a way out of the bundle rather than another bucket, and
	 * holding energy back from one would mean a machine running at half speed for no reason a
	 * player could see. Loss is not charged either: the hop has already been paid for on the way
	 * in, and charging again at the door would make a two-box run quietly worse than a one-box run.
	 */
	public static int drain(int held, int rate)
	{
		if(held <= 0||rate <= 0)
			return 0;
		return Math.min(held, rate);
	}

	private static double clamp(double loss)
	{
		return loss < 0?0: loss > 1?1: loss;
	}
}
