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
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * A length of surface-mounted conduit.
 * <p>
 * It holds two things and no more: which face it is clipped to, and which of the four directions in
 * that face it is joined to a neighbour in. Deliberately nothing else -- the conductors a run
 * carries live on the wire graph's {@code Connection}, not on the blocks along the way, which is
 * what keeps a corridor full of circuits from costing anything per tick. See
 * {@code api/energy/wires/conduit} and the plan's decision 1.
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
	 * One bit per {@link ConduitGeometry#inPlane} index. Derived from the world, saved anyway: a
	 * chunk can load with its neighbours still absent, and a run that drew itself as four
	 * disconnected stubs until something happened to poke it would look broken.
	 */
	private int connections;

	public int getConnections()
	{
		return connections;
	}

	public boolean isConnected(EnumFacing dir)
	{
		int index = ConduitGeometry.armIndex(facing, dir);
		return index >= 0&&(connections&(1 << index))!=0;
	}

	public ConduitGeometry.Shape getShape()
	{
		return ConduitGeometry.shapeOf(facing, connections);
	}

	/**
	 * Rebuild the mask from what is actually next door.
	 *
	 * @return true if anything changed, so the caller can skip a block update it does not need
	 */
	public boolean refreshConnections()
	{
		if(world==null)
			return false;
		int found = 0;
		EnumFacing[] plane = ConduitGeometry.inPlane(facing);
		for(int i = 0; i < plane.length; i++)
			if(connectsTo(plane[i]))
				found |= 1 << i;
		if(found==connections)
			return false;
		connections = found;
		return true;
	}

	private boolean connectsTo(EnumFacing dir)
	{
		BlockPos neighbour = getPos().offset(dir);
		//isBlockLoaded, not getTileEntity straight away: asking about an unloaded chunk would
		//generate it, and a conduit run along a border would drag chunks in behind it forever.
		if(!world.isBlockLoaded(neighbour))
			return false;
		TileEntity te = world.getTileEntity(neighbour);
		if(!(te instanceof TileEntityConduit))
			return false;
		return ConduitGeometry.connects(facing, ((TileEntityConduit)te).facing, dir);
	}

	@Override
	public void onNeighborBlockChange(BlockPos other)
	{
		if(world==null||world.isRemote)
			return;
		if(refreshConnections())
			markContainingBlockForUpdate(null);
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world!=null&&!world.isRemote&&refreshConnections())
			markContainingBlockForUpdate(null);
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
		nbt.setInteger("connections", connections);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		//Bounds-checked rather than trusted: the ordinal comes off disk, and EnumFacing.byIndex
		//wraps rather than failing, which would silently remount a conduit on the wrong wall.
		int ordinal = nbt.getInteger("facing");
		facing = ordinal >= 0&&ordinal < EnumFacing.VALUES.length
				?EnumFacing.VALUES[ordinal]: EnumFacing.DOWN;
		connections = nbt.getInteger("connections")&0xF;
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
		//The clicked face points out of the block being clicked; the conduit is clipped to it, so
		//its own mounting direction is the opposite.
		return side.getOpposite();
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
		return ConduitBounds.of(facing, connections);
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
		//Said here because it is the question a plane change provokes, and the answer is a block
		//rather than a rule: runs stay on one surface, and a junction box is how you leave it.
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
