/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.immersiveflux;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FluxStorageAdvanced} adds an exponentially decaying average of the in- and output to
 * {@link FluxStorage}. The average is {@code round(previous*factor + amount*(1-factor))} and is
 * only updated on non-simulated transfers.
 */
class FluxStorageAdvancedTest
{
	@Nested
	@DisplayName("inherited FluxStorage behaviour")
	class Inherited
	{
		@Test
		@DisplayName("the constructors mirror FluxStorage's")
		void constructors()
		{
			assertEquals(1000, new FluxStorageAdvanced(1000).getLimitReceive());
			assertEquals(1000, new FluxStorageAdvanced(1000).getLimitExtract());
			assertEquals(64, new FluxStorageAdvanced(1000, 64).getLimitExtract());
			assertEquals(32, new FluxStorageAdvanced(1000, 64, 32).getLimitExtract());
			assertEquals(64, new FluxStorageAdvanced(1000, 64, 32).getLimitReceive());
		}

		@Test
		@DisplayName("receive still respects capacity and the receive limit")
		void receiveStillClamps()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100, 40, 40);
			assertEquals(40, s.receiveEnergy(1000, false));
			assertEquals(40, s.receiveEnergy(1000, false));
			assertEquals(20, s.receiveEnergy(1000, false), "only 20 of space left");
			assertEquals(100, s.getEnergyStored());
		}

		@Test
		@DisplayName("extract still respects the stored energy and the extract limit")
		void extractStillClamps()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100, 40, 40);
			s.setEnergy(50);
			assertEquals(40, s.extractEnergy(1000, false));
			assertEquals(10, s.extractEnergy(1000, false));
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@DisplayName("NBT round-trips through the inherited implementation")
		void nbtRoundTrip()
		{
			FluxStorageAdvanced src = new FluxStorageAdvanced(1000);
			src.receiveEnergy(700, false);
			FluxStorageAdvanced dst = (FluxStorageAdvanced)new FluxStorageAdvanced(1000)
					.readFromNBT(src.writeToNBT(new NBTTagCompound()));
			assertEquals(700, dst.getEnergyStored());
			assertEquals(0, dst.getAverageInsertion(), "the averages are runtime-only and are not persisted");
		}
	}

	@Nested
	@DisplayName("average insertion")
	class AverageInsertion
	{
		@Test
		@DisplayName("starts at zero")
		void startsAtZero()
		{
			assertEquals(0, new FluxStorageAdvanced(1000).getAverageInsertion());
		}

		@Test
		@DisplayName("the default decay factor halves towards the newest value")
		void defaultDecay()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.receiveEnergy(100, false);
			assertEquals(50, s.getAverageInsertion());
			s.receiveEnergy(100, false);
			assertEquals(75, s.getAverageInsertion());
			s.receiveEnergy(100, false);
			assertEquals(88, s.getAverageInsertion(), "87.5 rounds half-up to 88");
		}

		@Test
		@DisplayName("tracks the amount actually received, not the amount requested")
		void tracksAcceptedNotRequested()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000, 20, 20);
			s.receiveEnergy(1000, false);
			assertEquals(10, s.getAverageInsertion(), "only 20 was accepted, so the average moves to 10");
		}

		@Test
		@DisplayName("decays back towards zero on idle ticks")
		void decaysWhenIdle()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.receiveEnergy(100, false);
			assertEquals(50, s.getAverageInsertion());
			s.receiveEnergy(0, false);
			assertEquals(25, s.getAverageInsertion());
			s.receiveEnergy(0, false);
			assertEquals(13, s.getAverageInsertion(), "12.5 rounds half-up to 13");
		}

		@Test
		@DisplayName("is not touched by a simulated receive")
		void simulateDoesNotTrack()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.receiveEnergy(100, true);
			assertEquals(0, s.getAverageInsertion());
		}

		@Test
		@DisplayName("is not touched by extraction")
		void extractionDoesNotTouchInsertion()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.setEnergy(1000);
			s.extractEnergy(500, false);
			assertEquals(0, s.getAverageInsertion());
		}

		@Test
		@DisplayName("a full storage records zero insertion")
		void fullStorageRecordsZero()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100);
			s.setEnergy(100);
			s.receiveEnergy(100, false);
			assertEquals(0, s.getAverageInsertion());
		}
	}

	@Nested
	@DisplayName("average extraction")
	class AverageExtraction
	{
		@Test
		@DisplayName("starts at zero")
		void startsAtZero()
		{
			assertEquals(0, new FluxStorageAdvanced(1000).getAverageExtraction());
		}

		@Test
		@DisplayName("the default decay factor halves towards the newest value")
		void defaultDecay()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.setEnergy(100000);
			s.extractEnergy(100, false);
			assertEquals(50, s.getAverageExtraction());
			s.extractEnergy(100, false);
			assertEquals(75, s.getAverageExtraction());
		}

		@Test
		@DisplayName("tracks the amount actually extracted, not the amount requested")
		void tracksActualNotRequested()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000, 20, 20);
			s.setEnergy(5);
			s.extractEnergy(1000, false);
			assertEquals(3, s.getAverageExtraction(), "only 5 was available, and 2.5 rounds half-up to 3");
		}

		@Test
		@DisplayName("is not touched by a simulated extract")
		void simulateDoesNotTrack()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.setEnergy(1000);
			s.extractEnergy(500, true);
			assertEquals(0, s.getAverageExtraction());
		}

		@Test
		@DisplayName("is not touched by insertion")
		void insertionDoesNotTouchExtraction()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.receiveEnergy(500, false);
			assertEquals(0, s.getAverageExtraction());
		}

		@Test
		@DisplayName("the two averages are tracked independently")
		void independentAverages()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000);
			s.receiveEnergy(200, false);
			s.setEnergy(100000);
			s.extractEnergy(80, false);
			assertEquals(100, s.getAverageInsertion());
			assertEquals(40, s.getAverageExtraction());
		}
	}

	@Nested
	@DisplayName("decay factor")
	class DecayFactor
	{
		@Test
		@DisplayName("setDecayFactor returns the storage for chaining")
		void returnsSelf()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(1000);
			assertSame(s, s.setDecayFactor(.25));
		}

		@Test
		@DisplayName("a factor of 0 makes the average follow the last transfer exactly")
		void zeroFactorFollowsLatest()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000).setDecayFactor(0);
			s.receiveEnergy(137, false);
			assertEquals(137, s.getAverageInsertion());
			s.receiveEnergy(4, false);
			assertEquals(4, s.getAverageInsertion());
		}

		@Test
		@DisplayName("a factor of 1 freezes the average")
		void oneFactorFreezes()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000).setDecayFactor(1);
			s.receiveEnergy(500, false);
			s.receiveEnergy(500, false);
			assertEquals(0, s.getAverageInsertion(), "with no weight on the new sample the average never moves");
		}

		@Test
		@DisplayName("a heavier factor approaches the steady state more slowly")
		void heavierFactorIsSlower()
		{
			FluxStorageAdvanced fast = new FluxStorageAdvanced(100000).setDecayFactor(.25);
			FluxStorageAdvanced slow = new FluxStorageAdvanced(100000).setDecayFactor(.75);
			fast.receiveEnergy(100, false);
			slow.receiveEnergy(100, false);
			assertEquals(75, fast.getAverageInsertion());
			assertEquals(25, slow.getAverageInsertion());
			assertTrue(fast.getAverageInsertion() > slow.getAverageInsertion());
		}

		@Test
		@DisplayName("the factor applies to extraction as well")
		void factorAppliesToExtraction()
		{
			FluxStorageAdvanced s = new FluxStorageAdvanced(100000).setDecayFactor(0);
			s.setEnergy(100000);
			s.extractEnergy(321, false);
			assertEquals(321, s.getAverageExtraction());
		}
	}
}
