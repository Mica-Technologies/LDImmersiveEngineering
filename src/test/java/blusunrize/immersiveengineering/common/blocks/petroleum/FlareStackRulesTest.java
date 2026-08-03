/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a Flare Stack will and will not burn.
 * <p>
 * The acceptance rule is the load-bearing one. A flare exists so that a wellhead's associated gas
 * has somewhere to go at the cost of throwing it away; if it would take anything else, then getting
 * rid of an unwanted fluid becomes free, cleaning up a spill becomes a no-op, and the Gas Scrubber
 * and the Re-injection Well stop being the answer to a problem and become two machines nobody has a
 * reason to build.
 */
class FlareStackRulesTest
{
	/** Every fluid this fork declares, so the acceptance list is asserted against the real set. */
	private static final String[] ALL_FLUIDS = {
			"creosote", "plantoil", "ethanol", "biodiesel", "propane", "natural_gas",
			"ie_crude_oil", "ie_naphtha", "ie_gasoline", "ie_diesel", "ie_heavy_fuel_oil",
			"ie_lubricant", "ie_sour_gas", "ie_steam", "ie_asphalt", "ie_bitumen", "concrete"
	};

	@Nested
	@DisplayName("what it accepts")
	class Acceptance
	{
		@Test
		@DisplayName("the three gases, and those are the only three")
		void exactlyTheThreeGases()
		{
			//Asserted against every fluid the fork declares rather than against a handful of
			//examples, so adding a fluid that the flare would silently eat breaks this test.
			for(String fluid : ALL_FLUIDS)
			{
				boolean expected = "ie_sour_gas".equals(fluid)
						||"natural_gas".equals(fluid)
						||"propane".equals(fluid);
				assertEquals(expected, FlareStackRules.isFlarable(fluid),
						"flare acceptance changed for "+fluid);
			}
		}

		@Test
		@DisplayName("never crude, or disposing of a spill would be free")
		void neverCrude()
		{
			assertFalse(FlareStackRules.isFlarable("ie_crude_oil"));
		}

		@Test
		@DisplayName("never a refined cut somebody could otherwise sell")
		void neverRefinedCuts()
		{
			assertFalse(FlareStackRules.isFlarable("ie_diesel"));
			assertFalse(FlareStackRules.isFlarable("ie_gasoline"));
			assertFalse(FlareStackRules.isFlarable("ie_naphtha"));
			assertFalse(FlareStackRules.isFlarable("ie_heavy_fuel_oil"));
			assertFalse(FlareStackRules.isFlarable("ie_lubricant"));
		}

		@Test
		@DisplayName("never water or anything else a mis-plumbed line might deliver")
		void neverAnythingElse()
		{
			assertFalse(FlareStackRules.isFlarable("water"));
			assertFalse(FlareStackRules.isFlarable("lava"));
			assertFalse(FlareStackRules.isFlarable("ie_steam"));
		}

		@Test
		@DisplayName("an absent or unnamed fluid is refused rather than throwing")
		void absentIsRefused()
		{
			assertFalse(FlareStackRules.isFlarable((String)null));
			assertFalse(FlareStackRules.isFlarable(""));
		}
	}

	@Nested
	@DisplayName("how much a delivery destroys")
	class BurnAmount
	{
		@Test
		@DisplayName("a full buffer burns the whole burn rate")
		void fullBufferBurnsTheRate()
		{
			assertEquals(FlareStackRules.BURN_RATE,
					FlareStackRules.burnAmount(TileEntityFlareStack.CAPACITY, false));
		}

		@Test
		@DisplayName("never more than is actually held")
		void neverMoreThanHeld()
		{
			//Or the flare would report burning gas that was never delivered, and the lifetime
			//counter would climb past what the field produced.
			assertEquals(50, FlareStackRules.burnAmount(50, false));
			for(int held = 0; held <= 2000; held += 7)
				assertTrue(FlareStackRules.burnAmount(held, false) <= held,
						"burned more than held at "+held);
		}

		@Test
		@DisplayName("an empty flare burns nothing")
		void emptyBurnsNothing()
		{
			assertEquals(0, FlareStackRules.burnAmount(0, false));
			assertEquals(0, FlareStackRules.burnAmount(-10, false));
			assertEquals(0, FlareStackRules.burnAmount(0, true));
		}

		@Test
		@DisplayName("city mode takes a token sip so the flame never goes out for want of gas")
		void cityModeSips()
		{
			assertEquals(FlareStackRules.CITY_SIP,
					FlareStackRules.burnAmount(TileEntityFlareStack.CAPACITY, true));
			assertTrue(FlareStackRules.burnAmount(2000, true) < FlareStackRules.burnAmount(2000, false),
					"city mode must cost less than the real burn, or it is not a sip");
		}

		@Test
		@DisplayName("the burn rate keeps up with a wellhead rather than backing it up")
		void keepsUpWithAWellhead()
		{
			//The whole reason the flare exists: gas arrives whether or not anything wants it, and a
			//flare that consumed slower than the well produced would stop the well it was fitted to.
			int perWellheadPass = WellheadFlow.associatedGas(
					blusunrize.immersiveengineering.api.petroleum.PetroleumConfig.peakFlowRate
							*blusunrize.immersiveengineering.common.util.petroleum
							.PetroleumTickHandler.PRODUCTION_INTERVAL,
					blusunrize.immersiveengineering.api.petroleum.PetroleumConfig.associatedGasRatio);
			assertTrue(FlareStackRules.BURN_RATE >= perWellheadPass,
					"a flare burns "+FlareStackRules.BURN_RATE+" mB per delivery but a wellhead pass "
							+"brings up "+perWellheadPass+" mB -- the well would back up behind it");
		}
	}

	@Nested
	@DisplayName("the flame")
	class Flame
	{
		@Test
		@DisplayName("a lit flare lights the ground and a cold one does not")
		void lightFollowsTheFlame()
		{
			//The light is the entire return on burning the gas, so it is worth pinning.
			assertEquals(FlareStackRules.LIT_LIGHT, FlareStackRules.lightValue(1));
			assertEquals(FlareStackRules.LIT_LIGHT,
					FlareStackRules.lightValue(TileEntityFlareStack.FLAME_TICKS));
			assertEquals(0, FlareStackRules.lightValue(0));
		}

		@Test
		@DisplayName("a negative tick count reads as cold rather than as lit")
		void negativeIsCold()
		{
			assertEquals(0, FlareStackRules.lightValue(-5));
			assertFalse(FlareStackRules.isLit(-5));
		}

		@Test
		@DisplayName("lit and light agree with each other at every tick count")
		void litAndLightAgree()
		{
			for(int ticks = -5; ticks <= 60; ticks++)
				assertEquals(FlareStackRules.isLit(ticks), FlareStackRules.lightValue(ticks) > 0,
						"isLit and lightValue disagree at "+ticks+" ticks");
		}

		@Test
		@DisplayName("one delivery outlasts a wellhead's production interval")
		void flameOutlastsTheFeed()
		{
			//Otherwise a steady feed reads as a stuttering flame: the flare would go dark between
			//deliveries from a well that is running perfectly well.
			assertTrue(TileEntityFlareStack.FLAME_TICKS
							> blusunrize.immersiveengineering.common.util.petroleum
							.PetroleumTickHandler.PRODUCTION_INTERVAL,
					"a delivery must keep the flame past the next one arriving");
		}
	}
}
