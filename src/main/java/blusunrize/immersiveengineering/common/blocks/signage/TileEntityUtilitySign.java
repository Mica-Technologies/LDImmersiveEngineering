/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.CommonProxy;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IAttachedIntegerProperies;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockBounds;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IGuiTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IHammerInteraction;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ITileDrop;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;

import javax.annotation.Nullable;

/**
 * One tag on a pole: which of the thirteen kinds it is, and what is printed on it.
 * <p>
 * <strong>Everything a sign is lives here rather than in the block state.</strong> Thirteen kinds
 * times four facings would be fifty-two states, which is affordable -- the text is what is not, and
 * once the text has to be on the tile entity there is no reason for the kind to be anywhere else.
 * The kind reaches the block state through {@link IAttachedIntegerProperies} only so that a plain
 * {@code variants} blockstate can pick the plate model, which is the cheapest way to draw thirteen
 * flat plates: static geometry, no smart model, and nothing per frame except the lettering.
 * <p>
 * <strong>The hammer is the whole interface.</strong> Hit a sign with an Engineer's Hammer and it
 * steps to the next kind; sneak and hit it and the editing window opens. That is one tool and two
 * gestures for a thing with thirteen shapes and three lines of text, and it is the gesture the same
 * playtester asked for on junction boxes in the same breath -- a tool you are already holding beats
 * a menu you have to find.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class TileEntityUtilitySign extends TileEntityIEBase implements IDirectionalTile,
		IAttachedIntegerProperies, IHammerInteraction, IGuiTile, IBlockBounds, ITileDrop
{
	/** How thick a plate is, in block pixels. A tag is bolted flat to a pole, not hung off it. */
	public static final float THICKNESS = 1;

	/** The name the kind travels under, in the block state and in NBT. */
	public static final String KIND = "kind";

	private EnumFacing facing = EnumFacing.NORTH;
	private UtilitySignKind kind = UtilitySignKind.YELLOW_VERTICAL;
	private final String[] lines = new String[UtilitySignKind.MAX_LINES];

	public TileEntityUtilitySign()
	{
		for(int i = 0; i < lines.length; i++)
			lines[i] = "";
	}

	public UtilitySignKind getKind()
	{
		return kind;
	}

	public void setKind(UtilitySignKind kind)
	{
		this.kind = kind;
	}

	/**
	 * @return what is printed on that line, which is never null -- a sign with nothing on it is a
	 * blank plate, and a blank plate is a perfectly ordinary thing to hang
	 */
	public String getLine(int index)
	{
		return index >= 0&&index < lines.length?lines[index]: "";
	}

	public void setLine(int index, String text)
	{
		if(index < 0||index >= lines.length)
			return;
		//Clipped here rather than trusted from the packet: the editing window limits what can be
		//typed, and a hand-made packet does not have to.
		lines[index] = text==null?"": text.length() > UtilitySignKind.MAX_LENGTH
				?text.substring(0, UtilitySignKind.MAX_LENGTH): text;
	}

	//	=================================
	//		THE HAMMER
	//	=================================

	@Override
	public boolean hammerUseSide(EnumFacing side, EntityPlayer player, float hitX, float hitY, float hitZ)
	{
		if(player.isSneaking())
		{
			//Sneak opens the window. The hammer bypasses the sneak-use check -- see
			//ItemIETool.doesSneakBypassUse -- so this is reached rather than swallowed, which is the
			//same route the junction box's colour cycle takes.
			CommonProxy.openGuiForTile(player, this);
			return true;
		}
		setKind(kind.next());
		markDirty();
		markContainingBlockForUpdate(null);
		return true;
	}

	@Override
	public boolean canOpenGui()
	{
		return true;
	}

	@Override
	public int getGuiID()
	{
		return Lib.GUIID_UtilitySign;
	}

	@Nullable
	@Override
	public TileEntity getGuiMaster()
	{
		return this;
	}

	//	=================================
	//		STATE AND SHAPE
	//	=================================

	@Override
	public String[] getIntPropertyNames()
	{
		return new String[]{KIND};
	}

	@Override
	public PropertyInteger getIntProperty(String name)
	{
		return BlockUtilitySign.KIND;
	}

	@Override
	public int getIntPropertyValue(String name)
	{
		return kind.ordinal();
	}

	@Override
	public void setValue(String name, int value)
	{
		setKind(UtilitySignKind.byIndex(value));
	}

	@Override
	public EnumFacing getFacing()
	{
		return facing;
	}

	@Override
	public void setFacing(EnumFacing facing)
	{
		//Horizontal only. A tag bolted to the underside of something is not a thing that exists, and
		//a facing with no model behind it is a purple block.
		this.facing = facing.getAxis()==EnumFacing.Axis.Y?EnumFacing.NORTH: facing;
	}

	@Override
	public int getFacingLimitation()
	{
		//Six: horizontal, preferring the side clicked. The facing is the direction the plate's back
		//points -- toward whatever it is bolted to -- which is what makes clicking the south face of
		//a pole hang a sign that reads from the south.
		return 6;
	}

	@Override
	public boolean mirrorFacingOnPlacement(EntityLivingBase placer)
	{
		return false;
	}

	@Override
	public boolean canHammerRotate(EnumFacing side, float hitX, float hitY, float hitZ, EntityLivingBase entity)
	{
		//False so the hammer reaches hammerUseSide. Rotating a sign means taking it down and putting
		//it on the face you meant, which is one click either way; cycling thirteen kinds without
		//leaving the ladder is worth more than that.
		return false;
	}

	@Override
	public boolean canRotate(EnumFacing axis)
	{
		return axis.getAxis()==EnumFacing.Axis.Y;
	}

	@Override
	public float[] getBlockBounds()
	{
		AxisAlignedBB box = plateBounds(facing, kind);
		return new float[]{(float)box.minX, (float)box.minY, (float)box.minZ,
				(float)box.maxX, (float)box.maxY, (float)box.maxZ};
	}

	/**
	 * Where a plate of that kind sits in a cell it is bolted to that way, in block units.
	 * <p>
	 * Derived rather than tabulated, so the selection box follows the model if a kind's plate ever
	 * changes size -- a sign you cannot click where you can see it is the kind of thing that is
	 * obvious in the hand and invisible in a diff.
	 */
	public static AxisAlignedBB plateBounds(EnumFacing facing, UtilitySignKind kind)
	{
		double halfWidth = kind.getWidth()/32d;
		double halfHeight = kind.getHeight()/32d;
		double thick = THICKNESS/16d;
		double lo = 0.5-halfWidth, hi = 0.5+halfWidth;
		double bottom = 0.5-halfHeight, top = 0.5+halfHeight;
		switch(facing)
		{
			case NORTH:
				return new AxisAlignedBB(lo, bottom, 0, hi, top, thick);
			case SOUTH:
				return new AxisAlignedBB(lo, bottom, 1-thick, hi, top, 1);
			case WEST:
				return new AxisAlignedBB(0, bottom, lo, thick, top, hi);
			default:
				return new AxisAlignedBB(1-thick, bottom, lo, 1, top, hi);
		}
	}

	//	=================================
	//		BEING PUT UP AND TAKEN DOWN
	//	=================================

	@Override
	public void readOnPlacement(@Nullable EntityLivingBase placer, ItemStack stack)
	{
		//A sign taken down and put back up is the same sign. Without this, hammering thirteen times
		//to find the right plate and then mining it by accident throws all of it away, which is a
		//thing a player only has to be caught by once.
		NBTTagCompound tag = stack.getSubCompound("sign");
		if(tag!=null)
			readSign(tag);
	}

	@Override
	public ItemStack getTileDrop(@Nullable EntityPlayer player, IBlockState state)
	{
		ItemStack stack = new ItemStack(IEContent.blockSignage, 1, BlockTypes_Signage.UTILITY_SIGN.getMeta());
		//Only when there is something to keep. A blank sign of the first kind stacks with every
		//other blank sign, which is what somebody carrying a box of them wants.
		if(kind!=UtilitySignKind.YELLOW_VERTICAL||hasText())
			stack.setTagInfo("sign", writeSign(new NBTTagCompound()));
		return stack;
	}

	private boolean hasText()
	{
		for(String line : lines)
			if(!line.isEmpty())
				return true;
		return false;
	}

	//	=================================
	//		SAVING
	//	=================================

	private NBTTagCompound writeSign(NBTTagCompound nbt)
	{
		nbt.setInteger(KIND, kind.ordinal());
		for(int i = 0; i < lines.length; i++)
			nbt.setString("line"+i, lines[i]);
		return nbt;
	}

	private void readSign(NBTTagCompound nbt)
	{
		kind = UtilitySignKind.byIndex(nbt.getInteger(KIND));
		for(int i = 0; i < lines.length; i++)
			setLine(i, nbt.getString("line"+i));
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("facing", facing.ordinal());
		writeSign(nbt);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		//Bounds-checked rather than trusted: the ordinal comes off disk, and EnumFacing.byIndex
		//wraps rather than failing, which would silently bolt a sign to the wrong wall.
		int ordinal = nbt.getInteger("facing");
		setFacing(ordinal >= 0&&ordinal < EnumFacing.VALUES.length
				?EnumFacing.VALUES[ordinal]: EnumFacing.NORTH);
		readSign(nbt);
	}

	/**
	 * Redraw when a description packet changes the plate.
	 * <p>
	 * The kind reaches the model through {@code getActualState}, which the client only asks for when
	 * it rebuilds the chunk section -- and nothing marks that section dirty when a packet changes a
	 * tile entity underneath an unchanged block state. The same hole {@code TileEntityConduit} has,
	 * closed the same way. The lettering is drawn every frame and needs none of this.
	 */
	@Override
	public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt)
	{
		UtilitySignKind before = kind;
		EnumFacing wasFacing = facing;
		super.onDataPacket(net, pkt);
		if(world!=null&&world.isRemote&&(kind!=before||facing!=wasFacing))
			world.markBlockRangeForRenderUpdate(getPos(), getPos());
	}

	@Override
	public double getMaxRenderDistanceSquared()
	{
		//Forty-eight blocks. The lettering is a per-frame draw with a matrix push behind it, and a
		//pole line is dozens of tags; past this the plate is one or two pixels across and the text
		//was never legible anyway.
		return 48*48;
	}
}
