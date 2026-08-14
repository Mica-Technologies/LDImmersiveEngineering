/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

/**
 * What a Gas Station Pump is allowed to hand over, what that costs its tank, and what its dials
 * read.
 * <p>
 * <strong>The transaction rules, away from the transaction.</strong> Everything here was inline in
 * {@code TileEntityGasPump}, wrapped around a Forge event, a player's inventory and a
 * {@code FluidStack} -- which between them made the one interesting part, the clamping, unreachable
 * from a test. The pump is the block a server's economy plugin actually talks to through
 * {@link blusunrize.immersiveengineering.api.petroleum.FuelDispensedEvent}, so "what happens when a
 * listener asks for more than the pump holds" is a question with an answer somebody may one day
 * depend on, and it should be written down as an assertion rather than as a comment.
 * <p>
 * World-free, like {@code ReservoirModel} and {@code WellheadFlow}.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class GasPumpAccounting
{
	private GasPumpAccounting()
	{
	}

	/**
	 * A price is a number the pump reports, not a currency this mod implements. Clamped so a
	 * malformed packet cannot store a negative or an absurd one.
	 */
	public static final int MAX_PRICE = 1000000;

	/**
	 * City mode: the token amount a fill actually costs the tank. The meter still turns and the
	 * odometer still climbs; only the tank stops emptying.
	 */
	public static final int CITY_SIP = 1;

	public static int clampPrice(int price)
	{
		return price < 0?0: price > MAX_PRICE?MAX_PRICE: price;
	}

	/**
	 * How much the pump may hand over before any listener has had its say.
	 *
	 * @param room      what the receiving container will take
	 * @param available what the pump actually holds
	 */
	public static int requested(int room, int available)
	{
		return Math.min(Math.max(0, room), Math.max(0, available));
	}

	/**
	 * What the pump hands over after the economy has had its say.
	 * <p>
	 * A listener that <em>lowers</em> the amount gets exactly what it asked for -- that is the whole
	 * point of being able to listen, and it is how a plugin says "this player can afford half a
	 * bucket". A listener that <em>raises</em> it gets no more than the pump is holding, because a
	 * pump cannot dispense fuel that is not in it however generous the economy feels.
	 * <p>
	 * A cancelled event, or a listener that answers with a negative, sells nothing at all rather
	 * than running the transaction backwards.
	 *
	 * @param listenerAmount what the event carries after listeners have run
	 * @param available      what the pump actually holds
	 * @param cancelled      whether a listener vetoed the sale
	 *
	 * @return millibuckets the pump may dispense, never negative
	 */
	public static int granted(int listenerAmount, int available, boolean cancelled)
	{
		if(cancelled)
			return 0;
		int allowed = Math.min(listenerAmount, Math.max(0, available));
		return Math.max(0, allowed);
	}

	/**
	 * What a dispense of this size actually takes out of the tank.
	 * <p>
	 * The odometer is deliberately <em>not</em> this number -- it counts what was handed over, so a
	 * city-mode forecourt still reads as having sold what it sold. See
	 * {@link #odometerAdvance(int)}.
	 */
	public static int tankDebit(int dispensed, boolean cityMode)
	{
		if(dispensed <= 0)
			return 0;
		return cityMode?Math.min(CITY_SIP, dispensed): dispensed;
	}

	/**
	 * What the lifetime meter advances by: the full amount, in both modes.
	 */
	public static int odometerAdvance(int dispensed)
	{
		return Math.max(0, dispensed);
	}

	/**
	 * Comparator level for the pump's tank.
	 * <p>
	 * Floors at 1 rather than 0 for any non-empty tank, so "a drop left" and "nothing at all" are
	 * distinguishable to redstone -- a forecourt that switches a refill pump on at zero would
	 * otherwise never switch it off until the tank was completely dry.
	 */
	public static int comparatorLevel(int amount, int capacity)
	{
		if(amount <= 0||capacity <= 0)
			return 0;
		return Math.max(1, Math.min(15, (int)((long)amount*15/capacity)));
	}

	/**
	 * How far the pump's model has to turn to face a given direction, in degrees about +Y.
	 * <p>
	 * Taken as a horizontal index rather than an {@code EnumFacing} so that the one piece of the
	 * pump's geometry that is easy to get backwards is also the one piece of it a test can reach:
	 * a model rotated the wrong way round is a pump whose price panel faces the wall, and nothing
	 * anywhere would report it.
	 * <p>
	 * The model is authored facing <strong>north</strong>, which is horizontal index 2 and the
	 * tile's default facing, so north must come out at zero. The sign follows the right-hand rule
	 * a rotation about +Y obeys in both the baked model and the renderer: +90 degrees carries
	 * north round to west.
	 *
	 * @param horizontalIndex {@link net.minecraft.util.EnumFacing#getHorizontalIndex()}
	 */
	public static float modelRotation(int horizontalIndex)
	{
		return 90f*(2-horizontalIndex);
	}

	/**
	 * @return true if a nozzle racked on a pump at {@code pumpX/Y/Z} will reach {@code targetX/Y/Z}
	 * <p>
	 * Squared throughout, so this never takes a square root and never disagrees with itself about a
	 * target sitting exactly on the limit.
	 */
	public static boolean withinNozzleRange(int pumpX, int pumpY, int pumpZ,
											int targetX, int targetY, int targetZ, int range)
	{
		long dx = targetX-pumpX, dy = targetY-pumpY, dz = targetZ-pumpZ;
		return dx*dx+dy*dy+dz*dz <= (long)range*range;
	}
}
