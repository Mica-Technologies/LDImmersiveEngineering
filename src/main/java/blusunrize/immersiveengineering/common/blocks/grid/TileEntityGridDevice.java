/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.energy.grid.*;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.*;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.chickenbones.Matrix4;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared behaviour of the wall/pole-mount grid boxes.
 * <p>
 * These tile entities are deliberately <strong>not</strong> {@code ITickable}: all energy
 * movement happens in {@code GridTickHandler}'s single server-tick pass over the devices
 * that are actually online. The tile's job is only to be an {@link IGridEndpoint} and to
 * keep its registration in {@link VirtualGrid} in step with the block existing in the world.
 * <p>
 * {@code facing} follows the wire-connector convention: it points at the block the box is
 * bolted to. Placing one against a wall, a machine or an IE post therefore needs no
 * special casing.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public abstract class TileEntityGridDevice extends TileEntityIEBase implements IDirectionalTile,
		IBlockBounds, IPlayerInteraction, IGuiTile, IGridEndpoint, IComparatorOverride, IBlockOverlayText
{
	public EnumFacing facing = EnumFacing.NORTH;

	/**
	 * The live record, resolved on load. Null on the client and while unloaded.
	 */
	@Nullable
	protected transient GridDevice device;

	//	=================================
	//		NBT BACKUP
	//	=================================
	// GridSaveData is authoritative. These fields mirror it so that if the grid save file
	// is lost or hand-deleted, reloading the chunk re-registers the box with its old
	// settings instead of silently producing an unconfigured one.
	@Nullable
	protected UUID backupSegment;
	protected String backupName = "";
	protected int backupCap = GridConfig.defaultDeviceCap;
	protected int backupPriority;
	protected boolean backupCritical;
	protected boolean backupChunkLoad;
	protected boolean backupEnabled = true;

	/**
	 * Values mirrored to the client purely for the in-world readout.
	 */
	protected String clientSegmentName = "";
	protected int clientSegmentColor = 0xFFFFFF;
	protected int clientState = STATE_UNLINKED;
	protected int clientThroughput;

	public static final int STATE_UNLINKED = 0;
	public static final int STATE_OFF = 1;
	public static final int STATE_IDLE = 2;
	public static final int STATE_ACTIVE = 3;

	public abstract GridDeviceType getDeviceType();

	@Nullable
	public GridDevice getDevice()
	{
		return device;
	}

	//	=================================
	//		REGISTRATION LIFECYCLE
	//	=================================

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world==null||world.isRemote)
			return;
		DimensionBlockPos dPos = new DimensionBlockPos(pos, world);
		boolean known = VirtualGrid.INSTANCE.getDevice(dPos)!=null;
		GridDevice registered = VirtualGrid.INSTANCE.attach(dPos, getDeviceType(), this);
		if(!known)
			restoreBackupInto(registered);
		this.device = registered;
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		detach();
	}

	@Override
	public void onChunkUnload()
	{
		super.onChunkUnload();
		detach();
	}

	private void detach()
	{
		if(world!=null&&!world.isRemote)
			VirtualGrid.INSTANCE.detach(new DimensionBlockPos(pos, world));
		this.device = null;
	}

	/**
	 * Called from the block's {@code breakBlock}: the box is gone for good, so drop the
	 * record rather than leaving a permanently offline ghost in the console list.
	 */
	public void onBlockBroken()
	{
		if(world!=null&&!world.isRemote)
		{
			VirtualGrid.INSTANCE.unregisterDevice(new DimensionBlockPos(pos, world));
			//A box that was holding its chunk loaded must stop the moment it is mined, or the ticket
			//outlives the block that asked for it and the chunk stays pinned until something else
			//happens to trigger a rebuild -- possibly the next server start.
			blusunrize.immersiveengineering.common.util.grid.GridChunkLoader.refresh();
		}
		this.device = null;
	}

	private void restoreBackupInto(GridDevice target)
	{
		target.setCustomName(backupName);
		target.setTransferCap(backupCap);
		target.setPriority(backupPriority);
		target.setCritical(backupCritical);
		target.setChunkLoad(backupChunkLoad);
		target.setEnabled(backupEnabled);
		if(backupSegment!=null&&VirtualGrid.INSTANCE.getSegment(backupSegment)!=null)
			VirtualGrid.INSTANCE.assignDevice(target, backupSegment);
	}

	/**
	 * Refreshes the NBT mirror from the live record. Called before writing NBT and
	 * whenever the record changes.
	 */
	protected void captureBackup()
	{
		if(device==null)
			return;
		backupSegment = device.getSegment();
		backupName = device.getCustomName();
		backupCap = device.getTransferCap();
		backupPriority = device.getPriority();
		backupCritical = device.isCritical();
		backupChunkLoad = device.isChunkLoadRequested();
		backupEnabled = device.isEnabled();
	}

	@Override
	public void onGridConfigChanged(GridDevice changed)
	{
		captureBackup();
		applyLimits();
		markDirty();
		if(world!=null&&!world.isRemote)
			pushClientState();
	}

	/**
	 * Resizes internal buffers for the current transfer cap and power mode.
	 */
	protected void applyLimits()
	{
	}

	/**
	 * Whether the status lamp should read "active" rather than "idle". Throughput is the
	 * right answer for the boxes that move flux; the ones that do not override it.
	 */
	protected boolean isDoingWork(GridDevice device)
	{
		return device.getLastThroughput() > 0;
	}

	/**
	 * Recomputes the values the client renders and syncs them if they changed. Deliberately
	 * change-gated: the readout must not cost a packet per tick.
	 */
	protected void pushClientState()
	{
		int state = STATE_UNLINKED;
		String name = "";
		int color = 0xFFFFFF;
		if(device!=null&&device.isLinked())
		{
			GridSegment segment = VirtualGrid.INSTANCE.getSegment(device.getSegment());
			if(segment!=null)
			{
				name = segment.getName();
				color = segment.getColor();
				if(!device.isEnabled()||!segment.isOperational())
					state = STATE_OFF;
				else
					state = isDoingWork(device)?STATE_ACTIVE: STATE_IDLE;
			}
		}
		int throughput = device!=null?device.getLastThroughput(): 0;
		if(state!=clientState||!name.equals(clientSegmentName)||color!=clientSegmentColor
				||throughput!=clientThroughput)
		{
			clientState = state;
			clientSegmentName = name;
			clientSegmentColor = color;
			clientThroughput = throughput;
			markContainingBlockForUpdate(null);
		}
	}

	//	=================================
	//		IGridEndpoint -- defaults
	//	=================================

	@Override
	public int extractForGrid(int max, boolean simulate)
	{
		return 0;
	}

	@Override
	public int insertFromGrid(int max, boolean simulate)
	{
		return 0;
	}

	//	=================================
	//		INTERACTION / READOUT
	//	=================================

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		//Sneak for a quick chat readout without opening anything; a plain right click falls
		//through to the IGuiTile branch in BlockIETileProvider, which opens the panel.
		if(!player.isSneaking())
			return false;
		if(!world.isRemote)
			for(String line : buildStatusLines())
				ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(line));
		return true;
	}

	//	=================================
	//		GUI
	//	=================================

	@Override
	public boolean canOpenGui()
	{
		return true;
	}

	@Override
	public int getGuiID()
	{
		return Lib.GUIID_GridDevice;
	}

	@Nullable
	@Override
	public TileEntity getGuiMaster()
	{
		return this;
	}

	protected List<String> buildStatusLines()
	{
		List<String> lines = new ArrayList<>();
		String label = TextFormatting.GOLD+getDeviceLabel()+TextFormatting.RESET;
		if(device==null||!device.isLinked())
		{
			lines.add(label+": "+TextFormatting.RED+"unlinked"+TextFormatting.RESET);
			lines.add("Assign it from a Grid Management Console.");
			return lines;
		}
		GridSegment segment = VirtualGrid.INSTANCE.getSegment(device.getSegment());
		if(segment==null)
		{
			lines.add(label+": "+TextFormatting.RED+"segment missing"+TextFormatting.RESET);
			return lines;
		}
		lines.add(label+": "+segment.getName());
		String state;
		if(!device.isEnabled())
			state = TextFormatting.RED+"device disabled";
		else if(segment.isTripped())
			state = TextFormatting.RED+"segment TRIPPED";
		else if(!segment.isEnabled())
			state = TextFormatting.YELLOW+"segment off";
		else if(segment.isForcedOff())
			state = TextFormatting.YELLOW+"held off by a signal unit";
		else if(segment.isScheduleSuppressed())
			state = TextFormatting.YELLOW+"outside its schedule";
		else if(CityMode.grid())
			state = segment.isEnergized()?TextFormatting.GREEN+"energized": TextFormatting.RED+"no power";
		else
			state = TextFormatting.GREEN+"online";
		lines.add("State: "+state+TextFormatting.RESET);
		if(movesEnergy())
		{
			lines.add("Last tick: "+device.getLastThroughput()+" / "+device.getTransferCap()+" IF"
					+(device.isCritical()?TextFormatting.AQUA+"  [critical]"+TextFormatting.RESET: ""));
			if(!CityMode.grid())
				lines.add("Segment buffer: "+segment.getBuffer()+" / "+segment.getPolicy().getBufferCap()+" IF");
		}
		String hookup = describeWorldHookup();
		if(hookup!=null)
			lines.add(TextFormatting.YELLOW+hookup+TextFormatting.RESET);
		return lines;
	}

	/**
	 * A hint about the world side of the connection, or null when power is clearly moving.
	 * <p>
	 * These boxes exchange flux with adjacent blocks rather than accepting wires directly --
	 * exactly like every other IE machine, where a connector is what bridges to the wire
	 * network. That is not obvious from looking at one, so say it when nothing is flowing.
	 */
	@Nullable
	protected String describeWorldHookup()
	{
		return null;
	}

	protected String getDeviceLabel()
	{
		return getDeviceType()==GridDeviceType.FEED?"Grid Feed Unit": "Grid Service Unit";
	}

	/**
	 * @return true if this device moves flux, and therefore has throughput worth reporting
	 */
	protected boolean movesEnergy()
	{
		return getDeviceType().movesEnergy();
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(clientState==STATE_UNLINKED)
			return new String[]{TextFormatting.RED+"Unlinked"+TextFormatting.RESET};
		String state = clientState==STATE_OFF?TextFormatting.RED+"off"
				: clientState==STATE_ACTIVE?TextFormatting.GREEN+"active"
				: TextFormatting.YELLOW+"idle";
		return new String[]{
				clientSegmentName,
				state+TextFormatting.RESET+(movesEnergy()?"  "+clientThroughput+" IF/t": "")
		};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		if(device==null||!device.isLinked())
			return 0;
		GridSegment segment = VirtualGrid.INSTANCE.getSegment(device.getSegment());
		if(segment==null||!segment.isOperational())
			return 0;
		if(CityMode.grid())
			return segment.isEnergized()?15: 0;
		int cap = device.getTransferCap();
		if(cap <= 0)
			return 0;
		return Math.min(15, (int)Math.ceil(15.0*device.getLastThroughput()/cap));
	}

	//	=================================
	//		PLACEMENT / BOUNDS
	//	=================================

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
		//0 = "side clicked", same as a wire connector. Combined with
		//mirrorFacingOnPlacement this yields clickedSide.getOpposite(), i.e. facing points
		//at the block the box is bolted to.
		//
		//Do NOT use 2 here: despite being described as "horizontal", limit 2 derives the
		//facing from the placer's yaw and ignores which face was clicked entirely, which
		//puts the box's decorated front against the wall.
		return 0;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		//Face the surface that was clicked, exactly as a wire connector does.
		return true;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ, EntityLivingBase entity)
	{
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
		//A 10 x 12 x 6 pixel service box sitting flush against whatever it is bolted to.
		Vec3d start = new Vec3d(.1875, .125, 0);
		Vec3d end = new Vec3d(.8125, .875, .375);
		Matrix4 mat = new Matrix4(facing);
		start = mat.apply(start);
		end = mat.apply(end);
		return new float[]{
				(float)Math.min(start.x, end.x), (float)Math.min(start.y, end.y), (float)Math.min(start.z, end.z),
				(float)Math.max(start.x, end.x), (float)Math.max(start.y, end.y), (float)Math.max(start.z, end.z)};
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		captureBackup();
		nbt.setInteger("facing", facing.ordinal());
		if(backupSegment!=null)
			nbt.setString("segment", backupSegment.toString());
		nbt.setString("deviceName", backupName);
		nbt.setInteger("transferCap", backupCap);
		nbt.setInteger("priority", backupPriority);
		nbt.setBoolean("critical", backupCritical);
		nbt.setBoolean("chunkLoad", backupChunkLoad);
		nbt.setBoolean("deviceEnabled", backupEnabled);
		if(descPacket)
		{
			nbt.setString("cSegName", clientSegmentName);
			nbt.setInteger("cSegColor", clientSegmentColor);
			nbt.setInteger("cState", clientState);
			nbt.setInteger("cThroughput", clientThroughput);
		}
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		facing = EnumFacing.byIndex(nbt.getInteger("facing"));
		backupSegment = nbt.hasKey("segment")?GridDevice.parseUUID(nbt.getString("segment")): null;
		backupName = nbt.getString("deviceName");
		backupCap = nbt.hasKey("transferCap")?nbt.getInteger("transferCap"): GridConfig.defaultDeviceCap;
		backupPriority = nbt.getInteger("priority");
		backupCritical = nbt.getBoolean("critical");
		backupChunkLoad = nbt.getBoolean("chunkLoad");
		backupEnabled = !nbt.hasKey("deviceEnabled")||nbt.getBoolean("deviceEnabled");
		if(descPacket)
		{
			clientSegmentName = nbt.getString("cSegName");
			clientSegmentColor = nbt.getInteger("cSegColor");
			clientState = nbt.getInteger("cState");
			clientThroughput = nbt.getInteger("cThroughput");
		}
	}

	/**
	 * @return the tint applied to the box's painted segment band
	 */
	public int getSegmentColour()
	{
		return clientState==STATE_UNLINKED?0xFFFFFF: clientSegmentColor;
	}

	public int getClientState()
	{
		return clientState;
	}

	/**
	 * Translation key prefix for this device's chat/GUI strings.
	 */
	protected static String langKey(String key)
	{
		return Lib.DESC_INFO+"grid."+key;
	}
}
