package blusunrize.immersiveengineering.common.util.compat.opencomputers;

import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityMultiblockMetal;
import li.cil.oc.api.Network;
import li.cil.oc.api.driver.NamedBlock;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.AbstractManagedEnvironment;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Optional;

public abstract class ManagedEnvironmentIE<T extends TileEntityIEBase> extends AbstractManagedEnvironment implements NamedBlock
{
	World w;
	BlockPos pos;
	Class<? extends TileEntityIEBase> myClass;

	//teClass and the type parameter HAVE to match
	public ManagedEnvironmentIE(World w, BlockPos p, Class<? extends TileEntityIEBase> teClass)
	{
		this.w = w;
		pos = p;
		myClass = teClass;
		setNode(Network.newNode(this, Visibility.Network).withComponent(preferredName(), Visibility.Network).create());
	}

	/**
	 * The machine this environment speaks for, never null.
	 * <p>
	 * A computer's connection outlives the block by a tick or two -- break the machine while a
	 * program is polling it and the next callback runs against a position that no longer holds
	 * anything. Every one of the ninety-odd call sites in this package dereferenced the old
	 * nullable result immediately, so that window produced a bare NPE from inside the callback.
	 * Throwing here turns it into an ordinary Lua-side error naming the block that went away,
	 * which a program can catch with pcall. Use {@link #getTileEntityOrNull()} where absence is
	 * expected rather than exceptional.
	 */
	protected T getTileEntity()
	{
		T te = getTileEntityOrNull();
		if(te==null)
			throw new IllegalStateException("There is no "+myClass.getSimpleName()+" at "+pos
					+" any more -- the block was removed while this component was connected.");
		return te;
	}

	@SuppressWarnings("unchecked")
	protected T getTileEntityOrNull()
	{
		TileEntity te = w.getTileEntity(pos);
		if(te!=null&&myClass.isAssignableFrom(te.getClass()))
			return (T)te;
		return null;
	}

	public abstract static class ManagedEnvMultiblock<T2 extends TileEntityMultiblockMetal<?, ?>> extends ManagedEnvironmentIE<T2>
	{

		public ManagedEnvMultiblock(World w, BlockPos p, Class<? extends TileEntityIEBase> teClass)
		{
			super(w, p, teClass);
		}

		protected Object[] enableComputerControl(Context context, Arguments args)
		{
			boolean allow = args.checkBoolean(0);
			if(allow)
				getTileEntity().computerOn = Optional.of(true);
			else
				getTileEntity().computerOn = Optional.empty();
			return null;
		}

		protected Object[] setEnabled(Context context, Arguments args)
		{
			boolean enabled = args.checkBoolean(0);
			TileEntityMultiblockMetal<?, ?> te = getTileEntity();
			if(!te.computerOn.isPresent())
				throw new IllegalStateException("Computer control must be enabled to enable or disable the machine");
			te.computerOn = Optional.of(enabled);
			return null;
		}
	}
}
