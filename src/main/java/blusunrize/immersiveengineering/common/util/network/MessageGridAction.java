/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.grid.*;
import blusunrize.immersiveengineering.common.gui.ContainerGridBase;
import blusunrize.immersiveengineering.common.util.IELogger;
import blusunrize.immersiveengineering.common.util.grid.GridChunkLoader;
import blusunrize.immersiveengineering.common.util.grid.GridSaveData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client to server: one operation performed in the Grid Management Console GUI.
 * <p>
 * Every mutating control in the GUI goes through here and the client then re-renders from
 * the next {@link MessageGridSync}; nothing is applied optimistically client-side, so a
 * rejected action can never leave the screen disagreeing with the world.
 * <p>
 * The handler is the trust boundary: it re-checks that the sender actually has a console
 * open, that the segment exists, and that they are allowed to edit it. Values are clamped
 * by the model's own setters, so a hand-crafted packet cannot exceed the configured limits.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class MessageGridAction implements IMessage
{
	public enum Op
	{
		CREATE_SEGMENT,
		DELETE_SEGMENT,
		RENAME_SEGMENT,
		SET_COLOR,
		SET_ENABLED,
		SET_LOCKED,
		SET_POLICY,
		RESET_BREAKER,
		RESET_METER,
		ASSIGN_DEVICE,
		SET_DEVICE,
		ADD_FAILOVER,
		REMOVE_FAILOVER,
		MOVE_FAILOVER;

		private static final Op[] VALUES = values();

		static Op byIndex(int index)
		{
			return index >= 0&&index < VALUES.length?VALUES[index]: null;
		}
	}

	private Op op;
	@Nullable
	private UUID segment;
	private NBTTagCompound args;

	public MessageGridAction()
	{
	}

	public MessageGridAction(Op op, @Nullable UUID segment, NBTTagCompound args)
	{
		this.op = op;
		this.segment = segment;
		this.args = args==null?new NBTTagCompound(): args;
	}

	public MessageGridAction(Op op, @Nullable UUID segment)
	{
		this(op, segment, new NBTTagCompound());
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		op = Op.byIndex(buf.readByte());
		segment = buf.readBoolean()?new UUID(buf.readLong(), buf.readLong()): null;
		args = ByteBufUtils.readTag(buf);
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeByte(op.ordinal());
		buf.writeBoolean(segment!=null);
		if(segment!=null)
		{
			buf.writeLong(segment.getMostSignificantBits());
			buf.writeLong(segment.getLeastSignificantBits());
		}
		ByteBufUtils.writeTag(buf, args);
	}

	public static class Handler implements IMessageHandler<MessageGridAction, IMessage>
	{
		@Override
		public IMessage onMessage(MessageGridAction message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> apply(message, player));
			return null;
		}

		private static void apply(MessageGridAction message, EntityPlayerMP player)
		{
			if(message.op==null)
				return;
			//Having one of the grid windows open is what proves the player is standing at
			//something entitled to edit the grid -- a console, or the device itself.
			if(!(player.openContainer instanceof ContainerGridBase))
			{
				IELogger.warn("Player "+player.getName()+" sent a grid action with no grid window open");
				return;
			}
			VirtualGrid grid = VirtualGrid.INSTANCE;
			NBTTagCompound args = message.args;

			if(message.op==Op.CREATE_SEGMENT)
			{
				String name = trimName(args.getString("name"));
				if(name.isEmpty()||grid.getSegmentByName(name)!=null)
					return;
				grid.createSegment(name, player.getUniqueID());
				GridSaveData.setDirty();
				pushSync(player);
				return;
			}

			//Device-scoped operations carry their own target and are legal with no segment
			//at all -- unlinking, or configuring a box that has not been assigned yet.
			if(message.op==Op.ASSIGN_DEVICE||message.op==Op.SET_DEVICE)
			{
				applyToDevice(message, grid, args, player);
				pushSync(player);
				return;
			}

			GridSegment segment = grid.getSegment(message.segment);
			if(segment==null||!segment.canEdit(player.getUniqueID()))
				return;

			switch(message.op)
			{
				case DELETE_SEGMENT:
					grid.deleteSegment(segment.getId());
					break;
				case RENAME_SEGMENT:
				{
					String name = trimName(args.getString("name"));
					GridSegment clash = grid.getSegmentByName(name);
					if(!name.isEmpty()&&(clash==null||clash==segment))
						segment.setName(name);
					break;
				}
				case SET_COLOR:
					segment.setColor(args.getInteger("color")&0xFFFFFF);
					break;
				case SET_ENABLED:
					segment.setEnabled(args.getBoolean("value"));
					break;
				case SET_LOCKED:
					segment.setLocked(args.getBoolean("value"));
					if(segment.getOwner()==null)
						segment.setOwner(player.getUniqueID());
					break;
				case RESET_BREAKER:
					segment.setTripped(false);
					break;
				case RESET_METER:
				{
					//Distinguish "this device" from "the whole segment" by whether a position
					//was actually supplied. Reading an absent position would yield 0,0,0 and
					//silently reset whichever device happens to sit at the origin.
					if(args.hasKey("x"))
					{
						GridDevice device = grid.getDevice(readPos(args));
						if(device!=null)
							device.resetMeter();
					}
					else
						for(GridDevice each : segment.getDevices())
							each.resetMeter();
					break;
				}
				case SET_POLICY:
				{
					GridPolicy policy = segment.getPolicy();
					//Each key is optional so the GUI can push a single field.
					if(args.hasKey("maxInput"))
						policy.setMaxInput(args.getInteger("maxInput"));
					if(args.hasKey("maxOutput"))
						policy.setMaxOutput(args.getInteger("maxOutput"));
					if(args.hasKey("lossPct"))
						policy.setLossPct(args.getDouble("lossPct"));
					if(args.hasKey("bufferCap"))
						policy.setBufferCap(args.getInteger("bufferCap"));
					if(args.hasKey("failoverTopUp"))
						policy.setFailoverTopUp(args.getBoolean("failoverTopUp"));
					if(args.hasKey("scheduleEnabled"))
						policy.setScheduleEnabled(args.getBoolean("scheduleEnabled"));
					if(args.hasKey("scheduleOn"))
						policy.setScheduleOn(args.getInteger("scheduleOn"));
					if(args.hasKey("scheduleOff"))
						policy.setScheduleOff(args.getInteger("scheduleOff"));
					//A lowered buffer cap must pull the stored amount down with it.
					segment.setBuffer(segment.getBuffer());
					break;
				}
				case ADD_FAILOVER:
					segment.addFailover(GridDevice.parseUUID(args.getString("target")));
					break;
				case REMOVE_FAILOVER:
					segment.removeFailover(GridDevice.parseUUID(args.getString("target")));
					break;
				case MOVE_FAILOVER:
					segment.moveFailover(GridDevice.parseUUID(args.getString("target")),
							args.getBoolean("up"));
					break;
				default:
					break;
			}
			GridSaveData.setDirty();
			pushSync(player);
		}

		/**
		 * The viewer's copy is the only thing the GUI draws from, so refresh it as soon as
		 * the change lands rather than waiting for the next periodic push.
		 */
		private static void pushSync(EntityPlayerMP player)
		{
			if(player.openContainer instanceof ContainerGridBase)
				((ContainerGridBase)player.openContainer).syncNow();
		}

		/**
		 * Applies an operation that targets one device rather than a segment.
		 * <p>
		 * Permission is checked against the segment the device currently belongs to <em>and</em>
		 * the one it is moving to, so a locked segment can neither be raided for devices nor
		 * have foreign ones pushed into it.
		 */
		private static void applyToDevice(MessageGridAction message, VirtualGrid grid,
										  NBTTagCompound args, EntityPlayerMP player)
		{
			if(!args.hasKey("x"))
				return;
			GridDevice device = grid.getDevice(readPos(args));
			if(device==null)
				return;
			GridSegment owner = grid.getSegment(device.getSegment());
			if(owner!=null&&!owner.canEdit(player.getUniqueID()))
				return;

			if(message.op==Op.ASSIGN_DEVICE)
			{
				boolean unlink = args.getBoolean("unlink")||message.segment==null;
				GridSegment target = unlink?null: grid.getSegment(message.segment);
				if(!unlink)
				{
					if(target==null||!target.canEdit(player.getUniqueID()))
						return;
				}
				grid.assignDevice(device, target==null?null: target.getId());
				//Assignment changes which segment's ordering the device belongs to, and an
				//unlinked device stops pinning its chunk.
				GridChunkLoader.refresh();
			}
			else
			{
				if(args.hasKey("transferCap"))
					device.setTransferCap(args.getInteger("transferCap"));
				if(args.hasKey("priority"))
					device.setPriority(args.getInteger("priority"));
				if(args.hasKey("critical"))
					device.setCritical(args.getBoolean("critical"));
				if(args.hasKey("chunkLoad"))
				{
					device.setChunkLoad(args.getBoolean("chunkLoad"));
					GridChunkLoader.refresh();
				}
				if(args.hasKey("enabled"))
				{
					device.setEnabled(args.getBoolean("enabled"));
					GridChunkLoader.refresh();
				}
				if(args.hasKey("signalOutput"))
					device.setSignalOutput(args.getBoolean("signalOutput"));
				if(args.hasKey("signalInverted"))
					device.setSignalInverted(args.getBoolean("signalInverted"));
				if(args.hasKey("name"))
					device.setCustomName(trimName(args.getString("name")));
				if(args.getBoolean("resetMeter"))
					device.resetMeter();
				//Priority and the critical flag change the serving order.
				GridSegment segment = grid.getSegment(device.getSegment());
				if(segment!=null)
					segment.invalidateViews();
			}
			GridSaveData.setDirty();
		}

		private static DimensionBlockPos readPos(NBTTagCompound args)
		{
			return new DimensionBlockPos(args.getInteger("x"), args.getInteger("y"),
					args.getInteger("z"), args.getInteger("dim"));
		}

		/**
		 * Names reach the console screen and the chat readout, so cap the length and strip
		 * formatting codes rather than trusting the client's text field.
		 */
		private static String trimName(String raw)
		{
			if(raw==null)
				return "";
			String clean = raw.replaceAll("§.", "").trim();
			return clean.length() > 32?clean.substring(0, 32): clean;
		}
	}
}
