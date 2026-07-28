/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

/**
 * The three buried tanks' shapes and capacities, kept free of any dependency on registered
 * blocks so the arithmetic stays directly testable.
 * <p>
 * All three are the same object at three scales -- a box with a hollow middle and one fitting
 * let into the top face -- so they are one parameterised {@link Tier} rather than three
 * hand-written shapes. That is not only less code: it means "how many blocks does a bulk depot
 * cost" and "where is the cap" are computed the same way for all three, and a mistake in either
 * shows up on the smallest tank, which is the one that gets built first and tested most.
 * <p>
 * <strong>The middle is hollow on purpose.</strong> A solid nine by nine by six depot would be
 * 486 blocks and 486 tile entities; a shell is 290, and it is also what a tank actually is. The
 * saving grows with the tier, which is the right way round -- the biggest structure is the one
 * that can least afford to be solid.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class BuriedTankGeometry
{
	private BuriedTankGeometry()
	{
	}

	/**
	 * How finely a tank's level is synced to the client: the gauge only moves when the reading
	 * crosses one of this many divisions, so a pipe filling a depot at 500 mB/t sends at most
	 * sixty-four packets over the whole fill instead of one every tick.
	 */
	public static final int GAUGE_DIVISIONS = 64;

	/**
	 * @return which of {@link #GAUGE_DIVISIONS} bands a level falls in. Empty is its own band, so
	 * the transition to and from empty always syncs -- "is there anything in it at all" is the one
	 * reading a player checks at a glance, and it is the one a coarse band would swallow.
	 */
	public static int divisionOf(int amount, int capacity)
	{
		if(amount <= 0)
			return 0;
		if(capacity <= 0)
			return GAUGE_DIVISIONS;
		//In longs: a four-million millibucket depot times sixty-four overflows an int, and the
		//negative that comes back would read as a level going down while the tank fills.
		return 1+(int)((long)(amount-1)*GAUGE_DIVISIONS/capacity);
	}

	/**
	 * One size of buried tank: a shell, a capacity, and the fitting on top.
	 */
	public static final class Tier
	{
		/**
		 * Lower-case identifier, matching the block meta's name.
		 */
		public final String name;
		public final int height;
		public final int depth;
		public final int width;
		/**
		 * H, L, W, in the order {@code TileEntityMultiblockPart} expects.
		 */
		public final int[] size;
		/**
		 * Millibuckets the assembled tank holds.
		 */
		public final int capacity;

		Tier(String name, int height, int depth, int width, int capacity)
		{
			this.name = name;
			this.height = height;
			this.depth = depth;
			this.width = width;
			this.size = new int[]{height, depth, width};
			this.capacity = capacity;
		}

		/**
		 * @return whether the shell occupies this cell of its {@code H x L x W} box. Everything on
		 * the outer surface is wall; everything strictly inside it is the void the fluid notionally
		 * occupies, and is not part of the structure at all.
		 */
		public boolean isPart(int h, int l, int w)
		{
			if(h < 0||h >= height||l < 0||l >= depth||w < 0||w >= width)
				return false;
			return h==0||h==height-1||l==0||l==depth-1||w==0||w==width-1;
		}

		/**
		 * @return whether this cell is the fill cap: the middle of the top face, and the only
		 * block of the tank a player can reach once it is backfilled
		 */
		public boolean isCap(int h, int l, int w)
		{
			return h==height-1&&l==depth/2&&w==width/2;
		}

		/**
		 * @return whether this cell is on the top face, which is the course that has to be covered
		 * for the tank to count as buried
		 */
		public boolean isRoof(int h, int l, int w)
		{
			return h==height-1&&isPart(h, l, w);
		}

		public int capIndex()
		{
			return PetroleumGeometry.structureIndex(size, height-1, depth/2, width/2);
		}

		/**
		 * @return how many blocks the whole shell is built from, the cap included
		 */
		public int blockCount()
		{
			int count = 0;
			for(int h = 0; h < height; h++)
				for(int l = 0; l < depth; l++)
					for(int w = 0; w < width; w++)
						if(isPart(h, l, w))
							count++;
			return count;
		}

		/**
		 * @return how many blocks of wall the shell needs, i.e. everything but the one cap
		 */
		public int wallCount()
		{
			return blockCount()-1;
		}

		/**
		 * @return how many cells of the box are hollow
		 */
		public int voidCount()
		{
			return height*depth*width-blockCount();
		}

		/**
		 * @return the number of cells in the box, which is the range {@code pos} indices run over
		 */
		public int cellCount()
		{
			return height*depth*width;
		}
	}

	/**
	 * One house. A cellar-sized box of iron sheetmetal holding a couple of days of heating oil,
	 * cheap enough to be the first thing built rather than the reward for a refinery.
	 * <p>
	 * Two by two by two has no interior at all, so this tier is solid whatever the shell rule
	 * says -- which is correct, and is why the rule is expressed as "the outer surface" rather
	 * than as an explicit hollowing step that would have to special-case it.
	 */
	public static final Tier DOMESTIC = new Tier("domestic_tank", 2, 2, 2, 32000);
	/**
	 * A forecourt, a workshop, a small district. Deliberately the same capacity as the existing
	 * Sheetmetal Tank: this is not a bigger store, it is the same store put underground, and the
	 * price of that is a five by five hole rather than a three by three tower.
	 */
	public static final Tier COMMERCIAL = new Tier("commercial_tank", 4, 5, 5, 512000);
	/**
	 * A refinery's own stock or a town's supply -- the thing a tanker fills.
	 * <p>
	 * Nearly eight times the commercial tank's capacity for three and a half times the blocks, so
	 * the tiers reward scale rather than merely permitting it. That progression is the reason to
	 * build the big one at all, given the hole it needs.
	 */
	public static final Tier BULK = new Tier("bulk_depot", 6, 9, 9, 4000000);

	public static final Tier[] TIERS = {DOMESTIC, COMMERCIAL, BULK};
}
