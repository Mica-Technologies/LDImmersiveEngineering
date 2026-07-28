/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.fluid.network.*;
import blusunrize.immersiveengineering.common.gui.ContainerFluidNetBase;
import blusunrize.immersiveengineering.common.util.IELogger;
import blusunrize.immersiveengineering.common.util.fluidnet.FluidNetChunkLoader;
import blusunrize.immersiveengineering.common.util.fluidnet.FluidNetSaveData;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client to server: one operation performed in the Fluid Control Console GUI.
 * <p>
 * Every mutating control in the GUI goes through here and the client then re-renders from the next
 * {@link MessageFluidNetSync}; nothing is applied optimistically client-side, so a rejected action
 * can never leave the screen disagreeing with the world.
 * <p>
 * The handler is the trust boundary: it re-checks that the sender actually has a console open,
 * that the main exists, and that they are allowed to edit it. Values are clamped by the model's own
 * setters, so a hand-crafted packet cannot exceed the configured limits -- and
 * {@code SET_FLUID} goes through {@code FluidMain.setFluid}, which refuses while the line pack is
 * non-empty, so a packet cannot re-type a live main either.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class MessageFluidNetAction implements IMessage
{
	public enum Op
	{
		CREATE_MAIN,
		DELETE_MAIN,
		RENAME_MAIN,
		SET_COLOR,
		SET_ENABLED,
		SET_LOCKED,
		SET_POLICY,
		SET_FLUID,
		RESET_TRIP,
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
	private UUID main;
	private NBTTagCompound args;

	public MessageFluidNetAction()
	{
	}

	public MessageFluidNetAction(Op op, @Nullable UUID main, NBTTagCompound args)
	{
		this.op = op;
		this.main = main;
		this.args = args==null?new NBTTagCompound(): args;
	}

	public MessageFluidNetAction(Op op, @Nullable UUID main)
	{
		this(op, main, new NBTTagCompound());
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		op = Op.byIndex(buf.readByte());
		main = buf.readBoolean()?new UUID(buf.readLong(), buf.readLong()): null;
		args = ByteBufUtils.readTag(buf);
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeByte(op.ordinal());
		buf.writeBoolean(main!=null);
		if(main!=null)
		{
			buf.writeLong(main.getMostSignificantBits());
			buf.writeLong(main.getLeastSignificantBits());
		}
		ByteBufUtils.writeTag(buf, args);
	}

	public static class Handler implements IMessageHandler<MessageFluidNetAction, IMessage>
	{
		@Override
		public IMessage onMessage(MessageFluidNetAction message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> apply(message, player));
			return null;
		}

		private static void apply(MessageFluidNetAction message, EntityPlayerMP player)
		{
			if(message.op==null)
				return;
			//Having a console window open is what proves the player is standing at something
			//entitled to edit the network.
			if(!(player.openContainer instanceof ContainerFluidNetBase))
			{
				IELogger.warn("Player "+player.getName()
						+" sent a fluid network action with no console window open");
				return;
			}
			VirtualFluidNet net = VirtualFluidNet.INSTANCE;
			NBTTagCompound args = message.args;

			if(message.op==Op.CREATE_MAIN)
			{
				String name = trimName(args.getString("name"));
				if(name.isEmpty()||net.getMainByName(name)!=null)
					return;
				net.createMain(name, player.getUniqueID());
				FluidNetSaveData.setDirty();
				pushSync(player);
				return;
			}

			//Device-scoped operations carry their own target and are legal with no main at all --
			//unlinking, or configuring a fitting that has not been assigned yet.
			if(message.op==Op.ASSIGN_DEVICE||message.op==Op.SET_DEVICE)
			{
				applyToDevice(message, net, args, player);
				pushSync(player);
				return;
			}

			FluidMain main = net.getMain(message.main);
			if(main==null||!main.canEdit(player.getUniqueID()))
				return;

			switch(message.op)
			{
				case DELETE_MAIN:
					net.deleteMain(main.getId());
					break;
				case RENAME_MAIN:
				{
					String name = trimName(args.getString("name"));
					FluidMain clash = net.getMainByName(name);
					if(!name.isEmpty()&&(clash==null||clash==main))
						main.setName(name);
					break;
				}
				case SET_COLOR:
					main.setColor(args.getInteger("color")&0xFFFFFF);
					break;
				case SET_ENABLED:
					main.setEnabled(args.getBoolean("value"));
					break;
				case SET_LOCKED:
					main.setLocked(args.getBoolean("value"));
					if(main.getOwner()==null)
						main.setOwner(player.getUniqueID());
					break;
				case SET_FLUID:
				{
					String name = args.getString("fluid");
					//An unregistered name would type the main as something no endpoint can ever
					//match, which is indistinguishable from the network being broken.
					if(!name.isEmpty()&&FluidRegistry.getFluid(name)==null)
						break;
					main.setFluid(name.isEmpty()?null: name);
					break;
				}
				case RESET_TRIP:
					main.setTripped(false);
					break;
				case RESET_METER:
				{
					//Distinguish "this fitting" from "the whole main" by whether a position was
					//actually supplied. Reading an absent position would yield 0,0,0 and silently
					//reset whichever fitting happens to sit at the origin.
					if(args.hasKey("x"))
					{
						FluidDevice device = net.getDevice(readPos(args));
						if(device!=null)
							device.resetMeter();
					}
					else
						for(FluidDevice each : main.getDevices())
							each.resetMeter();
					break;
				}
				case SET_POLICY:
				{
					FluidPolicy policy = main.getPolicy();
					//Each key is optional so the GUI can push a single field.
					if(args.hasKey("maxInput"))
						policy.setMaxInput(args.getInteger("maxInput"));
					if(args.hasKey("maxOutput"))
						policy.setMaxOutput(args.getInteger("maxOutput"));
					if(args.hasKey("leakPct"))
						policy.setLeakPct(args.getDouble("leakPct"));
					if(args.hasKey("packCap"))
						policy.setPackCap(args.getInteger("packCap"));
					if(args.hasKey("failoverTopUp"))
						policy.setFailoverTopUp(args.getBoolean("failoverTopUp"));
					if(args.hasKey("scheduleEnabled"))
						policy.setScheduleEnabled(args.getBoolean("scheduleEnabled"));
					if(args.hasKey("scheduleOn"))
						policy.setScheduleOn(args.getInteger("scheduleOn"));
					if(args.hasKey("scheduleOff"))
						policy.setScheduleOff(args.getInteger("scheduleOff"));
					//A lowered pack cap must pull the stored amount down with it.
					main.setPack(main.getPack());
					break;
				}
				case ADD_FAILOVER:
					main.addFailover(FluidDevice.parseUUID(args.getString("target")));
					break;
				case REMOVE_FAILOVER:
					main.removeFailover(FluidDevice.parseUUID(args.getString("target")));
					break;
				case MOVE_FAILOVER:
					main.moveFailover(FluidDevice.parseUUID(args.getString("target")),
							args.getBoolean("up"));
					break;
				default:
					break;
			}
			FluidNetSaveData.setDirty();
			pushSync(player);
		}

		/**
		 * The viewer's copy is the only thing the GUI draws from, so refresh it as soon as the
		 * change lands rather than waiting for the next periodic push.
		 */
		private static void pushSync(EntityPlayerMP player)
		{
			if(player.openContainer instanceof ContainerFluidNetBase)
				((ContainerFluidNetBase)player.openContainer).syncNow();
		}

		/**
		 * Applies an operation that targets one fitting rather than a main.
		 * <p>
		 * Permission is checked against the main the fitting currently belongs to <em>and</em> the
		 * one it is moving to, so a locked main can neither be raided for fittings nor have foreign
		 * ones pushed into it.
		 */
		private static void applyToDevice(MessageFluidNetAction message, VirtualFluidNet net,
										  NBTTagCompound args, EntityPlayerMP player)
		{
			if(!args.hasKey("x"))
				return;
			FluidDevice device = net.getDevice(readPos(args));
			if(device==null)
				return;
			FluidMain owner = net.getMain(device.getMain());
			if(owner!=null&&!owner.canEdit(player.getUniqueID()))
				return;

			if(message.op==Op.ASSIGN_DEVICE)
			{
				boolean unlink = args.getBoolean("unlink")||message.main==null;
				FluidMain target = unlink?null: net.getMain(message.main);
				if(!unlink)
				{
					if(target==null||!target.canEdit(player.getUniqueID()))
						return;
				}
				net.assignDevice(device, target==null?null: target.getId());
				//Assignment changes which main's ordering the fitting belongs to, and an unlinked
				//fitting stops pinning its chunk.
				FluidNetChunkLoader.refresh();
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
					FluidNetChunkLoader.refresh();
				}
				if(args.hasKey("enabled"))
				{
					//A disabled fitting stops pinning its chunk: the toggle means "this fitting is
					//not in service", and a fitting that is not in service should not be holding
					//a chunk in memory.
					device.setEnabled(args.getBoolean("enabled"));
					FluidNetChunkLoader.refresh();
				}
				if(args.hasKey("valveOutput"))
					device.setValveOutput(args.getBoolean("valveOutput"));
				if(args.hasKey("valveInverted"))
					device.setValveInverted(args.getBoolean("valveInverted"));
				if(args.hasKey("name"))
					device.setCustomName(trimName(args.getString("name")));
				if(args.getBoolean("resetMeter"))
					device.resetMeter();
				//Priority and the critical flag change the serving order.
				FluidMain main = net.getMain(device.getMain());
				if(main!=null)
					main.invalidateViews();
			}
			FluidNetSaveData.setDirty();
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
