/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import java.util.Random;

/**
 * Every rule about how a deposit behaves: how big it rolls, how fast it gives fluid up, when it
 * stops doing so without help, and what depletion costs.
 * <p>
 * Nothing here touches {@code World} or {@code TileEntity} -- that is what makes the decline
 * curve, the free-flow threshold, the residual seep and the city-mode path all directly
 * unit-testable. The extraction blocks are thin callers; this is where the behaviour lives.
 * The same split the grid uses between {@code GridEngine} and its tile entities.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class ReservoirModel
{
	private ReservoirModel()
	{
	}

	/**
	 * Ceiling on how much elapsed time a single draw may be paid for, in ticks.
	 * <p>
	 * A safety clamp rather than a tuning knob, which is why it is not in the config: it exists
	 * only to stop a device that has been unloaded for months from computing months of flow at
	 * today's pressure in one go.
	 */
	public static final int MAX_CATCH_UP_TICKS = 200;

	//	=================================
	//		GENERATION
	//	=================================

	/**
	 * Rolls a capacity between a type's bounds, <strong>log-distributed</strong> rather than
	 * uniform.
	 * <p>
	 * A uniform roll over a 2M-16M range makes the average field enormous and a small one
	 * vanishingly rare, which flattens the whole discovery curve -- every field you find is
	 * "big enough". Weighting by magnitude instead means modest fields are the common case and
	 * a giant is a genuine event worth building a refinery around.
	 */
	public static int rollCapacity(Random random, ReservoirType type)
	{
		int min = Math.max(1, type.getMinCapacity());
		int max = Math.max(min, type.getMaxCapacity());
		if(min==max)
			return min;
		double rolled = min*Math.pow((double)max/min, random.nextDouble());
		return (int)Math.min(max, Math.round(rolled));
	}

	//	=================================
	//		FLOW
	//	=================================

	/**
	 * @return true if the deposit still has enough pressure to reach the surface unaided, so a
	 * wellhead alone is enough and no pump is needed
	 */
	public static boolean isFreeFlowing(Reservoir reservoir)
	{
		if(reservoir==null||reservoir.isEmpty())
			return false;
		return reservoir.getFraction() >= clampFraction(PetroleumConfig.freeFlowThreshold);
	}

	/**
	 * How much this deposit will give up per tick.
	 * <p>
	 * Above the free-flow threshold it produces at its peak rate whether or not a pump is
	 * attached -- that is the early-game reward for finding a fresh field. Below it, pressure
	 * has fallen too far to lift the fluid and an unpumped wellhead produces nothing at all,
	 * while a pumped one declines linearly from the peak toward a residual floor.
	 * <p>
	 * That floor is the important part: an exhausted field keeps seeping rather than stopping
	 * dead. A base built around a pumpjack should get slower, never stranded.
	 *
	 * @param pumped whether extraction equipment is driving the well
	 * @return rate in mB/tick, never negative
	 */
	public static double flowRate(Reservoir reservoir, boolean pumped)
	{
		if(reservoir==null||reservoir.isEmpty())
			return 0;
		double peak = Math.max(0, PetroleumConfig.peakFlowRate);
		double residual = Math.max(0, PetroleumConfig.residualFlowRate);
		double threshold = clampFraction(PetroleumConfig.freeFlowThreshold);
		double fraction = reservoir.getFraction();

		if(fraction >= threshold)
			return peak;
		if(!pumped)
			return 0;
		if(threshold <= 0)
			return Math.max(residual, peak);
		//Linear decline from the peak at the threshold down to the residual floor at empty.
		double scaled = residual+(peak-residual)*(fraction/threshold);
		return Math.max(residual, scaled);
	}

	/**
	 * Draws from a deposit, honouring its current flow rate.
	 * <p>
	 * In <strong>city mode</strong> the deposit never depletes: the rate is computed from its
	 * stored pressure as normal, but nothing is taken away. Prospecting, drilling and the
	 * free-flow-then-pump progression all still happen and still look identical -- only the
	 * bookkeeping goes away, which is exactly the trade city mode makes everywhere else.
	 *
	 * @param elapsedTicks ticks since this deposit was last drawn from; extraction is computed
	 *                     analytically over the interval rather than polled every tick
	 * @param pumped       whether extraction equipment is driving the well
	 * @param cityMode     see {@code CityMode.petroleum()}
	 * @param simulate     report what would move without moving it
	 * @return the amount available, in mB, never more than {@code max}
	 */
	public static int extract(Reservoir reservoir, int max, int elapsedTicks, boolean pumped,
							  boolean cityMode, boolean simulate)
	{
		if(reservoir==null||reservoir.isEmpty()||max <= 0||elapsedTicks <= 0)
			return 0;
		//The rate curve already bottoms out at the residual floor, so an exhausted deposit
		//reports its seep here without needing a special case.
		double rate = flowRate(reservoir, pumped);
		if(rate <= 0)
			return 0;

		//The rate is sampled once, at the pressure the deposit is at now. Over a long enough
		//interval that is simply wrong -- pressure would have declined as the fluid came out --
		//so a device that has been unloaded for a week must not be paid a week of peak flow.
		//Capping the interval says the well was not producing because nothing was there to
		//receive it, which is both the cheap answer and the physically honest one.
		int ticks = Math.min(elapsedTicks, MAX_CATCH_UP_TICKS);

		//Carry the sub-millibucket remainder, or a well seeping at a fraction of a millibucket
		//per tick would floor to nothing on every poll and never yield anything at all.
		double produced = rate*ticks+reservoir.getPending();
		long whole = (long)Math.floor(produced);
		int available = (int)Math.min(max, Math.min(Integer.MAX_VALUE, whole));

		if(!simulate)
			//Only the remainder is banked. Whole millibuckets the caller did not ask for are
			//dropped: the well flows whether or not anything is there to catch it.
			reservoir.setPending(produced-whole);

		if(available <= 0)
			return 0;
		//City mode leaves the pool alone, so the pressure reading -- and therefore the rate --
		//stays where it was rolled. The field keeps reading as the field it is.
		if(cityMode||simulate)
			return available;

		//An already-exhausted deposit is running on its residual seep, and `available` is that
		//seep -- the rate curve bottomed out at the floor and the carried remainder made it a
		//whole number. There is nothing in the pool to bound it against.
		//
		//A deposit that still held something is bounded by what was actually down there, so a
		//small field cannot be over-drawn just because the sampled rate was generous.
		boolean wasExhausted = reservoir.getRemaining()==0;
		int fromPool = reservoir.deplete(available);
		return wasExhausted?available: fromPool;
	}

	private static double clampFraction(double value)
	{
		return value < 0?0: value > 1?1: value;
	}
}
