/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * One deposit's state: what it is, how much it started with, and how much is left.
 * <p>
 * Deliberately a plain data holder. Every rule about how fast it flows, when it needs a pump
 * and what depletion does lives in {@link ReservoirModel}, which keeps this serialisable and
 * that testable.
 * <p>
 * The type is held by name so that a deposit rolled from a type that a later build removes
 * degrades to "unknown, inert" rather than failing world load.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class Reservoir
{
	private final String type;
	private final int originalCapacity;
	private int remaining;

	/**
	 * Sub-millibucket production carried between draws, 0..1.
	 * <p>
	 * A depleted well seeps at a fraction of a millibucket per tick. Without carrying the
	 * remainder, a pump asking every 20 ticks would floor 0.5 mB to nothing every single time
	 * and an "exhausted but still seeping" field would in fact be stone dead -- which is the
	 * opposite of the promise the decline curve makes.
	 * <p>
	 * Transient: losing under a millibucket across a reload is not worth a save field.
	 */
	private transient double pending;

	/**
	 * How much has ever been put back into this deposit by enhanced recovery.
	 * <p>
	 * Persisted, and the only thing standing between a re-injection well and infinite oil: without
	 * it a player could cycle water in and crude out forever, because {@link #restore} on its own
	 * only stops at the original capacity and says nothing about how many times you may refill to
	 * it. The cap this is checked against lives in {@code PetroleumConfig}.
	 */
	private int restoredTotal;

	public Reservoir(String type, int originalCapacity)
	{
		this(type, originalCapacity, originalCapacity);
	}

	public Reservoir(String type, int originalCapacity, int remaining)
	{
		this.type = type==null?"": type;
		this.originalCapacity = Math.max(0, originalCapacity);
		this.remaining = Math.max(0, Math.min(remaining, this.originalCapacity));
	}

	public String getType()
	{
		return type;
	}

	@Nullable
	public ReservoirType resolveType()
	{
		return ReservoirHandler.getType(type);
	}

	/**
	 * @return true if this cell was rolled as containing nothing at all
	 */
	public boolean isEmpty()
	{
		return originalCapacity <= 0;
	}

	public int getOriginalCapacity()
	{
		return originalCapacity;
	}

	public int getRemaining()
	{
		return remaining;
	}

	/**
	 * Removes from the remaining pool, never below zero.
	 *
	 * @return the amount actually removed
	 */
	public int deplete(int amount)
	{
		if(amount <= 0)
			return 0;
		int taken = Math.min(remaining, amount);
		remaining -= taken;
		return taken;
	}

	/**
	 * Returns fluid to the deposit, never above what it originally held. Used by enhanced
	 * recovery, and by nothing else -- this is not a general-purpose setter.
	 *
	 * @return the amount actually restored
	 */
	public int restore(int amount)
	{
		if(amount <= 0)
			return 0;
		int room = originalCapacity-remaining;
		int stored = Math.min(room, amount);
		remaining += stored;
		restoredTotal += stored;
		return stored;
	}

	/**
	 * @return how much enhanced recovery has ever put back into this deposit
	 */
	public int getRestoredTotal()
	{
		return restoredTotal;
	}

	/**
	 * @return how much more enhanced recovery this deposit will accept before its lifetime
	 * allowance is used up. Zero for a deposit with no original capacity, so a dry hole cannot be
	 * turned into a well by pumping water at it.
	 */
	public int getRestoreAllowance(double capFraction)
	{
		if(originalCapacity <= 0||capFraction <= 0)
			return 0;
		long allowed = (long)(originalCapacity*Math.min(1.0, capFraction));
		return (int)Math.max(0, Math.min(Integer.MAX_VALUE, allowed-restoredTotal));
	}

	/**
	 * @return how much of the original pool is left, 0..1. An exhausted deposit reads 0 but is
	 * still capable of a residual seep -- see {@link ReservoirModel#flowRate}.
	 */
	public double getPending()
	{
		return pending;
	}

	/**
	 * Stores the carried sub-millibucket remainder. Clamped to 0..1: this banks a rounding
	 * remainder, not production the well was never asked for.
	 */
	public void setPending(double pending)
	{
		this.pending = pending < 0?0: pending > 1?1: pending;
	}

	public double getFraction()
	{
		if(originalCapacity <= 0)
			return 0;
		return (double)remaining/originalCapacity;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		nbt.setString("type", type);
		nbt.setInteger("capacity", originalCapacity);
		nbt.setInteger("remaining", remaining);
		if(restoredTotal > 0)
			nbt.setInteger("restored", restoredTotal);
		return nbt;
	}

	@Nullable
	public static Reservoir readFromNBT(NBTTagCompound nbt)
	{
		if(nbt==null||!nbt.hasKey("capacity"))
			return null;
		Reservoir reservoir = new Reservoir(nbt.getString("type"), nbt.getInteger("capacity"),
				nbt.getInteger("remaining"));
		//Absent on a save written before enhanced recovery existed, which reads as "none used yet"
		//-- the generous default, and the only one that does not retroactively punish a field.
		reservoir.restoredTotal = Math.max(0, nbt.getInteger("restored"));
		return reservoir;
	}

	@Override
	public String toString()
	{
		return "Reservoir["+(isEmpty()?"empty": type+", "+remaining+"/"+originalCapacity+" mB")+"]";
	}
}
