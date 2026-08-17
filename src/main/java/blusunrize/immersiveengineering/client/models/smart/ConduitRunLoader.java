/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client.models.smart;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry.ArmMode;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.common.model.IModelState;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Puts {@link ConduitRunModel} behind a model location the run's blockstate can name.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitRunLoader implements ICustomModelLoader
{
	/**
	 * What {@code conduit_run.json} asks for.
	 * <p>
	 * <strong>Both halves of the prefix are added for us, and by different things.</strong>
	 * {@code ModelLoaderRegistry.getActualLocation} prepends {@code models/} to everything before
	 * offering it to a loader, and a model named inside a <em>vanilla</em> {@code variants} or
	 * {@code multipart} blockstate has {@code block/} prepended by the variant deserializer. So a
	 * blockstate saying {@code immersiveengineering:smartmodel/conduit_run} arrives here as exactly
	 * this string, and writing the {@code block/} out in the blockstate would resolve to
	 * {@code models/block/block/...} -- a purple block with nothing in the log.
	 * <p>
	 * {@code ConduitAssetsTest} compares this constant against the blockstate's reference, because
	 * if the two ever drift apart this loader is simply never asked and every run goes purple.
	 */
	public static final String RESOURCE_LOCATION = "models/block/smartmodel/conduit_run";

	@Override
	public void onResourceManagerReload(@Nonnull IResourceManager resourceManager)
	{
		//The assembled quad lists hold sprites from an atlas that has just been re-stitched, so they
		//are not merely stale, they point into a texture that no longer exists at those UVs.
		ConduitRunModel.modelCache.clear();
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
		return new RawRunModel();
	}

	/** The names of the seventy-eight parts, hub first, in the order they are baked into. */
	private static List<ResourceLocation> partLocations()
	{
		List<ResourceLocation> out = new ArrayList<>();
		for(EnumFacing mount : EnumFacing.VALUES)
		{
			out.add(ConduitParts.location(ConduitGeometry.hubModelName(mount)));
			for(EnumFacing dir : ConduitGeometry.inPlane(mount))
			{
				out.add(ConduitParts.location(ConduitGeometry.armModelName(mount, dir)));
				out.add(ConduitParts.location(ConduitGeometry.riserModelName(mount, dir)));
				out.add(ConduitParts.location(ConduitGeometry.wrapModelName(mount, dir)));
			}
		}
		return out;
	}

	private static class RawRunModel implements IModel
	{
		@Nonnull
		@Override
		public Collection<ResourceLocation> getDependencies()
		{
			//Every part, declared. Nothing else references these files any more -- the blockstate
			//names this loader and nothing else -- so without this the model loader would never see
			//them, their texture would not be stitched into the block atlas, and the first conduit
			//placed would be drawn with whatever sprite happened to be at those coordinates.
			return partLocations();
		}

		@Nonnull
		@Override
		public Collection<ResourceLocation> getTextures()
		{
			//The parts' own textures come with the parts, through the dependencies above.
			return java.util.Collections.emptyList();
		}

		@Nonnull
		@Override
		public IBakedModel bake(@Nonnull IModelState state, @Nonnull VertexFormat format,
								@Nonnull Function<ResourceLocation, TextureAtlasSprite> bakedTextureGetter)
		{
			IBakedModel[] hubs = new IBakedModel[EnumFacing.VALUES.length];
			IBakedModel[][] arms = new IBakedModel[EnumFacing.VALUES.length][];
			for(EnumFacing mount : EnumFacing.VALUES)
			{
				hubs[mount.ordinal()] = ConduitParts.bake(ConduitGeometry.hubModelName(mount),
						state, format, bakedTextureGetter);
				EnumFacing[] plane = ConduitGeometry.inPlane(mount);
				IBakedModel[] mounted = new IBakedModel[plane.length*3];
				for(int i = 0; i < plane.length; i++)
				{
					//Baked in ArmMode order, which is what ConduitRunModel.armSlot indexes by.
					mounted[ConduitRunModel.armSlot(i, ArmMode.STRAIGHT)] = ConduitParts.bake(
							ConduitGeometry.armModelName(mount, plane[i]), state, format, bakedTextureGetter);
					mounted[ConduitRunModel.armSlot(i, ArmMode.RISER)] = ConduitParts.bake(
							ConduitGeometry.riserModelName(mount, plane[i]), state, format, bakedTextureGetter);
					mounted[ConduitRunModel.armSlot(i, ArmMode.WRAP)] = ConduitParts.bake(
							ConduitGeometry.wrapModelName(mount, plane[i]), state, format, bakedTextureGetter);
				}
				arms[mount.ordinal()] = mounted;
			}
			//A rebake means new sprites behind every one of those parts, so anything assembled from
			//the old ones has to go with them.
			ConduitRunModel.modelCache.clear();
			return new ConduitRunModel(hubs, arms);
		}
	}
}
