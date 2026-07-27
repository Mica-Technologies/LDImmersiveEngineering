/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The registry of deposit types and the map of what is underneath the world.
 * <p>
 * Modelled on {@code ExcavatorHandler}, and for the same reasons: deposits are
 * <strong>virtual</strong>. Nothing is placed underground, nothing is generated at chunk-gen
 * time, and a cell is rolled deterministically from the world seed the first time anybody asks
 * about it. Three things fall out of that, all of them worth having:
 * <ul>
 * <li>world generation costs nothing, because there is none;</li>
 * <li><strong>existing worlds get oil for free</strong> -- there is no retrogen problem
 * because there is nothing to retro-generate;</li>
 * <li>only cells somebody has actually drawn from need persisting.</li>
 * </ul>
 * Deposits are rolled per <em>cell</em> of {@link PetroleumConfig#cellChunkSize} square chunks
 * rather than per chunk, so a field covers a believable area and neighbouring chunks agree
 * about what lies beneath them.
 * <p>
 * The rolling itself is a pure function of a seeded {@link Random} ({@link #rollCell}), so the
 * distribution can be tested without a world.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class ReservoirHandler
{
	/**
	 * Bumped whenever the persisted shape changes, so a future reader can migrate.
	 */
	public static final int DATA_VERSION = 1;

	private static final Map<String, ReservoirType> TYPES = new LinkedHashMap<>();

	/**
	 * Rolled cells, keyed by dimension and cell coordinates. Only cells that have been queried
	 * are present, and only those that have been drawn from are worth saving.
	 */
	private static final Map<CellPos, Reservoir> CACHE = new HashMap<>();

	//	=================================
	//		TYPE REGISTRY
	//	=================================

	/**
	 * @return the registered type, replacing any earlier one of the same name
	 */
	public static ReservoirType registerType(ReservoirType type)
	{
		if(type!=null)
			TYPES.put(type.getName(), type);
		return type;
	}

	@Nullable
	public static ReservoirType getType(String name)
	{
		return name==null?null: TYPES.get(name.toLowerCase(Locale.ENGLISH));
	}

	public static Collection<ReservoirType> getTypes()
	{
		return Collections.unmodifiableCollection(TYPES.values());
	}

	/**
	 * Drops every registered type. Exists so a config reload can rebuild the table from
	 * scratch rather than accumulating stale bounds, and so tests start from a known state.
	 */
	public static void clearTypes()
	{
		TYPES.clear();
	}

	/**
	 * The name of the deposit every world rolls by default.
	 */
	public static final String CRUDE_OIL = "crude_oil";

	/**
	 * (Re-)installs the built-in deposit types from the current config.
	 * <p>
	 * Called at load <em>and</em> whenever the config changes, because the capacity bounds are
	 * config values baked into each type: without the second call, raising the ceiling would do
	 * nothing until the next restart and would look like the setting was ignored.
	 * <p>
	 * Deposits already rolled keep the size they were rolled at. Re-rolling them would rewrite
	 * a player's oil field out from under them on a config reload.
	 */
	public static void registerDefaults()
	{
		clearTypes();
		registerType(new ReservoirType(CRUDE_OIL, "ie_crude_oil", 1,
				PetroleumConfig.minCapacity, PetroleumConfig.maxCapacity));
	}

	public static int getTotalWeight()
	{
		int total = 0;
		for(ReservoirType type : TYPES.values())
			total += type.getWeight();
		return total;
	}

	/**
	 * Weighted pick across every registered type.
	 */
	@Nullable
	public static ReservoirType selectType(Random random)
	{
		int total = getTotalWeight();
		if(total <= 0)
			return null;
		int roll = random.nextInt(total);
		for(ReservoirType type : TYPES.values())
		{
			roll -= type.getWeight();
			if(roll < 0)
				return type;
		}
		return null;
	}

	/**
	 * Rolls what a single cell contains.
	 * <p>
	 * Pure: given the same {@link Random} state it always produces the same answer, which is
	 * what makes the whole scheme deterministic and what lets the distribution be tested
	 * without a world.
	 *
	 * @return the deposit, or an empty one if this cell rolled nothing
	 */
	public static Reservoir rollCell(Random random)
	{
		//Draw the presence roll unconditionally so that the random stream advances the same
		//way regardless of the outcome. Short-circuiting here would make the result depend on
		//how many types happen to be registered.
		double presence = random.nextDouble();
		ReservoirType type = selectType(random);
		if(type==null||presence > PetroleumConfig.cellChance)
			return new Reservoir("", 0);
		return new Reservoir(type.getName(), ReservoirModel.rollCapacity(random, type));
	}

	//	=================================
	//		THE MAP
	//	=================================

	/**
	 * Converts a chunk coordinate to the coordinate of the cell containing it.
	 */
	public static int toCell(int chunkCoord)
	{
		return Math.floorDiv(chunkCoord, Math.max(1, PetroleumConfig.cellChunkSize));
	}

	/**
	 * @return what lies under this chunk, rolling and caching it if this is the first ask.
	 * Never null; an absent deposit is an {@link Reservoir#isEmpty() empty} one.
	 */
	public static Reservoir getReservoir(long worldSeed, int dimension, int chunkX, int chunkZ)
	{
		CellPos cell = new CellPos(dimension, toCell(chunkX), toCell(chunkZ));
		Reservoir cached = CACHE.get(cell);
		if(cached!=null)
			return cached;

		Reservoir rolled;
		if(!PetroleumConfig.enabled||isBlacklisted(dimension))
			rolled = new Reservoir("", 0);
		else
			rolled = rollCell(new Random(cellSeed(worldSeed, cell)));
		CACHE.put(cell, rolled);
		return rolled;
	}

	/**
	 * Mixes the world seed with the cell's identity.
	 * <p>
	 * The multipliers are the odd constants vanilla uses for its own per-chunk seeding; the
	 * point is only that neighbouring cells land far apart in the sequence rather than
	 * producing visibly correlated fields.
	 */
	static long cellSeed(long worldSeed, CellPos cell)
	{
		long seed = worldSeed;
		seed = seed*341873128712L+cell.x*132897987541L;
		seed = seed*6364136223846793005L+cell.z*1442695040888963407L;
		seed ^= (long)cell.dimension*0x9E3779B97F4A7C15L;
		return seed;
	}

	public static boolean isBlacklisted(int dimension)
	{
		int[] blacklist = PetroleumConfig.dimensionBlacklist;
		if(blacklist==null)
			return false;
		for(int dim : blacklist)
			if(dim==dimension)
				return true;
		return false;
	}

	/**
	 * Drops every rolled cell. Called before loading a world so one world's deposits can never
	 * leak into another in the same process -- the trap {@code serverStarted} avoids for wires.
	 */
	public static void clear()
	{
		CACHE.clear();
	}

	/**
	 * @return every cell worth persisting: those that have been drawn from. A cell still at its
	 * rolled capacity re-rolls identically from the seed, so saving it would be pure noise.
	 */
	public static Map<CellPos, Reservoir> getDirtyCells()
	{
		Map<CellPos, Reservoir> out = new HashMap<>();
		for(Map.Entry<CellPos, Reservoir> entry : CACHE.entrySet())
		{
			Reservoir reservoir = entry.getValue();
			if(!reservoir.isEmpty()&&reservoir.getRemaining()!=reservoir.getOriginalCapacity())
				out.put(entry.getKey(), reservoir);
		}
		return out;
	}

	/**
	 * Drops cells that were cached as holding nothing.
	 * <p>
	 * A cell rolled while the feature was disabled -- or while its dimension was blacklisted --
	 * caches as empty like any genuine miss, and nothing ever asked it again. Turning petroleum
	 * back on mid-session therefore left every cell a player had already walked over permanently
	 * dry, with no way to tell that from bad luck.
	 * <p>
	 * Only the empty ones go. Rolling is deterministic, so a genuine miss re-rolls to the same
	 * miss and this costs nothing; a deposit that has been drawn from is never empty, so no
	 * player's depletion is thrown away by this.
	 */
	public static void invalidateEmptyCells()
	{
		CACHE.values().removeIf(Reservoir::isEmpty);
	}

	public static int getCachedCellCount()
	{
		return CACHE.size();
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	public static NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		nbt.setInteger("petroleumDataVersion", DATA_VERSION);
		NBTTagList list = new NBTTagList();
		for(Map.Entry<CellPos, Reservoir> entry : getDirtyCells().entrySet())
		{
			NBTTagCompound tag = entry.getValue().writeToNBT(new NBTTagCompound());
			tag.setInteger("dim", entry.getKey().dimension);
			tag.setInteger("cellX", entry.getKey().x);
			tag.setInteger("cellZ", entry.getKey().z);
			list.appendTag(tag);
		}
		nbt.setTag("reservoirs", list);
		return nbt;
	}

	public static void readFromNBT(NBTTagCompound nbt)
	{
		clear();
		if(nbt==null)
			return;
		NBTTagList list = nbt.getTagList("reservoirs", 10);
		for(int i = 0; i < list.tagCount(); i++)
		{
			NBTTagCompound tag = list.getCompoundTagAt(i);
			Reservoir reservoir = Reservoir.readFromNBT(tag);
			if(reservoir!=null)
				CACHE.put(new CellPos(tag.getInteger("dim"), tag.getInteger("cellX"),
						tag.getInteger("cellZ")), reservoir);
		}
	}

	/**
	 * Identity of one cell: a dimension and a pair of cell coordinates.
	 */
	public static class CellPos
	{
		public final int dimension;
		public final int x;
		public final int z;

		public CellPos(int dimension, int x, int z)
		{
			this.dimension = dimension;
			this.x = x;
			this.z = z;
		}

		@Override
		public boolean equals(Object o)
		{
			if(this==o)
				return true;
			if(!(o instanceof CellPos))
				return false;
			CellPos other = (CellPos)o;
			return dimension==other.dimension&&x==other.x&&z==other.z;
		}

		@Override
		public int hashCode()
		{
			int hash = dimension;
			hash = 31*hash+x;
			hash = 31*hash+z;
			return hash;
		}

		@Override
		public String toString()
		{
			return "cell "+x+", "+z+" in dim "+dimension;
		}
	}
}
