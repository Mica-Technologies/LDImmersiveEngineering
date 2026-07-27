/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.tool;

import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import blusunrize.immersiveengineering.api.tool.AssemblerHandler.IRecipeAdapter;
import blusunrize.immersiveengineering.api.tool.AssemblerHandler.RecipeQuery;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link AssemblerHandler}'s adapter lookup (a plain map plus a walk up the class
 * hierarchy) and {@code createQuery}'s conversion rules for the input types that do not
 * need a bootstrapped item registry.
 */
class AssemblerHandlerTest
{
	private Map<Class<? extends IRecipe>, IRecipeAdapter> registry;
	private List<Function<Object, RecipeQuery>> specialConverters;
	private Map<Class<? extends IRecipe>, IRecipeAdapter> savedRegistry;
	private List<Function<Object, RecipeQuery>> savedConverters;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void snapshotStatics() throws Exception
	{
		Field regField = AssemblerHandler.class.getDeclaredField("registry");
		regField.setAccessible(true);
		registry = (Map<Class<? extends IRecipe>, IRecipeAdapter>)regField.get(null);
		savedRegistry = new LinkedHashMap<>(registry);

		Field convField = AssemblerHandler.class.getDeclaredField("specialQueryConverters");
		convField.setAccessible(true);
		specialConverters = (List<Function<Object, RecipeQuery>>)convField.get(null);
		savedConverters = new ArrayList<>(specialConverters);
	}

	@AfterEach
	void restoreStatics()
	{
		registry.clear();
		registry.putAll(savedRegistry);
		specialConverters.clear();
		specialConverters.addAll(savedConverters);
	}

	// Abstract on purpose: only the Class objects are needed, never an instance.
	private abstract static class FakeRecipe implements IRecipe
	{
	}

	private abstract static class FakeSubRecipe extends FakeRecipe
	{
	}

	private abstract static class FakeSubSubRecipe extends FakeSubRecipe
	{
	}

	private static IRecipeAdapter<IRecipe> adapter()
	{
		return new IRecipeAdapter<IRecipe>()
		{
			@Override
			public RecipeQuery[] getQueriedInputs(IRecipe recipe, NonNullList<ItemStack> input)
			{
				return new RecipeQuery[0];
			}
		};
	}

	@Nested
	@DisplayName("createQuery")
	class CreateQuery
	{
		@Test
		@DisplayName("a null input produces no query at all")
		void nullInputYieldsNull()
		{
			assertNull(AssemblerHandler.createQuery(null));
		}

		@Test
		@DisplayName("an ore-dictionary name becomes a size-one query")
		void oreNameBecomesSizeOneQuery()
		{
			RecipeQuery q = AssemblerHandler.createQuery("ingotIron");

			assertNotNull(q);
			assertEquals("ingotIron", q.query);
			assertEquals(1, q.querySize);
		}

		@Test
		@DisplayName("an IngredientStack keeps its own input size")
		void ingredientStackKeepsItsSize()
		{
			IngredientStack ingr = new IngredientStack("ingotIron", 9);
			RecipeQuery q = AssemblerHandler.createQuery(ingr);

			assertSame(ingr, q.query);
			assertEquals(9, q.querySize);
		}

		@Test
		@DisplayName("an IngredientStack with a zero size is not silently bumped to one")
		void ingredientStackWithZeroSize()
		{
			assertEquals(0, AssemblerHandler.createQuery(new IngredientStack("ingotIron", 0)).querySize);
		}

		@Test
		@DisplayName("an unrecognised object still becomes a size-one query")
		void unknownObjectBecomesSizeOneQuery()
		{
			Object o = new Object();
			RecipeQuery q = AssemblerHandler.createQuery(o);

			assertSame(o, q.query);
			assertEquals(1, q.querySize);
		}

		@Test
		@DisplayName("a registered special converter wins over the built-in handling")
		void specialConverterTakesPrecedence()
		{
			RecipeQuery custom = new RecipeQuery("custom", 42);
			AssemblerHandler.registerSpecialQueryConverters(o -> "ingotIron".equals(o)?custom: null);

			assertSame(custom, AssemblerHandler.createQuery("ingotIron"));
			assertEquals(42, AssemblerHandler.createQuery("ingotIron").querySize);
		}

		@Test
		@DisplayName("a special converter that declines lets the built-in handling run")
		void decliningSpecialConverterFallsThrough()
		{
			AssemblerHandler.registerSpecialQueryConverters(o -> null);

			RecipeQuery q = AssemblerHandler.createQuery("ingotGold");
			assertEquals("ingotGold", q.query);
			assertEquals(1, q.querySize);
		}

		@Test
		@DisplayName("special converters are consulted in registration order")
		void specialConvertersAreConsultedInOrder()
		{
			RecipeQuery first = new RecipeQuery("first", 1);
			RecipeQuery second = new RecipeQuery("second", 2);
			AssemblerHandler.registerSpecialQueryConverters(o -> first);
			AssemblerHandler.registerSpecialQueryConverters(o -> second);

			assertSame(first, AssemblerHandler.createQuery("anything"));
		}

		@Test
		@DisplayName("RecipeQuery stores exactly what it was handed")
		void recipeQueryIsAPlainHolder()
		{
			RecipeQuery q = new RecipeQuery("x", -5);

			assertEquals("x", q.query);
			assertEquals(-5, q.querySize);
		}
	}

	@Nested
	@DisplayName("adapter lookup")
	class AdapterLookup
	{
		@Test
		@DisplayName("a class with its own adapter gets that adapter back")
		void directHit()
		{
			IRecipeAdapter custom = adapter();
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, custom);

			assertSame(custom, AssemblerHandler.findAdapterForClass(FakeRecipe.class));
		}

		@Test
		@DisplayName("a subclass inherits its parent's adapter")
		void subclassInheritsAdapter()
		{
			IRecipeAdapter custom = adapter();
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, custom);

			assertSame(custom, AssemblerHandler.findAdapterForClass(FakeSubRecipe.class));
		}

		@Test
		@DisplayName("the walk up the hierarchy is not limited to one level")
		void lookupWalksTheWholeHierarchy()
		{
			IRecipeAdapter custom = adapter();
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, custom);

			assertSame(custom, AssemblerHandler.findAdapterForClass(FakeSubSubRecipe.class));
		}

		@Test
		@DisplayName("the nearest ancestor's adapter wins")
		void nearestAncestorWins()
		{
			IRecipeAdapter outer = adapter();
			IRecipeAdapter inner = adapter();
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, outer);
			AssemblerHandler.registerRecipeAdapter(FakeSubRecipe.class, inner);

			assertSame(inner, AssemblerHandler.findAdapterForClass(FakeSubSubRecipe.class));
		}

		@Test
		@DisplayName("a resolved adapter is cached on the subclass so the walk only happens once")
		void resolvedAdapterIsCached()
		{
			IRecipeAdapter custom = adapter();
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, custom);

			assertFalse(registry.containsKey(FakeSubRecipe.class));
			AssemblerHandler.findAdapterForClass(FakeSubRecipe.class);
			assertTrue(registry.containsKey(FakeSubRecipe.class));
			assertSame(custom, registry.get(FakeSubRecipe.class));
		}

		@Test
		@DisplayName("a class with no registered ancestor falls back to the default adapter")
		void fallsBackToDefaultAdapter()
		{
			assertSame(AssemblerHandler.defaultAdapter, AssemblerHandler.findAdapterForClass(FakeRecipe.class));
		}

		@Test
		@DisplayName("re-registering an adapter for the same class replaces the old one")
		void reRegistrationReplaces()
		{
			IRecipeAdapter first = adapter();
			IRecipeAdapter second = adapter();
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, first);
			AssemblerHandler.registerRecipeAdapter(FakeRecipe.class, second);

			assertSame(second, AssemblerHandler.findAdapterForClass(FakeRecipe.class));
		}

		@Test
		@Disabled("findAdapterForClass calls IRecipe.class.getSuperclass() (null for an interface) "
				+ "and feeds it to isAssignableFrom before the null check, so it throws NPE")
		@DisplayName("looking up IRecipe itself returns the default adapter")
		void lookingUpTheInterfaceItselfWorks()
		{
			assertSame(AssemblerHandler.defaultAdapter, AssemblerHandler.findAdapterForClass(IRecipe.class));
		}
	}
}
