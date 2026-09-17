package blusunrize.immersiveengineering.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic of the city-mode push's first pass. The push itself needs a world and a wire
 * graph, so what can be pinned down here is the share each output is offered before any of them
 * is offered the rest -- which is the whole of what changed when the push stopped being greedy.
 */
@DisplayName("WireNetTransfer")
class WireNetTransferTest
{
	@Nested
	@DisplayName("the equal share of the city push's first pass")
	class EqualShare
	{
		@Test
		@DisplayName("splits evenly when it divides")
		void splitsEvenly()
		{
			assertEquals(128, WireNetTransfer.equalShare(256, 2));
			assertEquals(64, WireNetTransfer.equalShare(256, 4));
			assertEquals(256, WireNetTransfer.equalShare(256, 1));
		}

		@Test
		@DisplayName("rounds down and leaves the remainder for the second pass")
		void roundsDown()
		{
			//Three outputs on an LV connector: 85 each, and the odd one is handed out greedily
			//afterwards rather than minted.
			assertEquals(85, WireNetTransfer.equalShare(256, 3));
			assertTrue(3*WireNetTransfer.equalShare(256, 3) <= 256);
		}

		@Test
		@DisplayName("is never less than one while there is anything to give")
		void neverLessThanOne()
		{
			//More outputs than flux: a presence-driven receiver is lit by any credit at all, so
			//the first pass still touches as many as it can rather than offering everyone nothing.
			assertEquals(1, WireNetTransfer.equalShare(5, 500));
			assertEquals(1, WireNetTransfer.equalShare(1, 1));
		}

		@Test
		@DisplayName("is nothing when there is nothing, or nobody")
		void nothingForNothing()
		{
			assertEquals(0, WireNetTransfer.equalShare(0, 3));
			assertEquals(0, WireNetTransfer.equalShare(-10, 3));
			assertEquals(0, WireNetTransfer.equalShare(256, 0));
			assertEquals(0, WireNetTransfer.equalShare(256, -1));
		}
	}
}
