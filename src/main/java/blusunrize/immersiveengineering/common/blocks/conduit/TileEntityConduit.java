/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockBounds;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A length of surface-mounted conduit.
 * <p>
 * It holds two things and no more: which face it is clipped to, and what each of the four arms in
 * that face is doing. Deliberately nothing else -- the conductors a run carries live on the wire
 * graph's {@code Connection}, not on the blocks along the way, which is what keeps a corridor full
 * of circuits from costing anything per tick. See {@code api/energy/wires/conduit} and the plan's
 * decision 1.
 * <p>
 * There are three ways for an arm to be joined and they are decided independently: flat along the
 * surface, up an inner corner, and around an outer one. See {@link ConduitGeometry} for the rules
 * and {@link ConduitArms} for how four modes fit in the state the block already had.
 * <p>
 * The connection mask is recomputed when a neighbour changes rather than every tick. There is no
 * {@code update} here at all, and that is the point: a conduit is scenery with a graph edge
 * attached, and the profiled history of this mod is largely a story about blocks that polled.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class TileEntityConduit extends TileEntityIEBase implements IDirectionalTile, IBlockBounds,
		INeighbourChangeTile, IBlockOverlayText, IStatusLineProvider
{
	/**
	 * The face this conduit is clipped to -- the direction from the conduit toward its surface. A
	 * conduit lying on a floor faces DOWN; one under a ceiling faces UP.
	 */
	public EnumFacing facing = EnumFacing.DOWN;

	/**
	 * What each of the four arms is doing. Derived from the world, saved anyway: a chunk can load
	 * with its neighbours still absent, and a run that drew itself as four disconnected stubs until
	 * something happened to poke it would look broken.
	 */
	private final ConduitArms arms = new ConduitArms();

	public int getConnections()
	{
		return arms.getConnections();
	}

	public boolean isConnected(EnumFacing dir)
	{
		return arms.isConnected(ConduitGeometry.armIndex(facing, dir));
	}

	public ConduitGeometry.ArmMode armMode(EnumFacing dir)
	{
		return arms.mode(ConduitGeometry.armIndex(facing, dir));
	}

	public ConduitGeometry.Shape getShape()
	{
		return ConduitGeometry.shapeOf(facing, arms.getConnections());
	}

	/**
	 * Rebuild the arms from what is actually next door.
	 *
	 * @return true if anything changed, so the caller can skip a block update it does not need
	 */
	public boolean refreshConnections()
	{
		if(world==null)
			return false;
		ConduitArms found = new ConduitArms();
		EnumFacing[] plane = ConduitGeometry.inPlane(facing);
		for(int i = 0; i < plane.length; i++)
		{
			EnumFacing dir = plane[i];
			//Three ways to be joined in one direction, and the three are decided independently: a
			//run may reach a wall and climb it where a flat neighbour would have been, and may
			//round an outer corner in a direction that also holds a junction box. What differs is
			//only which of them gets to say what the arm looks like -- see ConduitGeometry.armMode.
			boolean wrap = wrapsAt(dir);
			found.set(i, ConduitGeometry.armMode(
					connectsTo(dir)||turnsFlatAt(dir), risesAt(dir), wrap,
					wrap&&ConduitGeometry.drawsCornerCap(facing, ConduitGeometry.outerCornerMount(dir))));
		}
		return arms.copyFrom(found);
	}

	/**
	 * The block at {@code pos}, or null where asking would be expensive or wrong.
	 * <p>
	 * isBlockLoaded, not getTileEntity straight away: asking about an unloaded chunk would generate
	 * it, and a conduit run along a border would drag chunks in behind it forever.
	 */
	@Nullable
	private TileEntity tileAt(BlockPos pos)
	{
		return world.isBlockLoaded(pos)?world.getTileEntity(pos): null;
	}

	/** The flat case: the run carries on along the same surface. */
	private boolean connectsTo(EnumFacing dir)
	{
		TileEntity te = tileAt(getPos().offset(dir));
		//A box is the far end of a run, so the arm pointing at one is drawn like any other. Without
		//this every run rendered as two loose stubs with a gap at each box -- the run worked, since
		//ConduitRoute walks the world rather than this mask, but it read as broken while you built
		//it, which is worse than broken in a feature nobody has used before.
		if(te instanceof TileEntityJunctionBox)
			//dir came out of inPlane(facing), so the step already runs along this conduit's surface:
			//the same rule ConduitRoute applies from the box's side.
			return true;
		//A feeder is the far end of a run too, but only along its own axis. Drawing the arm on that
		//test and no other is what makes a feeder laid the wrong way round visible: the conduit
		//stops short of it instead of joining, which is exactly what the run does.
		if(te instanceof TileEntityGroundFeeder)
			return ((TileEntityGroundFeeder)te).getAxis()==dir.getAxis();
		if(!(te instanceof TileEntityConduit))
			return false;
		return ConduitGeometry.connects(facing, ((TileEntityConduit)te).facing, dir);
	}

	/**
	 * The flat half of an inner corner: this conduit is the one on the wall, and the piece it meets
	 * is clipped to the very face this arm is pointing at -- a floor, seen from a wall.
	 */
	private boolean turnsFlatAt(EnumFacing dir)
	{
		BlockPos neighbour = getPos().offset(dir);
		TileEntity te = tileAt(neighbour);
		if(!(te instanceof TileEntityConduit))
			return false;
		EnumFacing otherMount = ((TileEntityConduit)te).facing;
		return ConduitGeometry.joinsInnerCorner(facing, otherMount, dir)
				&&cornerIsSupported(ConduitGeometry.innerCornerSupport(getPos(), facing, otherMount, dir),
				ConduitGeometry.innerCornerSupportFace(facing, otherMount, dir));
	}

	/**
	 * The climbing half: this conduit is the one on the floor, its arm reaches the wall in front of
	 * it and turns up, and the piece it meets is one cell out along its own mounting axis.
	 */
	private boolean risesAt(EnumFacing dir)
	{
		BlockPos above = getPos().offset(facing.getOpposite());
		TileEntity te = tileAt(above);
		if(!(te instanceof TileEntityConduit)||((TileEntityConduit)te).facing!=dir)
			return false;
		return cornerIsSupported(getPos().offset(dir), dir.getOpposite());
	}

	/**
	 * The other corner: the run reaches the edge of the block it is mounted on and follows that
	 * block's next face round. The partner is a diagonal neighbour clipped to the same block.
	 */
	private boolean wrapsAt(EnumFacing dir)
	{
		TileEntity te = tileAt(ConduitGeometry.outerCornerCell(getPos(), facing, dir));
		return te instanceof TileEntityConduit
				&&((TileEntityConduit)te).facing==ConduitGeometry.outerCornerMount(dir);
	}

	/**
	 * Whether there is a block in the corner for a riser to climb, and nothing else claiming it.
	 * <p>
	 * Solid, so a run does not turn corners around a wall somebody has not built; and empty of
	 * conduit hardware, so a run reaching a ground feeder passes through it -- which is what a
	 * feeder is for -- rather than climbing it.
	 */
	private boolean cornerIsSupported(BlockPos corner, EnumFacing face)
	{
		if(!world.isBlockLoaded(corner))
			return false;
		TileEntity te = tileAt(corner);
		if(te instanceof TileEntityConduit||te instanceof TileEntityJunctionBox
				||te instanceof TileEntityGroundFeeder)
			return false;
		return world.getBlockState(corner).isSideSolid(world, corner, face);
	}

	@Override
	public void onNeighborBlockChange(BlockPos other)
	{
		if(world==null||world.isRemote)
			return;
		if(refreshConnections())
		{
			markContainingBlockForUpdate(null);
			wakeBoxes();
		}
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		//No wakeBoxes here: on a chunk load every box on the run runs rebuildRuns from its own
		//onLoad already, and a hundred conduits each walking the same run to reach the same two
		//boxes would be a hundred walks to learn what two walks already knew.
		if(world!=null&&!world.isRemote&&refreshConnections())
			markContainingBlockForUpdate(null);
	}

	/**
	 * Tell every junction box on this run that its shape changed.
	 * <p>
	 * A box rebuilds when one of its <em>own</em> neighbours changes, which covers every gesture
	 * that finishes a run at a box -- lay conduit outward until the last length lands against the
	 * far box, and that box walks the run and makes the connection. It does not cover finishing a
	 * run in the <em>middle</em>: joining two half-runs, or dropping a ground feeder into a floor
	 * with conduit already above and below it. Neither box is anywhere near the block that did it,
	 * so neither ever hears, and the run is joined on the wall and absent from the graph until
	 * something else pokes a box or the chunk reloads.
	 * <p>
	 * Hung off the connection mask actually changing rather than off every neighbour update, so it
	 * costs a walk when somebody builds something and nothing at all the rest of the time -- the
	 * same bargain {@link TileEntityJunctionBox#rebuildRuns} makes.
	 */
	private void wakeBoxes()
	{
		for(BlockPos box : ConduitRoute.junctionsAround(getPos(), new ConduitWorldProbe(world)))
		{
			TileEntity te = Utils.getExistingTileEntity(world, box);
			if(te instanceof TileEntityJunctionBox)
				((TileEntityJunctionBox)te).rebuildRuns();
		}
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
		arms.writeToNBT(nbt);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		//Bounds-checked rather than trusted: the ordinal comes off disk, and EnumFacing.byIndex
		//wraps rather than failing, which would silently remount a conduit on the wrong wall.
		int ordinal = nbt.getInteger("facing");
		facing = ordinal >= 0&&ordinal < EnumFacing.VALUES.length
				?EnumFacing.VALUES[ordinal]: EnumFacing.DOWN;
		arms.readFromNBT(nbt);
	}

	@Override
	public EnumFacing getFacing()
	{
		return facing;
	}

	@Override
	public void setFacing(EnumFacing facing)
	{
		this.facing = facing;
		refreshConnections();
	}

	@Override
	public int getFacingLimitation()
	{
		//Zero: the face clicked. A conduit is clipped to the surface you put it against, which is
		//the only placement rule anybody would guess without being told.
		return 0;
	}

	@Override
	public EnumFacing getFacingForPlacement(EntityLivingBase placer, BlockPos pos, EnumFacing side,
											float hitX, float hitY, float hitZ)
	{
		//Clicking bare wall still means "clip to the face I clicked" -- see ConduitPlacement, which
		//owns the rule and widens it to cover the two gestures that used to produce dead stubs:
		//continuing a run off the end of one, and starting a run off a junction box.
		return ConduitPlacement.mountFor(pos, side, new Surroundings());
	}

	/**
	 * The placement rule's view of the world. An inner class rather than a lambda because the rule
	 * asks three different questions.
	 */
	private final class Surroundings implements ConduitPlacement.Surroundings
	{
		@Nullable
		@Override
		public EnumFacing conduitMountAt(BlockPos at)
		{
			//getExistingTileEntity, not getTileEntity: a placement must not generate the chunk next
			//door to decide which way round a block goes.
			TileEntity te = Utils.getExistingTileEntity(world, at);
			return te instanceof TileEntityConduit?((TileEntityConduit)te).facing: null;
		}

		@Override
		public boolean isJunctionAt(BlockPos at)
		{
			return Utils.getExistingTileEntity(world, at) instanceof TileEntityJunctionBox;
		}

		@Nullable
		@Override
		public EnumFacing.Axis feederAxisAt(BlockPos at)
		{
			TileEntity te = Utils.getExistingTileEntity(world, at);
			return te instanceof TileEntityGroundFeeder?((TileEntityGroundFeeder)te).getAxis(): null;
		}

		@Override
		public boolean isMountable(BlockPos at, EnumFacing face)
		{
			//Solidity rather than "is there a block": BlockConduit reports every side non-solid, so
			//this is also what stops the box rule quietly mounting a conduit onto another conduit.
			return world.getBlockState(at).isSideSolid(world, at, face);
		}
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		return false;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ,
								   EntityLivingBase entity)
	{
		//Rotating a conduit means moving it to a different wall, which is what breaking and
		//replacing it is for. Allowing it here would silently disconnect a run.
		return false;
	}

	@Override
	public boolean canRotate(EnumFacing axis)
	{
		return false;
	}

	@Override
	public float[] getBlockBounds()
	{
		return ConduitBounds.of(facing, arms.getConnections(), arms.getRisers());
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		return new String[]{describeShape()};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public List<String> getStatusLines()
	{
		List<String> lines = new ArrayList<>();
		lines.add(TextFormatting.GOLD+"Conduit"+TextFormatting.RESET+": "+describeShape());
		//A bare length is the one state worth saying out loud: it looks exactly like a joined one
		//from any distance, and it is what somebody who has just mounted a length to the wrong
		//surface is looking at.
		if(getShape()==ConduitGeometry.Shape.BARE)
			lines.add(TextFormatting.YELLOW+"Not joined to anything yet."+TextFormatting.RESET);
		return lines;
	}

	private String describeShape()
	{
		switch(getShape())
		{
			case BARE:
				return "bare";
			case END:
				return "end of a run";
			case STRAIGHT:
				return "straight";
			case CORNER:
				return "corner";
			case TEE:
				return "tee";
			default:
				return "cross";
		}
	}
}
