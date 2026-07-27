/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.api.tool.IElectricEquipment;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A plain, world-free {@link WireType} for tests.
 * <p>
 * Every method that would drag in the item registry ({@link #getWireCoil()}) or a texture atlas
 * ({@link #getIcon(Connection)}) returns null; nothing in the tested code paths calls them.
 * {@link #getElectricSource()} is overridden because {@code WireType}'s inherited implementation
 * delegates to {@code COPPER}, which recurses forever if COPPER is itself a non-overriding subclass.
 */
public class TestWireType extends WireType
{
	private final String uniqueName;
	private final double lossRatio;
	private final int transferRate;
	private final int maxLength;
	private final String category;
	private final boolean energyWire;
	private final int colour;

	public TestWireType(String uniqueName, double lossRatio, int transferRate, int maxLength)
	{
		this(uniqueName, lossRatio, transferRate, maxLength, null, true, 0xff00ff);
	}

	public TestWireType(String uniqueName, double lossRatio, int transferRate, int maxLength,
						@Nullable String category, boolean energyWire, int colour)
	{
		super();
		this.uniqueName = uniqueName;
		this.lossRatio = lossRatio;
		this.transferRate = transferRate;
		this.maxLength = maxLength;
		this.category = category;
		this.energyWire = energyWire;
		this.colour = colour;
	}

	@Override
	public String getUniqueName()
	{
		return uniqueName;
	}

	@Override
	public double getLossRatio()
	{
		return lossRatio;
	}

	@Override
	public int getTransferRate()
	{
		return transferRate;
	}

	@Override
	public int getColour(Connection connection)
	{
		return colour;
	}

	@Override
	public double getSlack()
	{
		return 1.005;
	}

	@Override
	public TextureAtlasSprite getIcon(Connection connection)
	{
		return null;
	}

	@Override
	public int getMaxLength()
	{
		return maxLength;
	}

	@Override
	public ItemStack getWireCoil()
	{
		return null;
	}

	@Override
	public double getRenderDiameter()
	{
		return .03125;
	}

	@Override
	public boolean isEnergyWire()
	{
		return energyWire;
	}

	@Nullable
	@Override
	public String getCategory()
	{
		return category;
	}

	@Override
	public IElectricEquipment.ElectricSource getElectricSource()
	{
		return new IElectricEquipment.ElectricSource(-1);
	}

	// ---------------------------------------------------------------- test plumbing

	/**
	 * Builds one of IE's own private {@code WireType$IEBASE} instances. The class is private, so the
	 * only way to exercise the real per-tier values is reflection. Callers must have populated the
	 * {@code WireType.wire*} config arrays first.
	 */
	public static WireType newIEBase(int ordinal)
	{
		try
		{
			Class<?> c = Class.forName("blusunrize.immersiveengineering.api.energy.wires.WireType$IEBASE");
			Constructor<?> ctor = c.getDeclaredConstructor(int.class);
			ctor.setAccessible(true);
			return (WireType)ctor.newInstance(ordinal);
		} catch(ReflectiveOperationException e)
		{
			throw new AssertionError("Could not build WireType$IEBASE("+ordinal+")", e);
		}
	}

	/** The set backing {@link WireType#getValues()}; every constructed WireType registers itself in it. */
	@SuppressWarnings("unchecked")
	public static LinkedHashSet<WireType> registry()
	{
		try
		{
			Field f = WireType.class.getDeclaredField("values");
			f.setAccessible(true);
			return (LinkedHashSet<WireType>)f.get(null);
		} catch(ReflectiveOperationException e)
		{
			throw new AssertionError("Could not reach WireType.values", e);
		}
	}

	/**
	 * Empties the two global registries WireType construction writes to, so a test class starts from a
	 * known state regardless of what ran before it in the same JVM.
	 */
	public static void resetRegistries()
	{
		registry().clear();
		for(Map.Entry<String, Set<WireType>> e : WireApi.WIRES_BY_CATEGORY.entrySet())
			e.getValue().clear();
		WireApi.WIRES_BY_CATEGORY.clear();
		WireType.COPPER = null;
		WireType.ELECTRUM = null;
		WireType.STEEL = null;
		WireType.STRUCTURE_ROPE = null;
		WireType.STRUCTURE_STEEL = null;
		WireType.REDSTONE = null;
		WireType.COPPER_INSULATED = null;
		WireType.ELECTRUM_INSULATED = null;
	}

	/** Installs deterministic values into the config-populated statics of {@link WireType}. */
	public static void installConfigArrays()
	{
		WireType.wireLossRatio = new double[]{.05, .1, .2, 0, 0, 0};
		WireType.wireTransferRate = new int[]{256, 1024, 4096, 0, 0, 0};
		WireType.wireColouration = new int[]{0xb36c3f, 0xeda045, 0x6e6e6e, 0x9a693b, 0x6e6e6e, 0xff2f2f,
				0xad3e3e, 0x3e5aad};
		WireType.wireLength = new int[]{16, 16, 32, 32, 32, 32};
	}
}
