/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.api.MultiblockHandler.IMultiblock;
import blusunrize.immersiveengineering.api.TargetingInfo;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.GridDeviceType;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.energy.wires.WireType;
import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidDeviceType;
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.IESaveData;
import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsAll;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IDirectionalTile;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.blocks.conduit.BlockTypes_Conduit;
import blusunrize.immersiveengineering.common.blocks.conduit.TileEntityJunctionBox;
import blusunrize.immersiveengineering.common.blocks.fluidnet.BlockTypes_FluidNetDevice;
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridDevice;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_Connector;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration0;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDevice0;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDevice1;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityCapacitorLV;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFermenter;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockFluidConsole;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGridConsole;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockSqueezer;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityPortableGenerator;
import blusunrize.immersiveengineering.common.blocks.wooden.BlockTypes_WoodenDevice0;
import blusunrize.immersiveengineering.common.blocks.wooden.TileEntityWoodenBarrel;
import blusunrize.immersiveengineering.common.entities.EntityHydraulicCrawler;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.fluidnet.FluidNetSaveData;
import blusunrize.immersiveengineering.common.util.grid.GridSaveData;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /ie demo ...} -- builds a walkable showcase of everything this fork adds.
 * <p>
 * A playtester should not have to be told, in prose, how to build a rig that demonstrates a fix.
 * This lays a stone boulevard with a station for each feature on it, every station signposted with
 * what to look at and how to poke it, and every piece of hardware wired, patched, formed and fuelled
 * the way it would be if somebody had built it by hand. Load a fresh superflat, run
 * {@code /ie demo build}, and walk east.
 * <p>
 * <strong>Everything goes through the mod's own APIs.</strong> Multiblocks are formed through their
 * own {@code createStructure}, wires through {@code ImmersiveNetHandler} and {@code connectCable},
 * grid and fluid fittings through {@code VirtualGrid} / {@code VirtualFluidNet}. Where a multiblock
 * cannot be formed without a gesture the command has no way to make -- the Gas Pump, which is
 * assembled by its own hammer interaction -- its blocks are placed and a sign says to hammer it.
 * That is deliberate: a demo that reached past a feature's own entry points would stop being
 * evidence that the feature works.
 * <p>
 * The geometry and the sign text live in {@link DemoLayout}, which is world-free and tested.
 *
 * @author LDImmersiveEngineering -- demo command
 */
public class CommandDemo extends CommandTreeBase
{
	/** The grid segment the boulevard's devices are put on. Reused rather than duplicated. */
	private static final String SEGMENT_NAME = "demo";

	/** The fluid main the boulevard's fittings are put on. */
	private static final String MAIN_NAME = "demo water";

	/**
	 * Where the last build put its origin, so {@code /ie demo clear} with no arguments removes the
	 * street that is actually there rather than one centred on wherever the player has wandered to.
	 * Server-session only and deliberately not saved: it is a convenience, and the coordinates are
	 * printed on every build for the case where it is gone.
	 */
	@Nullable
	private static BlockPos lastOrigin;

	{
		addSubcommand(new SubBuild());
		addSubcommand(new SubClear());
		addSubcommand(new CommandTreeHelp(this));
	}

	@Nonnull
	@Override
	public String getName()
	{
		return "demo";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "Use \"/ie demo help\" for more information";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 4;
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}

	/**
	 * Where the boulevard starts, in the coordinates a player thinks in: the block they are standing
	 * <em>in</em>, so the floor comes out one below and the street is at their feet.
	 */
	private static BlockPos resolveOrigin(ICommandSender sender, String[] args, boolean remembered)
			throws CommandException
	{
		if(args.length >= 3)
			return CommandBase.parseBlockPos(sender, args, 0, false);
		if(remembered&&lastOrigin!=null)
			return lastOrigin;
		return sender.getPosition();
	}

	private class SubBuild extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "build";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie demo build [x y z] -- defaults to where you are standing";
		}

		@Nonnull
		@Override
		public List<String> getTabCompletions(@Nonnull MinecraftServer server,
											  @Nonnull ICommandSender sender, @Nonnull String[] args,
											  @Nullable BlockPos targetPos)
		{
			return getTabCompletionCoordinate(args, 0, targetPos);
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
							@Nonnull String[] args) throws CommandException
		{
			BlockPos origin = resolveOrigin(sender, args, false);
			World world = sender.getEntityWorld();
			if(world.isRemote)
				return;
			EntityPlayer player = sender.getCommandSenderEntity() instanceof EntityPlayer
					?(EntityPlayer)sender.getCommandSenderEntity(): null;

			Builder builder = new Builder(world, origin, player);
			builder.clear();
			builder.platform();
			builder.stations();
			lastOrigin = origin;

			msg(sender, TextFormatting.GOLD+"Demo boulevard built"+TextFormatting.RESET
					+" at "+origin.getX()+" "+origin.getY()+" "+origin.getZ()
					+", running east for "+(DemoLayout.stationCount()*DemoLayout.SPACING)+" blocks.");
			msg(sender, TextFormatting.GRAY+"City mode is "
					+(CityMode.enabled()?TextFormatting.GREEN+"on": TextFormatting.YELLOW+"off")
					+TextFormatting.GRAY+"; stations 6 and 7 read differently either way."
					+TextFormatting.RESET);
			for(String note : builder.notes)
				msg(sender, TextFormatting.YELLOW+"  "+note+TextFormatting.RESET);
			msg(sender, TextFormatting.GRAY+"Remove it again with /ie demo clear "
					+origin.getX()+" "+origin.getY()+" "+origin.getZ()+TextFormatting.RESET);
		}
	}

	private class SubClear extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "clear";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie demo clear [x y z] -- defaults to the last build, then to where you stand";
		}

		@Nonnull
		@Override
		public List<String> getTabCompletions(@Nonnull MinecraftServer server,
											  @Nonnull ICommandSender sender, @Nonnull String[] args,
											  @Nullable BlockPos targetPos)
		{
			return getTabCompletionCoordinate(args, 0, targetPos);
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
							@Nonnull String[] args) throws CommandException
		{
			BlockPos origin = resolveOrigin(sender, args, true);
			World world = sender.getEntityWorld();
			if(world.isRemote)
				return;
			new Builder(world, origin, null).clear();
			msg(sender, "Cleared the demo region at "+origin.getX()+" "+origin.getY()+" "
					+origin.getZ()+".");
		}
	}

	//	=================================
	//		THE BUILDER
	//	=================================

	/**
	 * Everything that touches the world, with the station's own coordinate frame folded in.
	 * <p>
	 * A station is written in {@code (dx, dy, dz)} relative to its own centre: {@code dy} 0 is the
	 * floor's top solid layer, {@code dy} 1 is the first block a player could stand in, and negative
	 * {@code dz} is north, away from the sign row. That frame is the whole reason twelve stations
	 * can be written without any of them knowing where it is.
	 */
	private static final class Builder
	{
		private final World world;
		private final int originX;
		private final int originZ;
		/** The floor's top solid layer -- one below the block the player was standing in. */
		private final int groundY;
		@Nullable
		private final EntityPlayer player;
		/** Anything the tester should know did not happen. Reported in chat, never swallowed. */
		private final List<String> notes = new ArrayList<>();

		private int station;

		private Builder(World world, BlockPos origin, @Nullable EntityPlayer player)
		{
			this.world = world;
			this.originX = origin.getX();
			this.originZ = origin.getZ();
			this.groundY = origin.getY()-1;
			this.player = player;
		}

		private BlockPos at(int dx, int dy, int dz)
		{
			return new BlockPos(DemoLayout.stationX(originX, station)+dx, groundY+dy, originZ+dz);
		}

		//	=================================
		//		BULK
		//	=================================

		/**
		 * Empty the whole region, entities included.
		 * <p>
		 * Flag 2 rather than 3: notifying neighbours across forty thousand blocks costs far more
		 * than the fill itself, and there is nothing left standing for a neighbour update to tell.
		 * The stations below place their own hardware with the ordinary flag.
		 */
		private void clear()
		{
			fill(DemoLayout.minY(groundY), DemoLayout.maxY(groundY), Blocks.AIR.getDefaultState());
			//A crawler is an entity and survives its street being deleted, so it would pile up one
			//per rebuild. Only ours: the region is the region the command owns.
			AxisAlignedBB box = new AxisAlignedBB(
					DemoLayout.minX(originX), DemoLayout.minY(groundY), DemoLayout.minZ(originZ),
					DemoLayout.maxX(originX)+1, DemoLayout.maxY(groundY)+1, DemoLayout.maxZ(originZ)+1);
			for(EntityHydraulicCrawler crawler :
					world.getEntitiesWithinAABB(EntityHydraulicCrawler.class, box))
				crawler.setDead();
		}

		private void platform()
		{
			fill(DemoLayout.minY(groundY), groundY, Blocks.STONE.getDefaultState());
			//Glowstone under glass rather than torches: a torch on a floor is knocked off by
			//anything walking into it, and the signs have to stay readable at night.
			for(int x = DemoLayout.minX(originX); x <= DemoLayout.maxX(originX); x += 3)
				for(int dz : new int[]{DemoLayout.HALF_WIDTH-1, -(DemoLayout.HALF_WIDTH-1)})
				{
					BlockPos lamp = new BlockPos(x, groundY-1, originZ+dz);
					world.setBlockState(lamp, Blocks.GLOWSTONE.getDefaultState(), 2);
					world.setBlockState(lamp.up(), Blocks.GLASS.getDefaultState(), 2);
				}
		}

		private void fill(int fromY, int toY, IBlockState state)
		{
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for(int x = DemoLayout.minX(originX); x <= DemoLayout.maxX(originX); x++)
				for(int z = DemoLayout.minZ(originZ); z <= DemoLayout.maxZ(originZ); z++)
					for(int y = fromY; y <= toY; y++)
					{
						pos.setPos(x, y, z);
						world.setBlockState(pos, state, 2);
					}
		}

		//	=================================
		//		PRIMITIVES
		//	=================================

		private void set(BlockPos pos, IBlockState state)
		{
			world.setBlockState(pos, state, 3);
		}

		/**
		 * Place a block and point its tile entity somewhere.
		 * <p>
		 * The facing has to be set on the tile rather than only in the state: a conduit, a connector
		 * and a grid box all keep their mount on the tile entity, and {@code setBlockState} with a
		 * default state hands them all NORTH. That is the one difference between a command-built rig
		 * and a hand-built one that has ever mattered here -- a run of conduit facing the wrong wall
		 * simply does not join up.
		 */
		private void place(BlockPos pos, IBlockState state, EnumFacing facing)
		{
			world.setBlockState(pos, state, 3);
			TileEntity te = world.getTileEntity(pos);
			if(te instanceof IDirectionalTile)
			{
				((IDirectionalTile)te).setFacing(facing);
				te.markDirty();
			}
			if(te instanceof TileEntityIEBase)
				((TileEntityIEBase)te).markContainingBlockForUpdate(null);
		}

		private void sign(BlockPos pos, String[] lines)
		{
			//Rotation 0 is a standing sign whose face points south, i.e. at the walkway.
			world.setBlockState(pos, Blocks.STANDING_SIGN.getStateFromMeta(0), 3);
			writeSign(pos, lines);
		}

		private void wallSign(BlockPos pos, EnumFacing facing, String[] lines)
		{
			world.setBlockState(pos, Blocks.WALL_SIGN.getStateFromMeta(facing.getIndex()), 3);
			writeSign(pos, lines);
		}

		private void writeSign(BlockPos pos, String[] lines)
		{
			TileEntity te = world.getTileEntity(pos);
			if(!(te instanceof TileEntitySign))
				return;
			TileEntitySign sign = (TileEntitySign)te;
			for(int i = 0; i < sign.signText.length; i++)
				sign.signText[i] = new TextComponentString(i < lines.length?lines[i]: "");
			sign.markDirty();
			IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}

		private IBlockState conduit(BlockTypes_Conduit type)
		{
			return IEContent.blockConduit.getStateFromMeta(type.getMeta());
		}

		/**
		 * A length of conduit clipped to {@code mount}.
		 * <p>
		 * The mount goes into the block state as well as onto the tile, because
		 * {@code BlockIETileProvider.createTileEntity} reads it from there -- setting only the tile
		 * afterwards works too, but a tile that is right from the first instant never has a tick in
		 * which it is looking at the wrong wall.
		 */
		private void run(BlockPos pos, EnumFacing mount)
		{
			place(pos, conduit(BlockTypes_Conduit.CONDUIT_RUN)
					.withProperty(IEProperties.FACING_ALL, mount), mount);
		}

		private void feeder(BlockPos pos, EnumFacing axis)
		{
			place(pos, conduit(BlockTypes_Conduit.GROUND_FEEDER)
					.withProperty(IEProperties.FACING_ALL, axis), axis);
		}

		/**
		 * A junction box, with the faces it should break conductors out of.
		 * <p>
		 * Patched here rather than left to auto-patching, because auto-patching hands out the lowest
		 * free conductor and a demo that says "look at the coloured plates" should decide which
		 * colours those are. Auto-patching is still what happens at station 5, where the wire
		 * arrives on a bare face.
		 */
		private TileEntityJunctionBox box(BlockPos pos, EnumFacing[] faces, WireChannel[] channels)
		{
			set(pos, conduit(BlockTypes_Conduit.JUNCTION_BOX));
			TileEntity te = world.getTileEntity(pos);
			if(!(te instanceof TileEntityJunctionBox))
				return null;
			TileEntityJunctionBox junction = (TileEntityJunctionBox)te;
			for(int i = 0; i < faces.length&&i < channels.length; i++)
				junction.getPatch().set(faces[i], channels[i]);
			junction.markDirty();
			junction.markContainingBlockForUpdate(null);
			//Queued rather than walked here: a box performs at most one walk a tick from its own
			//update, which is the entry point that exists so a rig built in one go costs one walk
			//per box instead of one per block laid against it.
			junction.queueRebuild();
			return junction;
		}

		private void connector(BlockPos pos, BlockTypes_Connector type, EnumFacing facing)
		{
			place(pos, IEContent.blockConnectors.getStateFromMeta(type.getMeta()), facing);
		}

		/**
		 * A creative capacitor. Every side is an output from the moment it exists, so nothing here
		 * has to configure one.
		 */
		private void creativeSource(BlockPos pos)
		{
			set(pos, IEContent.blockMetalDevice0
					.getStateFromMeta(BlockTypes_MetalDevice0.CAPACITOR_CREATIVE.getMeta()));
		}

		/**
		 * An LV capacitor with one side opened as an input, so a demo has somewhere visible for the
		 * energy to end up. Up is already an input on a fresh one; anything else has to be said.
		 */
		private void sinkCapacitor(BlockPos pos, EnumFacing input)
		{
			set(pos, IEContent.blockMetalDevice0
					.getStateFromMeta(BlockTypes_MetalDevice0.CAPACITOR_LV.getMeta()));
			TileEntity te = world.getTileEntity(pos);
			if(te instanceof TileEntityCapacitorLV)
			{
				((TileEntityCapacitorLV)te).sideConfig[input.ordinal()] = SideConfig.INPUT;
				te.markDirty();
				((TileEntityCapacitorLV)te).markContainingBlockForUpdate(null);
			}
		}

		/**
		 * String a wire between two nodes, the way {@code ApiUtils.handleCoilItem} does when a
		 * player uses a coil: one connection in the graph, both ends told about it, the block-wire
		 * map updated and the save marked dirty.
		 * <p>
		 * The catenary obstruction check is deliberately not repeated. A player is refused a wire
		 * that would pass through a wall; this command placed the wall, and every run below is clear
		 * by construction.
		 */
		private void wire(BlockPos a, EnumFacing aFace, BlockPos b, EnumFacing bFace, WireType type)
		{
			TileEntity teA = world.getTileEntity(a);
			TileEntity teB = world.getTileEntity(b);
			if(!(teA instanceof IImmersiveConnectable)||!(teB instanceof IImmersiveConnectable))
			{
				notes.add("no wire between "+a+" and "+b+": one end is not connectable");
				return;
			}
			IImmersiveConnectable nodeA = (IImmersiveConnectable)teA;
			IImmersiveConnectable nodeB = (IImmersiveConnectable)teB;
			int distance = (int)Math.sqrt(a.distanceSq(b));
			Connection conn = ImmersiveNetHandler.INSTANCE.addAndGetConnection(world, a, b,
					distance, type);
			nodeA.connectCable(type, new TargetingInfo(aFace, 0.5f, 0.5f, 0.5f), nodeB, BlockPos.ORIGIN);
			nodeB.connectCable(type, new TargetingInfo(bFace, 0.5f, 0.5f, 0.5f), nodeA, BlockPos.ORIGIN);
			ImmersiveNetHandler.INSTANCE.addBlockData(world, conn);
			IESaveData.setDirty(world.provider.getDimension());
			refresh(a);
			refresh(b);
		}

		private void refresh(BlockPos pos)
		{
			TileEntity te = world.getTileEntity(pos);
			if(te==null)
				return;
			te.markDirty();
			//-1 is the wire graph's own event: it tells a client to rebuild the wire models at this
			//block, which is what stops a catenary being drawn as one half of itself.
			world.addBlockEvent(pos, world.getBlockState(pos).getBlock(), -1, 0);
			IBlockState state = world.getBlockState(pos);
			world.notifyBlockUpdate(pos, state, state, 3);
		}

		private void fillInventory(BlockPos pos, ItemStack stack)
		{
			TileEntity te = world.getTileEntity(pos);
			if(!(te instanceof IInventory))
				return;
			IInventory inventory = (IInventory)te;
			for(int slot = 0; slot < inventory.getSizeInventory(); slot++)
				inventory.setInventorySlotContents(slot, stack.copy());
			te.markDirty();
		}

		//	=================================
		//		THE STREET
		//	=================================

		private void stations()
		{
			for(DemoLayout.Station current : DemoLayout.Station.VALUES)
			{
				station = current.ordinal();
				signs(current);
				switch(current)
				{
					case CONDUIT_BASICS:
						conduitBasics();
						break;
					case CONDUIT_CORNERS:
						conduitCorners();
						break;
					case ADJACENT_BOXES:
						adjacentBoxes();
						break;
					case GROUND_FEEDER:
						groundFeeder();
						break;
					case WIRE_TO_BOX:
						wireToBox();
						break;
					case CITY_MODE_MACHINE:
						cityModeSqueezer();
						break;
					case WIRED_BEFORE_FORMED:
						wiredBeforeFormed();
						break;
					case VIRTUAL_GRID:
						virtualGrid();
						break;
					case FLUID_NETWORK:
						fluidNetwork();
						break;
					case PETROLEUM:
						petroleum();
						break;
					case CRAWLER:
						crawler();
						break;
					case FIXES:
						fixesBoard();
						break;
				}
			}
		}

		private void signs(DemoLayout.Station current)
		{
			String[][] panels = current.getSigns();
			for(int i = 0; i < panels.length&&i < DemoLayout.MAX_SIGNS; i++)
				sign(at(DemoLayout.SIGN_ROW_START_X+i, 1, DemoLayout.SIGN_ROW_Z), panels[i]);
		}

		/**
		 * Station 1 -- the rig that was used to verify the conduit fix: a creative source against one
		 * box, a hand-laid run, a second box, and an LV capacitor that visibly fills.
		 */
		private void conduitBasics()
		{
			creativeSource(at(-5, 1, -1));
			box(at(-4, 1, -1),
					new EnumFacing[]{EnumFacing.WEST, EnumFacing.NORTH, EnumFacing.UP},
					new WireChannel[]{WireChannel.WHITE, WireChannel.RED, WireChannel.BLUE});
			for(int dx = -3; dx <= 3; dx++)
				run(at(dx, 1, -1), EnumFacing.DOWN);
			box(at(4, 1, -1),
					new EnumFacing[]{EnumFacing.EAST, EnumFacing.NORTH, EnumFacing.UP},
					new WireChannel[]{WireChannel.WHITE, WireChannel.GREEN, WireChannel.YELLOW});
			sinkCapacitor(at(5, 1, -1), EnumFacing.WEST);
		}

		/**
		 * Station 2 -- both shapes of corner, because they are not the same shape and the difference
		 * is the one thing about conduit geometry a player has to be shown rather than told.
		 */
		private void conduitCorners()
		{
			//Inner: a floor run reaches a wall and climbs it, turning inside its own cell. The wall
			//is what it turns around, so the block at the foot of it is load-bearing in the literal
			//sense -- take it away and there is no corner.
			for(int dy = 1; dy <= 4; dy++)
				set(at(0, dy, -1), Blocks.STONE.getDefaultState());
			box(at(-5, 1, -1), new EnumFacing[]{EnumFacing.UP}, new WireChannel[]{WireChannel.WHITE});
			for(int dx = -4; dx <= -1; dx++)
				run(at(dx, 1, -1), EnumFacing.DOWN);
			run(at(-1, 2, -1), EnumFacing.EAST);
			run(at(-1, 3, -1), EnumFacing.EAST);
			box(at(-1, 4, -1), new EnumFacing[]{EnumFacing.WEST}, new WireChannel[]{WireChannel.WHITE});

			//Outer: a shelf with a run on top, and the run follows the shelf's end face down. The two
			//halves are diagonal neighbours bolted to the same block, which is why no third block is
			//involved and why it needs nothing in the corner.
			for(int dx = 1; dx <= 4; dx++)
				set(at(dx, 4, -5), Blocks.STONE.getDefaultState());
			for(int dy = 1; dy <= 3; dy++)
			{
				set(at(1, dy, -5), Blocks.STONE.getDefaultState());
				set(at(4, dy, -5), Blocks.STONE.getDefaultState());
			}
			box(at(1, 5, -5), new EnumFacing[]{EnumFacing.EAST}, new WireChannel[]{WireChannel.WHITE});
			for(int dx = 2; dx <= 4; dx++)
				run(at(dx, 5, -5), EnumFacing.DOWN);
			for(int dy = 2; dy <= 4; dy++)
				run(at(5, dy, -5), EnumFacing.WEST);
			box(at(5, 1, -5), new EnumFacing[]{EnumFacing.UP}, new WireChannel[]{WireChannel.WHITE});
		}

		/**
		 * Station 3 -- three boxes touching, next to the neighbours that used to make it worse.
		 * Nothing here does anything; that is the whole exhibit.
		 */
		private void adjacentBoxes()
		{
			for(int dx = -1; dx <= 1; dx++)
				box(at(dx, 1, -1), new EnumFacing[]{EnumFacing.UP},
						new WireChannel[]{WireChannel.byIndex(dx+1)});
			set(at(-2, 1, -1), Blocks.UNPOWERED_COMPARATOR.getDefaultState());
			//Facing 2 is a hopper pointing north, into nothing. It is here to fire neighbour updates
			//at the boxes, which is what used to cost a full run walk every tick.
			set(at(2, 1, -1), Blocks.HOPPER.getStateFromMeta(2));
			set(at(0, 1, -3), Blocks.STONE.getDefaultState());
			set(at(0, 2, -3), Blocks.LEVER.getStateFromMeta(5));
		}

		/**
		 * Station 4 -- a run leaving the floor through a feeder, with the floor cut away beside it so
		 * the half nobody would otherwise see is visible.
		 */
		private void groundFeeder()
		{
			for(int dy = 1; dy <= 4; dy++)
				set(at(1, dy, -1), Blocks.STONE.getDefaultState());
			box(at(0, 4, -1), new EnumFacing[]{EnumFacing.WEST}, new WireChannel[]{WireChannel.WHITE});
			for(int dy = 1; dy <= 3; dy++)
				run(at(0, dy, -1), EnumFacing.EAST);

			//The pit, opened before the feeder goes in so the survey that decides what the feeder
			//wears sees the finished surroundings rather than a solid block of stone. The floor is
			//taken away west of the run and left under the feeder's own column, so the half of the
			//run that is normally buried can be looked at without breaking anything.
			for(int dx = -3; dx <= 0; dx++)
				for(int dz = -4; dz <= 0; dz++)
					for(int dy = dx==0?-1: 0; dy >= -3; dy--)
						set(at(dx, dy, dz), Blocks.AIR.getDefaultState());

			feeder(at(0, 0, -1), EnumFacing.UP);
			run(at(0, -1, -1), EnumFacing.EAST);
			run(at(0, -2, -1), EnumFacing.EAST);
			box(at(0, -3, -1), new EnumFacing[]{EnumFacing.WEST}, new WireChannel[]{WireChannel.WHITE});
		}

		/**
		 * Station 5 -- wires attached to box faces with no connector in between, which is the seam
		 * the feature closed. The wire arrives on a bare face on purpose: auto-patching is what is
		 * being demonstrated.
		 */
		private void wireToBox()
		{
			for(int dx = -1; dx <= 1; dx++)
				set(at(dx, 1, -1), Blocks.STONE.getDefaultState());
			creativeSource(at(-5, 1, -1));
			connector(at(-5, 2, -1), BlockTypes_Connector.CONNECTOR_LV, EnumFacing.DOWN);
			box(at(-1, 2, -1), new EnumFacing[0], new WireChannel[0]);
			run(at(0, 2, -1), EnumFacing.DOWN);
			box(at(1, 2, -1), new EnumFacing[0], new WireChannel[0]);
			sinkCapacitor(at(5, 1, -1), EnumFacing.UP);
			connector(at(5, 2, -1), BlockTypes_Connector.CONNECTOR_LV, EnumFacing.DOWN);

			wire(at(-5, 2, -1), EnumFacing.NORTH, at(-1, 2, -1), EnumFacing.WEST, WireType.COPPER);
			wire(at(1, 2, -1), EnumFacing.EAST, at(5, 2, -1), EnumFacing.NORTH, WireType.COPPER);
		}

		/**
		 * Station 6 -- the worklog's squeezer rig: a Portable Generator on gasoline, an LV wire to
		 * the machine's energy block, a hopper of seeds, and a lever on the redstone block.
		 */
		private void cityModeSqueezer()
		{
			BlockPos centre = at(0, 2, -3);
			EnumFacing dir = EnumFacing.NORTH;
			machineFrame(centre, dir,
					IEContent.blockWoodenDevice0.getStateFromMeta(BlockTypes_WoodenDevice0.BARREL.getMeta()),
					IEContent.blockMetalDecoration1
							.getStateFromMeta(BlockTypes_MetalDecoration1.STEEL_FENCE.getMeta()),
					Blocks.PISTON.getDefaultState());

			//Wired after forming here and before it at station 7, which is the whole difference
			//between the two stations. The rest goes in whether or not it formed: a station missing
			//its generator because the machine could not be hammered from a command block would be
			//two problems to work out instead of one.
			form(MultiblockSqueezer.instance, centre, dir, "Squeezer");

			BlockPos energy = centre.offset(dir, -1).offset(dir.rotateY(), -1);
			BlockPos redstone = centre.offset(dir, -1).offset(dir.rotateY(), 1);
			connector(energy.up(), BlockTypes_Connector.CONNECTOR_LV, EnumFacing.DOWN);
			set(redstone.up(), Blocks.LEVER.getStateFromMeta(5));

			BlockPos generator = at(-4, 1, -2);
			set(generator, IEContent.blockPetroleumDevice
					.getStateFromMeta(BlockTypes_PetroleumDevice.PORTABLE_GENERATOR.getMeta()));
			fuelGenerator(generator);
			connector(generator.up(), BlockTypes_Connector.CONNECTOR_LV, EnumFacing.DOWN);
			wire(generator.up(), EnumFacing.NORTH, energy.up(), EnumFacing.SOUTH, WireType.COPPER);

			feedHopper(centre, dir, new ItemStack(Items.WHEAT_SEEDS, 64));
		}

		/**
		 * Station 7 -- the same shape of machine, built in the order that used to break it: connector
		 * and wire first, multiblock afterwards.
		 */
		private void wiredBeforeFormed()
		{
			BlockPos centre = at(0, 2, -3);
			EnumFacing dir = EnumFacing.NORTH;
			machineFrame(centre, dir, Blocks.CAULDRON.getDefaultState(),
					IEContent.blockSheetmetal.getStateFromMeta(BlockTypes_MetalsAll.IRON.getMeta()),
					null);

			BlockPos energy = centre.offset(dir, -1).offset(dir.rotateY(), -1);
			connector(energy.up(), BlockTypes_Connector.CONNECTOR_LV, EnumFacing.DOWN);
			creativeSource(at(-4, 1, -2));
			connector(at(-4, 2, -2), BlockTypes_Connector.CONNECTOR_LV, EnumFacing.DOWN);
			//The wire goes on while the light engineering block behind the connector is still an
			//ordinary block. That is the state the route cache used to bake in permanently.
			wire(at(-4, 2, -2), EnumFacing.NORTH, energy.up(), EnumFacing.SOUTH, WireType.COPPER);

			form(MultiblockFermenter.instance, centre, dir, "Fermenter");
			feedHopper(centre, dir, new ItemStack(Items.REEDS, 64));
		}

		/**
		 * The 3x3x3 shell the Squeezer and the Fermenter share, laid out with the same loop and the
		 * same skip conditions their own {@code structureCheck} uses -- a second copy of that
		 * arithmetic would only be somewhere for the two to disagree.
		 *
		 * @param vessel    the barrel or cauldron of the middle layer
		 * @param top       the fence or sheetmetal of the top layer
		 * @param topCentre the piston, where there is one
		 */
		private void machineFrame(BlockPos centre, EnumFacing dir, IBlockState vessel, IBlockState top,
								  @Nullable IBlockState topCentre)
		{
			IBlockState lightEngineering = IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta());
			IBlockState rsEngineering = IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.RS_ENGINEERING.getMeta());
			IBlockState scaffolding = IEContent.blockMetalDecoration1
					.getStateFromMeta(BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
			IBlockState pipe = IEContent.blockMetalDevice1
					.getStateFromMeta(BlockTypes_MetalDevice1.FLUID_PIPE.getMeta());
			EnumFacing right = dir.rotateY();
			for(int h = -1; h <= 1; h++)
				for(int l = -1; l <= 1; l++)
					for(int w = -1; w <= 1; w++)
					{
						if((h==0&&w==0&&l==-1)||(h==0&&w==1&&l > -1)||(h==1&&(l < 0||w > 0)))
							continue;
						BlockPos pos = centre.offset(dir, l).offset(right, w).add(0, h, 0);
						IBlockState state;
						if(h==-1)
							state = l==-1&&w==-1?lightEngineering
									: l==0&&w > -1?pipe: scaffolding;
						else if(h==0)
							state = l==-1&&w==-1?lightEngineering
									: l==-1&&w==1?rsEngineering: vessel;
						else
							state = topCentre!=null&&l==0&&w==0?topCentre: top;
						set(pos, state);
					}
		}

		/**
		 * Form a multiblock through its own {@code createStructure}, which is the only entry point
		 * that sets every part's offset and fires the formation event.
		 * <p>
		 * {@code createStructure} takes the face that was <em>hit</em> and turns it round, so the
		 * opposite of the direction we want is what goes in. It also wants a player, for the hammer
		 * and for the event; a command block has none, and the honest answer there is a note in chat
		 * rather than a machine that quietly did not form.
		 *
		 * @return true if it formed
		 */
		private boolean form(IMultiblock multiblock, BlockPos trigger, EnumFacing dir, String name)
		{
			if(player==null)
			{
				notes.add(name+" was not formed: only a player can form a multiblock. "
						+"Hammer it by hand.");
				return false;
			}
			if(multiblock.createStructure(world, trigger, dir.getOpposite(), player))
				return true;
			notes.add(name+" did not form at "+trigger+". Hammer it by hand.");
			return false;
		}

		private void fuelGenerator(BlockPos pos)
		{
			TileEntity te = world.getTileEntity(pos);
			if(!(te instanceof TileEntityPortableGenerator))
				return;
			Fluid gasoline = FluidRegistry.getFluid("ie_gasoline");
			if(gasoline==null)
			{
				notes.add("no gasoline is registered, so the Portable Generator is dry");
				return;
			}
			((TileEntityPortableGenerator)te).tank.fill(
					new FluidStack(gasoline, TileEntityPortableGenerator.TANK_CAPACITY), true);
			te.markDirty();
			((TileEntityPortableGenerator)te).markContainingBlockForUpdate(null);
		}

		/**
		 * A hopper and a chest feeding the machine's own input position -- structure index 15, the
		 * vessel one step along the machine's facing and one to its left, which is the block the
		 * Squeezer and the Fermenter both expose an item handler on.
		 */
		private void feedHopper(BlockPos centre, EnumFacing dir, ItemStack stack)
		{
			EnumFacing right = dir.rotateY();
			BlockPos intake = centre.offset(dir, 1).offset(right, -1);
			BlockPos hopper = intake.offset(right, -1);
			set(hopper, Blocks.HOPPER.getStateFromMeta(right.getIndex()));
			fillInventory(hopper, stack);
			set(hopper.up(), Blocks.CHEST.getDefaultState());
			fillInventory(hopper.up(), stack);
		}

		/**
		 * Station 8 -- a console, a feed, a service and a signal unit, all on one segment, with no
		 * wire anywhere between the source and the load.
		 */
		private void virtualGrid()
		{
			GridSegment segment = VirtualGrid.INSTANCE.getSegmentByName(SEGMENT_NAME);
			if(segment==null)
				segment = VirtualGrid.INSTANCE.createSegment(SEGMENT_NAME);
			segment.setEnabled(true);

			BlockPos consoleOrigin = at(-3, 1, -3);
			set(consoleOrigin, IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta()));
			set(consoleOrigin.east(), IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta()));
			set(consoleOrigin.up(), IEContent.blockGridDevice
					.getStateFromMeta(BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta()));
			set(consoleOrigin.up().east(), IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.RS_ENGINEERING.getMeta()));
			form(MultiblockGridConsole.instance, consoleOrigin, EnumFacing.NORTH, "Grid Console");

			creativeSource(at(1, 1, -3));
			gridDevice(at(1, 2, -3), BlockTypes_GridDevice.FEED_UNIT, EnumFacing.DOWN,
					GridDeviceType.FEED, segment);
			sinkCapacitor(at(3, 1, -3), EnumFacing.UP);
			gridDevice(at(3, 2, -3), BlockTypes_GridDevice.SERVICE_UNIT, EnumFacing.DOWN,
					GridDeviceType.SERVICE, segment);
			set(at(5, 1, -3), Blocks.STONE.getDefaultState());
			gridDevice(at(5, 2, -3), BlockTypes_GridDevice.SIGNAL_UNIT, EnumFacing.DOWN,
					GridDeviceType.SIGNAL, segment);
			GridSaveData.setDirty();
		}

		private void gridDevice(BlockPos pos, BlockTypes_GridDevice type, EnumFacing facing,
								GridDeviceType deviceType, GridSegment segment)
		{
			place(pos, IEContent.blockGridDevice.getStateFromMeta(type.getMeta()), facing);
			//registerDevice rather than waiting for the tile's own onLoad: the record is what the
			//assignment hangs off, and attaching later finds it and keeps the segment.
			GridDevice device = VirtualGrid.INSTANCE.registerDevice(
					new DimensionBlockPos(pos, world), deviceType);
			VirtualGrid.INSTANCE.assignDevice(device, segment.getId());
		}

		/**
		 * Station 9 -- the grid's mirror in millibuckets. A barrel of water pushes into an inlet, the
		 * main types itself from it, and an outlet fills a keg on the far side of the station.
		 */
		private void fluidNetwork()
		{
			FluidMain main = VirtualFluidNet.INSTANCE.getMainByName(MAIN_NAME);
			if(main==null)
				main = VirtualFluidNet.INSTANCE.createMain(MAIN_NAME);
			main.setEnabled(true);

			BlockPos consoleOrigin = at(-3, 1, -3);
			set(consoleOrigin, IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.LIGHT_ENGINEERING.getMeta()));
			set(consoleOrigin.east(), IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.getMeta()));
			set(consoleOrigin.up(), IEContent.blockFluidNetDevice
					.getStateFromMeta(BlockTypes_FluidNetDevice.CONSOLE_HOUSING.getMeta()));
			set(consoleOrigin.up().east(), IEContent.blockMetalDecoration0
					.getStateFromMeta(BlockTypes_MetalDecoration0.RS_ENGINEERING.getMeta()));
			form(MultiblockFluidConsole.instance, consoleOrigin, EnumFacing.NORTH, "Fluid Console");

			//The inlet is bolted to the barrel's underside: the barrel's down face is an output on a
			//fresh one, so it pushes into the fitting without anybody configuring anything.
			fluidDevice(at(1, 1, -3), BlockTypes_FluidNetDevice.FLUID_INLET, EnumFacing.UP,
					FluidDeviceType.INLET, main);
			set(at(1, 2, -3), IEContent.blockWoodenDevice0
					.getStateFromMeta(BlockTypes_WoodenDevice0.BARREL.getMeta()));
			fillBarrel(at(1, 2, -3));

			fluidDevice(at(3, 2, -3), BlockTypes_FluidNetDevice.FLUID_OUTLET, EnumFacing.DOWN,
					FluidDeviceType.OUTLET, main);
			set(at(3, 1, -3), IEContent.blockWoodenDevice0
					.getStateFromMeta(BlockTypes_WoodenDevice0.BARREL.getMeta()));

			set(at(5, 1, -3), Blocks.STONE.getDefaultState());
			fluidDevice(at(5, 2, -3), BlockTypes_FluidNetDevice.MAIN_VALVE, EnumFacing.DOWN,
					FluidDeviceType.VALVE, main);
			FluidNetSaveData.setDirty();
		}

		private void fluidDevice(BlockPos pos, BlockTypes_FluidNetDevice type, EnumFacing facing,
								 FluidDeviceType deviceType, FluidMain main)
		{
			place(pos, IEContent.blockFluidNetDevice.getStateFromMeta(type.getMeta()), facing);
			FluidDevice device = VirtualFluidNet.INSTANCE.registerDevice(
					new DimensionBlockPos(pos, world), deviceType);
			VirtualFluidNet.INSTANCE.assignDevice(device, main.getId());
		}

		private void fillBarrel(BlockPos pos)
		{
			TileEntity te = world.getTileEntity(pos);
			if(!(te instanceof TileEntityWoodenBarrel))
				return;
			((TileEntityWoodenBarrel)te).tank.fill(
					new FluidStack(FluidRegistry.WATER, TileEntityWoodenBarrel.CAPACITY), true);
			te.markDirty();
			((TileEntityWoodenBarrel)te).markContainingBlockForUpdate(null);
		}

		/**
		 * Station 10 -- the forecourt. The Gas Pump is left loose on purpose: it is assembled by its
		 * own hammer interaction, and reaching past that to set the two flags by hand would prove
		 * nothing about the feature.
		 */
		private void petroleum()
		{
			BlockPos generator = at(-4, 1, -2);
			set(generator, petroleumDevice(BlockTypes_PetroleumDevice.PORTABLE_GENERATOR));
			fuelGenerator(generator);

			place(at(-1, 1, -2), petroleumDevice(BlockTypes_PetroleumDevice.GAS_PUMP), EnumFacing.SOUTH);
			place(at(-1, 2, -2), petroleumDevice(BlockTypes_PetroleumDevice.GAS_PUMP), EnumFacing.SOUTH);
			set(at(1, 1, -2), petroleumDevice(BlockTypes_PetroleumDevice.FORECOURT_SIGN));
			set(at(2, 1, -2), petroleumDevice(BlockTypes_PetroleumDevice.PROPANE_CYLINDER));
			set(at(3, 1, -2), petroleumDevice(BlockTypes_PetroleumDevice.PROPANE_TANK_UPRIGHT));
			//No facing: the torpedo tank lies north to south and does not turn -- see its class doc.
			set(at(4, 1, -2), petroleumDevice(BlockTypes_PetroleumDevice.PROPANE_TANK_TORPEDO));
			set(at(-3, 1, -5), petroleumDevice(BlockTypes_PetroleumDevice.WELLHEAD));
			set(at(-1, 1, -5), petroleumDevice(BlockTypes_PetroleumDevice.OILFIELD_FRAME));
			set(at(1, 1, -5), petroleumDevice(BlockTypes_PetroleumDevice.FLARE_STACK));
		}

		private IBlockState petroleumDevice(BlockTypes_PetroleumDevice type)
		{
			return IEContent.blockPetroleumDevice.getStateFromMeta(type.getMeta());
		}

		/**
		 * Station 11 -- the machine itself, fuelled and parked. Spawned through the entity the item
		 * spawns, and fuelled the way a dismantled one is: by handing it the tagged item, so the one
		 * path that fills its tank stays the only one.
		 */
		private void crawler()
		{
			BlockPos park = at(0, 1, -4);
			EntityHydraulicCrawler machine = new EntityHydraulicCrawler(world);
			//Yaw 0 is south, so it is parked facing the walkway rather than showing its back.
			machine.setLocationAndAngles(park.getX()+0.5, park.getY(), park.getZ()+0.5, 0, 0);
			Fluid diesel = FluidRegistry.getFluid(EntityHydraulicCrawler.FUEL_FLUID);
			if(diesel!=null)
			{
				ItemStack fuelled = new ItemStack(IEContent.itemHydraulicCrawler);
				NBTTagCompound tag = new NBTTagCompound();
				tag.setTag(EntityHydraulicCrawler.TAG_FUEL,
						new FluidStack(diesel, machine.getFuelCapacity())
								.writeToNBT(new NBTTagCompound()));
				fuelled.setTagCompound(tag);
				machine.readFuelFromItem(fuelled);
			}
			else
				notes.add("no diesel is registered, so the Crawler is parked empty");
			world.spawnEntity(machine);
		}

		/**
		 * Station 12 -- the board. Wall signs on a stone backing, because six more standing signs
		 * would run into station 11's row.
		 */
		private void fixesBoard()
		{
			for(int dx = -2; dx <= 2; dx++)
				for(int dy = 1; dy <= 3; dy++)
					set(at(dx, dy, -1), Blocks.STONE.getDefaultState());
			for(int i = 0; i < DemoLayout.FIXES_BOARD.length; i++)
			{
				int dx = -1+i%3;
				int dy = 2-i/3;
				wallSign(at(dx, dy, 0), EnumFacing.SOUTH, DemoLayout.FIXES_BOARD[i]);
			}
		}
	}
}
