/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.entities;

import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
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
public class EntityHydraulicCrawler extends Entity
{
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
	 * The pose the arm sits in with nobody aiming it -- folded in, the way a machine is left at the
	 * end of a shift. Replaced by a solve against the operator's view once the arm is driven.
	 */
	public static final float PARKED_BOOM = -38F;
	public static final float PARKED_STICK = 96F;
	public static final float PARKED_TOOL = 24F;

	/** Interpolated on the client so the house does not snap between sync packets. */
	public float prevSlew;

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
	}

	@Override
	protected void entityInit()
	{
		dataManager.register(SLEW, 0F);
		dataManager.register(BOOM, PARKED_BOOM);
		dataManager.register(STICK, PARKED_STICK);
		dataManager.register(TOOL, PARKED_TOOL);
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
		if(!world.isRemote&&getControllingPassenger()==null)
			player.startRiding(this);
		//True on both sides regardless, or the click falls through to the held item and the operator
		//places a block against the machine they were trying to get into.
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
			if(!player.capabilities.isCreativeMode)
				entityDropItem(new ItemStack(IEContent.itemHydraulicCrawler), 0.5F);
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
			followOperatorsView();
			drive();
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
	 * The house and the arm follow the operator's head.
	 * <p>
	 * Straight off the rider's synced yaw and pitch, which is the decision the whole feature rests on
	 * -- see the class comment. With nobody aboard the machine holds its last pose, because a parked
	 * machine neither spins nor waves its arm about.
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
			return;
		setSlew(rider.rotationYaw);
		double[] target = CrawlerArm.solve(rider.rotationPitch);
		double[] next = CrawlerArm.step(
				new double[]{getBoomAngle(), getStickAngle(), getToolAngle()}, target);
		setArm((float)next[0], (float)next[1], (float)next[2]);
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
	}

	@Override
	protected void writeEntityToNBT(NBTTagCompound nbt)
	{
		nbt.setFloat("slew", getSlew());
		nbt.setFloat("boom", getBoomAngle());
		nbt.setFloat("stick", getStickAngle());
		nbt.setFloat("tool", getToolAngle());
	}
}
