/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase.IBlockEnum;
import blusunrize.immersiveengineering.common.blocks.cloth.BlockTypes_ClothDevice;
import blusunrize.immersiveengineering.common.blocks.conduit.BlockTypes_Conduit;
import blusunrize.immersiveengineering.common.blocks.fluidnet.BlockTypes_FluidNetDevice;
import blusunrize.immersiveengineering.common.blocks.fluidnet.BlockTypes_FluidNetMultiblock;
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridDevice;
import blusunrize.immersiveengineering.common.blocks.grid.BlockTypes_GridMultiblock;
import blusunrize.immersiveengineering.common.blocks.metal.*;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDecoration;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumDevice;
import blusunrize.immersiveengineering.common.blocks.petroleum.BlockTypes_PetroleumMultiblock;
import blusunrize.immersiveengineering.common.blocks.stone.BlockTypes_StoneDecoration;
import blusunrize.immersiveengineering.common.blocks.stone.BlockTypes_StoneDevices;
import blusunrize.immersiveengineering.common.blocks.wooden.BlockTypes_TreatedWood;
import blusunrize.immersiveengineering.common.blocks.wooden.BlockTypes_WoodenDecoration;
import blusunrize.immersiveengineering.common.blocks.wooden.BlockTypes_WoodenDevice0;
import blusunrize.immersiveengineering.common.blocks.wooden.BlockTypes_WoodenDevice1;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How close each meta-indexed enum is to the four-bit ceiling, across all of them at once.
 * <p>
 * <strong>The per-enum tests catch the mistake; this one is meant to prevent it.</strong> Each
 * {@code BlockTypes_*Test} asserts its own enum still fits, which fires only once somebody has
 * already written the seventeenth constant. By then the natural next move -- deleting something to
 * make room -- is the one that renumbers every constant after it and silently turns already-placed
 * blocks into other blocks.
 * <p>
 * So this lists the full occupancy in one place. A nearly-full enum is visible before it is a
 * problem, and the two that are one value from the ceiling are named outright, because "which
 * block do I add this to" is a question asked long before "why did it not fit".
 */
class BlockMetadataHeadroomTest
{
	/** Every meta-indexed enum in the mod, fork additions included. */
	private static Map<String, IBlockEnum[]> allEnums()
	{
		Map<String, IBlockEnum[]> map = new LinkedHashMap<>();
		map.put("metal_multiblock", BlockTypes_MetalMultiblock.values());
		map.put("connector", BlockTypes_Connector.values());
		map.put("metal_device0", BlockTypes_MetalDevice0.values());
		map.put("metal_device1", BlockTypes_MetalDevice1.values());
		map.put("metal_decoration0", BlockTypes_MetalDecoration0.values());
		map.put("metal_decoration1", BlockTypes_MetalDecoration1.values());
		map.put("metal_decoration2", BlockTypes_MetalDecoration2.values());
		map.put("metal_ladder", BlockTypes_MetalLadder.values());
		map.put("stone_decoration", BlockTypes_StoneDecoration.values());
		map.put("stone_devices", BlockTypes_StoneDevices.values());
		map.put("wooden_decoration", BlockTypes_WoodenDecoration.values());
		map.put("wooden_device0", BlockTypes_WoodenDevice0.values());
		map.put("wooden_device1", BlockTypes_WoodenDevice1.values());
		map.put("treated_wood", BlockTypes_TreatedWood.values());
		map.put("cloth_device", BlockTypes_ClothDevice.values());
		map.put("ore", BlockTypes_Ore.values());
		map.put("metals_all", BlockTypes_MetalsAll.values());
		map.put("metals_ie", BlockTypes_MetalsIE.values());
		//Fork additions.
		map.put("petroleum_multiblock", BlockTypes_PetroleumMultiblock.values());
		map.put("petroleum_device", BlockTypes_PetroleumDevice.values());
		map.put("petroleum_decoration", BlockTypes_PetroleumDecoration.values());
		map.put("grid_device", BlockTypes_GridDevice.values());
		map.put("grid_multiblock", BlockTypes_GridMultiblock.values());
		map.put("fluidnet_device", BlockTypes_FluidNetDevice.values());
		map.put("fluidnet_multiblock", BlockTypes_FluidNetMultiblock.values());
		map.put("conduit", BlockTypes_Conduit.values());
		return map;
	}

	@Test
	@DisplayName("no meta-indexed enum anywhere in the mod exceeds the four-bit ceiling")
	void nothingExceedsTheCeiling()
	{
		List<String> over = new ArrayList<>();
		for(Map.Entry<String, IBlockEnum[]> entry : allEnums().entrySet())
			if(entry.getValue().length > BlockEnumTestSupport.MAX_META)
				over.add(entry.getKey()+" ("+entry.getValue().length+")");
		assertTrue(over.isEmpty(),
				"these enums cannot be stored in block metadata: "+over
						+" -- give the new block its own block class, the way the Grid Management "
						+"Console became grid_multiblock, and do NOT delete a constant to make room "
						+"unless it is the last one");
	}

	@Test
	@DisplayName("the two enums that are one value from full are still exactly those two")
	void theNearlyFullOnesAreKnown()
	{
		//	=================================
		//	Named on purpose.
		//	=================================
		//
		// petroleum_multiblock and connector both sit at 15 of 16. Anything added to either is the
		// last one that will ever fit, so the next petroleum machine or the next connector needs a
		// decision made about it rather than a constant appended without thinking.
		//
		// If this test fails because a third enum joined them, that is the moment to plan the split
		// -- not later, when the sixteenth constant is already written and the seventeenth is the
		// one being asked for.
		List<String> nearlyFull = new ArrayList<>();
		for(Map.Entry<String, IBlockEnum[]> entry : allEnums().entrySet())
			if(BlockEnumTestSupport.headroom(entry.getValue()) <= 1)
				nearlyFull.add(entry.getKey());
		assertEquals(java.util.Arrays.asList("metal_multiblock", "connector", "petroleum_multiblock"),
				nearlyFull,
				"the set of nearly-full block enums changed. metal_multiblock is exactly full and "
						+"has been for years; connector and petroleum_multiblock have one slot each. "
						+"Anything new here needs its own block rather than a constant.");
	}

	@Test
	@DisplayName("every enum reports headroom consistent with its own length")
	void headroomAgreesWithLength()
	{
		for(Map.Entry<String, IBlockEnum[]> entry : allEnums().entrySet())
		{
			int length = entry.getValue().length;
			int expected = Math.max(0, BlockEnumTestSupport.MAX_META-length);
			assertEquals(expected, BlockEnumTestSupport.headroom(entry.getValue()),
					"headroom disagrees with length for "+entry.getKey());
		}
	}
}
