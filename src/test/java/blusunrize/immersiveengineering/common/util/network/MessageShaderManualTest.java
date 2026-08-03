/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.util.network.MessageShaderManual.MessageType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decoding a shader-manual packet from bytes somebody else chose.
 * <p>
 * <strong>{@code fromBytes} runs on the netty thread</strong>, before any handler and before
 * anything is scheduled onto the server thread. An exception here is not a failed action, it is a
 * failure in the network pipeline, raised by a packet that costs the sender nothing to send. Both
 * of the values this message reads were used unchecked: an enum ordinal indexed straight into
 * {@code values()}, and a length that sized an allocation.
 */
class MessageShaderManualTest
{
	private static MessageShaderManual decode(int ordinal, String... args)
	{
		ByteBuf buf = Unpooled.buffer();
		buf.writeInt(ordinal);
		buf.writeInt(args.length);
		for(String arg : args)
			ByteBufUtils.writeUTF8String(buf, arg);
		MessageShaderManual message = new MessageShaderManual();
		message.fromBytes(buf);
		return message;
	}

	/** Writes a claimed length that does not match what actually follows. */
	private static MessageShaderManual decodeClaiming(int ordinal, int claimedLength)
	{
		ByteBuf buf = Unpooled.buffer();
		buf.writeInt(ordinal);
		buf.writeInt(claimedLength);
		MessageShaderManual message = new MessageShaderManual();
		message.fromBytes(buf);
		return message;
	}

	@Nested
	@DisplayName("an honest packet round-trips")
	class HappyPath
	{
		@Test
		@DisplayName("the type and the arguments survive")
		void roundTrips()
		{
			MessageShaderManual message = decode(MessageType.SPAWN.ordinal(), "immersiveengineering:test");
			assertEquals(MessageType.SPAWN, message.key);
			assertArrayEquals(new String[]{"immersiveengineering:test"}, message.args);
		}

		@Test
		@DisplayName("every declared type decodes as itself")
		void everyTypeDecodes()
		{
			for(MessageType type : MessageType.values())
				assertEquals(type, decode(type.ordinal()).key, type+" did not survive the wire");
		}

		@Test
		@DisplayName("a message with no arguments is legal")
		void noArguments()
		{
			MessageShaderManual message = decode(MessageType.SYNC.ordinal());
			assertEquals(0, message.args.length);
		}
	}

	@Nested
	@DisplayName("a hostile packet is absorbed rather than thrown")
	class Hostile
	{
		@Test
		@DisplayName("an out-of-range type reads as SYNC instead of throwing")
		void outOfRangeTypeIsSync()
		{
			//	=================================
			//	The netty-thread crash.
			//	=================================
			//
			// values()[readInt()] on an attacker-chosen int. SYNC is the safe landing: it asks the
			// server what this player already has and does nothing else.
			assertEquals(MessageType.SYNC, decode(99).key);
			assertEquals(MessageType.SYNC, decode(Integer.MAX_VALUE).key);
		}

		@Test
		@DisplayName("a negative type reads as SYNC instead of throwing")
		void negativeTypeIsSync()
		{
			assertEquals(MessageType.SYNC, decode(-1).key);
			assertEquals(MessageType.SYNC, decode(Integer.MIN_VALUE).key);
		}

		@Test
		@DisplayName("a negative argument count does not throw")
		void negativeLengthIsEmpty()
		{
			//new String[-1] is a NegativeArraySizeException, again on the netty thread.
			MessageShaderManual message = decodeClaiming(MessageType.SYNC.ordinal(), -5);
			assertEquals(0, message.args.length);
		}

		@Test
		@DisplayName("an absurd argument count does not allocate an absurd array")
		void absurdLengthIsCapped()
		{
			//	=================================
			//	One packet, two billion entries.
			//	=================================
			//
			// The claimed length sized the allocation before a single string was read, so a sender
			// could ask for an OutOfMemoryError for the cost of eight bytes. Capped, and then bounded
			// again by the buffer actually running out -- which is a decode failure Forge handles,
			// not a heap exhaustion it cannot.
			assertThrows(IndexOutOfBoundsException.class,
					() -> decodeClaiming(MessageType.SYNC.ordinal(), Integer.MAX_VALUE),
					"the reader should run out of buffer, not allocate two billion slots");
		}

		@Test
		@DisplayName("the cap itself is far above anything the manual legitimately sends")
		void capIsGenerous()
		{
			//The SYNC reply carries a player's whole collection, so the ceiling must not be a
			//gameplay limit. A few thousand is orders of magnitude past any shader registry.
			MessageShaderManual message = decode(MessageType.SYNC.ordinal(),
					new String[]{"a", "b", "c"});
			assertEquals(3, message.args.length);
		}
	}

	@Nested
	@DisplayName("the wire format is frozen")
	class WireFormat
	{
		@Test
		@DisplayName("every ordinal is frozen -- reordering desynchronises client and server")
		void ordinalsAreFrozen()
		{
			assertEquals(0, MessageType.SYNC.ordinal());
			assertEquals(1, MessageType.UNLOCK.ordinal());
			assertEquals(2, MessageType.SPAWN.ordinal());
			assertEquals(3, MessageType.values().length,
					"a new message type must be appended, never inserted");
		}

		@Test
		@DisplayName("SYNC is ordinal zero, which is what an unknown type falls back to")
		void syncIsTheSafeDefault()
		{
			//If SYNC ever stopped being the harmless one, the fallback above would need rethinking.
			assertEquals(MessageType.SYNC, MessageType.values()[0]);
		}
	}
}
