/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.common.model.IModelState;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.function.Function;

/**
 * Puts {@link ConduitDisguiseModel} behind a model location the ground feeder's blockstate can name.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitDisguiseLoader implements ICustomModelLoader
{
	/**
	 * What {@code conduit_ground_feeder.json} asks for.
	 * <p>
	 * <strong>Both halves of the prefix are added for us, and by different things.</strong>
	 * {@code ModelLoaderRegistry.getActualLocation} prepends {@code models/} to everything before
	 * offering it to a loader, and a model named inside a <em>vanilla</em> {@code variants} or
	 * {@code multipart} blockstate has {@code block/} prepended by the variant deserializer. So a
	 * blockstate saying {@code immersiveengineering:smartmodel/conduit_disguise} arrives here as
	 * exactly this string.
	 * <p>
	 * {@link FeedthroughLoader} next door writes {@code block/} out in its blockstate and still ends
	 * up with the same shape of constant, which looks like an inconsistency and is not: it is named
	 * through Forge's {@code custom} mechanism, which passes the path through untouched. Vanilla
	 * blockstates must <em>not</em> write {@code block/} -- doing so resolves to
	 * {@code models/block/block/...}, which is a purple block with nothing in the log.
	 * <p>
	 * {@code ConduitAssetsTest} compares this constant against the blockstate's reference, because
	 * if the two ever drift apart this loader is simply never asked and every feeder goes purple.
	 */
	public static final String RESOURCE_LOCATION = "models/block/smartmodel/conduit_disguise";

	/** What a feeder draws before it has found anything to wear, and what the item shows. */
	public static final ResourceLocation BARE = new ResourceLocation(
			"immersiveengineering", "block/conduit/ground_feeder");

	@Override
	public void onResourceManagerReload(@Nonnull IResourceManager resourceManager)
	{
		//Nothing to drop. The model caches no quads of its own -- it borrows the worn block's baked
		//model on the spot -- so a resource reload that rebakes everything else has already fixed
		//this too.
	}

	@Override
	public boolean accepts(@Nonnull ResourceLocation modelLocation)
	{
		return modelLocation.getPath().equals(RESOURCE_LOCATION);
	}

	@Nonnull
	@Override
	public IModel loadModel(@Nonnull ResourceLocation modelLocation)
	{
		return new RawDisguiseModel();
	}

	private static class RawDisguiseModel implements IModel
	{
		@Nonnull
		@Override
		public Collection<ResourceLocation> getDependencies()
		{
			//Declared, so the bare model's texture is stitched into the atlas whether or not any
			//feeder in the save happens to be bare at the moment.
			return ImmutableList.of(BARE);
		}

		@Nonnull
		@Override
		public Collection<ResourceLocation> getTextures()
		{
			return ImmutableList.of();
		}

		@Nonnull
		@Override
		public IBakedModel bake(@Nonnull IModelState state, @Nonnull VertexFormat format,
								@Nonnull Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
		{
			IModel bare;
			try
			{
				bare = ModelLoaderRegistry.getModel(BARE);
			} catch(Exception e)
			{
				//The missing model rather than a crash: a feeder drawn as the purple-and-black cube
				//is a bad afternoon, and a client that will not start is a worse one.
				e.printStackTrace();
				bare = ModelLoaderRegistry.getMissingModel();
			}
			return new ConduitDisguiseModel(bare.bake(state, format, bakedTextureGetter));
		}
	}
}
