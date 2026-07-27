/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.BlockIEFluid;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.property.ExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Wet asphalt: pours, spreads, and sets into road.
 * <p>
 * Modelled on the concrete this fork already ships, and deliberately simpler than it. Concrete
 * sets into five different shapes depending on how deep the pour was, which suits a structural
 * material poured into forms. A road is flat by definition, so asphalt always sets into a full
 * block and the player gets a predictable surface however sloppily it was poured -- the mess is
 * the paving, not the result.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class BlockIEFluidAsphalt extends BlockIEFluid
{
	/**
	 * Ticks a pour sits before it sets. Long enough to spread over the area being paved,
	 * short enough that laying a road is not a coffee break.
	 */
	private static final int SET_TICKS = 12;

	public BlockIEFluidAsphalt(String name, net.minecraftforge.fluids.Fluid fluid,
							   net.minecraft.block.material.Material material)
	{
		super(name, fluid, material);
		this.setQuantaPerBlock(8);
	}

	@Override
	protected BlockStateContainer createBlockState()
	{
		return new ExtendedBlockState(this, new IProperty[]{LEVEL, IEProperties.INT_16},
				FLUID_RENDER_PROPS.toArray(new IUnlistedProperty<?>[0]));
	}

	@Override
	public void updateTick(World world, BlockPos pos, IBlockState state, Random rand)
	{
		int timer = state.getValue(IEProperties.INT_16);
		if(timer >= SET_TICKS)
		{
			world.setBlockState(pos, IEContent.blockPetroleumDecoration.getStateFromMeta(
					BlockTypes_PetroleumDecoration.ASPHALT.getMeta()));
			return;
		}
		//Let it keep flowing while it sets, so a pour finds its own level first.
		world.setBlockState(pos, state.withProperty(IEProperties.INT_16, Math.min(15, timer+1)));
		super.updateTick(world, pos, world.getBlockState(pos), rand);
	}
}
