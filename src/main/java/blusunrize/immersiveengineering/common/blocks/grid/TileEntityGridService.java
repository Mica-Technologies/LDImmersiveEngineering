/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.energy.grid.GridConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridDeviceType;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.AbstractConnection;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.Utils;
import blusunrize.immersiveengineering.common.util.WireNetTransfer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Grid Service Unit: the point where power leaves a segment and re-enters the world.
 * <p>
 * It powers whatever it touches, trying the block it is bolted to first and then the
 * remaining faces. That rule is what makes both useful placements work with no special
 * casing: bolt it straight onto a machine or capacitor to power that, or bolt it to a pole
 * and attach an IE connector to feed a wire network.
 * <p>
 * Delivery is push-only, exactly like a wire connector's {@code outputEnergy}. There is
 * deliberately no extractable capability: a second, unbudgeted path into the segment ledger
 * would let a pull-consumer bypass the per-tick caps the engine enforces.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class TileEntityGridService extends TileEntityGridDevice implements INeighbourChangeTile
{
	/**
	 * Which faces had a flux receiver last time we looked. Recomputed on neighbour change
	 * rather than every tick.
	 */
	private final boolean[] receiverFaces = new boolean[6];
	private boolean facesDirty = true;

	/**
	 * Scratch for the wire push, owned here rather than allocated per call for the same reason the
	 * connector owns one: this runs once a tick for every service unit that is actually serving.
	 * Server thread only, and never re-entrant -- nothing downstream calls back into this box.
	 */
	private final Map<AbstractConnection, IImmersiveConnectable> transferEndCache = new HashMap<>();

	@Override
	public GridDeviceType getDeviceType()
	{
		return GridDeviceType.SERVICE;
	}

	//	=================================
	//		WIRE ENDPOINT
	//	=================================
	// A wire attaches straight to the terminal post on the front, with no connector in between.
	// This box is a source, so it is deliberately not an "energy output" on the graph: it pushes in
	// its own turn during the grid's tick pass, which is the same moment a connector would have,
	// and leaving an accepting path open as well would let a second network draw from the segment
	// outside the per-tick budget the engine enforces.

	@Override
	protected boolean canTakeLV()
	{
		return true;
	}

	@Override
	protected boolean canTakeMV()
	{
		return true;
	}

	@Override
	protected boolean canTakeHV()
	{
		return true;
	}

	@Override
	public int insertFromGrid(int max, boolean simulate)
	{
		if(max <= 0||world==null)
			return 0;
		if(facesDirty)
			rescanFaces();

		int delivered = 0;
		//Mount face first: "the box powers the thing it is bolted to" stays true even when
		//other neighbours would also accept.
		delivered += deliverTo(facing, max, simulate);
		if(delivered >= max)
			return delivered;
		for(EnumFacing side : EnumFacing.VALUES)
		{
			if(side==facing)
				continue;
			delivered += deliverTo(side, max-delivered, simulate);
			if(delivered >= max)
				break;
		}
		//Then out along any wire strung to the terminal post. Last on purpose: touching stays the
		//strongest claim, so bolting a unit onto a machine still powers that machine before the
		//run leaves the building. Nothing here is a second budget -- max is what the segment
		//allowed this tick, and everything above has already spent part of it.
		if(delivered < max)
			delivered += deliverToWires(max-delivered, simulate);
		return delivered;
	}

	/**
	 * Pushes onto the wire network, exactly as a connector's own tick would.
	 * <p>
	 * The same code path a connector uses -- see {@link WireNetTransfer} -- so loss, the split
	 * between competing outputs and what an Energy Meter in the middle reads are all identical to
	 * what a connector bolted beside this box used to produce. Removing the connector removes a
	 * block, not a rule.
	 */
	private int deliverToWires(int amount, boolean simulate)
	{
		if(amount <= 0||world.isRemote)
			return 0;
		//The device's own transfer cap stands in for a connector's rate. It is the figure the loss
		//curve's shoulder is measured against, and it is the same number the console shows for this
		//box, so a player who raises the cap sees the run behave as a fatter connector would.
		int rate = device!=null?device.getTransferCap(): GridConfig.defaultDeviceCap;
		if(rate <= 0)
			return 0;
		if(CityMode.grid())
			//City mode is presence rather than accounting, and the wire side of it is lossless by
			//the same argument. Simulation still reports the demand honestly, so the engine's probe
			//is not fooled into thinking a dark network is hungry.
			return simulate?0: WireNetTransfer.city(world, pos, amount);
		return WireNetTransfer.transfer(world, pos, rate, rate, amount, simulate, 0, transferEndCache);
	}

	private int deliverTo(EnumFacing side, int amount, boolean simulate)
	{
		if(amount <= 0||!receiverFaces[side.ordinal()])
			return 0;
		TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
		//Never feed another grid box: a Service into a Feed would be a closed loop that
		//launders energy back into the grid it just left.
		if(target==null||target instanceof TileEntityGridDevice)
			return 0;
		//acceptingSide rather than side.getOpposite(): a wire connector beside this unit takes flux
		//only on the face it is bolted to, which is the whole of why hooking one up felt like guesswork.
		EnumFacing from = EnergyHelper.acceptingSide(target, side.getOpposite());
		return from==null?0: Math.max(0, EnergyHelper.insertFlux(target, from, amount, simulate));
	}

	private void rescanFaces()
	{
		for(EnumFacing side : EnumFacing.VALUES)
		{
			TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
			receiverFaces[side.ordinal()] = target!=null&&!(target instanceof TileEntityGridDevice)
					&&EnergyHelper.acceptingSide(target, side.getOpposite())!=null;
		}
		facesDirty = false;
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		facesDirty = true;
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		facesDirty = true;
	}

	@Override
	protected String describeWorldHookup()
	{
		if(hasReceiver()||hasWire())
			return null;
		return "Nothing is connected. Bolt this onto a machine or capacitor, string a wire "
				+"straight to it, or put a connector against it.";
	}

	@Override
	protected String describeWorldHookupHint()
	{
		//Said while it is working, not only once it has failed. See describeWorldHookupHint's own
		//comment for why that distinction is the whole point of the second line existing.
		return "Powers what it touches, and anything on a wire strung to it.";
	}

	/**
	 * @return true if anything is strung to the terminal post
	 */
	private boolean hasWire()
	{
		if(world==null||world.isRemote)
			return false;
		Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(world, pos);
		return conns!=null&&!conns.isEmpty();
	}

	/**
	 * @return true if anything adjacent is currently able to accept flux
	 */
	public boolean hasReceiver()
	{
		if(facesDirty&&world!=null)
			rescanFaces();
		for(boolean face : receiverFaces)
			if(face)
				return true;
		return false;
	}
}
