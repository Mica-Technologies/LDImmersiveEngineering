/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.petroleum;

import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityReinjectionWell;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced recovery: the lifetime allowance that stands between a Re-injection Well and infinite
 * oil.
 * <p>
 * {@code Reservoir.restore} on its own only stops at the original capacity, which says nothing
 * about how many times a field may be refilled to it. Without the allowance a player could cycle
 * water in and crude out forever, and nothing in game would look wrong while they did -- which is
 * exactly why this is worth a test rather than a comment.
 */
class EnhancedRecoveryTest
{
	@Nested
	@DisplayName("the lifetime allowance")
	class Allowance
	{
		@Test
		@DisplayName("a fresh deposit offers exactly its configured share")
		void freshDepositOffersTheCap()
		{
			Reservoir reservoir = new Reservoir("crude_oil", 1000000);
			assertEquals(200000, reservoir.getRestoreAllowance(0.2));
			assertEquals(0, reservoir.getRestoredTotal());
		}

		@Test
		@DisplayName("restoring spends the allowance and never comes back")
		void restoringSpendsIt()
		{
			Reservoir reservoir = new Reservoir("crude_oil", 1000000, 500000);
			assertEquals(120000, reservoir.restore(120000));
			assertEquals(120000, reservoir.getRestoredTotal());
			assertEquals(80000, reservoir.getRestoreAllowance(0.2));

			//Draining the field again does not give the allowance back. That is the whole point:
			//the cap is per deposit and permanent, not per fill.
			reservoir.deplete(reservoir.getRemaining());
			assertEquals(80000, reservoir.getRestoreAllowance(0.2));
		}

		@Test
		@DisplayName("the allowance bottoms out at zero rather than going negative")
		void allowanceCannotGoNegative()
		{
			Reservoir reservoir = new Reservoir("crude_oil", 1000, 0);
			reservoir.restore(1000);
			assertEquals(0, reservoir.getRestoreAllowance(0.2),
					"restoring past the allowance must not read as owing the field fluid");
		}

		@Test
		@DisplayName("a dry hole cannot be turned into a well by pumping water at it")
		void dryHolesGetNothing()
		{
			assertEquals(0, new Reservoir("crude_oil", 0).getRestoreAllowance(0.2));
		}

		@Test
		@DisplayName("switching enhanced recovery off leaves no allowance at all")
		void zeroCapMeansNoRecovery()
		{
			assertEquals(0, new Reservoir("crude_oil", 1000000).getRestoreAllowance(0.0));
		}

		@Test
		@DisplayName("the spent allowance survives a save and reload")
		void allowancePersists()
		{
			//If it did not, a server restart would hand every field its allowance back, which is
			//the same exploit with an extra step.
			Reservoir reservoir = new Reservoir("crude_oil", 1000000, 400000);
			reservoir.restore(150000);
			Reservoir loaded = Reservoir.readFromNBT(reservoir.writeToNBT(new NBTTagCompound()));
			assertNotNull(loaded);
			assertEquals(150000, loaded.getRestoredTotal());
			assertEquals(50000, loaded.getRestoreAllowance(0.2));
		}

		@Test
		@DisplayName("a save from before enhanced recovery existed reads as unspent")
		void oldSavesGetTheirAllowance()
		{
			//The generous default, and the only one that does not retroactively punish a field
			//that was drilled before the machine was written.
			NBTTagCompound old = new NBTTagCompound();
			old.setString("type", "crude_oil");
			old.setInteger("capacity", 500000);
			old.setInteger("remaining", 100000);
			Reservoir loaded = Reservoir.readFromNBT(old);
			assertNotNull(loaded);
			assertEquals(0, loaded.getRestoredTotal());
		}
	}

	@Nested
	@DisplayName("the injectants")
	class Injectants
	{
		@Test
		@DisplayName("every injectant returns less than it puts in")
		void nothingIsFreeOil()
		{
			//A ratio at or above one would mean a bucket of water is worth a bucket of crude, which
			//is not enhanced recovery -- it is a fluid converter.
			for(String fluid : new String[]{"water", "natural_gas", "ie_sour_gas"})
			{
				int perMille = TileEntityReinjectionWell.recoveryPerMille(fluid);
				assertTrue(perMille > 0, fluid+" is not registered as an injectant");
				assertTrue(perMille < 1000, fluid+" returns "+perMille+" per mille, which is free oil");
			}
		}

		@Test
		@DisplayName("gas beats water, and clean gas beats sour")
		void injectantsAreRanked()
		{
			//The ranking is the decision: water is cheap and poor, gas is valuable and good, and
			//sour gas straight off the wellhead sits between them -- which is what lets a field
			//drive its own recovery on the waste stream it was already producing.
			int water = TileEntityReinjectionWell.recoveryPerMille("water");
			int sour = TileEntityReinjectionWell.recoveryPerMille("ie_sour_gas");
			int clean = TileEntityReinjectionWell.recoveryPerMille("natural_gas");
			assertTrue(sour > water, "sour gas should beat water");
			assertTrue(clean > sour, "scrubbed gas should beat sour gas");
		}

		@Test
		@DisplayName("anything else is refused")
		void otherFluidsAreNotInjectants()
		{
			//The tank is guarded on this, so a mis-plumbed line cannot pump a barrel of diesel into
			//the ground where it is gone for good.
			assertEquals(0, TileEntityReinjectionWell.recoveryPerMille("ie_diesel"));
			assertEquals(0, TileEntityReinjectionWell.recoveryPerMille("ie_crude_oil"));
			assertEquals(0, TileEntityReinjectionWell.recoveryPerMille(null));
		}

		@Test
		@DisplayName("recovery scales with what was injected")
		void recoveryIsProportional()
		{
			int perMille = TileEntityReinjectionWell.recoveryPerMille("water");
			assertEquals(0, TileEntityReinjectionWell.recoveredFrom(0, perMille));
			assertEquals(perMille, TileEntityReinjectionWell.recoveredFrom(1000, perMille));
			assertEquals(perMille*10, TileEntityReinjectionWell.recoveredFrom(10000, perMille));
			//In longs, or a large injection would come back negative and read as nothing at all.
			assertTrue(TileEntityReinjectionWell.recoveredFrom(Integer.MAX_VALUE/2, perMille) > 0);
		}
	}
}
