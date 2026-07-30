/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

/**
 * The Hydraulic Crawler's numbers.
 * <p>
 * Gathered here rather than scattered across the classes that use them, so a pack author changing how
 * thirsty the machine is does not have to know that fuel is spent in the entity, capacity is declared
 * on the tank, and the reserve that stops it stranding you is a third place again.
 * <p>
 * Plain fields rather than an {@code IEConfig} block for now: the machine is new, these are the values
 * most likely to move, and wiring a config category for numbers that are still being argued about
 * would mean migrating a config file every time one of them changed. Promoting them is a small change
 * once they settle.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public final class CrawlerConfig
{
	private CrawlerConfig()
	{
	}

	/** Eight buckets of diesel. Enough for a session's work, not enough to forget about. */
	public static int fuelCapacity = 8000;

	/**
	 * Millibuckets a tick with the engine running -- which is whenever somebody is aboard.
	 * <p>
	 * A tenth of a bucket a second: the eight-bucket tank runs about eighty seconds of idling, and a
	 * machine left running with nobody working it is meant to be a waste.
	 */
	public static int fuelIdle = 2;

	/**
	 * And on top of that, per tick, while the attachment is working.
	 * <p>
	 * Demolition is what costs. Driving about is nearly free by comparison, which is the right shape:
	 * the fuel is a limit on how much you can knock down before refuelling, not a tax on moving.
	 */
	public static int fuelWorking = 12;

	/**
	 * Fuel below which the attachment stops but the tracks still turn.
	 * <p>
	 * The point is not to be generous, it is to avoid the worst failure this machine has: running dry
	 * mid-demolition, five hundred blocks from home, in a thing that cannot be picked up without a
	 * hammer and cannot be pushed. The reserve is enough to drive back.
	 */
	public static int fuelReserve = 400;
}
