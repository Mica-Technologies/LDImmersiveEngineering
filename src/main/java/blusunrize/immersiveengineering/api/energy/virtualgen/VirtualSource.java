/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * One metered plant: where it is, what it measured, and whether it may run virtually.
 * <p>
 * A Generation Meter sits in line between a generator and the wire connector that carries its power
 * away. Three positions matter, and they can straddle a chunk border: the <b>generator</b> behind the
 * meter, the <b>meter</b> itself, and the <b>source</b> connector in front of it, which is the node the
 * virtual push starts from. The plant is running for real only when all three are loaded.
 * <p>
 * The record outlives the meter's chunk being loaded -- that is the point of it -- and is saved in its
 * own file. Live figures (what was delivered this tick) are not persisted.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public class VirtualSource
{
	private final int dimension;
	private final BlockPos meterPos;
	private BlockPos generatorPos;
	private BlockPos sourcePos;
	/**
	 * Last measured sustainable output, IF/t.
	 */
	private int measuredRate;
	/**
	 * The source connector's own rate, IF/t -- a virtual push never exceeds what the real connector could
	 * carry.
	 */
	private int connectorRate;
	/**
	 * Whether the generator burns no fuel. Only free sources run virtually outside City Mode.
	 */
	private boolean freeSource;
	/**
	 * Registry name of the generator, for the readout and for diagnosing a classification.
	 */
	private String generatorId = "";
	/**
	 * World time of the last measurement that had data.
	 */
	private long lastMeasuredTime;
	private boolean enabled = true;

	//Live, not saved
	private int lastVirtualDelivered;
	private boolean virtualActive;

	public VirtualSource(int dimension, BlockPos meterPos, BlockPos generatorPos, BlockPos sourcePos)
	{
		this.dimension = dimension;
		this.meterPos = meterPos.toImmutable();
		this.generatorPos = generatorPos.toImmutable();
		this.sourcePos = sourcePos.toImmutable();
	}

	public int getDimension()
	{
		return dimension;
	}

	public BlockPos getMeterPos()
	{
		return meterPos;
	}

	public BlockPos getGeneratorPos()
	{
		return generatorPos;
	}

	public BlockPos getSourcePos()
	{
		return sourcePos;
	}

	/**
	 * The meter was rotated: the generator and source sides moved with it.
	 */
	public void setSides(BlockPos generatorPos, BlockPos sourcePos)
	{
		this.generatorPos = generatorPos.toImmutable();
		this.sourcePos = sourcePos.toImmutable();
	}

	public int getMeasuredRate()
	{
		return measuredRate;
	}

	public int getConnectorRate()
	{
		return connectorRate;
	}

	/**
	 * @return what this plant pushes per tick while virtual: the measurement, capped by the connector's
	 * rate and the configured ceiling
	 */
	public int getVirtualRate()
	{
		int rate = Math.min(measuredRate, Math.max(0, VirtualGenConfig.maxRate));
		if(connectorRate > 0)
			rate = Math.min(rate, connectorRate);
		return Math.max(0, rate);
	}

	/**
	 * Stores a new measurement.
	 *
	 * @return true if anything saved changed
	 */
	public boolean updateMeasurement(int rate, int connectorRate, boolean freeSource, @Nullable String generatorId,
									 long worldTime)
	{
		String id = generatorId==null?"": generatorId;
		boolean changed = rate!=measuredRate||connectorRate!=this.connectorRate||freeSource!=this.freeSource
				||!id.equals(this.generatorId);
		this.measuredRate = Math.max(0, rate);
		this.connectorRate = Math.max(0, connectorRate);
		this.freeSource = freeSource;
		this.generatorId = id;
		this.lastMeasuredTime = worldTime;
		return changed;
	}

	public boolean isFreeSource()
	{
		return freeSource;
	}

	public String getGeneratorId()
	{
		return generatorId;
	}

	public long getLastMeasuredTime()
	{
		return lastMeasuredTime;
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	/**
	 * @return whether this plant may run virtually in the given mode: free sources always, fuel-burning
	 * ones only in City Mode, where an unloaded fuel plant burns nothing
	 */
	public boolean qualifies(boolean cityMode)
	{
		return cityMode||freeSource;
	}

	public int getLastVirtualDelivered()
	{
		return lastVirtualDelivered;
	}

	public boolean isVirtualActive()
	{
		return virtualActive;
	}

	void setLive(boolean virtualActive, int delivered)
	{
		this.virtualActive = virtualActive;
		this.lastVirtualDelivered = delivered;
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound nbt = new NBTTagCompound();
		nbt.setInteger("dim", dimension);
		nbt.setLong("meter", meterPos.toLong());
		nbt.setLong("generator", generatorPos.toLong());
		nbt.setLong("source", sourcePos.toLong());
		nbt.setInteger("rate", measuredRate);
		nbt.setInteger("connectorRate", connectorRate);
		nbt.setBoolean("free", freeSource);
		nbt.setString("generatorId", generatorId);
		nbt.setLong("measured", lastMeasuredTime);
		nbt.setBoolean("enabled", enabled);
		return nbt;
	}

	@Nullable
	public static VirtualSource readFromNBT(NBTTagCompound nbt)
	{
		if(!nbt.hasKey("meter")||!nbt.hasKey("source")||!nbt.hasKey("generator"))
			return null;
		VirtualSource source = new VirtualSource(nbt.getInteger("dim"), BlockPos.fromLong(nbt.getLong("meter")),
				BlockPos.fromLong(nbt.getLong("generator")), BlockPos.fromLong(nbt.getLong("source")));
		source.measuredRate = Math.max(0, nbt.getInteger("rate"));
		source.connectorRate = Math.max(0, nbt.getInteger("connectorRate"));
		source.freeSource = nbt.getBoolean("free");
		source.generatorId = nbt.getString("generatorId");
		source.lastMeasuredTime = nbt.getLong("measured");
		source.enabled = !nbt.hasKey("enabled")||nbt.getBoolean("enabled");
		return source;
	}
}
