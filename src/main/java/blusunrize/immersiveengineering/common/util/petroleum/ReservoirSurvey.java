/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.petroleum;

import blusunrize.immersiveengineering.api.petroleum.PetroleumConfig;
import blusunrize.immersiveengineering.api.petroleum.Reservoir;
import blusunrize.immersiveengineering.api.petroleum.ReservoirModel;
import blusunrize.immersiveengineering.api.petroleum.ReservoirType;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * What a core sample records about the oil beneath the chunk it was taken from.
 * <p>
 * Reservoirs are server state: {@code ReservoirHandler}'s map only exists on the server, and a
 * client asking it anything gets whatever that process happens to have cached, which in
 * multiplayer is nothing. So the reading is taken once, on the server, at the moment the drill
 * finishes, and <strong>baked into the sample's NBT</strong> -- which travels with the item
 * stack for free. That is the same trick the mineral side already plays with {@code depletion},
 * and it is why the tooltip can be rendered client-side at all.
 * <p>
 * Baking it means the reading is a snapshot rather than a live feed, and it ages: a sample taken
 * before a neighbour drained the field still reports the field as it was. The sample already
 * carries a timestamp and already tells the player how old it is, so this is the honesty the
 * item was designed for rather than a wart.
 * <p>
 * What gets recorded deliberately stops short of exact millibuckets. A prospecting sample says
 * <em>whether</em> there is a field, roughly how big, and whether it still has the pressure to
 * flow -- which is the decision a player is actually making. Everything here is pure and works
 * on plain data, so the banding and the rounding are directly testable.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class ReservoirSurvey
{
	private ReservoirSurvey()
	{
	}

	/**
	 * Marks a sample as taken by a build that looks for oil at all.
	 * <p>
	 * Load-bearing for old saves: a sample cut before this existed has no such key, and must
	 * read as "we do not know" rather than "we looked and found nothing". Absence of the whole
	 * survey is a different statement from a survey that came back empty, and only this key can
	 * tell them apart.
	 */
	public static final String KEY_SURVEYED = "oilSurveyed";
	public static final String KEY_TYPE = "oilType";
	public static final String KEY_SIZE = "oilSize";
	public static final String KEY_PRESSURE = "oilPressure";
	public static final String KEY_FREE_FLOWING = "oilFreeFlowing";

	/**
	 * Size descriptions, smallest first. Suffixes of a lang key, never shown raw.
	 */
	public static final String[] SIZE_BANDS = {"marginal", "small", "moderate", "large", "giant"};

	//	=================================
	//		READINGS
	//	=================================

	/**
	 * Describes a deposit's size relative to the range it could have rolled in.
	 * <p>
	 * A band rather than a number because that is the level the mineral side reports at, and
	 * because an absolute millibucket figure is meaningless to a player who has no idea what a
	 * normal field holds. "Large for this world" is the useful statement.
	 *
	 * @return one of {@link #SIZE_BANDS}, or an empty string if there is nothing to describe
	 */
	public static String sizeBand(int capacity, int minCapacity, int maxCapacity)
	{
		if(capacity <= 0)
			return "";
		int min = Math.max(1, minCapacity);
		int max = Math.max(min, maxCapacity);
		if(max <= min)
			//Every field in this world is the same size, so no band distinguishes anything.
			return SIZE_BANDS[SIZE_BANDS.length/2];

		//Capacities are rolled log-distributed, so the bands have to be cut on the same scale.
		//Splitting the range linearly would drop the overwhelming majority of fields into the
		//bottom band and the top one would essentially never be printed.
		int clamped = capacity < min?min: capacity > max?max: capacity;
		double position = Math.log((double)clamped/min)/Math.log((double)max/min);
		int index = (int)Math.floor(position*SIZE_BANDS.length);
		return SIZE_BANDS[index < 0?0: index >= SIZE_BANDS.length?SIZE_BANDS.length-1: index];
	}

	public static String sizeBand(@Nullable Reservoir reservoir)
	{
		if(reservoir==null||reservoir.isEmpty())
			return "";
		//A type dropped by a later build leaves the deposit without bounds of its own; the
		//config's are the closest honest stand-in, and the alternative is refusing to describe a
		//field that is plainly still down there.
		ReservoirType type = reservoir.resolveType();
		int min = type!=null?type.getMinCapacity(): PetroleumConfig.minCapacity;
		int max = type!=null?type.getMaxCapacity(): PetroleumConfig.maxCapacity;
		return sizeBand(reservoir.getOriginalCapacity(), min, max);
	}

	/**
	 * @return how much of the deposit is left, as a whole percentage
	 */
	public static int pressurePercent(@Nullable Reservoir reservoir)
	{
		if(reservoir==null||reservoir.isEmpty())
			return 0;
		int percent = (int)Math.round(100*reservoir.getFraction());
		if(percent > 100)
			return 100;
		//A field worked down to under half a percent still holds tens of thousands of
		//millibuckets and still seeps. Rounding it to a flat zero would read as "exhausted" and
		//send a player away from a well that is only slow.
		return percent < 1&&reservoir.getRemaining() > 0?1: percent;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	/**
	 * Writes a reading onto a sample's tag.
	 * <p>
	 * The marker goes on whether or not anything was found, because "there is nothing under this
	 * chunk" is the answer a prospector most often gets and the one they most need to trust.
	 */
	public static NBTTagCompound write(NBTTagCompound nbt, @Nullable Reservoir reservoir)
	{
		if(nbt==null)
			return null;
		nbt.setBoolean(KEY_SURVEYED, true);
		if(reservoir==null||reservoir.isEmpty())
			return nbt;
		nbt.setString(KEY_TYPE, reservoir.getType());
		nbt.setString(KEY_SIZE, sizeBand(reservoir));
		nbt.setInteger(KEY_PRESSURE, pressurePercent(reservoir));
		nbt.setBoolean(KEY_FREE_FLOWING, ReservoirModel.isFreeFlowing(reservoir));
		return nbt;
	}

	public static boolean isSurveyed(@Nullable NBTTagCompound nbt)
	{
		return nbt!=null&&nbt.getBoolean(KEY_SURVEYED);
	}

	/**
	 * Keyed off the size band rather than the type name: a deposit whose type a later build
	 * removed still has a size, and is still very much down there.
	 */
	public static boolean hasReservoir(@Nullable NBTTagCompound nbt)
	{
		return isSurveyed(nbt)&&nbt.hasKey(KEY_SIZE);
	}

	public static String getType(@Nullable NBTTagCompound nbt)
	{
		return nbt==null?"": nbt.getString(KEY_TYPE);
	}

	public static String getSizeBand(@Nullable NBTTagCompound nbt)
	{
		return nbt==null?"": nbt.getString(KEY_SIZE);
	}

	public static int getPressure(@Nullable NBTTagCompound nbt)
	{
		return nbt==null?0: nbt.getInteger(KEY_PRESSURE);
	}

	public static boolean isFreeFlowing(@Nullable NBTTagCompound nbt)
	{
		return nbt!=null&&nbt.getBoolean(KEY_FREE_FLOWING);
	}
}
