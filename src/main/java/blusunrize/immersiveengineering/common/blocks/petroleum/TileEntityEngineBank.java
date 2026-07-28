/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockEngineBank;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.IELogger;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Reciprocating Engine Bank: the honest workhorse, and the only plant in the expansion that
 * scales by building more of itself.
 * <p>
 * Everything else in section five buys its power with a condition. The turbine wants ten seconds
 * before it is worth anything and hates being cycled; the boiler and the hall want a whole
 * combined cycle laid out around them. This one wants a fuel line and nothing else. It goes from
 * nothing to six thousand Flux in a single work pass, it takes diesel, biodiesel or gas without
 * caring which, and it gives that back by being the least efficient thing on the table: three
 * hundred Flux per millibucket of diesel against the combined-cycle plant's five hundred. That
 * inefficiency is the price of never having to think about it, and it is deliberately not
 * negotiable -- there is no ramp to tune, no waste heat to recover, and no reason to ever switch
 * one off.
 *
 * <h3>Why "extensible" is implemented as linking</h3>
 * The plan describes this machine as extensible: add cylinder sections along one axis to scale
 * output. IE's multiblock framework cannot do that. {@code IMultiblock} is a fixed
 * {@code H x L x W} box: {@code TileEntityMultiblockPart} stores one integer index into that box,
 * {@code getBlockPosForPos} divides by the box's dimensions to turn an index back into a position,
 * and {@code disassemble} walks exactly those dimensions. A structure whose size is not known when
 * the tile entity is constructed has no valid index at all, and every one of those three would have
 * to be replaced to give it one.
 * <p>
 * So a bank is a fixed four-by-five-by-five building, and <strong>banks placed flush against each
 * other along the width axis link into one installation</strong>: they pool their fuel, they
 * present as one plant, and a longer line runs slightly more efficiently than the same engines
 * scattered around a base. The shape was drawn to make that read as a building rather than as a
 * rule -- the cylinder row, the fuel gallery, the walkway and the roof all run across the width, so
 * a second bank does not stand next to the first, it continues it. See
 * {@link MultiblockEngineBank}.
 * <p>
 * The efficiency bonus is small on purpose. Two per cent per extra bank, capped at fourteen, means
 * a full hall of eight makes what nine and a bit scattered banks would. That is a nicety for
 * building tidily, not a tax on building untidily; anyone who wants eight banks in eight different
 * places loses almost nothing, which is the right weight for a bonus nobody should have to plan
 * around.
 *
 * <h3>The four ways linking could go wrong, and what stops each</h3>
 * <ol>
 * <li><strong>Two banks each counting the other.</strong> A chain has exactly one leader and only
 * the leader burns fuel, computes output or pushes flux ({@link #runPass()} returns immediately for
 * everyone else). The other banks are inert; they do not so much as look at their own tank. A
 * removal in the middle would leave the old leader briefly working from a stale chain, so a bank
 * whose links have been invalidated runs its pass on that tick rather than waiting for its stagger
 * -- the correction lands on the same tick the block update does.</li>
 * <li><strong>Disagreeing about who leads.</strong> The leader is found by walking the cached
 * low-width links to the end of the row, which every member of a chain does identically because
 * they all share a facing and therefore a width axis. Ask any bank in a hall and it names the same
 * building.</li>
 * <li><strong>A chain that loops or grows without bound.</strong> A link is only ever resolved one
 * bank-width along a fixed axis, so following the low links strictly decreases one world
 * coordinate and a cycle is arithmetically impossible; {@link #MAX_ROW_SCAN} guards it anyway and
 * complains in the log if it ever fires. The installation itself is capped at {@link #MAX_CHAIN}:
 * a physically longer row is divided into consecutive halls of eight, counted from the end of the
 * row, which is a rule every bank in the row evaluates to the same answer.</li>
 * <li><strong>Breaking a bank out of the middle.</strong> Both survivors get block updates on the
 * seam they shared with it, re-resolve, and find nothing where it was. The walk from either side
 * then terminates at the gap, so what is left is two complete halls with a leader each -- not one
 * corrupt one. Nothing is stored about chain membership, on either side, which is why there is
 * nothing left over to be wrong.</li>
 * </ol>
 *
 * <h3>What this costs per tick</h3>
 * Nothing that scales with the hall. Links are resolved from the world only when a neighbour
 * changes or a chunk loads, and cached as references; the leader's pass follows those references
 * rather than searching, and happens once every {@link #WORK_INTERVAL} ticks staggered by position.
 * Between passes the leader walks an array of connectors that were resolved on the pass, and the
 * other seven banks of a hall of eight do a modulo and return. A linked hall of eight is strictly
 * cheaper per tick than eight unlinked banks, because seven of the eight passes are gone.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityEngineBank extends TileEntityMultiblockPart<TileEntityEngineBank>
		implements IBlockOverlayText, IComparatorOverride, INeighbourChangeTile
{
	//	=================================
	//		OUTPUT
	//	=================================

	/**
	 * What one bank makes at full load, in Flux per tick.
	 * <p>
	 * Just under a bank and a half of Diesel Generators, on a building fifteen times the size of
	 * one. That is not a good trade taken on its own, and it is not meant to be: what the size buys
	 * is a single fuel connection, a single pair of terminals and a machine that keeps scaling
	 * after the point where a wall of generators has stopped being a thing anyone wants to plumb.
	 */
	public static final int BANK_OUTPUT = 6000;

	/**
	 * Ticks between passes. Fuel accounting, the redstone switch, the chain walk and the search for
	 * connectors all happen here and nowhere else.
	 * <p>
	 * Half a second of latency on a machine whose entire selling point is instant response looks
	 * like a contradiction and is not one: the response being sold is against the turbine's ten
	 * seconds of spool, and the interval is the same accounting granularity every throttled machine
	 * in the expansion uses. A bank goes from nothing to full output in one pass and back again in
	 * one pass, with no state in between that could be called a ramp.
	 */
	public static final int WORK_INTERVAL = 10;

	/**
	 * How many banks may join one installation.
	 * <p>
	 * Eight is where the efficiency bonus caps, which makes the cap self-explaining: past this
	 * point another bank adds power and nothing else, so there is no reason to build a hall longer
	 * than the bonus rewards. It also bounds every walk in this class to a length that can be
	 * reasoned about, and forty metres of engine house is already a considerable building.
	 */
	public static final int MAX_CHAIN = 8;

	/**
	 * Extra output per bank beyond the first, in thousandths.
	 */
	public static final int CHAIN_BONUS_PERMILLE = 20;
	/**
	 * The ceiling on that bonus, reached at exactly {@link #MAX_CHAIN} banks.
	 */
	public static final int MAX_CHAIN_BONUS_PERMILLE = CHAIN_BONUS_PERMILLE*(MAX_CHAIN-1);

	/**
	 * The hall bonus, in thousandths of the un-bonused output.
	 * <p>
	 * A shared crankshaft house, one set of auxiliaries and one exhaust trunk are genuinely worth
	 * something over the same engines in eight sheds, and this is that, sized so it never becomes
	 * the reason to build anything.
	 *
	 * @param banks how many banks are running as one installation
	 * @return between 0 and {@link #MAX_CHAIN_BONUS_PERMILLE}
	 */
	public static int chainBonusPermille(int banks)
	{
		if(banks <= 1)
			return 0;
		return Math.min(MAX_CHAIN_BONUS_PERMILLE, (Math.min(banks, MAX_CHAIN)-1)*CHAIN_BONUS_PERMILLE);
	}

	/**
	 * What an installation of this many running banks delivers, in Flux per tick.
	 * <p>
	 * Zero for zero banks and for anything negative, which is not a defensive nicety: a chain
	 * resolved during a chunk load can legitimately come back empty, and a plant that answered
	 * "some" to "how many engines are running" would be generating power out of a hall that is not
	 * there.
	 */
	public static int chainOutput(int banks)
	{
		if(banks <= 0)
			return 0;
		int counted = Math.min(banks, MAX_CHAIN);
		return counted*BANK_OUTPUT*(1000+chainBonusPermille(counted))/1000;
	}

	/**
	 * Flux one bank is accounted for over one whole pass. Fuel is charged against this rather than
	 * against the delivered figure, so the hall bonus arrives as free output -- which is what makes
	 * it an efficiency bonus rather than a second throttle.
	 */
	public static final int ENERGY_PER_BANK_PASS = BANK_OUTPUT*WORK_INTERVAL;

	//	=================================
	//		FUEL
	//	=================================

	/**
	 * Flux per millibucket, keyed by fluid registry name.
	 * <p>
	 * Its own table rather than {@link blusunrize.immersiveengineering.api.energy.DieselHandler}'s,
	 * for the same reason the turbine keeps its own: that registry is the Diesel Generator's, its
	 * numbers are tuned against a 4096 Flux machine, and quietly inheriting it would mean every
	 * fuel anyone ever registers for a generator silently becomes an engine bank fuel at a rate
	 * nobody chose.
	 * <p>
	 * The ordering is the whole design. Diesel is refined, dense and worth the column that made it.
	 * Biodiesel is the same idea grown in a field and is a fifth worse, which is exactly the margin
	 * it already carries against diesel elsewhere in the mod. Natural gas is deliberately the worst
	 * of the three here and comfortably beaten by the Gas Turbine, because gas is the turbine's
	 * home fuel and this machine should not be the answer to everything.
	 * <p>
	 * The absolute level is set against the <em>Diesel Generator</em>, which gets 664 flux from a
	 * millibucket of diesel and 512 from biodiesel. These numbers were first pinned well below
	 * that, which made this machine strictly dominated: bigger, costlier, and worse per
	 * millibucket than a starter machine burning the same fuel, so there was no reason to ever
	 * build one. It now beats the generator on the liquids, by roughly the margin its size and
	 * cost deserve, while a combined-cycle plant still runs three to five times cleaner than it
	 * does. Mediocre efficiency means mediocre next to a power station, not worse than the box it
	 * replaces.
	 */
	public static final int FLUX_PER_MB_DIESEL = 750;
	public static final int FLUX_PER_MB_BIODIESEL = 600;
	public static final int FLUX_PER_MB_NATURAL_GAS = 500;

	private static final Map<String, Integer> FUELS = new HashMap<String, Integer>();

	static
	{
		//Keyed by registry name rather than by Fluid so the table can be read -- and checked --
		//without a fluid registry, which is also how DieselHandler and the turbine store their own.
		registerFuel("ie_diesel", FLUX_PER_MB_DIESEL);
		registerFuel("biodiesel", FLUX_PER_MB_BIODIESEL);
		registerFuel("natural_gas", FLUX_PER_MB_NATURAL_GAS);
	}

	/**
	 * @param fluidName the fluid's registry name
	 * @param fluxPerMb Flux yielded by one millibucket; zero or less removes the fuel
	 */
	public static void registerFuel(String fluidName, int fluxPerMb)
	{
		if(fluidName==null)
			return;
		if(fluxPerMb > 0)
			FUELS.put(fluidName, fluxPerMb);
		else
			FUELS.remove(fluidName);
	}

	/**
	 * @return Flux one millibucket of the named fluid is worth here, or 0 if the engines will not
	 * take it
	 */
	public static int getFluxPerMillibucket(String fluidName)
	{
		Integer flux = fluidName==null?null: FUELS.get(fluidName);
		return flux==null?0: flux;
	}

	public static boolean isValidFuel(String fluidName)
	{
		return getFluxPerMillibucket(fluidName) > 0;
	}

	public static boolean isValidFuel(@Nullable Fluid fluid)
	{
		return fluid!=null&&isValidFuel(fluid.getName());
	}

	/**
	 * Millibuckets one bank draws over one whole pass on the given fuel.
	 * <p>
	 * Rounded <em>up</em>. A fuel whose Flux value does not divide the pass exactly would otherwise
	 * be sold at a discount that grows the worse the fuel is, which is precisely backwards.
	 *
	 * @param fluxPerMb the fuel's worth, from {@link #getFluxPerMillibucket(String)}
	 * @return millibuckets, or 0 for a fluid the engines do not burn
	 */
	public static int fuelPerBankPass(int fluxPerMb)
	{
		if(fluxPerMb <= 0)
			return 0;
		return (ENERGY_PER_BANK_PASS+fluxPerMb-1)/fluxPerMb;
	}

	/**
	 * One bank's fuel tank.
	 * <p>
	 * Twenty seconds of gas at full load, forty of diesel. Shallow on purpose for a machine this
	 * size: a bank is meant to sit on a line, and the linking is a far better answer to "I want
	 * more buffer" than a deeper tank is -- eight linked banks pool three hundred and eighty-four
	 * buckets and any one of them can feed the lot.
	 */
	public static final int TANK_CAPACITY = 48000;

	/**
	 * City mode fuel sip: a token millibucket per pass for the whole installation, so tanks still
	 * visibly empty and refuelling still matters, but nothing is metered. The same trade the diesel
	 * generator, the turbine and the burner all make.
	 */
	private static final int CITY_FUEL_SIP = 1;

	/**
	 * A tank that refuses anything it cannot burn.
	 * <p>
	 * Not paranoia: a wellhead in this same expansion once destroyed its own output because its
	 * tank accepted a foreign fluid and then had nowhere to put the real one. A fuel line that
	 * carries the wrong cut has to bounce off the gallery, not fill it with something the engines
	 * will sit on top of forever.
	 */
	public final FluidTank tank = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return fluid!=null&&isValidFuel(fluid.getFluid());
		}
	};

	//	=================================
	//		STATE
	//	=================================

	private static final TileEntity[] NO_TERMINALS = new TileEntity[0];

	/**
	 * Whether this bank's own engines are firing. Synced, because it drives the plumes and the
	 * overlay and is only ever decided on the server.
	 */
	public boolean active;
	/**
	 * Which bank of the installation this one is, counting from the leader, and how many there are.
	 * <p>
	 * Both are synced for one reason: the chain is resolved server-side and
	 * {@link #getOverlayText} runs on the client. The lubrication manifold shipped with exactly
	 * this bug -- it resolved its host on the server and read the field on the client, so it
	 * cheerfully reported itself unattached while it was visibly working.
	 */
	public int chainIndex;
	public int chainSize = 1;
	/**
	 * What the whole installation is delivering, in Flux per tick. Synced so every bank of a hall
	 * can be asked what the hall is doing, which is the number a player actually wants.
	 */
	public int installationOutput;

	/**
	 * Whether this bank runs the installation. Derived on the pass and never persisted: it is a
	 * property of what is currently standing next to the building, and a saved copy would be a
	 * second source of truth for something the world already knows.
	 */
	private boolean leader = true;

	/**
	 * The banks against this one's two mating faces, resolved from the world only when
	 * {@link #linksDirty} says something has moved, and held as references in between. This is the
	 * lubrication manifold's arrangement -- resolve on neighbour change, cache, never poll -- with
	 * the one addition that a change here has to be handed on, because a bank at the far end of a
	 * hall never receives a block update from the near end.
	 */
	private TileEntityEngineBank linkLow;
	private TileEntityEngineBank linkHigh;
	private boolean linksDirty = true;

	/**
	 * Every connector standing in the switchyards of the whole installation, resolved on the pass.
	 * Only ever populated on the leader.
	 */
	private TileEntity[] terminals = NO_TERMINALS;

	private int stagger = -1;

	/**
	 * The one block of the structure a comparator reads from.
	 * <p>
	 * Gated to a single index rather than answered by every block: a seventy-five block building
	 * that is a comparator face on all sides would make redstone next to it behave differently
	 * from every other multiblock in the mod, and a hall of eight would turn a hundred metres of
	 * wall into one.
	 */
	public static final int REDSTONE_INDEX = MultiblockEngineBank.REDSTONE_POS;

	/**
	 * Hard stop on how far the row walk will go before it decides something is wrong.
	 * <p>
	 * It cannot fire. Links are resolved exactly one bank-width along a fixed axis, so the walk
	 * strictly decreases one world coordinate and cannot return to where it started. It is here
	 * because "cannot" is a claim about code that other people will edit, and the failure mode it
	 * guards against is a server hang rather than a wrong number.
	 */
	private static final int MAX_ROW_SCAN = 64;

	public TileEntityEngineBank()
	{
		super(PetroleumGeometry.ENGINE_SIZE);
	}

	//	=================================
	//		LINKING
	//	=================================

	@Override
	public void onLoad()
	{
		super.onLoad();
		//A chunk load produces no block updates, so nothing else would ever tell a bank that the
		//hall it belongs to has come back.
		linksDirty = true;
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		//Fired on whichever block of the building the change touched, which for the seam between
		//two banks is a raft block and never the master. Forwarding is the whole reason the master
		//ever hears about its neighbour being taken apart.
		TileEntityEngineBank master = master();
		if(master!=null)
			master.markLinksDirty();
	}

	public void markLinksDirty()
	{
		linksDirty = true;
	}

	/**
	 * The direction along which banks link: the structure's own width axis, so that a linked bank
	 * continues this one's cylinder row instead of standing behind it.
	 */
	private EnumFacing widthDirection()
	{
		return mirrored?facing.rotateYCCW(): facing.rotateY();
	}

	/**
	 * Looks for a bank flush against one of the two mating faces.
	 *
	 * @param high which face, high-width or low-width
	 * @return that bank's master, or null if there is nothing there this one can join
	 */
	@Nullable
	private TileEntityEngineBank findNeighbour(boolean high)
	{
		EnumFacing dir = high?widthDirection(): widthDirection().getOpposite();
		BlockPos mine = getBlockPosForPos(high?MultiblockEngineBank.MATING_HIGH: MultiblockEngineBank.MATING_LOW);
		TileEntity te = Utils.getExistingTileEntity(world, mine.offset(dir));
		if(!(te instanceof TileEntityEngineBank))
			return null;
		TileEntityEngineBank part = (TileEntityEngineBank)te;
		//Three things have to agree, and each rules out a different way of being next to something
		//without being part of it. The facing and the handedness, because two halls at right angles
		//share a wall but not a crankshaft. And the structure index, because the only block that
		//can legitimately be against this bank's high-width raft cell is the neighbour's low-width
		//one: anything else means the two buildings are offset, and an offset hall would have its
		//cylinder rows a block out of line with each other.
		if(!part.formed||part.facing!=facing||part.mirrored!=mirrored)
			return null;
		if(part.pos!=(high?MultiblockEngineBank.MATING_LOW: MultiblockEngineBank.MATING_HIGH))
			return null;
		TileEntityEngineBank neighbour = part.master();
		if(neighbour==null||neighbour==this||neighbour.isInvalid()||!neighbour.formed)
			return null;
		return neighbour;
	}

	private void resolveLinks()
	{
		//Cleared first, so that handing the flag to a neighbour below can never come back round and
		//re-enter this method.
		linksDirty = false;
		TileEntityEngineBank oldLow = linkLow;
		TileEntityEngineBank oldHigh = linkHigh;
		linkLow = findNeighbour(false);
		linkHigh = findNeighbour(true);
		boolean changed = oldLow!=linkLow||oldHigh!=linkHigh;

		//Two separate reasons to pass the flag on, and both are needed.
		//A neighbour whose own back-pointer does not name this bank has never seen it: that is a
		//bank that has just been hammered together, or a chunk that has just loaded, neither of
		//which produces a block update on the other side of the seam.
		//A neighbour that does name this bank still has to be told when something moved, because a
		//bank at the far end of a hall gets no block update from the near end. Passing the flag on
		//only when something actually changed is what keeps that from becoming an endless round of
		//mutual dirtying: the second bank re-resolves, finds its own links unchanged, and stops.
		if(linkLow!=null&&(changed||linkLow.linkHigh!=this))
			linkLow.markLinksDirty();
		if(linkHigh!=null&&(changed||linkHigh.linkLow!=this))
			linkHigh.markLinksDirty();
		if(oldLow!=null&&oldLow!=linkLow&&!oldLow.isInvalid())
			oldLow.markLinksDirty();
		if(oldHigh!=null&&oldHigh!=linkHigh&&!oldHigh.isInvalid())
			oldHigh.markLinksDirty();
	}

	@Nullable
	private TileEntityEngineBank neighbour(boolean high)
	{
		if(linksDirty)
			resolveLinks();
		TileEntityEngineBank found = high?linkHigh: linkLow;
		//A cached reference can go stale without anything reaching this bank: a chunk unload
		//invalidates a tile entity in place. Re-resolving on the spot costs two block lookups and
		//only happens the once.
		if(found!=null&&(found.isInvalid()||!found.formed))
		{
			resolveLinks();
			found = high?linkHigh: linkLow;
		}
		return found;
	}

	/**
	 * Works out which installation this bank belongs to.
	 * <p>
	 * The row is walked to its low-width end to find out how far along it this bank sits, and the
	 * installation is then the block of at most {@link #MAX_CHAIN} banks that position falls in,
	 * counted from that end. Splitting a longer row into consecutive halls rather than truncating
	 * it means the ninth bank of a row of twelve is the leader of a hall of four instead of a
	 * building that quietly does nothing, and every bank in the row evaluates the rule to the same
	 * answer -- which is the property that matters, because any of them may be asked first.
	 *
	 * @return the installation's banks in row order, leader first, always containing this one
	 */
	private List<TileEntityEngineBank> resolveChain()
	{
		int distance = 0;
		TileEntityEngineBank walk = this;
		while(distance < MAX_ROW_SCAN)
		{
			TileEntityEngineBank low = walk.neighbour(false);
			if(low==null)
				break;
			walk = low;
			distance++;
		}
		if(distance >= MAX_ROW_SCAN)
			IELogger.warn("Engine bank row at {} is longer than {} banks, or its links have looped; "
					+"treating this bank as the end of it.", getPos(), MAX_ROW_SCAN);

		int index = distance%MAX_CHAIN;
		TileEntityEngineBank first = this;
		for(int step = 0; step < index; step++)
		{
			TileEntityEngineBank low = first.neighbour(false);
			if(low==null)
			{
				//The row shortened between the two walks -- something was broken mid-resolve. Take
				//what is actually there rather than an index into a hall that no longer exists.
				index = step;
				break;
			}
			first = low;
		}

		List<TileEntityEngineBank> chain = new ArrayList<TileEntityEngineBank>(MAX_CHAIN);
		chain.add(first);
		TileEntityEngineBank next = first;
		while(chain.size() < MAX_CHAIN)
		{
			next = next.neighbour(true);
			if(next==null)
				break;
			chain.add(next);
		}
		//Deliberately does not touch chainIndex. That field is synced, and the only place allowed
		//to move it is setDisplay, which sends the packet that goes with it -- a follower quietly
		//writing its own index here would leave the client holding the old one forever, because the
		//leader's next comparison would find nothing to send.
		return chain;
	}

	//	=================================
	//		RUNNING
	//	=================================

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(isDummy()||!formed)
			return;
		if(world.isRemote)
		{
			if(active&&world.getTotalWorldTime()%4==0)
				spawnPlumes();
			return;
		}

		//A bank whose links have been invalidated settles up on the spot instead of waiting for its
		//stagger. That is what keeps a bank taken out of the middle of a hall from leaving the old
		//leader working from a chain that includes a building which is no longer there: the
		//correction lands on the same tick as the block update, not up to half a second later.
		if(linksDirty||(world.getTotalWorldTime()+getStagger())%WORK_INTERVAL==0)
			runPass();
		//Flux has to be handed over every tick -- a connector accepts a limited amount per tick and
		//would throw away anything delivered in a lump -- but this is a walk of an array of
		//references resolved on the pass, done by one bank of the hall. No world lookup happens
		//here, and no part of it grows with the number of banks that are linked.
		if(leader&&installationOutput > 0)
			pushOutput();
	}

	/**
	 * One pass. Everything that costs anything happens here, on the leader, once per interval.
	 */
	private void runPass()
	{
		List<TileEntityEngineBank> chain = resolveChain();
		boolean isLeader = chain.get(0)==this;
		if(isLeader!=leader)
		{
			leader = isLeader;
			if(!isLeader)
			{
				//Stood down. Drop everything the old post came with, so that nothing is left that
				//could deliver power for an installation this bank no longer speaks for.
				terminals = NO_TERMINALS;
				installationOutput = 0;
			}
		}
		//A follower's display is written by its leader, every leader pass. All it has to do for
		//itself is notice the day it stops being one, which the check above has just done.
		if(!isLeader)
			return;
		runInstallation(chain);
	}

	private void runInstallation(List<TileEntityEngineBank> chain)
	{
		//Resolved before anything is burnt: a plant with nowhere to put its power is shut down
		//rather than run against an open breaker, exactly as the turbine is.
		refreshTerminals(chain);

		int size = chain.size();
		boolean[] firing = new boolean[size];
		int running = 0;
		if(terminals.length > 0)
		{
			boolean city = CityMode.petroleum();
			//City mode takes one token millibucket for the whole installation and then stops
			//counting. The building still runs dry, still has to be filled and still has a redstone
			//switch; what it no longer has is arithmetic per bank per pass.
			boolean cityFuelled = city&&drawCitySip(chain);
			for(int i = 0; i < size; i++)
			{
				TileEntityEngineBank bank = chain.get(i);
				//Per bay rather than per hall: a redstone signal on one building's gallery shuts
				//that bay down and leaves the rest of the hall running, which is the only sensible
				//reading of a switch on a machine made of switchable pieces.
				if(bank.isSwitchedOff())
					continue;
				if(city)
				{
					if(!cityFuelled)
						break;
				}
				else if(!payForOneBank(chain))
					break;
				firing[i] = true;
				running++;
			}
		}

		int output = chainOutput(running);
		for(int i = 0; i < size; i++)
			chain.get(i).setDisplay(i, size, firing[i], output);
		//Nothing is marked dirty here on purpose. The only thing a pass changes that has to survive
		//a chunk save is a tank, and the bank whose tank paid marks itself. A leader dirtying the
		//whole hall every ten ticks would be eight chunk saves an installation for state that is
		//not written down.
	}

	/**
	 * Takes one bank's worth of fuel out of the installation, from whichever tank in it can pay.
	 * <p>
	 * This is the pooling, and it is the practical reason to link rather than the arithmetic one: a
	 * hall is fed through whichever gallery is nearest the pipe, and a bay whose own tank is dry
	 * still runs on its neighbour's. Tanks are tried in row order so the answer does not depend on
	 * which bank happened to ask.
	 *
	 * @return whether an entire bank-pass was paid for. Nothing is drained unless it was: a bank
	 * half-fuelled would be a ramp, and this machine does not have one.
	 */
	private static boolean payForOneBank(List<TileEntityEngineBank> chain)
	{
		for(int i = 0; i < chain.size(); i++)
		{
			TileEntityEngineBank bank = chain.get(i);
			FluidStack fuel = bank.tank.getFluid();
			if(fuel==null||fuel.getFluid()==null)
				continue;
			int cost = fuelPerBankPass(getFluxPerMillibucket(fuel.getFluid().getName()));
			//Both halves of this matter, and the second one is the one that gets forgotten. A fluid
			//the engines do not burn costs nothing per pass, and nothing is affordable out of an
			//empty tank -- which is exactly how the industrial burner once heated for free forever.
			if(cost <= 0||bank.tank.getFluidAmount() < cost)
				continue;
			bank.tank.drain(cost, true);
			bank.markDirty();
			return true;
		}
		return false;
	}

	private static boolean drawCitySip(List<TileEntityEngineBank> chain)
	{
		for(int i = 0; i < chain.size(); i++)
		{
			TileEntityEngineBank bank = chain.get(i);
			FluidStack fuel = bank.tank.getFluid();
			if(fuel==null||!isValidFuel(fuel.getFluid())||bank.tank.getFluidAmount() < CITY_FUEL_SIP)
				continue;
			bank.tank.drain(CITY_FUEL_SIP, true);
			bank.markDirty();
			return true;
		}
		return false;
	}

	private boolean isSwitchedOff()
	{
		return world.getRedstonePowerFromNeighbors(getPos()) > 0;
	}

	/**
	 * Re-reads what is standing in the switchyards of the whole installation.
	 * <p>
	 * All of them, not just the running bays: a hall is one plant with one switchyard, and a player
	 * who wired the far end of it should not lose their connection because the near end ran out of
	 * diesel.
	 */
	private void refreshTerminals(List<TileEntityEngineBank> chain)
	{
		List<TileEntity> found = null;
		for(int i = 0; i < chain.size(); i++)
		{
			TileEntityEngineBank bank = chain.get(i);
			for(int t = 0; t < MultiblockEngineBank.TERMINAL_COUNT; t++)
			{
				BlockPos deck = bank.getBlockPosForPos(MultiblockEngineBank.terminalPos(t)).up();
				TileEntity te = Utils.getExistingTileEntity(world, deck);
				if(!EnergyHelper.isFluxReceiver(te, EnumFacing.DOWN))
					continue;
				if(found==null)
					found = new ArrayList<TileEntity>(chain.size()*MultiblockEngineBank.TERMINAL_COUNT);
				found.add(te);
			}
		}
		terminals = found==null?NO_TERMINALS: found.toArray(new TileEntity[found.size()]);
	}

	/**
	 * Hands the tick's flux to the installation's connectors, split as evenly as whole units allow.
	 */
	private void pushOutput()
	{
		int live = 0;
		for(TileEntity terminal : terminals)
			if(terminal!=null&&!terminal.isInvalid())
				live++;
		if(live < 1)
			return;
		int share = installationOutput/live;
		int leftover = installationOutput%live;
		for(TileEntity terminal : terminals)
			if(terminal!=null&&!terminal.isInvalid())
				EnergyHelper.insertFlux(terminal, EnumFacing.DOWN, share+(leftover-- > 0?1: 0), false);
	}

	/**
	 * Writes the four things a client needs and sends a packet only if one of them moved. Called on
	 * every bank of the installation by its leader, so a player pointing at any part of a hall is
	 * told the truth about the whole of it.
	 */
	private void setDisplay(int index, int size, boolean firing, int output)
	{
		if(chainIndex==index&&chainSize==size&&active==firing&&installationOutput==output)
			return;
		chainIndex = index;
		chainSize = size;
		active = firing;
		installationOutput = output;
		//A block update and no markDirty: none of these four are saved, so there is nothing here
		//for a chunk save to carry, and the packet is the entire point.
		markContainingBlockForUpdate(null);
	}

	/**
	 * Spreads passes across ticks by position, so a hall's neighbours never all settle their fuel
	 * on the same one.
	 */
	public int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), WORK_INTERVAL);
		return stagger;
	}

	private void spawnPlumes()
	{
		for(int s = 0; s < MultiblockEngineBank.STACK_COUNT; s++)
		{
			BlockPos stack = getBlockPosForPos(MultiblockEngineBank.stackPos(s));
			//Straight up and slow. A reciprocating engine's exhaust is a lazy stack plume, not a
			//turbine's jet, and the difference is worth having at a glance across a base.
			world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
					stack.getX()+.5, stack.getY()+1.1, stack.getZ()+.5, 0, .03, 0);
		}
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		//Read off this block's own master, which is the bank being pointed at rather than the one
		//running the hall: "which building am I looking at" is the question, and the leader answers
		//it for the whole installation by writing these fields onto every member.
		TileEntityEngineBank master = master();
		if(!formed||master==null||!master.formed)
			return null;
		int size = Math.max(1, master.chainSize);
		String state = master.active
				?TextFormatting.GREEN+"Running"+TextFormatting.RESET
				: TextFormatting.GRAY+"Stopped"+TextFormatting.RESET;
		String hall = size > 1
				?"Bank "+(master.chainIndex+1)+" of "+size+" -- +"+(chainBonusPermille(size)/10)+"% hall bonus"
				: "Standing alone";
		return new String[]{state, hall,
				master.installationOutput+" IF/t -- "+master.tank.getFluidAmount()+" / "+TANK_CAPACITY+" mB"};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		if(pos!=REDSTONE_INDEX)
			return 0;
		TileEntityEngineBank master = master();
		if(master==null||!master.formed)
			return 0;
		//This bank's own tank, not the hall's pooled level. Each building has its own gallery and
		//its own comparator, so a player automating a fuel line to one bay reads that bay -- which
		//is the level that decides whether their pipe has to run.
		return master.tank.getFluidAmount()*15/TANK_CAPACITY;
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The building is a box of boxes; there is no shape to carve.
		return null;
	}

	/**
	 * @return whether fuel may be pushed into this part of the building
	 */
	public static boolean isFuelPort(int pos)
	{
		//The raft all round, plus the fuel gallery across the front. Generous on purpose: a fuel
		//line that has to find one exact block of a seventy-five block building is a fuel line the
		//player gets wrong, and a hall of eight makes that six hundred blocks to guess among.
		if(pos < 0)
			return false;
		return pos < PetroleumGeometry.ENGINE_DEPTH*PetroleumGeometry.ENGINE_WIDTH
				+PetroleumGeometry.ENGINE_WIDTH;
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityEngineBank master = master();
		if(master!=null&&formed&&isFuelPort(pos))
			return new IFluidTank[]{master.tank};
		return new IFluidTank[0];
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return resource!=null&&isValidFuel(resource.getFluid());
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Fuel goes in and is burnt. Letting it back out would make a bank a free fluid store that
		//happens to also be a machine -- and a linked hall an eight-fold one.
		return false;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		//Read back out of the shape rather than assumed: the building is five different blocks, and
		//handing back seventy-five oilfield frames would turn every disassembly into a free trade
		//of sheetmetal, scaffolding and engineering blocks for frames.
		int perLayer = PetroleumGeometry.ENGINE_DEPTH*PetroleumGeometry.ENGINE_WIDTH;
		ItemStack original = pos < 0?null
				: MultiblockEngineBank.instance.getStructureManual()
				[pos/perLayer]
				[pos%perLayer/PetroleumGeometry.ENGINE_WIDTH]
				[pos%PetroleumGeometry.ENGINE_WIDTH];
		//An unformed block dropped in the world is always a frame; that is the part of the building
		//that is a placeable item in its own right.
		return original==null?new ItemStack(IEContent.blockPetroleumDevice, 1,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta()): original.copy();
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		active = nbt.getBoolean("active");
		chainIndex = nbt.getInteger("chainIndex");
		chainSize = Math.max(1, nbt.getInteger("chainSize"));
		installationOutput = nbt.getInteger("installationOutput");
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		if(!descPacket)
			return;
		//These three go on the description packet and nowhere else. They have to be sent, because
		//the overlay and the plumes are drawn on a side that never resolves a chain -- the
		//lubrication manifold's "not attached to a machine" bug is exactly what happens when that
		//is left to look after itself. They must equally never be *saved*, because they describe
		//what is standing around this building rather than anything about the building, and a
		//reloaded chunk that came back already believing it was leading a hall of eight would
		//deliver a hall of eight's power for as long as it took to find out otherwise. Derived
		//state that is only ever resolved forwards cannot go stale if it is never written down.
		nbt.setBoolean("active", active);
		nbt.setInteger("chainIndex", chainIndex);
		nbt.setInteger("chainSize", chainSize);
		nbt.setInteger("installationOutput", installationOutput);
	}
}
