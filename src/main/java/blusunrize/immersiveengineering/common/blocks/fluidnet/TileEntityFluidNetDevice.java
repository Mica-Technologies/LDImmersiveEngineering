/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.fluid.network.*;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.*;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.chickenbones.Matrix4;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared behaviour of the wall-mount fluid network fittings.
 * <p>
 * These tile entities are deliberately <strong>not</strong> {@code ITickable}: all fluid movement
 * happens in {@code FluidNetTickHandler}'s single server-tick pass over the devices that are
 * actually online. The tile's job is only to be an {@link IFluidEndpoint} and to keep its
 * registration in {@link VirtualFluidNet} in step with the block existing in the world.
 * <p>
 * {@code facing} follows the wire-connector convention: it points at the block the fitting is
 * bolted to. Bolting one against a tank, a machine or an IE post therefore needs no special
 * casing.
 * <p>
 * The deliberate mirror of {@code TileEntityGridDevice}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public abstract class TileEntityFluidNetDevice extends TileEntityIEBase implements IDirectionalTile,
		IBlockBounds, IPlayerInteraction, IFluidEndpoint, IComparatorOverride, IBlockOverlayText
{
	public EnumFacing facing = EnumFacing.NORTH;

	/**
	 * The live record, resolved on load. Null on the client and while unloaded.
	 */
	@Nullable
	protected transient FluidDevice device;

	//	=================================
	//		NBT BACKUP
	//	=================================
	// FluidNetSaveData is authoritative. These fields mirror it so that if the network save file
	// is lost or hand-deleted, reloading the chunk re-registers the fitting with its old settings
	// instead of silently producing an unconfigured one.
	@Nullable
	protected UUID backupMain;
	protected String backupName = "";
	protected int backupCap = FluidNetConfig.defaultDeviceCap;
	protected int backupPriority;
	protected boolean backupCritical;
	protected boolean backupChunkLoad;
	protected boolean backupEnabled = true;

	/**
	 * Values mirrored to the client purely for the in-world readout.
	 */
	protected String clientMainName = "";
	protected int clientMainColor = 0xFFFFFF;
	protected int clientState = STATE_UNLINKED;
	protected int clientThroughput;

	public static final int STATE_UNLINKED = 0;
	public static final int STATE_CLOSED = 1;
	public static final int STATE_IDLE = 2;
	public static final int STATE_FLOWING = 3;

	public abstract FluidDeviceType getDeviceType();

	@Nullable
	public FluidDevice getDevice()
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
		boolean known = VirtualFluidNet.INSTANCE.getDevice(dPos)!=null;
		FluidDevice registered = VirtualFluidNet.INSTANCE.attach(dPos, getDeviceType(), this);
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
			VirtualFluidNet.INSTANCE.detach(new DimensionBlockPos(pos, world));
		this.device = null;
	}

	/**
	 * Called from the block's {@code breakBlock}: the fitting is gone for good, so drop the record
	 * rather than leaving a permanently offline ghost in the console list.
	 */
	public void onBlockBroken()
	{
		if(world!=null&&!world.isRemote)
			VirtualFluidNet.INSTANCE.unregisterDevice(new DimensionBlockPos(pos, world));
		this.device = null;
	}

	private void restoreBackupInto(FluidDevice target)
	{
		target.setCustomName(backupName);
		target.setTransferCap(backupCap);
		target.setPriority(backupPriority);
		target.setCritical(backupCritical);
		target.setChunkLoad(backupChunkLoad);
		target.setEnabled(backupEnabled);
		if(backupMain!=null&&VirtualFluidNet.INSTANCE.getMain(backupMain)!=null)
			VirtualFluidNet.INSTANCE.assignDevice(target, backupMain);
	}

	/**
	 * Refreshes the NBT mirror from the live record. Called before writing NBT and whenever the
	 * record changes.
	 */
	protected void captureBackup()
	{
		if(device==null)
			return;
		backupMain = device.getMain();
		backupName = device.getCustomName();
		backupCap = device.getTransferCap();
		backupPriority = device.getPriority();
		backupCritical = device.isCritical();
		backupChunkLoad = device.isChunkLoadRequested();
		backupEnabled = device.isEnabled();
	}

	@Override
	public void onNetConfigChanged(FluidDevice changed)
	{
		captureBackup();
		applyLimits();
		markDirty();
		if(world!=null&&!world.isRemote)
			pushClientState();
	}

	/**
	 * Resizes internal buffers for the current transfer cap, fluid and power mode.
	 */
	protected void applyLimits()
	{
	}

	/**
	 * @return the main this fitting belongs to, or null
	 */
	@Nullable
	protected FluidMain getMain()
	{
		return device==null?null: VirtualFluidNet.INSTANCE.getMain(device.getMain());
	}

	/**
	 * @return the registry name of what this fitting's main carries, or null
	 */
	@Nullable
	protected String getMainFluid()
	{
		FluidMain main = getMain();
		return main==null?null: main.getFluid();
	}

	/**
	 * Whether the status lamp should read "flowing" rather than "idle".
	 */
	protected boolean isDoingWork(FluidDevice device)
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
			FluidMain main = VirtualFluidNet.INSTANCE.getMain(device.getMain());
			if(main!=null)
			{
				name = main.getName();
				color = main.getColor();
				if(!device.isEnabled()||!main.isOperational())
					state = STATE_CLOSED;
				else
					state = isDoingWork(device)?STATE_FLOWING: STATE_IDLE;
			}
		}
		int throughput = device!=null?device.getLastThroughput(): 0;
		if(state!=clientState||!name.equals(clientMainName)||color!=clientMainColor
				||throughput!=clientThroughput)
		{
			clientState = state;
			clientMainName = name;
			clientMainColor = color;
			clientThroughput = throughput;
			markContainingBlockForUpdate(null);
		}
	}

	//	=================================
	//		IFluidEndpoint -- defaults
	//	=================================

	@Override
	public int extractForMain(String fluid, int max, boolean simulate)
	{
		return 0;
	}

	@Override
	public int insertFromMain(String fluid, int max, boolean simulate)
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
		//Sneak for a quick chat readout without opening anything; a plain right click falls through
		//to the IGuiTile branch in BlockIETileProvider, which opens the panel.
		if(!player.isSneaking())
			return false;
		if(!world.isRemote)
			for(String line : buildStatusLines())
				ChatUtils.sendServerNoSpamMessages(player, new TextComponentString(line));
		return true;
	}

	protected List<String> buildStatusLines()
	{
		List<String> lines = new ArrayList<>();
		String label = TextFormatting.GOLD+getDeviceLabel()+TextFormatting.RESET;
		if(device==null||!device.isLinked())
		{
			lines.add(label+": "+TextFormatting.RED+"unlinked"+TextFormatting.RESET);
			lines.add("Assign it from a Fluid Control Console.");
			return lines;
		}
		FluidMain main = VirtualFluidNet.INSTANCE.getMain(device.getMain());
		if(main==null)
		{
			lines.add(label+": "+TextFormatting.RED+"main missing"+TextFormatting.RESET);
			return lines;
		}
		lines.add(label+": "+main.getName()
				+(main.isTyped()?TextFormatting.GRAY+"  ("+main.getFluid()+")"+TextFormatting.RESET
				: TextFormatting.GRAY+"  (untyped)"+TextFormatting.RESET));
		String state;
		if(!device.isEnabled())
			state = TextFormatting.RED+"fitting disabled";
		else if(main.isTripped())
			state = TextFormatting.RED+"main TRIPPED on overpressure";
		else if(!main.isEnabled())
			state = TextFormatting.YELLOW+"main closed";
		else if(main.isForcedClosed())
			state = TextFormatting.YELLOW+"held closed by a valve";
		else if(main.isScheduleSuppressed())
			state = TextFormatting.YELLOW+"outside its schedule";
		else if(CityMode.petroleum())
			state = main.isPressurised()?TextFormatting.GREEN+"pressurised"
					: TextFormatting.RED+"no supply";
		else
			state = TextFormatting.GREEN+"open";
		lines.add("State: "+state+TextFormatting.RESET);
		if(movesFluid())
		{
			lines.add("Last tick: "+device.getLastThroughput()+" / "+device.getTransferCap()+" mB"
					+(device.isCritical()?TextFormatting.AQUA+"  [critical]"+TextFormatting.RESET: ""));
			if(!CityMode.petroleum())
				lines.add("Line pack: "+main.getPack()+" / "+main.getPolicy().getPackCap()+" mB");
		}
		String hookup = describeWorldHookup();
		if(hookup!=null)
			lines.add(TextFormatting.YELLOW+hookup+TextFormatting.RESET);
		return lines;
	}

	/**
	 * A hint about the world side of the connection, or null when fluid is clearly moving.
	 * <p>
	 * These fittings exchange fluid with adjacent blocks rather than accepting pipe runs of their
	 * own. That is not obvious from looking at one, so say it when nothing is flowing.
	 */
	@Nullable
	protected String describeWorldHookup()
	{
		return null;
	}

	protected String getDeviceLabel()
	{
		switch(getDeviceType())
		{
			case INLET:
				return "Fluid Inlet";
			case OUTLET:
				return "Fluid Outlet";
			case VALVE:
				return "Main Valve";
			default:
				return "Fluid Control Console";
		}
	}

	/**
	 * @return true if this fitting moves fluid, and therefore has throughput worth reporting
	 */
	protected boolean movesFluid()
	{
		return getDeviceType().movesFluid();
	}

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(clientState==STATE_UNLINKED)
			return new String[]{TextFormatting.RED+"Unlinked"+TextFormatting.RESET};
		String state = clientState==STATE_CLOSED?TextFormatting.RED+"closed"
				: clientState==STATE_FLOWING?TextFormatting.GREEN+"flowing"
				: TextFormatting.YELLOW+"idle";
		return new String[]{
				clientMainName,
				state+TextFormatting.RESET+(movesFluid()?"  "+clientThroughput+" mB/t": "")
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
		FluidMain main = VirtualFluidNet.INSTANCE.getMain(device.getMain());
		if(main==null||!main.isOperational())
			return 0;
		if(CityMode.petroleum())
			return main.isPressurised()?15: 0;
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
		//0 = "side clicked", same as a wire connector. Combined with mirrorFacingOnPlacement this
		//yields clickedSide.getOpposite(), i.e. facing points at the block the fitting is bolted
		//to. Do NOT use 2: despite being described as "horizontal", it derives the facing from the
		//placer's yaw and ignores which face was clicked, which puts the decorated front into the
		//wall.
		return 0;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
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
		//A 10 x 12 x 6 pixel fitting sitting flush against whatever it is bolted to -- the same
		//box the grid's units use, so a wall carrying both reads as one installation.
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
		if(backupMain!=null)
			nbt.setString("main", backupMain.toString());
		nbt.setString("deviceName", backupName);
		nbt.setInteger("transferCap", backupCap);
		nbt.setInteger("priority", backupPriority);
		nbt.setBoolean("critical", backupCritical);
		nbt.setBoolean("chunkLoad", backupChunkLoad);
		nbt.setBoolean("deviceEnabled", backupEnabled);
		if(descPacket)
		{
			nbt.setString("cMainName", clientMainName);
			nbt.setInteger("cMainColor", clientMainColor);
			nbt.setInteger("cState", clientState);
			nbt.setInteger("cThroughput", clientThroughput);
		}
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		facing = EnumFacing.byIndex(nbt.getInteger("facing"));
		backupMain = nbt.hasKey("main")?FluidDevice.parseUUID(nbt.getString("main")): null;
		backupName = nbt.getString("deviceName");
		backupCap = nbt.hasKey("transferCap")?nbt.getInteger("transferCap"): FluidNetConfig.defaultDeviceCap;
		backupPriority = nbt.getInteger("priority");
		backupCritical = nbt.getBoolean("critical");
		backupChunkLoad = nbt.getBoolean("chunkLoad");
		backupEnabled = !nbt.hasKey("deviceEnabled")||nbt.getBoolean("deviceEnabled");
		if(descPacket)
		{
			clientMainName = nbt.getString("cMainName");
			clientMainColor = nbt.getInteger("cMainColor");
			clientState = nbt.getInteger("cState");
			clientThroughput = nbt.getInteger("cThroughput");
		}
	}

	/**
	 * @return the tint applied to the fitting's painted main band
	 */
	public int getMainColour()
	{
		return clientState==STATE_UNLINKED?0xFFFFFF: clientMainColor;
	}

	public int getClientState()
	{
		return clientState;
	}

	/**
	 * Translation key prefix for this fitting's chat/GUI strings.
	 */
	protected static String langKey(String key)
	{
		return Lib.DESC_INFO+"fluidnet."+key;
	}

	/**
	 * @return the block this fitting is bolted to, or null. Never another fluid network fitting:
	 * an Outlet feeding an Inlet would be a closed loop that launders fluid back into the main it
	 * just left, and the ledger would show a network doing infinite work.
	 */
	@Nullable
	protected TileEntity getMountedBlock()
	{
		if(world==null)
			return null;
		TileEntity target = blusunrize.immersiveengineering.common.util.Utils
				.getExistingTileEntity(world, pos.offset(facing));
		return target instanceof TileEntityFluidNetDevice?null: target;
	}
}
