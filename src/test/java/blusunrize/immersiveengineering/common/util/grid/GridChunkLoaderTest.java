/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.grid.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bookkeeping half of {@link GridChunkLoader} -- how many chunks the grid asks to keep
 * loaded. The ticket plumbing itself needs a live server and is exercised in game; what is
 * tested here is the counting, because that is what decides whether the budget is hit and
 * what the console reports.
 */
class GridChunkLoaderTest
{
	private VirtualGrid grid;
	private GridSegment segment;

	@BeforeEach
	void setUp()
	{
		GridConfig.resetToDefaults();
		GridConfig.maxSegmentIO = 1000000;
		grid = new VirtualGrid();
		segment = grid.createSegment("Main");
	}

	@AfterEach
	void tearDown()
	{
		GridConfig.resetToDefaults();
	}

	private GridDevice device(int x, int z, int dim, boolean chunkLoad)
	{
		GridDevice device = grid.registerDevice(new DimensionBlockPos(x, 64, z, dim),
				GridDeviceType.FEED);
		grid.assignDevice(device, segment.getId());
		device.setChunkLoad(chunkLoad);
		return device;
	}

	@Test
	@DisplayName("an empty grid requests nothing")
	void emptyGridRequestsNothing()
	{
		assertEquals(0, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("a device without the flag requests nothing")
	void unflaggedDeviceRequestsNothing()
	{
		device(0, 0, 0, false);
		assertEquals(0, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("a flagged device requests its chunk")
	void flaggedDeviceRequestsOne()
	{
		device(0, 0, 0, true);
		assertEquals(1, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("devices sharing a chunk only cost one")
	void devicesInSameChunkCountOnce()
	{
		//0..15 on both axes is a single chunk.
		device(1, 1, 0, true);
		device(14, 9, 0, true);
		device(7, 15, 0, true);
		assertEquals(1, GridChunkLoader.countRequestedChunks(grid),
				"a city block's worth of boxes in one chunk must not cost three tickets");
	}

	@Test
	@DisplayName("devices in different chunks each cost one")
	void differentChunksCountSeparately()
	{
		device(0, 0, 0, true);
		device(16, 0, 0, true);
		device(0, 16, 0, true);
		assertEquals(3, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("negative coordinates map to the right chunk")
	void negativeCoordinates()
	{
		//-1 belongs to chunk -1, not chunk 0; an arithmetic shift is what makes this work.
		device(-1, -1, 0, true);
		device(-16, -16, 0, true);
		assertEquals(1, GridChunkLoader.countRequestedChunks(grid),
				"-1 and -16 are both inside chunk (-1, -1)");
	}

	@Test
	@DisplayName("the same chunk in two dimensions counts twice")
	void chunksAreCountedPerDimension()
	{
		device(0, 0, 0, true);
		device(0, 0, -1, true);
		assertEquals(2, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("a disabled device stops requesting")
	void disabledDeviceDoesNotRequest()
	{
		GridDevice device = device(0, 0, 0, true);
		assertEquals(1, GridChunkLoader.countRequestedChunks(grid));
		device.setEnabled(false);
		assertEquals(0, GridChunkLoader.countRequestedChunks(grid),
				"switching a device off should release its chunk");
	}

	@Test
	@DisplayName("the request survives the config switch, which only gates the forcing")
	void requestIgnoresConfigGate()
	{
		device(0, 0, 0, true);
		GridConfig.allowChunkloading = false;
		//countRequestedChunks reports intent, so the console can say "requested but disabled"
		//rather than silently showing zero.
		assertEquals(1, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("an unregistered device stops counting")
	void unregisteredDeviceStopsCounting()
	{
		GridDevice device = device(0, 0, 0, true);
		grid.unregisterDevice(device.getPos());
		assertEquals(0, GridChunkLoader.countRequestedChunks(grid));
	}

	@Test
	@DisplayName("many devices across many chunks are counted exactly")
	void manyChunks()
	{
		for(int i = 0; i < 20; i++)
			device(i*16, 0, 0, true);
		assertEquals(20, GridChunkLoader.countRequestedChunks(grid));
	}
}
