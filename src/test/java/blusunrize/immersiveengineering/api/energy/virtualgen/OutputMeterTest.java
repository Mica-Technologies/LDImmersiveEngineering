/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputMeterTest
{
	/**
	 * Offers {@code perTick} on every tick of [from, to).
	 */
	private static void run(OutputMeter meter, long from, long to, int perTick)
	{
		for(long t = from; t < to; t++)
			meter.record(t, perTick);
	}

	@Test
	@DisplayName("a steady offer reads back as that rate once a second has completed")
	void steady()
	{
		OutputMeter meter = new OutputMeter(60);
		run(meter, 0, 40, 4096);
		assertEquals(4096, meter.getRate(40));
	}

	@Test
	@DisplayName("the second still in progress is not counted, so a partial second cannot understate the rate")
	void partialSecondIgnored()
	{
		OutputMeter meter = new OutputMeter(60);
		run(meter, 0, 10, 4096);
		assertEquals(0, meter.getRate(10));
		assertEquals(2048, meter.getRate(20), "one completed second holding half a second of offers");
	}

	@Test
	@DisplayName("the rate is the peak second, not the average, so a breathing watermill reads what it sustains")
	void peakNotAverage()
	{
		OutputMeter meter = new OutputMeter(60);
		run(meter, 0, 20, 1000);
		run(meter, 20, 40, 3000);
		run(meter, 40, 60, 500);
		assertEquals(3000, meter.getRate(60));
	}

	@Test
	@DisplayName("several offers on one tick add up -- a dynamo pushes to each face in turn")
	void sameTickAdds()
	{
		OutputMeter meter = new OutputMeter(60);
		for(long t = 0; t < 20; t++)
		{
			meter.record(t, 100);
			meter.record(t, 50);
		}
		assertEquals(150, meter.getRate(20));
	}

	@Test
	@DisplayName("a peak ages out of the window")
	void windowExpiry()
	{
		OutputMeter meter = new OutputMeter(5);
		run(meter, 0, 20, 5000);
		run(meter, 20, 200, 100);
		assertEquals(100, meter.getRate(200));
	}

	@Test
	@DisplayName("with nothing offered for a whole window the rate falls to zero and there is no data")
	void idle()
	{
		OutputMeter meter = new OutputMeter(5);
		run(meter, 0, 20, 5000);
		assertTrue(meter.hasData(20));
		assertEquals(0, meter.getRate(1000));
		assertFalse(meter.hasData(1000));
	}

	@Test
	@DisplayName("non-positive offers are ignored")
	void nonPositive()
	{
		OutputMeter meter = new OutputMeter(5);
		run(meter, 0, 20, 0);
		run(meter, 0, 20, -50);
		assertEquals(0, meter.getRate(40));
		assertFalse(meter.hasData(40));
	}

	@Test
	@DisplayName("a freshly loaded meter can raise the record but not lower it until it has watched a whole window")
	void warmUpNeverLowers()
	{
		//A player flying past loads the plant for a moment: the empty window must not zero the plant.
		assertEquals(4096, OutputMeter.chooseRate(0, 4096, 20, 60, true));
		assertEquals(4096, OutputMeter.chooseRate(1000, 4096, 20*59, 60, true));
		assertEquals(5000, OutputMeter.chooseRate(5000, 4096, 20, 60, true), "a higher reading still counts at once");
		assertEquals(1000, OutputMeter.chooseRate(1000, 4096, 20*60, 60, true), "after a whole window the reading is the truth");
		assertEquals(0, OutputMeter.chooseRate(0, 4096, 20*60, 60, true));
	}

	@Test
	@DisplayName("a missing generator publishes zero at once, warm-up or not")
	void missingGenerator()
	{
		assertEquals(0, OutputMeter.chooseRate(4096, 4096, 0, 60, false));
		assertEquals(0, OutputMeter.chooseRate(4096, 4096, 20*600, 60, false));
	}

	@Test
	@DisplayName("setWindowChanged reports a real change only")
	void windowChangeReported()
	{
		OutputMeter meter = new OutputMeter(60);
		assertFalse(meter.setWindowChanged(60));
		assertTrue(meter.setWindowChanged(30));
		assertFalse(meter.setWindowChanged(30));
	}

	@Test
	@DisplayName("changing the window discards measurements rather than mis-slicing them")
	void resize()
	{
		OutputMeter meter = new OutputMeter(60);
		run(meter, 0, 20, 4096);
		meter.setWindow(10);
		assertEquals(0, meter.getRate(40));
		meter.setWindow(10);
		run(meter, 40, 60, 10);
		assertEquals(10, meter.getRate(60), "setting the same window again keeps data");
	}
}
