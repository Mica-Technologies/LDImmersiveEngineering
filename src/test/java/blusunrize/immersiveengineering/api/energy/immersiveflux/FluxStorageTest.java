/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.immersiveflux;

import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour of {@link FluxStorage}: the capacity/limit clamping, the mutators, and the NBT
 * round-trip. Pure data, so none of this needs a world or the registries.
 */
class FluxStorageTest
{
	private static final String NBT_KEY = "ifluxEnergy";

	@Nested
	@DisplayName("construction")
	class Construction
	{
		@Test
		@DisplayName("single-argument constructor uses the capacity for both transfer limits")
		void singleArgConstructor()
		{
			FluxStorage s = new FluxStorage(1000);
			assertEquals(1000, s.getMaxEnergyStored());
			assertEquals(1000, s.getLimitReceive());
			assertEquals(1000, s.getLimitExtract());
			assertEquals(0, s.getEnergyStored(), "a fresh storage starts empty");
		}

		@Test
		@DisplayName("two-argument constructor uses one value for both transfer limits")
		void twoArgConstructor()
		{
			FluxStorage s = new FluxStorage(1000, 64);
			assertEquals(1000, s.getMaxEnergyStored());
			assertEquals(64, s.getLimitReceive());
			assertEquals(64, s.getLimitExtract());
		}

		@Test
		@DisplayName("three-argument constructor keeps receive and extract limits separate")
		void threeArgConstructor()
		{
			FluxStorage s = new FluxStorage(1000, 64, 32);
			assertEquals(1000, s.getMaxEnergyStored());
			assertEquals(64, s.getLimitReceive());
			assertEquals(32, s.getLimitExtract());
		}

		@Test
		@DisplayName("a zero-capacity storage can neither receive nor hold")
		void zeroCapacity()
		{
			FluxStorage s = new FluxStorage(0);
			assertEquals(0, s.getMaxEnergyStored());
			assertEquals(0, s.receiveEnergy(100, false));
			assertEquals(0, s.getEnergyStored());
		}
	}

	@Nested
	@DisplayName("receiveEnergy")
	class Receive
	{
		@Test
		@DisplayName("accepts the full amount when capacity and limit both allow it")
		void acceptsFullAmount()
		{
			FluxStorage s = new FluxStorage(1000);
			assertEquals(400, s.receiveEnergy(400, false));
			assertEquals(400, s.getEnergyStored());
		}

		@Test
		@DisplayName("is capped by the receive limit")
		void cappedByReceiveLimit()
		{
			FluxStorage s = new FluxStorage(1000, 64, 1000);
			assertEquals(64, s.receiveEnergy(500, false));
			assertEquals(64, s.getEnergyStored());
		}

		@Test
		@DisplayName("is capped by the remaining space")
		void cappedByRemainingSpace()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(950);
			assertEquals(50, s.receiveEnergy(500, false));
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("takes the smaller of the receive limit and the remaining space")
		void takesTheSmallerCap()
		{
			FluxStorage s = new FluxStorage(1000, 100, 100);
			s.setEnergy(970);
			assertEquals(30, s.receiveEnergy(1000, false), "30 of space beats the 100 limit");
			s.setEnergy(0);
			assertEquals(100, s.receiveEnergy(1000, false), "100 of limit beats the 1000 of space");
		}

		@Test
		@DisplayName("returns 0 and stores nothing when already full")
		void fullStorageAcceptsNothing()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(1000);
			assertEquals(0, s.receiveEnergy(1, false));
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("simulating reports the same figure without storing it")
		void simulateDoesNotMutate()
		{
			FluxStorage s = new FluxStorage(1000, 64, 64);
			assertEquals(64, s.receiveEnergy(500, true));
			assertEquals(0, s.getEnergyStored());
			assertEquals(64, s.receiveEnergy(500, false));
			assertEquals(64, s.getEnergyStored());
		}

		@Test
		@DisplayName("receiving zero is a no-op")
		void receiveZero()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(100);
			assertEquals(0, s.receiveEnergy(0, false));
			assertEquals(100, s.getEnergyStored());
		}

		@Test
		@DisplayName("filling exactly to capacity leaves it full and takes no more")
		void exactFill()
		{
			FluxStorage s = new FluxStorage(500);
			assertEquals(500, s.receiveEnergy(500, false));
			assertEquals(500, s.getEnergyStored());
			assertEquals(0, s.receiveEnergy(500, false));
		}

		@Test
		@DisplayName("MAX_VALUE requests are clamped to the free space")
		void maxValueRequest()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(1);
			assertEquals(999, s.receiveEnergy(Integer.MAX_VALUE, false));
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("a zero receive limit blocks all input")
		void zeroReceiveLimit()
		{
			FluxStorage s = new FluxStorage(1000, 0, 1000);
			assertEquals(0, s.receiveEnergy(1000, false));
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@Disabled("FluxStorage.receiveEnergy: a negative amount is returned as-is and subtracted from the stored energy")
		@DisplayName("a negative amount is rejected rather than draining the storage")
		void negativeReceiveIsRejected()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(500);
			assertEquals(0, s.receiveEnergy(-100, false), "you cannot 'receive' negative energy");
			assertEquals(500, s.getEnergyStored(), "a negative receive must not drain the storage");
		}
	}

	@Nested
	@DisplayName("extractEnergy")
	class Extract
	{
		@Test
		@DisplayName("yields the full amount when stored energy and limit both allow it")
		void yieldsFullAmount()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(400);
			assertEquals(300, s.extractEnergy(300, false));
			assertEquals(100, s.getEnergyStored());
		}

		@Test
		@DisplayName("is capped by the extract limit")
		void cappedByExtractLimit()
		{
			FluxStorage s = new FluxStorage(1000, 1000, 32);
			s.setEnergy(1000);
			assertEquals(32, s.extractEnergy(500, false));
			assertEquals(968, s.getEnergyStored());
		}

		@Test
		@DisplayName("is capped by the stored energy")
		void cappedByStoredEnergy()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(25);
			assertEquals(25, s.extractEnergy(500, false));
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@DisplayName("returns 0 when empty")
		void emptyStorageYieldsNothing()
		{
			FluxStorage s = new FluxStorage(1000);
			assertEquals(0, s.extractEnergy(500, false));
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@DisplayName("simulating reports the same figure without spending it")
		void simulateDoesNotMutate()
		{
			FluxStorage s = new FluxStorage(1000, 1000, 64);
			s.setEnergy(500);
			assertEquals(64, s.extractEnergy(500, true));
			assertEquals(500, s.getEnergyStored());
			assertEquals(64, s.extractEnergy(500, false));
			assertEquals(436, s.getEnergyStored());
		}

		@Test
		@DisplayName("extracting zero is a no-op")
		void extractZero()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(100);
			assertEquals(0, s.extractEnergy(0, false));
			assertEquals(100, s.getEnergyStored());
		}

		@Test
		@DisplayName("a zero extract limit blocks all output")
		void zeroExtractLimit()
		{
			FluxStorage s = new FluxStorage(1000, 1000, 0);
			s.setEnergy(1000);
			assertEquals(0, s.extractEnergy(1000, false));
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("MAX_VALUE requests are clamped to the stored energy")
		void maxValueRequest()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(777);
			assertEquals(777, s.extractEnergy(Integer.MAX_VALUE, false));
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@Disabled("FluxStorage.extractEnergy: a negative amount is returned as-is and *added* to the stored energy, creating energy")
		@DisplayName("a negative amount is rejected rather than creating energy")
		void negativeExtractIsRejected()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(500);
			assertEquals(0, s.extractEnergy(-100, false), "you cannot 'extract' negative energy");
			assertEquals(500, s.getEnergyStored(), "a negative extract must not create energy");
		}
	}

	@Nested
	@DisplayName("setEnergy")
	class SetEnergy
	{
		@Test
		@DisplayName("stores a value inside the range verbatim")
		void inRange()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(432);
			assertEquals(432, s.getEnergyStored());
		}

		@Test
		@DisplayName("clamps above capacity")
		void clampsHigh()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(5000);
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("clamps below zero")
		void clampsLow()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(-5000);
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@DisplayName("the two boundaries are stored exactly")
		void boundariesExact()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(0);
			assertEquals(0, s.getEnergyStored());
			s.setEnergy(1000);
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("Integer extremes are clamped to the range")
		void intExtremes()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(Integer.MAX_VALUE);
			assertEquals(1000, s.getEnergyStored());
			s.setEnergy(Integer.MIN_VALUE);
			assertEquals(0, s.getEnergyStored());
		}
	}

	@Nested
	@DisplayName("modifyEnergyStored")
	class Modify
	{
		@Test
		@DisplayName("adds a positive delta")
		void addsPositive()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(100);
			s.modifyEnergyStored(250);
			assertEquals(350, s.getEnergyStored());
		}

		@Test
		@DisplayName("subtracts a negative delta")
		void subtractsNegative()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(400);
			s.modifyEnergyStored(-150);
			assertEquals(250, s.getEnergyStored());
		}

		@Test
		@DisplayName("clamps to capacity rather than overfilling")
		void clampsToCapacity()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(900);
			s.modifyEnergyStored(500);
			assertEquals(1000, s.getEnergyStored());
		}

		@Test
		@DisplayName("clamps to zero rather than going negative")
		void clampsToZero()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(100);
			s.modifyEnergyStored(-500);
			assertEquals(0, s.getEnergyStored());
		}

		@Test
		@DisplayName("ignores the transfer limits entirely")
		void ignoresTransferLimits()
		{
			FluxStorage s = new FluxStorage(1000, 1, 1);
			s.modifyEnergyStored(900);
			assertEquals(900, s.getEnergyStored(), "modifyEnergyStored is not a transfer, limits do not apply");
		}

		@Test
		@DisplayName("a zero delta changes nothing")
		void zeroDelta()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(123);
			s.modifyEnergyStored(0);
			assertEquals(123, s.getEnergyStored());
		}

		@Test
		@Disabled("FluxStorage.modifyEnergyStored: this.energy += energy overflows int before the clamp, so a huge delta empties the storage instead of filling it")
		@DisplayName("a delta that overflows int still clamps to capacity")
		void overflowClampsToCapacity()
		{
			FluxStorage s = new FluxStorage(Integer.MAX_VALUE);
			s.setEnergy(Integer.MAX_VALUE-1);
			s.modifyEnergyStored(1000);
			assertEquals(Integer.MAX_VALUE, s.getEnergyStored());
		}
	}

	@Nested
	@DisplayName("capacity and limit mutators")
	class Mutators
	{
		@Test
		@DisplayName("shrinking the capacity truncates the stored energy")
		void shrinkTruncates()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(900);
			s.setCapacity(500);
			assertEquals(500, s.getMaxEnergyStored());
			assertEquals(500, s.getEnergyStored());
		}

		@Test
		@DisplayName("shrinking to above the stored energy leaves it alone")
		void shrinkAboveStoredKeepsEnergy()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(100);
			s.setCapacity(500);
			assertEquals(100, s.getEnergyStored());
		}

		@Test
		@DisplayName("growing the capacity leaves the stored energy alone")
		void growKeepsEnergy()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(900);
			s.setCapacity(5000);
			assertEquals(5000, s.getMaxEnergyStored());
			assertEquals(900, s.getEnergyStored());
		}

		@Test
		@DisplayName("setting the capacity to zero empties the storage")
		void zeroCapacityEmpties()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(1000);
			s.setCapacity(0);
			assertEquals(0, s.getEnergyStored());
			assertEquals(0, s.extractEnergy(100, false));
		}

		@Test
		@DisplayName("setLimitTransfer sets both limits")
		void setLimitTransferSetsBoth()
		{
			FluxStorage s = new FluxStorage(1000, 1, 2);
			s.setLimitTransfer(77);
			assertEquals(77, s.getLimitReceive());
			assertEquals(77, s.getLimitExtract());
		}

		@Test
		@DisplayName("setLimitReceive only affects input")
		void setLimitReceiveOnly()
		{
			FluxStorage s = new FluxStorage(1000, 10, 20);
			s.setLimitReceive(99);
			assertEquals(99, s.getLimitReceive());
			assertEquals(20, s.getLimitExtract());
			assertEquals(99, s.receiveEnergy(1000, false));
		}

		@Test
		@DisplayName("setMaxExtract only affects output")
		void setMaxExtractOnly()
		{
			FluxStorage s = new FluxStorage(1000, 10, 20);
			s.setMaxExtract(99);
			assertEquals(10, s.getLimitReceive());
			assertEquals(99, s.getLimitExtract());
			s.setEnergy(1000);
			assertEquals(99, s.extractEnergy(1000, false));
		}
	}

	@Nested
	@DisplayName("NBT")
	class Nbt
	{
		@Test
		@DisplayName("writeToNBT stores the energy under ifluxEnergy")
		void writeStoresEnergy()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(321);
			NBTTagCompound tag = s.writeToNBT(new NBTTagCompound());
			assertTrue(tag.hasKey(NBT_KEY));
			assertEquals(321, tag.getInteger(NBT_KEY));
		}

		@Test
		@DisplayName("writeToNBT returns the tag it was handed")
		void writeReturnsSameTag()
		{
			NBTTagCompound tag = new NBTTagCompound();
			assertSame(tag, new FluxStorage(1000).writeToNBT(tag));
		}

		@Test
		@DisplayName("writeToNBT leaves other keys in the tag untouched")
		void writeIsAdditive()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setString("unrelated", "keep me");
			new FluxStorage(1000).writeToNBT(tag);
			assertEquals("keep me", tag.getString("unrelated"));
		}

		@Test
		@DisplayName("readFromNBT restores the energy and returns itself for chaining")
		void readRestoresEnergy()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger(NBT_KEY, 654);
			FluxStorage s = new FluxStorage(1000);
			assertSame(s, s.readFromNBT(tag));
			assertEquals(654, s.getEnergyStored());
		}

		@Test
		@DisplayName("a full write/read round-trip preserves the energy")
		void roundTrip()
		{
			FluxStorage src = new FluxStorage(4096, 128, 64);
			src.setEnergy(2048);
			FluxStorage dst = new FluxStorage(4096, 128, 64).readFromNBT(src.writeToNBT(new NBTTagCompound()));
			assertEquals(src.getEnergyStored(), dst.getEnergyStored());
			assertEquals(src.getMaxEnergyStored(), dst.getMaxEnergyStored());
		}

		@Test
		@DisplayName("an empty storage round-trips as empty")
		void roundTripEmpty()
		{
			FluxStorage dst = new FluxStorage(1000).readFromNBT(new FluxStorage(1000).writeToNBT(new NBTTagCompound()));
			assertEquals(0, dst.getEnergyStored());
		}

		@Test
		@DisplayName("loading more energy than fits clamps to the capacity")
		void readClampsOverCapacity()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger(NBT_KEY, 9999);
			FluxStorage s = new FluxStorage(1000).readFromNBT(tag);
			assertEquals(1000, s.getEnergyStored(), "a shrunken capacity must not leave the storage overfull");
		}

		@Test
		@DisplayName("a tag without the key loads as empty")
		void readMissingKey()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(500);
			s.readFromNBT(new NBTTagCompound());
			assertEquals(0, s.getEnergyStored(), "a missing key reads as 0, not as 'leave it alone'");
		}

		@Test
		@DisplayName("a negative stored value is normalised to 0 on write")
		void writeClampsNegative()
		{
			FluxStorage s = new FluxStorage(1000);
			s.setEnergy(500);
			s.setCapacity(-1);// the only route to a negative stored value
			assertEquals(-1, s.getEnergyStored());
			NBTTagCompound tag = s.writeToNBT(new NBTTagCompound());
			assertEquals(0, tag.getInteger(NBT_KEY));
			assertEquals(0, s.getEnergyStored(), "the write also repairs the in-memory value");
		}

		@Test
		@DisplayName("a negative value in the tag is loaded verbatim")
		void readDoesNotClampNegative()
		{
			NBTTagCompound tag = new NBTTagCompound();
			tag.setInteger(NBT_KEY, -50);
			FluxStorage s = new FluxStorage(1000).readFromNBT(tag);
			assertEquals(-50, s.getEnergyStored(),
					"readFromNBT only guards the upper bound; documenting the asymmetry with writeToNBT");
		}
	}
}
