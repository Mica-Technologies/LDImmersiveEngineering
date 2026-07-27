/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.tool.BelljarHandler.IPlantHandler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the parts of {@link BelljarHandler} that are pure guards or pure arithmetic: the
 * empty-input short circuits in the three lookups and the growth curves of the built-in
 * plant handlers.
 * <p>
 * Anything keyed by {@code ComparableItemStack} needs a bootstrapped item registry (its
 * hashCode dereferences the item), so seed/soil registration is out of reach here.
 */
class BelljarHandlerTest
{
	private float savedSolid;
	private float savedFluid;

	@BeforeEach
	void saveModifiers()
	{
		savedSolid = BelljarHandler.solidFertilizerModifier;
		savedFluid = BelljarHandler.fluidFertilizerModifier;
	}

	@AfterEach
	void restoreModifiers()
	{
		BelljarHandler.solidFertilizerModifier = savedSolid;
		BelljarHandler.fluidFertilizerModifier = savedFluid;
	}

	/** An IPlantHandler that implements only the abstract methods, so the defaults show through. */
	private static IPlantHandler bareHandler()
	{
		return new IPlantHandler()
		{
			@Override
			public boolean isCorrectSoil(ItemStack seed, ItemStack soil)
			{
				return false;
			}

			@Override
			public float getGrowthStep(ItemStack seed, ItemStack soil, float growth, TileEntity tile, float fertilizer,
									   boolean render)
			{
				return 0;
			}

			@Override
			public ItemStack[] getOutput(ItemStack seed, ItemStack soil, TileEntity tile)
			{
				return new ItemStack[0];
			}

			@Override
			public boolean isValid(ItemStack seed)
			{
				return false;
			}

			@Override
			public IBlockState[] getRenderedPlant(ItemStack seed, ItemStack soil, float growth, TileEntity tile)
			{
				return new IBlockState[0];
			}
		};
	}

	@Test
	@DisplayName("an empty seed never resolves to a plant handler")
	void emptySeedHasNoHandler()
	{
		assertNull(BelljarHandler.getHandler(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("an empty stack is never a solid fertilizer")
	void emptyStackIsNoFertilizer()
	{
		assertNull(BelljarHandler.getItemFertilizerHandler(ItemStack.EMPTY));
	}

	@Test
	@DisplayName("a null fluid is never a liquid fertilizer")
	void nullFluidIsNoFertilizer()
	{
		assertNull(BelljarHandler.getFluidFertilizerHandler(null));
	}

	@Test
	@DisplayName("both fertilizer modifiers start neutral")
	void modifiersStartNeutral()
	{
		assertEquals(1f, BelljarHandler.solidFertilizerModifier, 1e-6);
		assertEquals(1f, BelljarHandler.fluidFertilizerModifier, 1e-6);
	}

	@Test
	@DisplayName("the modifiers are plain, writable config fields")
	void modifiersAreWritable()
	{
		BelljarHandler.solidFertilizerModifier = 2.5f;
		BelljarHandler.fluidFertilizerModifier = .5f;

		assertEquals(2.5f, BelljarHandler.solidFertilizerModifier, 1e-6);
		assertEquals(.5f, BelljarHandler.fluidFertilizerModifier, 1e-6);
	}

	@Test
	@DisplayName("a plain plant handler does not override the soil texture")
	void defaultSoilTextureIsNull()
	{
		assertNull(bareHandler().getSoilTexture(ItemStack.EMPTY, ItemStack.EMPTY, null));
	}

	@Test
	@DisplayName("a plain plant handler resets growth to zero after harvest")
	void defaultResetGrowthIsZero()
	{
		assertEquals(0f, bareHandler().resetGrowth(ItemStack.EMPTY, ItemStack.EMPTY, 1f, null, false), 1e-6);
	}

	@Test
	@DisplayName("a plain plant renders at seven eighths of a block and does not override rendering")
	void defaultRenderDefaults()
	{
		assertEquals(.875f, bareHandler().getRenderSize(ItemStack.EMPTY, ItemStack.EMPTY, 1f, null), 1e-6);
		assertFalse(bareHandler().overrideRender(ItemStack.EMPTY, ItemStack.EMPTY, 1f, null, null));
	}

	@Test
	@DisplayName("crops grow at a flat rate scaled by the fertilizer multiplier")
	void cropGrowthIsLinear()
	{
		assertEquals(.003125f, BelljarHandler.cropHandler.getGrowthStep(null, null, 0f, null, 1f, false), 1e-9);
		assertEquals(.00625f, BelljarHandler.cropHandler.getGrowthStep(null, null, .9f, null, 2f, false), 1e-9);
		assertEquals(0f, BelljarHandler.cropHandler.getGrowthStep(null, null, .5f, null, 0f, false), 1e-9);
	}

	@Test
	@DisplayName("crops reset all the way back to nothing after harvest")
	void cropsResetToZero()
	{
		assertEquals(0f, BelljarHandler.cropHandler.resetGrowth(null, null, 1f, null, false), 1e-9);
	}

	@Test
	@DisplayName("stems grow four times faster before the halfway point")
	void stemGrowthIsTwoPhase()
	{
		assertEquals(.00625f, BelljarHandler.stemHandler.getGrowthStep(null, null, .0f, null, 1f, false), 1e-9);
		assertEquals(.00625f, BelljarHandler.stemHandler.getGrowthStep(null, null, .49f, null, 1f, false), 1e-9);
		assertEquals(.0015625f, BelljarHandler.stemHandler.getGrowthStep(null, null, .5f, null, 1f, false), 1e-9);
		assertEquals(.0015625f, BelljarHandler.stemHandler.getGrowthStep(null, null, 1f, null, 1f, false), 1e-9);
	}

	@Test
	@DisplayName("the stem growth rate scales with the fertilizer multiplier too")
	void stemGrowthScalesWithFertilizer()
	{
		assertEquals(.0125f, BelljarHandler.stemHandler.getGrowthStep(null, null, .1f, null, 2f, false), 1e-9);
		assertEquals(0f, BelljarHandler.stemHandler.getGrowthStep(null, null, .1f, null, 0f, false), 1e-9);
	}

	@Test
	@DisplayName("stems only reset to the halfway point, keeping the vine")
	void stemsResetToHalf()
	{
		assertEquals(.5f, BelljarHandler.stemHandler.resetGrowth(null, null, 1f, null, false), 1e-9);
	}

	@Test
	@DisplayName("stems render at full block size")
	void stemRenderSize()
	{
		assertEquals(1f, BelljarHandler.stemHandler.getRenderSize(null, null, 1f, null), 1e-9);
	}

	@Test
	@DisplayName("stacking plants grow at the plain crop rate")
	void stackingPlantsUseTheDefaultGrowth()
	{
		assertEquals(.003125f, BelljarHandler.stackingHandler.getGrowthStep(null, null, .5f, null, 1f, false), 1e-9);
	}
}
