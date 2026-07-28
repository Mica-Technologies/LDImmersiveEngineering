/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
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

/**
 * The price board: the thing you read from the road before deciding whether to pull in.
 * <p>
 * It has no price of its own. It finds the nearest Gas Station Pump and shows that pump's, which
 * is the only arrangement that cannot go stale -- a sign carrying its own copy of a number set
 * somewhere else is a sign that will eventually be lying, and the whole job of this block is to
 * be believed.
 * <p>
 * The search runs on neighbour change and on load, not on a tick. A forecourt does not move.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityForecourtSign extends TileEntityIEBase implements IBlockOverlayText,
		IDirectionalTile, INeighbourChangeTile
{
	/**
	 * How far the sign will look for a pump. Matched to the nozzle's reach, so the rule is the
	 * same one in both places: a forecourt is what fits inside eight blocks of its pump.
	 */
	public static final int SEARCH_RADIUS = TileEntityGasPump.NOZZLE_RANGE;

	public EnumFacing facing = EnumFacing.NORTH;

	@Nullable
	private transient BlockPos cachedPump;
	private boolean dirty = true;

	@Nullable
	private TileEntityGasPump findPump()
	{
		if(world==null)
			return null;
		if(!dirty&&cachedPump!=null)
		{
			TileEntity cached = Utils.getExistingTileEntity(world, cachedPump);
			if(cached instanceof TileEntityGasPump)
				return (TileEntityGasPump)cached;
		}
		dirty = false;
		cachedPump = null;
		TileEntityGasPump best = null;
		double bestDist = Double.MAX_VALUE;
		//A cube rather than a sphere: it is one scan of a small volume on an event, and the corner
		//cases a sphere would exclude are exactly the ones a player would expect to work.
		for(int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++)
			for(int dy = -3; dy <= 3; dy++)
				for(int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++)
				{
					BlockPos at = getPos().add(dx, dy, dz);
					TileEntity te = Utils.getExistingTileEntity(world, at);
					if(!(te instanceof TileEntityGasPump))
						continue;
					double dist = at.distanceSq(getPos());
					if(dist < bestDist)
					{
						bestDist = dist;
						best = (TileEntityGasPump)te;
						cachedPump = at;
					}
				}
		return best;
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		dirty = true;
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		dirty = true;
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(world!=null&&world.isRemote)
			//The pump's price is synced to the client with the pump, so the client-side lookup
			//finds the same answer the server would.
			return readout();
		return readout();
	}

	private String[] readout()
	{
		TileEntityGasPump pump = findPump();
		if(pump==null)
			return new String[]{TextFormatting.GRAY+"No pump nearby"+TextFormatting.RESET};
		if(pump.getPrice() <= 0)
			return new String[]{TextFormatting.GREEN+"FREE"+TextFormatting.RESET};
		return new String[]{TextFormatting.GOLD+Integer.toString(pump.getPrice())+TextFormatting.RESET
				+" per bucket"};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		//The one block in the expansion that genuinely wants the mechanical-digit font.
		return true;
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
	}

	@Override
	public int getFacingLimitation()
	{
		return 2;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		return true;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ, EntityLivingBase entity)
	{
		return true;
	}

	@Override
	public boolean canRotate(EnumFacing axis)
	{
		return axis==EnumFacing.UP||axis==EnumFacing.DOWN;
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		facing = EnumFacing.byIndex(nbt.getInteger("facing"));
	}
}
