/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.IEDamageSources;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityMultiPart;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.MultiPartEntityPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/**
 * The Hydraulic Crawler: a tracked machine you climb into and operate.
 * <p>
 * <strong>Deliberately not called an Excavator.</strong> Immersive Engineering already has one -- the
 * bucket-wheel multiblock, with its own manual entry, JEI category and advancement -- and a second
 * differently-shaped thing of the same name, in the same mod, that also digs, would be ambiguous in
 * every sentence anybody said about either.
 * <p>
 * <strong>The undercarriage and the house turn separately, and that is the whole control scheme.</strong>
 * {@link #rotationYaw} is the tracks: it is steered with A and D, and it is the direction the machine
 * drives in. The house -- the cab, and later the arm -- slews to wherever the operator is looking,
 * independently. A real excavator works exactly this way, and it is also the reason this feature is
 * affordable: a rider's look direction and movement input are <em>already</em> synchronised by
 * vanilla, because that is how boats and pigs work. Driving and aiming therefore need no packet of
 * their own, and the server and client cannot end up disagreeing about where the arm is pointing --
 * which will matter a great deal once the arm is what decides which blocks get destroyed.
 * <p>
 * The accepted cost is that the operator cannot look around independently of the machine. That is
 * authentic, and it is also the sort of thing that has to be said in the manual rather than
 * discovered.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public class EntityHydraulicCrawler extends Entity implements IEntityMultiPart
{
	/**
	 * Where the arm's hitboxes sit, as distances along its centreline in model units, and how big each
	 * is in blocks.
	 * <p>
	 * Spread along the steel rather than concentrated at the tip, because a swinging boom hits things
	 * with its middle. The tool's box is the largest: it is the business end, and it is the one that
	 * will decide what gets dug or broken.
	 */
	private static final double[] PART_ALONG = {13, 26, 36, 46, 53};
	private static final float[] PART_SIZE = {0.9F, 0.9F, 0.8F, 0.9F, 1.1F};

	/** How fast the tool has to be moving before touching it hurts, in blocks per tick. */
	private static final double CONTACT_SPEED = 0.08;
	/** Ticks between one entity taking a knock and the next. */
	private static final int CONTACT_COOLDOWN = 10;
	private static final float CONTACT_DAMAGE = 6F;

	/**
	 * What the Bucket has dug.
	 * <p>
	 * Nine slots -- a chest row. Exposed as a capability as well as tipped out by hand, so a pipe or a
	 * conveyor can unload the machine where somebody has built for that, and the sneak gesture is for
	 * everywhere else.
	 */
	private final ItemStackHandler inventory = new ItemStackHandler(9)
	{
		/**
		 * Mirror the fill to the client, for the same reason the tank does: an entity's item handler
		 * is an ordinary field and never reaches a client, so a panel reading it off the ridden
		 * entity would read its own empty copy and report an empty bucket on a full machine.
		 */
		@Override
		protected void onContentsChanged(int slot)
		{
			syncBucket();
		}
	};

	/**
	 * The fuel tank. Diesel and nothing else.
	 * <p>
	 * Diesel because this fork has a whole petroleum chain and almost nothing that burns its diesel at
	 * a personal scale -- a machine you refuel at your own Gas Station Pump closes that loop without
	 * inventing an economy for it. Refused for anything else, so the tank cannot be filled with water
	 * and then jam.
	 */
	private final FluidTank fuel = new FluidTank(CrawlerConfig.fuelCapacity)
	{
		@Override
		public boolean canFillFluidType(FluidStack incoming)
		{
			return incoming!=null&&incoming.getFluid()!=null
					&&FUEL_FLUID.equals(incoming.getFluid().getName());
		}

		/**
		 * The one hook every path through a tank passes: filling by hand, filling through the
		 * capability, and burning. Mirroring the level here rather than at each call site is what
		 * stops a pipe-fed machine having a gauge that only moves when somebody uses a bucket.
		 */
		@Override
		protected void onContentsChanged()
		{
			syncFuel();
		}
	};

	/** The only thing it burns. Named once, because the tank guard and the refusal message must agree. */
	public static final String FUEL_FLUID = "ie_diesel";

	/** Where a dismantled machine's diesel rides on the item until it is put down again. */
	public static final String TAG_FUEL = "fuel";

	/**
	 * Take the fuel off the item this machine was just placed from.
	 * <p>
	 * The other half of {@code dismantle}: without it the tag would be written, carried and then
	 * quietly dropped, which is the same lost diesel by a longer route.
	 */
	public void readFuelFromItem(ItemStack stack)
	{
		NBTTagCompound tag = stack.getTagCompound();
		if(tag==null||!tag.hasKey(TAG_FUEL))
			return;
		fuel.readFromNBT(tag.getCompoundTag(TAG_FUEL));
		syncFuel();
	}

	/**
	 * @return how much diesel an undeployed machine is carrying, for the item's tooltip
	 */
	public static int fuelOnItem(ItemStack stack)
	{
		NBTTagCompound tag = stack.getTagCompound();
		if(tag==null||!tag.hasKey(TAG_FUEL))
			return 0;
		FluidStack held = FluidStack.loadFluidStackFromNBT(tag.getCompoundTag(TAG_FUEL));
		return held==null?0: held.amount;
	}

	/**
	 * What the Grapple has hold of: a block state id, or the id of an entity, or neither.
	 * <p>
	 * A state id rather than an ItemStack, so a block goes back down as exactly what came up --
	 * a stair keeps its facing, a slab its half. Blocks with a tile entity are refused rather than
	 * carried, because none of that survives the trip.
	 */
	private int carriedState;
	@Nullable
	private java.util.UUID carriedEntity;

	private final EntityCrawlerPart[] parts;
	/** Where the tool was last tick, so the arm knows whether it is swinging or resting. */
	private Vec3d lastToolTip;
	private int contactCooldown;

	/** Blocks per tick at full throttle. Slow, because a tracked machine is slow. */
	private static final double DRIVE_SPEED = 0.11;
	/** Reverse is slower still, as it is on anything with tracks. */
	private static final double REVERSE_FACTOR = 0.6;
	/** Degrees per tick the undercarriage turns under a full steering input. */
	private static final float TURN_RATE = 2.2F;
	/**
	 * How much of its speed the machine keeps each tick with no throttle. Low: twenty tonnes on
	 * steel tracks does not coast, and a machine that drifted after the key was released would feel
	 * like a boat.
	 */
	private static final double GROUND_FRICTION = 0.55;

	private static final DataParameter<Float> SLEW = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.FLOAT);
	private static final DataParameter<Float> BOOM = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.FLOAT);
	private static final DataParameter<Float> STICK = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.FLOAT);
	private static final DataParameter<Float> TOOL = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.FLOAT);
	/**
	 * How high the arm is being aimed, in degrees of depression.
	 * <p>
	 * Synced because the HUD will want to show it, and saved because a machine left with its arm down
	 * should still have it down tomorrow.
	 */
	private static final DataParameter<Float> AIM = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.FLOAT);
	/** Which tool is fitted. Synced so the HUD can name it, and so the model can eventually draw it. */
	private static final DataParameter<Byte> ATTACHMENT = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.BYTE);
	/** How far the arm is extended, in model units. The second aiming axis. */
	private static final DataParameter<Float> EXTENSION = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.FLOAT);
	/**
	 * How much diesel is aboard, in millibuckets.
	 * <p>
	 * <strong>Synced, because the panel is drawn on the client and the tank is not.</strong> An
	 * entity's {@code FluidTank} is an ordinary field: it goes to disk and it never goes to a
	 * client, so a HUD reading it off {@code getRidingEntity()} reads the client's own copy, which
	 * nothing has ever filled. The gauge therefore sat at zero on a full machine and the panel said
	 * NO FUEL to every operator -- which is exactly the message the trigger was taught to stop
	 * giving falsely, arriving instead from the one readout that was meant to explain it.
	 * <p>
	 * The tank stays the authority; this only mirrors it. {@link #syncFuel()} is called wherever the
	 * amount can change, rather than every tick, because {@code EntityDataManager} only sends a
	 * parameter whose value actually differs.
	 */
	private static final DataParameter<Integer> FUEL = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.VARINT);
	/** How many of the bucket's nine slots hold something. Synced for the panel, same as the fuel. */
	private static final DataParameter<Byte> BUCKET = EntityDataManager
			.createKey(EntityHydraulicCrawler.class, DataSerializers.BYTE);

	/**
	 * The pose the arm sits in with nobody aiming it -- folded in, the way a machine is left at the
	 * end of a shift. Replaced by a solve against the operator's view once the arm is driven.
	 */
	public static final float PARKED_BOOM = -38F;
	public static final float PARKED_STICK = 96F;
	public static final float PARKED_TOOL = 24F;

	/** Interpolated on the client so the house does not snap between sync packets. */
	public float prevSlew;

	//	=================================
	//		CONTROLS
	//	=================================

	public static final byte FLAG_ARM_UP = 1;
	public static final byte FLAG_ARM_DOWN = 2;
	/** Held: work the attachment. */
	public static final byte FLAG_TRIGGER = 4;
	/** Held, but acted on only as it goes down: change the attachment. */
	public static final byte FLAG_SWAP = 8;
	/** Reach further out, and draw back in. */
	public static final byte FLAG_EXTEND = 16;
	public static final byte FLAG_RETRACT = 32;

	/**
	 * Which controls the operator is holding down.
	 * <p>
	 * Transient, and deliberately expiring -- see {@link #CONTROL_TIMEOUT}.
	 */
	private byte controlFlags;
	/**
	 * What was held last tick, so a control can be acted on as it goes down rather than for as long as
	 * it is held. Swapping the attachment sixty times a second would otherwise be one keypress.
	 */
	private byte previousFlags;
	private int controlAge;
	/** Ticks until the Breaker may take another bite. */
	private int breakCooldown;

	/**
	 * How long a held control survives without being renewed.
	 * <p>
	 * The client repeats the message while anything is held, so this only matters when the repeats
	 * stop arriving: a disconnect, a crash, a chunk unload mid-press. Without it, a client that said
	 * "raising" and then went away would leave the arm rising against its stop forever, and the next
	 * person to climb in would find a machine that would not obey them.
	 */
	private static final int CONTROL_TIMEOUT = 6;

	/**
	 * Degrees of aim per tick under a held control.
	 * <p>
	 * Sized against the range, not chosen in isolation: at two degrees a tick the arm sweeps its whole
	 * hundred and thirty-seven degrees in about three and a half seconds, which is deliberate and slow.
	 * It also has to stay under CrawlerArm.JOINT_RATE, or the aim would run away from the joints trying
	 * to follow it and the arm would lag behind its own control.
	 */
	private static final double AIM_RATE = 2.0;

	/**
	 * Model units of extension per tick under a held control.
	 * <p>
	 * Twenty-one units of travel at three quarters of a unit a tick is about a second and a half end
	 * to end -- quicker than the elevation, because extending is the fine adjustment of the two and a
	 * fine adjustment that takes as long as a coarse one is not one anybody uses.
	 */
	private static final double REACH_RATE = 0.75;

	public void setControlFlags(byte flags)
	{
		this.controlFlags = flags;
		this.controlAge = 0;
	}

	public EntityHydraulicCrawler(World world)
	{
		super(world);
		//Both from CrawlerGeometry, so the box, the seat and the drawing cannot disagree about how
		//big the machine is.
		setSize((float)CrawlerGeometry.WIDTH, (float)CrawlerGeometry.HEIGHT);
		//A machine is something you climb on, not something that gets shoved about, and nothing
		//should spawn inside it.
		this.preventEntitySpawning = true;
		//A block of step, so it climbs a kerb instead of stopping at one. Tracks would.
		this.stepHeight = 1.0F;

		parts = new EntityCrawlerPart[PART_ALONG.length];
		for(int i = 0; i < parts.length; i++)
			//Named, because a part's name is what shows up when something goes wrong with one.
			parts[i] = new EntityCrawlerPart(this, "arm"+i, PART_SIZE[i], PART_SIZE[i]);
	}

	//	=================================
	//		THE ARM'S HITBOXES
	//	=================================

	@Nullable
	@Override
	public Entity[] getParts()
	{
		//This is the whole mechanism: World's entity queries walk getParts() on everything they find,
		//so returning them here is what makes the arm hittable without spawning five more entities.
		return parts;
	}

	@Override
	public World getWorld()
	{
		return world;
	}

	@Override
	public boolean attackEntityFromPart(MultiPartEntityPart part, DamageSource source, float damage)
	{
		//The arm is the machine. Hitting the boom has to do what hitting the tracks does, or the
		//dismantle gesture works on some of the machine and silently not on the rest of it.
		return attackEntityFrom(source, damage);
	}

	/**
	 * Put every hitbox where its piece of steel actually is.
	 * <p>
	 * On both sides, not just the server. The client is what ray traces a player's crosshair, so parts
	 * left unpositioned on the client are an arm you cannot click even though the server thinks you can.
	 */
	private void positionParts()
	{
		float boom = getBoomAngle(), stick = getStickAngle(), tool = getToolAngle();
		float slew = getSlew();
		for(int i = 0; i < parts.length; i++)
		{
			double[] offset = CrawlerArm.armPointOffset(slew, boom, stick, tool, PART_ALONG[i]);
			EntityCrawlerPart part = parts[i];
			//prevPos kept in step, or anything interpolating a part's position between ticks -- a
			//particle, a debug box -- smears it across the machine.
			part.prevPosX = part.posX;
			part.prevPosY = part.posY;
			part.prevPosZ = part.posZ;
			part.setLocationAndAngles(posX+offset[0], posY+offset[1]-part.height/2,
					posZ+offset[2], 0, 0);
		}
	}

	/**
	 * A swinging arm hurts whatever it catches.
	 * <p>
	 * Gated on the tool actually moving, because a parked arm resting against a cow should not grind it
	 * down. Gated on a cooldown as well: the arm intersects a given entity for as long as it is passing
	 * through, and damage every tick of that would be a mob deleter rather than a machine.
	 */
	private void bumpEntities()
	{
		if(contactCooldown > 0)
		{
			contactCooldown--;
			return;
		}
		Vec3d tip = getToolTip();
		double speed = lastToolTip==null?0: tip.distanceTo(lastToolTip);
		if(speed < CONTACT_SPEED)
			return;

		Entity rider = getControllingPassenger();
		for(EntityCrawlerPart part : parts)
			for(Entity hit : world.getEntitiesWithinAABBExcludingEntity(this,
					part.getEntityBoundingBox().grow(0.1)))
			{
				//Never the machine's own parts, and never the operator: an arm that swept its own cab
				//would kill whoever was driving it the first time they used it.
				if(hit==rider||hit instanceof EntityCrawlerPart||hit==this)
					continue;
				//	=================================
				//	Only things that can be hurt.
				//	=================================
				//
				// A boom sweeping through a pile of cobble does not delete it, and this did. An
				// EntityItem has five health against six damage, so one touch destroyed a whole
				// stack -- and its attackEntityFrom returns false whatever happens, so the cooldown
				// below never engaged and every item in reach died on every tick the arm moved.
				//
				// The Breaker drops its rubble at the tool tip, which is the middle of the very
				// hitbox doing the sweeping, so the machine ate its own spoil. It only fired while
				// the arm was moving, which is what made it read as random loss rather than as this.
				// XP orbs went the same way.
				if(!(hit instanceof EntityLivingBase))
					continue;
				if(hit.attackEntityFrom(IEDamageSources.crawlerArm, CONTACT_DAMAGE))
					contactCooldown = CONTACT_COOLDOWN;
			}
	}

	@Override
	protected void entityInit()
	{
		dataManager.register(SLEW, 0F);
		dataManager.register(BOOM, PARKED_BOOM);
		dataManager.register(STICK, PARKED_STICK);
		dataManager.register(TOOL, PARKED_TOOL);
		//Level, which is the arm out in front rather than folded: the pose somebody climbing in would
		//expect to start from.
		dataManager.register(AIM, 0F);
		dataManager.register(ATTACHMENT, (byte)CrawlerAttachment.BUCKET.ordinal());
		dataManager.register(EXTENSION, (float)CrawlerArm.REACH);
		dataManager.register(FUEL, 0);
		dataManager.register(BUCKET, (byte)0);
	}

	/** @see #syncFuel() */
	private void syncBucket()
	{
		if(world!=null&&!world.isRemote)
		{
			int used = 0;
			for(int slot = 0; slot < inventory.getSlots(); slot++)
				if(!inventory.getStackInSlot(slot).isEmpty())
					used++;
			dataManager.set(BUCKET, (byte)used);
		}
	}

	/**
	 * Push the tank's level out to whoever is watching.
	 * <p>
	 * Called from every place the amount can move -- burning it, filling it, loading it off disk --
	 * rather than from {@link #onUpdate}. Idle costs nothing either way, since the data manager
	 * suppresses a write that does not change the value, but doing it at the source means a tank
	 * filled by a pipe capability is covered as well as one filled by hand.
	 */
	private void syncFuel()
	{
		if(!world.isRemote)
			dataManager.set(FUEL, fuel.getFluidAmount());
	}

	public float getArmReach()
	{
		return dataManager.get(EXTENSION);
	}

	public void setArmReach(float reach)
	{
		dataManager.set(EXTENSION, reach);
	}

	public CrawlerAttachment getAttachment()
	{
		return CrawlerAttachment.byOrdinal(dataManager.get(ATTACHMENT));
	}

	public void setAttachment(CrawlerAttachment attachment)
	{
		dataManager.set(ATTACHMENT, (byte)attachment.ordinal());
	}

	public float getArmAim()
	{
		return dataManager.get(AIM);
	}

	public void setArmAim(float degrees)
	{
		dataManager.set(AIM, degrees);
	}

	//	=================================
	//		POSE
	//	=================================

	public float getSlew()
	{
		return dataManager.get(SLEW);
	}

	public void setSlew(float degrees)
	{
		dataManager.set(SLEW, MathHelper.wrapDegrees(degrees));
	}

	public float getBoomAngle()
	{
		return dataManager.get(BOOM);
	}

	public float getStickAngle()
	{
		return dataManager.get(STICK);
	}

	public float getToolAngle()
	{
		return dataManager.get(TOOL);
	}

	/**
	 * Set the arm's three joints at once.
	 * <p>
	 * One setter rather than three because the three are always solved together -- an arm holding two
	 * angles from one solve and one from the next would be a shape the solver never produced.
	 */
	public void setArm(float boom, float stick, float tool)
	{
		dataManager.set(BOOM, boom);
		dataManager.set(STICK, stick);
		dataManager.set(TOOL, tool);
	}

	//	=================================
	//		RIDING
	//	=================================

	@Override
	public boolean canBeCollidedWith()
	{
		return !isDead;
	}

	@Override
	public boolean canBePushed()
	{
		return false;
	}

	@Nullable
	@Override
	public AxisAlignedBB getCollisionBoundingBox()
	{
		//Given rather than left null, so the machine is something you walk into and stand against
		//instead of something you walk through. Only the body: see the plan on why the arm cannot
		//be a movement collider in 1.12.
		return getEntityBoundingBox();
	}

	@Override
	public boolean processInitialInteract(EntityPlayer player, EnumHand hand)
	{
		//Sneaking is reserved: it is how the machine is dismantled, and mounting on the same gesture
		//would make the two a coin toss.
		if(player.isSneaking())
			return dismantle(player, hand);
		//A can of diesel refuels it rather than mounting it.
		if(refuel(player, hand))
			return true;
		if(!world.isRemote&&getControllingPassenger()==null)
		{
			// Start facing wherever the driver is looking. The tracks' own heading -- rotationYaw -- only ever moves under A and D; nothing had ever
			// pointed it at the operator's view before now, so it was still wherever it was left, almost
			// always by somebody else, at a completely unrelated angle. The house and arm slew to match
			// the new operator's view the instant they climb in, because that is what look-to-aim means,
			// but the tracks did not, and W drives along the tracks. The result was a machine that, from
			// a driver who had just walked up and mounted it facing any direction but the one it was
			// already pointed, reliably moved opposite wherever they were looking the moment they
			// pressed it -- reported, correctly, as the controls being reversed. They were not; the
			// tracks and the view had simply never been introduced.
			//
			// Set once, on mounting, and not kept in sync afterwards: doing this every tick would make
			// A and D fight a heading that snapped straight back to the rider's view, which is the one
			// thing skid steer is for.
			rotationYaw = player.rotationYaw;
			player.startRiding(this);
		}
		//True on both sides regardless, or the click falls through to the held item and the operator
		//places a block against the machine they were trying to get into.
		return true;
	}

	/**
	 * Try to move diesel between the operator's container and the tank.
	 * <p>
	 * <strong>An empty container must not block the door.</strong> The obvious guard --
	 * {@code Utils.interactWithTank}, which every tank block in this fork uses -- spends the click
	 * for anything that <em>is</em> a container, whether or not a drop moved, and it discards
	 * {@code interactWithFluidHandler}'s answer to do it. On a block that is right: the click had
	 * nowhere else to go. On a machine you climb into it is not, because the click had somewhere
	 * very obvious to go, and the result was that holding an empty bucket -- or a water bucket, or
	 * any of the fork's seventeen fluids -- made the Crawler silently refuse to open. Nothing
	 * happened, nothing was said, and the machine read as broken.
	 * <p>
	 * So the answer is used, and only a container with fluid in it is treated as an attempt to
	 * refuel at all. What is kept from the original guard is the half that mattered: such a
	 * container still spends the click even when refused, because falling through reaches
	 * {@code ItemBucket.onItemRightClick}, which ray traces past the machine and pours the fluid
	 * onto the ground behind it. That is the defect the guard was written for, and it only ever
	 * applied to a full container.
	 *
	 * @return true if the click is spent and the machine should not be mounted
	 */
	private boolean refuel(EntityPlayer player, EnumHand hand)
	{
		ItemStack held = player.getHeldItem(hand);
		if(held.isEmpty())
			return false;
		//Against a copy, so asking what is in it cannot empty it.
		IFluidHandlerItem container = FluidUtil.getFluidHandler(held.copy());
		if(container==null)
			return false;

		//	=================================
		//	Filling only, never emptying.
		//	=================================
		//
		// An empty container is not an attempt to refuel, it is somebody walking up to their machine
		// holding a bucket. Letting the generic exchange run would siphon a bucket of diesel out of
		// a fuelled Crawler instead of opening the door -- which is the same "the machine will not
		// let me in" complaint as before, just narrower and harder to explain.
		//
		// Nothing is lost by refusing to drain: a dismantled machine now hands its diesel back with
		// the item, so there is a way to recover fuel, and it is a deliberate gesture rather than an
		// accident of what was in your hand.
		FluidStack offered = container.drain(Integer.MAX_VALUE, false);
		if(offered==null||offered.amount <= 0)
			return false;

		//Anything that actually moved needs nothing said about it: the gauge is the feedback.
		if(FluidUtil.interactWithFluidHandler(player, hand, fuel))
			return true;

		//Refused, and said so. Which of the two reasons it is matters: "wrong fuel" on a machine
		//whose tank is simply full would send somebody looking for the wrong problem.
		//
		// The click is still spent, and that half of the original guard is the half worth keeping:
		// falling through reaches ItemBucket.onItemRightClick, which ray traces past the machine and
		// pours the fluid onto the ground behind it.
		if(!world.isRemote)
		{
			boolean rightFluid = offered.getFluid()!=null
					&&FUEL_FLUID.equals(offered.getFluid().getName());
			ChatUtils.sendServerNoSpamMessages(player, new TextComponentTranslation(
					Lib.CHAT_INFO+(rightFluid?"crawlerTankFull": "crawlerWrongFuel")));
		}
		return true;
	}

	/**
	 * Sneak and strike it with an Engineer's Hammer to take it away again.
	 * <p>
	 * A gesture rather than damage, because the alternative is a machine you have to punch to death,
	 * and one that could be punched to death is one somebody loses to a stray click. The hammer is
	 * already the tool that assembles and dismantles everything else in this mod.
	 */
	private boolean dismantle(EntityPlayer player, EnumHand hand)
	{
		if(!Utils.isHammer(player.getHeldItem(hand)))
			return false;
		if(!world.isRemote)
		{
			//The bucket's contents come out with it. Folding nine slots into the item's NBT is possible
			//and would mean a dropped machine that quietly holds a chest's worth of stone -- easy to
			//lose track of, and easy to duplicate if the item is ever stacked by mistake.
			for(int slot = 0; slot < inventory.getSlots(); slot++)
			{
				ItemStack held = inventory.extractItem(slot, Integer.MAX_VALUE, false);
				if(!held.isEmpty())
					entityDropItem(held, 0.5F);
			}
			if(!player.capabilities.isCreativeMode)
			{
				//	=================================
				//	The diesel travels with the machine.
				//	=================================
				//
				// It used to be destroyed in silence: a machine dismantled with seven buckets
				// aboard gave back the item and nothing else, and said nothing about the rest.
				//
				// On the item rather than poured out as cans, because there is no can -- the fork
				// has no portable diesel container to pour it into, and inventing one to solve a
				// dismantle would be the tail wagging the dog. The objection that sank doing this
				// for the *bucket's* contents does not apply here: that was nine slots of arbitrary
				// items, easy to lose track of and worth duplicating; this is one number that a
				// player already expects a vehicle to keep.
				ItemStack machine = new ItemStack(IEContent.itemHydraulicCrawler);
				if(fuel.getFluidAmount() > 0)
				{
					NBTTagCompound tag = new NBTTagCompound();
					tag.setTag(TAG_FUEL, fuel.writeToNBT(new NBTTagCompound()));
					machine.setTagCompound(tag);
				}
				entityDropItem(machine, 0.5F);
			}
			world.playSound(null, posX, posY, posZ, net.minecraft.init.SoundEvents.BLOCK_ANVIL_LAND,
					SoundCategory.NEUTRAL, 0.6F, 0.8F);
			setDead();
		}
		return true;
	}

	@Override
	public boolean attackEntityFrom(DamageSource source, float amount)
	{
		//Nothing punches this apart. A creative player gets the shortcut because they are usually
		//tidying up, and everybody else uses the hammer.
		if(source.getTrueSource() instanceof EntityPlayer
				&&((EntityPlayer)source.getTrueSource()).capabilities.isCreativeMode)
		{
			if(!world.isRemote)
				setDead();
			return true;
		}
		return false;
	}

	@Nullable
	@Override
	public Entity getControllingPassenger()
	{
		return getPassengers().isEmpty()?null: getPassengers().get(0);
	}

	@Override
	public boolean shouldRiderSit()
	{
		return true;
	}

	@Override
	public double getMountedYOffset()
	{
		return CrawlerGeometry.CAB_HEIGHT;
	}

	@Override
	public void updatePassenger(Entity passenger)
	{
		if(!isPassenger(passenger))
			return;
		//The cab is on the house, so the seat travels round with the slew rather than staying at a
		//fixed corner of the tracks. Without this, slewing would swing the operator out into the air.
		Vec3d seat = cabOffset();
		passenger.setPosition(posX+seat.x, posY+getMountedYOffset()+passenger.getYOffset(),
				posZ+seat.z);
	}

	/**
	 * @return the seat's horizontal offset from the machine's centre, with the slew applied
	 */
	public Vec3d cabOffset()
	{
		double[] offset = CrawlerGeometry.cabOffset(getSlew());
		return new Vec3d(offset[0], 0, offset[1]);
	}

	//	=================================
	//		DRIVING
	//	=================================

	@Override
	public void onUpdate()
	{
		super.onUpdate();
		prevSlew = getSlew();
		prevPosX = posX;
		prevPosY = posY;
		prevPosZ = posZ;

		if(!world.isRemote)
		{
			//Counted down here rather than inside workAttachment, which only runs while the trigger
			//is held: a cooldown that only expired while working stayed latched the moment somebody
			//let go, so the next press waited out a delay it had already served.
			if(breakCooldown > 0)
				breakCooldown--;
			followOperatorsView();
			drive();
		}
		//Positioned on both sides -- see positionParts -- and before the contact check, so the arm is
		//tested where it is now rather than where it was last tick.
		positionParts();
		if(!world.isRemote)
		{
			carryAlong();
			bumpEntities();
			lastToolTip = getToolTip();
		}

		//Gravity, then a single move: a machine this heavy does not need airborne control, and
		//applying the throttle after the move would spend it on a position that no longer exists.
		motionY -= 0.08;
		move(MoverType.SELF, motionX, motionY, motionZ);
		motionX *= GROUND_FRICTION;
		motionZ *= GROUND_FRICTION;
		if(onGround)
			motionY = 0;
		else
			motionY *= 0.98;
	}

	/**
	 * The house follows the operator's head; the arm follows the controls.
	 * <p>
	 * <strong>The slew comes off the rider's yaw and the arm does not come off their pitch, and that
	 * split was learned by driving it.</strong> Turning to look at what you are working on is the same
	 * gesture as slewing the house towards it, so yaw is free and feels right. Pitch is not: the arm's
	 * height and the operator's view are two things somebody needs to set independently, and tying them
	 * together means you cannot look at what you are digging while you dig it. So the arm keeps a held
	 * aim of its own, worked by two controls.
	 * <p>
	 * The solver is untouched by that change. It always took a single aim parameter, so moving where
	 * that number comes from cost nothing -- which is the payoff for having made the arm aimed rather
	 * than jointed in the first place.
	 * <p>
	 * The arm is stepped towards its solved pose rather than set to it. Hydraulics have a speed, and
	 * beyond looking right it is what keeps the tool's path continuous -- an arm that could snap to a
	 * new angle in one tick could reach through a wall and take the far side of it, which will matter
	 * a great deal once the tool breaks what it touches.
	 */
	private void followOperatorsView()
	{
		Entity rider = getControllingPassenger();
		if(rider==null)
		{
			//Nothing aboard: drop any held control rather than leaving the arm creeping on its own.
			controlFlags = 0;
			return;
		}
		setSlew(rider.rotationYaw);
		//The engine burns while somebody is aboard, whether or not they are doing anything. A machine
		//that cost nothing to sit in would be a machine nobody ever switched off.
		burn(CrawlerConfig.fuelIdle);

		if(++controlAge > CONTROL_TIMEOUT)
			controlFlags = 0;
		double aim = getArmAim();
		if((controlFlags&FLAG_ARM_DOWN)!=0)
			aim += AIM_RATE;
		if((controlFlags&FLAG_ARM_UP)!=0)
			aim -= AIM_RATE;
		setArmAim((float)CrawlerGeometry.clamp(aim,
				CrawlerArm.MIN_DEPRESSION, CrawlerArm.MAX_DEPRESSION));

		//The second axis. Elevation says which way the arm points and this says how far along that
		//line the tool sits, which between them reach the whole band rather than a single arc.
		double reach = getArmReach();
		if((controlFlags&FLAG_EXTEND)!=0)
			reach += REACH_RATE;
		if((controlFlags&FLAG_RETRACT)!=0)
			reach -= REACH_RATE;
		setArmReach((float)CrawlerGeometry.clamp(reach, CrawlerArm.MIN_REACH, CrawlerArm.MAX_REACH));

		double[] target = CrawlerArm.solve(getArmAim(), getArmReach());
		double[] next = CrawlerArm.step(
				new double[]{getBoomAngle(), getStickAngle(), getToolAngle()}, target);
		setArm((float)next[0], (float)next[1], (float)next[2]);

		//On the way down, not while held: otherwise one keypress cycles the attachment every tick it
		//is held and the operator can never land on the one they wanted.
		if((controlFlags&FLAG_SWAP)!=0&&(previousFlags&FLAG_SWAP)==0)
		{
			setAttachment(getAttachment().next());
			//Said in chat as well as shown on the panel: the panel's legend has faded out by the time
			//anybody thinks to change tools, and swapping to the Breaker by accident should be
			//something you are told rather than something you discover by demolishing a wall.
			if(rider instanceof EntityPlayer)
				ChatUtils.sendServerNoSpamMessages((EntityPlayer)rider,
						new TextComponentTranslation(Lib.CHAT_INFO+"crawlerAttachment",
								new TextComponentTranslation(getAttachment().getTranslationKey())));
		}
		previousFlags = controlFlags;

		if((controlFlags&FLAG_TRIGGER)!=0)
			workAttachment(rider);
	}

	//	=================================
	//		THE ATTACHMENT AT WORK
	//	=================================

	/**
	 * Pull the trigger with whatever is fitted.
	 * <p>
	 * Only the Breaker does anything yet. The Bucket and the Grapple are named so the machine can be
	 * fitted with them and so the framework is not shaped around a single tool, but they are P7 and P8.
	 */
	private void workAttachment(Entity rider)
	{
		if(breakCooldown > 0)
			return;
		//Sneak and pull the trigger to tip the bucket out. Checked before anything else, so a full
		//bucket can always be emptied even while the tool is buried in something.
		if(rider.isSneaking())
		{
			if(rider instanceof EntityPlayer&&getAttachment().keepsDrops())
			{
				dumpBucket((EntityPlayer)rider);
				breakCooldown = CrawlerDemolition.COOLDOWN;
			}
			return;
		}
		if(getAttachment()==CrawlerAttachment.GRAPPLE)
		{
			if(rider instanceof EntityPlayer&&workGrapple((EntityPlayer)rider))
				breakCooldown = CrawlerDemolition.COOLDOWN;
			return;
		}
		if(!getAttachment().breaksBlocks())
			return;
		//	=================================
		//	The reserve.
		//	=================================
		//
		// The attachment stops well before the tank does, and the tracks keep going. The worst thing
		// this machine can do to somebody is run dry mid-demolition, hundreds of blocks from home, in a
		// thing that cannot be pushed and cannot be picked up without a hammer. The reserve is enough
		// to drive back with.
		if(!hasWorkingFuel())
		{
			//	=================================
			//	Empty and low are different problems.
			//	=================================
			//
			// They were one message, and it said "running on reserve" -- which on a machine that has
			// never been fuelled is not true and does not tell anybody what to do. A brand new Crawler
			// arrives with a dry tank, so this was the first thing every operator met: a trigger that
			// did nothing, on every block, with an explanation that described a situation they were
			// not in. It read as the attachment being broken, which is exactly how it was reported.
			if(rider instanceof EntityPlayer)
				ChatUtils.sendServerNoSpamMessages((EntityPlayer)rider,
						new TextComponentTranslation(Lib.CHAT_INFO
								+(CrawlerConfig.isDry(getFuel())?"crawlerNoFuel"
								: "crawlerReserve")));
			return;
		}
		burn(CrawlerConfig.fuelWorking);
		//	=================================
		//	Somebody has to be responsible.
		//	=================================
		//
		// Every broken block is attributed to the operator, and with no operator nothing breaks. That
		// is not a formality: BreakEvent needs a player, and it is what lets claim mods, protection
		// plugins and grief logs treat this exactly as they would treat somebody swinging a pick. A
		// demolition machine that bypassed all of that could not be allowed on a server, and would be
		// the single most destructive thing in the mod.
		if(!(rider instanceof EntityPlayer))
			return;
		EntityPlayer operator = (EntityPlayer)rider;
		Vec3d tip = getToolTip();
		boolean bit = false;
		for(BlockPos pos : CrawlerDemolition.targetsAround(tip.x, tip.y, tip.z,
				CrawlerDemolition.REACH, CrawlerDemolition.BUDGET))
			if(breakBlock(operator, pos))
				bit = true;
		if(bit)
			breakCooldown = CrawlerDemolition.COOLDOWN;
	}

	/**
	 * @return true if the block came out
	 */
	private boolean breakBlock(EntityPlayer operator, BlockPos pos)
	{
		IBlockState state = world.getBlockState(pos);
		if(state.getBlock().isAir(state, world, pos))
			return false;
		if(!CrawlerDemolition.withinHardnessLimit(state.getBlockHardness(world, pos)))
			return false;
		//isBlockModifiable covers spawn protection and the world border; the event covers everything
		//else anybody has ever written. Both, because they catch different things.
		if(!world.isBlockModifiable(operator, pos))
			return false;
		BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, operator);
		if(MinecraftForge.EVENT_BUS.post(event)||event.isCanceled())
			return false;

		if(!getAttachment().keepsDrops())
		{
			//The Breaker: straight on the floor. Somebody taking a house down does not want its
			//cobblestone anywhere but out of the way.
			world.destroyBlock(pos, true);
			return true;
		}
		//The Bucket keeps what it digs, so the drops are worked out here and the block removed without
		//them -- destroyBlock(pos, true) would scatter them on the ground first and leave this picking
		//its own spoil back up off the floor.
		NonNullList<ItemStack> drops = NonNullList.create();
		state.getBlock().getDrops(drops, world, pos, state, 0);
		float chance = ForgeEventFactory.fireBlockHarvesting(drops, world, pos, state, 0, 1F, false,
				operator);
		world.destroyBlock(pos, false);
		for(ItemStack drop : drops)
			if(world.rand.nextFloat() <= chance)
				keepOrDrop(drop);
		return true;
	}

	/**
	 * Put a dug item in the bucket, or on the floor if the bucket is full.
	 * <p>
	 * Never voided. A machine that quietly ate what it dug the moment its bucket filled would be a
	 * machine nobody could trust to dig anything they cared about, and "it stopped when full" is a
	 * worse answer than a small pile on the ground.
	 */
	private void keepOrDrop(ItemStack stack)
	{
		ItemStack left = stack;
		for(int slot = 0; slot < inventory.getSlots()&&!left.isEmpty(); slot++)
			left = inventory.insertItem(slot, left, false);
		if(!left.isEmpty())
			entityDropItem(left, 0.5F);
	}

	/**
	 * Tip the bucket out at the machine's feet.
	 * <p>
	 * On sneak plus the trigger, because the bucket has no window on it and there is otherwise no way
	 * to get anything back out short of dismantling the machine. It is also the gesture nobody presses
	 * by accident: sneaking is already how the machine is dismantled, so the operator is in "doing
	 * something deliberate" mode already.
	 */
	private void dumpBucket(EntityPlayer operator)
	{
		boolean anything = false;
		for(int slot = 0; slot < inventory.getSlots(); slot++)
		{
			ItemStack stack = inventory.extractItem(slot, Integer.MAX_VALUE, false);
			if(!stack.isEmpty())
			{
				entityDropItem(stack, 0.6F);
				anything = true;
			}
		}
		if(anything)
			ChatUtils.sendServerNoSpamMessages(operator,
					new TextComponentTranslation(Lib.CHAT_INFO+"crawlerBucketDumped"));
	}

	//	=================================
	//		FUEL
	//	=================================

	private void burn(int amount)
	{
		fuel.drainInternal(amount, true);
	}

	/**
	 * @return millibuckets aboard -- off the tank on the server, off the synced mirror on the client
	 * <p>
	 * Both, rather than always the mirror, so server-side logic keeps reading the authority and
	 * cannot be made wrong by a sync that has not gone out yet within the same tick.
	 */
	public int getFuel()
	{
		return world.isRemote?dataManager.get(FUEL): fuel.getFluidAmount();
	}

	public int getFuelCapacity()
	{
		return fuel.getCapacity();
	}

	/**
	 * @return true if there is enough left to work with, as opposed to only enough to drive home on
	 */
	public boolean hasWorkingFuel()
	{
		return CrawlerConfig.canWork(getFuel());
	}

	//	=================================
	//		THE GRAPPLE
	//	=================================

	/**
	 * One press: take hold. The next: let go.
	 * <p>
	 * A grab is tried against entities before blocks, because an entity standing in a doorway is
	 * plainly the thing being aimed at and the doorway plainly is not. Carrying holds it at the tool,
	 * so the arm moves it -- which is the whole point of a grapple and is also why it is on the same
	 * gesture as putting it down: the operator's hands are on the arm, not on an inventory.
	 *
	 * @return true if the grapple did something worth a cooldown
	 */
	private boolean workGrapple(EntityPlayer operator)
	{
		if(isCarrying())
			return release(operator);
		return grabEntity()||grabBlock(operator);
	}

	private boolean isCarrying()
	{
		return carriedState!=0||carriedEntity!=null;
	}

	/**
	 * Take hold of whatever is at the tool.
	 * <p>
	 * The machine's own parts and its operator are excluded, or the first press picks up the arm doing
	 * the picking.
	 */
	private boolean grabEntity()
	{
		Vec3d tip = getToolTip();
		AxisAlignedBB reach = new AxisAlignedBB(tip.x, tip.y, tip.z, tip.x, tip.y, tip.z)
				.grow(CrawlerDemolition.REACH);
		for(Entity candidate : world.getEntitiesWithinAABBExcludingEntity(this, reach))
		{
			if(candidate instanceof EntityCrawlerPart||candidate==getControllingPassenger())
				continue;
			//Nothing that is already being ridden or carried: two machines passing the same cow back
			//and forth would be a fight neither can win.
			if(candidate.isRiding()||candidate.isBeingRidden())
				continue;
			carriedEntity = candidate.getUniqueID();
			return true;
		}
		return false;
	}

	/**
	 * Lift the block at the tool out of the world.
	 * <p>
	 * Through the same permission checks the Breaker uses, because taking a block out of a wall is
	 * taking a block out of a wall regardless of whether you intend to put it back.
	 */
	private boolean grabBlock(EntityPlayer operator)
	{
		for(BlockPos pos : CrawlerDemolition.targetsAround(getToolTip().x, getToolTip().y,
				getToolTip().z, CrawlerDemolition.REACH, 1))
		{
			IBlockState state = world.getBlockState(pos);
			if(state.getBlock().isAir(state, world, pos))
				continue;
			if(!CrawlerDemolition.withinHardnessLimit(state.getBlockHardness(world, pos)))
				continue;
			if(state.getBlock().hasTileEntity(state))
				//A tile entity's contents would not survive being carried as a block state, and a chest
				//that came back empty is worse than one that could not be picked up.
				continue;
			if(!world.isBlockModifiable(operator, pos))
				continue;
			BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(world, pos, state, operator);
			if(MinecraftForge.EVENT_BUS.post(event)||event.isCanceled())
				continue;
			carriedState = Block.getStateId(state);
			world.setBlockToAir(pos);
			return true;
		}
		return false;
	}

	/**
	 * Put down whatever is being carried.
	 */
	private boolean release(EntityPlayer operator)
	{
		if(carriedEntity!=null)
		{
			carriedEntity = null;
			return true;
		}
		Vec3d tip = getToolTip();
		BlockPos at = new BlockPos(tip.x, tip.y, tip.z);
		IBlockState existing = world.getBlockState(at);
		//Only into somewhere there is room. Replacing a block would make the grapple a second, quieter
		//way of destroying things, and one with none of the Breaker's checks in front of it.
		if(!existing.getBlock().isReplaceable(world, at))
			return false;
		IBlockState state = Block.getStateById(carriedState);
		if(!world.isBlockModifiable(operator, at))
			return false;
		//Place, then post, then put it back if anybody objected. That is Forge's own order for this --
		//a PlaceEvent describes a placement that has happened, and a snapshot taken beforehand is what
		//makes undoing it exact rather than approximate.
		BlockSnapshot before = BlockSnapshot.getBlockSnapshot(world, at);
		world.setBlockState(at, state);
		BlockEvent.PlaceEvent event = new BlockEvent.PlaceEvent(before, existing, operator,
				EnumHand.MAIN_HAND);
		if(MinecraftForge.EVENT_BUS.post(event)||event.isCanceled())
		{
			before.restore(true, false);
			return false;
		}
		carriedState = 0;
		return true;
	}

	/**
	 * Keep whatever is being carried at the tool.
	 * <p>
	 * Position rather than a passenger relationship, because a carried entity is being <em>held</em> --
	 * riding would let it steer, and would put a cow in the driving seat of a machine that had just
	 * picked it up.
	 */
	private void carryAlong()
	{
		if(carriedEntity==null)
			return;
		Entity held = ((WorldServer)world).getEntityFromUuid(carriedEntity);
		if(held==null||held.isDead)
		{
			carriedEntity = null;
			return;
		}
		Vec3d tip = getToolTip();
		held.setPosition(tip.x, tip.y-held.height/2, tip.z);
		//Falling reset each tick, so being set down from a height is not fatal to whatever was carried.
		held.fallDistance = 0;
		held.motionX = held.motionY = held.motionZ = 0;
	}

	/**
	 * @return how many of the bucket's slots hold something, for the panel
	 */
	public int getBucketFill()
	{
		return dataManager.get(BUCKET);
	}

	public int getBucketSize()
	{
		return inventory.getSlots();
	}

	/**
	 * @return where the tool's tip is right now, in world coordinates
	 * <p>
	 * Nothing uses this yet. It is the number the attachments will work from -- the point whose
	 * surroundings get dug, grabbed or broken -- and it is here rather than in the attachment code
	 * because there is exactly one right answer to "where is the bucket" and every attachment needs
	 * the same one.
	 */
	public Vec3d getToolTip()
	{
		double[] offset = CrawlerArm.tipOffset(getSlew(), getBoomAngle(), getStickAngle(),
				getToolAngle());
		return new Vec3d(posX+offset[0], posY+offset[1], posZ+offset[2]);
	}

	private void drive()
	{
		Entity rider = getControllingPassenger();
		if(!(rider instanceof EntityLivingBase))
			return;
		EntityLivingBase operator = (EntityLivingBase)rider;

		//Skid steer: strafing turns the tracks rather than sliding the machine sideways, because
		//tracks cannot go sideways. This is what makes it read as tracked rather than as a car.
		float steer = operator.moveStrafing;
		if(steer!=0)
			rotationYaw += -steer*TURN_RATE;

		float throttle = operator.moveForward;
		if(throttle==0)
			return;
		double speed = DRIVE_SPEED*throttle*(throttle < 0?REVERSE_FACTOR: 1);
		double[] heading = CrawlerGeometry.heading(rotationYaw);
		motionX += heading[0]*speed;
		motionZ += heading[1]*speed;
	}

	@Override
	public boolean canPassengerSteer()
	{
		//False: the machine is driven from the server off the rider's synced input, not steered
		//client-side like a boat. A boat moves itself on the controlling client and reconciles, which
		//is right for something light and wrong for something that will shortly be deciding which
		//blocks to delete -- there must be one authority for that, and it is the server.
		return false;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	protected void readEntityFromNBT(NBTTagCompound nbt)
	{
		setSlew(nbt.getFloat("slew"));
		setArm(nbt.getFloat("boom"), nbt.getFloat("stick"), nbt.getFloat("tool"));
		setArmAim((float)CrawlerGeometry.clamp(nbt.getFloat("aim"),
				CrawlerArm.MIN_DEPRESSION, CrawlerArm.MAX_DEPRESSION));
		//Defaulted rather than clamped from zero: a machine saved before the arm had a second axis has
		//no "reach" tag at all, and clamping the zero that comes back would fold every existing
		//Crawler's arm all the way in the first time it loaded.
		setArmReach((float)CrawlerGeometry.clamp(
				nbt.hasKey("reach")?nbt.getFloat("reach"): CrawlerArm.REACH,
				CrawlerArm.MIN_REACH, CrawlerArm.MAX_REACH));
		//byOrdinal rather than values()[..]: this came off disk and may be from a version with a
		//different set of tools, and an out-of-range ordinal should fit the wrong tool rather than
		//throw on chunk load.
		setAttachment(CrawlerAttachment.byOrdinal(nbt.getByte("attachment")));
		if(nbt.hasKey("inventory"))
			inventory.deserializeNBT(nbt.getCompoundTag("inventory"));
		//deserializeNBT replaces the stacks wholesale without firing onContentsChanged, so the
		//mirror is set by hand -- the same gap FluidTank.readFromNBT leaves.
		syncBucket();
		carriedState = nbt.getInteger("carried");
		fuel.readFromNBT(nbt.getCompoundTag("fuel"));
		//FluidTank.readFromNBT is the one path that does not fire onContentsChanged, so the mirror
		//is set by hand here. Without it a machine that came off disk full would show an empty gauge
		//until the first millibucket was burnt.
		syncFuel();
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt)
	{
		nbt.setFloat("slew", getSlew());
		nbt.setFloat("boom", getBoomAngle());
		nbt.setFloat("stick", getStickAngle());
		nbt.setFloat("tool", getToolAngle());
		nbt.setFloat("aim", getArmAim());
		nbt.setFloat("reach", getArmReach());
		nbt.setByte("attachment", (byte)getAttachment().ordinal());
		nbt.setTag("inventory", inventory.serializeNBT());
		//A carried block is saved; a carried entity is not. The block only exists inside this machine
		//and would be destroyed by not saving it, while the entity exists perfectly well on its own and
		//is simply let go of when the world reloads -- which is also the right behaviour, since it may
		//not be anywhere near the machine when the chunk comes back.
		nbt.setInteger("carried", carriedState);
		nbt.setTag("fuel", fuel.writeToNBT(new NBTTagCompound()));
	}

	//	=================================
	//		UNLOADING BY MACHINE
	//	=================================

	@Override
	public boolean hasCapability(net.minecraftforge.common.capabilities.Capability<?> capability,
								 @Nullable EnumFacing facing)
	{
		return capability==CapabilityItemHandler.ITEM_HANDLER_CAPABILITY
				||capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
				||super.hasCapability(capability, facing);
	}

	@Nullable
	@Override
	public <T> T getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,
							   @Nullable EnumFacing facing)
	{
		if(capability==CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
			return CapabilityItemHandler.ITEM_HANDLER_CAPABILITY.cast(inventory);
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(fuel);
		return super.getCapability(capability, facing);
	}
}
