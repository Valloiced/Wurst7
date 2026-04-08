/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.mojang.blaze3d.vertex.PoseStack;

import net.wurstclient.hacks.automineralmine.OrderCollector;
import net.wurstclient.hacks.automineralmine.SellHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"auto mineral mine", "AutoMine", "mineral mine"})
public final class AutoMineralMineHack extends Hack
	implements UpdateListener, RenderListener
{
	private final TextFieldSetting centerX = new TextFieldSetting("Center X",
		"X coordinate of the center of block formation.", "0",
		s -> s.matches("-?\\d+"));
	private final TextFieldSetting centerY = new TextFieldSetting("Center Y",
		"Y coordinate of the bottom layer of the block formation.", "0",
		s -> s.matches("-?\\d+"));
	private final TextFieldSetting centerZ = new TextFieldSetting("Center Z",
		"Z coordinate of the center of block formation.", "0",
		s -> s.matches("-?\\d+"));
	
	private final SliderSetting minPlaceDelay =
		new SliderSetting("Min Place Delay (ticks)",
			"Minimum delay between each block placement.", 10, 0, 200, 1,
			ValueDisplay.INTEGER);
	private final SliderSetting maxPlaceDelay =
		new SliderSetting("Max Place Delay (ticks)",
			"Maximum delay between each block placement.", 25, 0, 200, 1,
			ValueDisplay.INTEGER);
	
	private final SliderSetting transitionDelay =
		new SliderSetting("Transition Delay (ticks)",
			"Delay between finishing placement and starting to mine.", 20, 5,
			200, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting rotationSpeed = new SliderSetting(
		"Rotation Speed",
		"How fast to rotate toward target. Lower = smoother, less suspicious.",
		0.4, 0.1, 1.0, 0.05, ValueDisplay.DECIMAL);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.SERVER);
	
	private final CheckboxSetting autoCollect =
		new CheckboxSetting("Auto Collect Orders",
			"Automatically collect completed /orders when ore count drops below"
				+ " the threshold.",
			true);
	
	private final SliderSetting collectThreshold =
		new SliderSetting("Collect Threshold",
			"Trigger collection when total ore in inventory falls below this.",
			9, 1, 54, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting minFillPct =
		new SliderSetting("Collect Threshold",
			"Trigger collection when total ore in inventory falls below this.",
			9, 1, 54, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting collectGuiDelay =
		new SliderSetting("Collect GUI Delay (ticks)",
			"Ticks to wait between GUI interactions during collection.", 6, 1,
			40, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting sellThreshold = new SliderSetting(
		"Sell Threshold", "Number of ore to have before starting to sell", 600,
		1, 2500, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting maxSlotsToPull = new SliderSetting(
		"Max Slots To Pull", "Maximum GUI slots to pull per collect cycle.", 10,
		1, 54, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting useDiamond =
		new CheckboxSetting("Diamond Ore", "Use diamond ore.", true);
	
	private final CheckboxSetting useGold =
		new CheckboxSetting("Gold Ore", "Use gold ore.", true);
	
	private final CheckboxSetting useRedstone =
		new CheckboxSetting("Redstone Ore", "Use redstone ore.", false);
	
	private final CheckboxSetting useUsernameSellFilter = new CheckboxSetting(
		"Use Username Filter", "Use username filter.", true);
	
	private final TextFieldSetting usernameToSell = new TextFieldSetting(
		"Username Filter", "Username to filter by.", "FBTob1");
	
	// Hardcoded 3x3 vertical face offsets at dz=0
	// Center block to mine is at offset {0, 1, 0}
	private static final int[][] PLACE_OFFSETS =
		{{-1, 0, 0}, {0, 0, 0}, {1, 0, 0}, {-1, 1, 0}, {0, 1, 0}, {1, 1, 0},
			{-1, 2, 0}, {0, 2, 0}, {1, 2, 0}};
	
	// ======================
	// ROTATION STATE
	// ======================
	
	private int rotationIndex = 0;
	private int attemptsThisCycle = 0;
	
	private enum State
	{
		PLACING,
		MINING,
		COLLECTING,
		SELLING
	}
	
	private State currentState = State.PLACING;
	private State stateAfterCollect = State.PLACING;
	private int delayTimer = 0;
	private final Random random = new Random();
	private BlockPos targetCenterPos = null;
	
	// Locked placement target — chosen once, held until placed
	private BlockPos lockedPlacePos = null;
	
	// Ensures transition delay fires only once per PLACING -> MINING transition
	private boolean transitionDelayApplied = false;
	
	private float currentYaw = 0;
	private float currentPitch = 0;
	
	private final OrderCollector orderCollector = new OrderCollector(MC);
	private final SellHandler sellHandler = new SellHandler(MC);
	
	private State stateAfterSell = State.PLACING;
	
	public AutoMineralMineHack()
	{
		super("AutoMineralMine");
		setCategory(Category.BLOCKS);
		addSetting(centerX);
		addSetting(centerY);
		addSetting(centerZ);
		addSetting(minPlaceDelay);
		addSetting(maxPlaceDelay);
		addSetting(transitionDelay);
		addSetting(rotationSpeed);
		addSetting(swingHand);
		addSetting(autoCollect);
		addSetting(collectThreshold);
		addSetting(collectGuiDelay);
		addSetting(maxSlotsToPull);
		addSetting(sellThreshold);
		addSetting(useDiamond);
		addSetting(useGold);
		addSetting(useRedstone);
		addSetting(useUsernameSellFilter);
		addSetting(usernameToSell);
	}
	
	@Override
	public void onEnable()
	{
		try
		{
			int cx = Integer.parseInt(centerX.getValue());
			int cy = Integer.parseInt(centerY.getValue());
			int cz = Integer.parseInt(centerZ.getValue());
			targetCenterPos = new BlockPos(cx, cy, cz);
		}catch(NumberFormatException e)
		{
			ChatUtils.error(
				"Invalid center coordinates. Using player's current position.");
			targetCenterPos = MC.player.blockPosition();
			centerX.setValue(String.valueOf(targetCenterPos.getX()));
			centerY.setValue(String.valueOf(targetCenterPos.getY()));
			centerZ.setValue(String.valueOf(targetCenterPos.getZ()));
		}
		
		currentYaw = MC.player.getYRot();
		currentPitch = MC.player.getXRot();
		
		currentState = State.PLACING;
		stateAfterCollect = State.PLACING;
		delayTimer = 0;
		lockedPlacePos = null;
		transitionDelayApplied = false;
		orderCollector.reset();
		sellHandler.reset();
		rotationIndex = 0;
		attemptsThisCycle = 0;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	public void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		lockedPlacePos = null;
		orderCollector.reset();
		sellHandler.reset();
		
		if(MC.gameMode.isDestroying)
		{
			MC.gameMode.isDestroying = false;
			MC.gameMode.stopDestroyBlock();
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(targetCenterPos == null)
			return;
		
		if(delayTimer > 0)
		{
			delayTimer--;
			return;
		}
		
		switch(currentState)
		{
			case PLACING ->
			{
				handlePlacing();
				break;
			}
			case MINING ->
			{
				handleMining();
				break;
			}
			case COLLECTING ->
			{
				handleCollecting();
				break;
			}
			case SELLING ->
			{
				handleSelling();
				break;
			}
		}
	}
	
	private List<Item> getSelectedOres()
	{
		List<Item> ores = new ArrayList<>();
		
		if(useDiamond.isChecked())
			ores.add(Items.DEEPSLATE_DIAMOND_ORE);
		
		if(useGold.isChecked())
			ores.add(Items.DEEPSLATE_GOLD_ORE);
		
		if(useRedstone.isChecked())
			ores.add(Items.DEEPSLATE_REDSTONE_ORE);
		
		return ores;
	}
	
	private List<Item> getSelectedProcessedOres()
	{
		List<Item> ores = new ArrayList<>();
		
		if(useDiamond.isChecked())
			ores.add(Items.DIAMOND);
		
		if(useGold.isChecked())
			ores.add(Items.GOLD_INGOT);
		
		if(useRedstone.isChecked())
			ores.add(Items.REDSTONE);
		
		return ores;
	}
	
	private Item getNextOreInRotation()
	{
		List<Item> ores = getSelectedOres();
		
		if(ores.isEmpty())
			return null;
		
		if(attemptsThisCycle >= ores.size())
			return null;
		
		Item ore = ores.get(rotationIndex);
		
		rotationIndex = (rotationIndex + 1) % ores.size();
		attemptsThisCycle++;
		
		return ore;
	}
	
	private int countAllSelectedOres()
	{
		int total = 0;
		for(Item ore : getSelectedOres())
			total += countInInventory(ore);
		return total;
	}
	
	private int countAllSelectedProcessedOres()
	{
		int total = 0;
		for(Item ore : getSelectedProcessedOres())
			total += countInInventory(ore);
		return total;
	}
	
	// ** Selling Logic ** //
	
	private void beginSell(State returnTo)
	{
		stateAfterSell = returnTo;
		
		List<Item> ores = getSelectedProcessedOres();
		if(ores.isEmpty())
		{
			ChatUtils.error("No ores selected!");
			setEnabled(false);
			return;
		}
		
		Item target = null;
		int amount = 0;
		
		for(Item ore : ores)
		{
			int count = countInInventory(ore);
			if(count > amount)
			{
				target = ore;
				amount = count;
			}
		}
		
		if(target == null || amount <= 0)
			return;
		
		sellHandler.reset();
		sellHandler.setSettings(10, 75, useUsernameSellFilter.isChecked());
		
		if(useUsernameSellFilter.isChecked())
			sellHandler.setUsernameFilter(usernameToSell.getValue());
		
		sellHandler.queueSell(target, amount);
		sellHandler.start();
		
		currentState = State.SELLING;
		
		ChatUtils.message("AutoMineralMine: Selling " + amount + "x "
			+ new ItemStack(target).getHoverName().getString() + "...");
	}
	
	private void handleSelling()
	{
		sellHandler.tick();
		
		if(sellHandler.isFinished())
			finishSell();
	}
	
	private void finishSell()
	{
		sellHandler.reset();
		currentState = stateAfterSell;
		delayTimer = transitionDelay.getValueI();
		ChatUtils.message("AutoMineralMine: Sell finished. Resuming.");
	}
	
	private boolean shouldTriggerSell()
	{
		return countAllSelectedProcessedOres() >= sellThreshold.getValueI();
	}
	
	private void smoothFaceTarget(Vec3 hitVec)
	{
		double dx = hitVec.x - MC.player.getX();
		double dy = hitVec.y - MC.player.getEyeY();
		double dz = hitVec.z - MC.player.getZ();
		
		double dist = Math.sqrt(dx * dx + dz * dz);
		float targetYaw = (float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f;
		float targetPitch = (float)(-Math.toDegrees(Math.atan2(dy, dist)));
		
		float yawDelta = targetYaw - currentYaw;
		while(yawDelta > 180f)
			yawDelta -= 360f;
		while(yawDelta < -180f)
			yawDelta += 360f;
		
		float pitchDelta = targetPitch - currentPitch;
		float speed = (float)rotationSpeed.getValue();
		
		// Snap when close enough to prevent endless oscillation
		currentYaw =
			Math.abs(yawDelta) < 1f ? targetYaw : currentYaw + yawDelta * speed;
		currentPitch = Math.abs(pitchDelta) < 1f ? targetPitch
			: currentPitch + pitchDelta * speed;
		
		MC.player.setYRot(currentYaw);
		MC.player.setXRot(currentPitch);
	}
	
	private boolean isAimedAt(Vec3 hitVec)
	{
		double dx = hitVec.x - MC.player.getX();
		double dy = hitVec.y - MC.player.getEyeY();
		double dz = hitVec.z - MC.player.getZ();
		
		double dist = Math.sqrt(dx * dx + dz * dz);
		float targetYaw = (float)(Math.toDegrees(Math.atan2(dz, dx))) - 90f;
		float targetPitch = (float)(-Math.toDegrees(Math.atan2(dy, dist)));
		
		float yawDiff = Math.abs(currentYaw - targetYaw) % 360f;
		if(yawDiff > 180f)
			yawDiff = 360f - yawDiff;
		
		return yawDiff < 1f && Math.abs(currentPitch - targetPitch) < 1f;
	}
	
	private void applyPlacementDelay()
	{
		int min = minPlaceDelay.getValueI();
		int max = maxPlaceDelay.getValueI();
		if(max < min)
			max = min;
		delayTimer = min + random.nextInt(max - min + 1);
	}
	
	private void handlePlacing()
	{
		if(shouldTriggerCollect())
		{
			beginCollect(State.PLACING);
			return;
		}
		
		List<BlockPos> emptySpots = getEmptySpots();
		
		if(emptySpots.isEmpty())
		{
			lockedPlacePos = null;
			transitionToMining();
			return;
		}
		
		List<Item> ores = getSelectedOres();
		
		if(ores.isEmpty())
		{
			ChatUtils.error("No ores selected!");
			setEnabled(false);
			return;
		}
		
		boolean found = false;
		
		for(Item ore : ores)
		{
			if(InventoryUtils.selectItem(ore))
			{
				found = true;
				break;
			}
		}
		
		if(!found)
		{
			lockedPlacePos = null;
			
			if(emptySpots.size() < 9)
				transitionToMining();
			else
			{
				ChatUtils.error("Out of selected ores!");
				setEnabled(false);
			}
			return;
		}
		
		if(lockedPlacePos == null || !emptySpots.contains(lockedPlacePos))
		{
			int size = emptySpots.size();
			
			if(size == 0)
			{
				lockedPlacePos = null;
				return;
			}
			
			lockedPlacePos = emptySpots.get(random.nextInt(size));
		}
		
		BlockPlacingParams params =
			BlockPlacer.getBlockPlacingParams(lockedPlacePos);
		if(params == null)
		{
			lockedPlacePos = null;
			return;
		}
		
		smoothFaceTarget(params.hitVec());
		
		if(!isAimedAt(params.hitVec()))
			return;
		
		InteractionHand hand = InteractionHand.MAIN_HAND;
		InteractionResult result =
			MC.gameMode.useItemOn(MC.player, hand, params.toHitResult());
		
		if(result instanceof InteractionResult.Success success
			&& success.swingSource() == InteractionResult.SwingSource.CLIENT)
			swingHand.swing(hand);
		
		lockedPlacePos = null;
		applyPlacementDelay();
	}
	
	private void transitionToMining()
	{
		currentState = State.MINING;
		transitionDelayApplied = false;
	}
	
	private void handleMining()
	{
		if(shouldTriggerSell())
		{
			beginSell(State.MINING);
			return;
		}
		
		BlockPos targetPos = new BlockPos(targetCenterPos.getX(),
			targetCenterPos.getY() + 1, targetCenterPos.getZ());
		
		if(BlockUtils.getState(targetPos).canBeReplaced())
		{
			transitionDelayApplied = false;
			int min = transitionDelay.getValueI();
			int max = (int)(min * 1.5);
			delayTimer = min + random.nextInt(max - min + 1);
			currentState = State.PLACING;
			return;
		}
		
		WURST.getHax().autoToolHack.equipBestTool(targetPos, false, true, 2);
		
		BlockBreakingParams params =
			BlockBreaker.getBlockBreakingParams(targetPos);
		if(params == null)
			return;
		
		smoothFaceTarget(params.hitVec());
		
		// Rotate first — only delay and mine once actually aimed
		if(!isAimedAt(params.hitVec()))
			return;
		
		if(!transitionDelayApplied)
		{
			transitionDelayApplied = true;
			int min = transitionDelay.getValueI();
			int max = (int)(min * 1.5);
			delayTimer = min + random.nextInt(max - min + 1);
			return;
		}
		
		if(MC.gameMode.continueDestroyBlock(targetPos, params.side()))
			swingHand.swing(InteractionHand.MAIN_HAND);
	}
	
	private boolean shouldTriggerCollect()
	{
		if(!autoCollect.isChecked())
			return false;
		
		return countAllSelectedOres() < collectThreshold.getValueI();
	}
	
	private void beginCollect(State returnTo)
	{
		stateAfterCollect = returnTo;
		
		List<Item> ores = getSelectedOres();
		
		if(ores.isEmpty())
		{
			ChatUtils.error("No ores selected!");
			setEnabled(false);
			return;
		}
		
		Item target = getNextOreInRotation();
		
		if(target == null)
		{
			ChatUtils.error(
				"AutoMineralMine: No orders found for any selected ore. Terminating.");
			setEnabled(false);
			return;
		}
		
		int totalCount = countAllSelectedOres();
		int desiredAmount = collectThreshold.getValueI() - totalCount;
		
		if(desiredAmount <= 0)
			return;
		
		orderCollector.reset();
		orderCollector.setSettings(maxSlotsToPull.getValueI(),
			minFillPct.getValueI(), false);
		
		orderCollector.queueCollect(target, desiredAmount);
		orderCollector.start();
		
		currentState = State.COLLECTING;
		
		ChatUtils.message("Collecting " + desiredAmount + "x "
			+ new ItemStack(target).getHoverName().getString() + "...");
	}
	
	private void handleCollecting()
	{
		orderCollector.tick();
		
		if(orderCollector.isFinished())
		{
			// If nothing was collected → try next ore
			if(orderCollector.getCollectedAmount() == 0)
			{
				beginCollect(stateAfterCollect);
				return;
			}
			
			// success → reset rotation attempts
			attemptsThisCycle = 0;
			
			finishCollect();
		}
	}
	
	private void finishCollect()
	{
		orderCollector.reset();
		currentState = stateAfterCollect;
		delayTimer = collectGuiDelay.getValueI() * 3;
		ChatUtils.message("AutoMineralMine: Collection finished. Resuming.");
	}
	
	private List<BlockPos> getEmptySpots()
	{
		List<BlockPos> spots = new ArrayList<>();
		int cx = targetCenterPos.getX();
		int cy = targetCenterPos.getY();
		int cz = targetCenterPos.getZ();
		
		for(int[] offset : PLACE_OFFSETS)
		{
			BlockPos p =
				new BlockPos(cx + offset[0], cy + offset[1], cz + offset[2]);
			if(BlockUtils.getState(p).canBeReplaced())
				spots.add(p);
		}
		return spots;
	}
	
	private int countInInventory(Item item)
	{
		if(MC.player == null || item == null)
			return 0;
		
		var inv = MC.player.getInventory();
		int count = 0;
		for(int i = 0; i < inv.getContainerSize(); i++)
		{
			ItemStack s = inv.getItem(i);
			if(!s.isEmpty() && s.getItem() == item)
				count += s.getCount();
		}
		return count;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(targetCenterPos == null)
			return;
		
		int cx = targetCenterPos.getX();
		int cy = targetCenterPos.getY();
		int cz = targetCenterPos.getZ();
		
		AABB box = new AABB(cx - 1, cy, cz - 0.05, cx + 2, cy + 3, cz + 0.05);
		RenderUtils.drawOutlinedBox(matrixStack, box, 0xC000FF00, false);
		
		BlockPos mineTarget = new BlockPos(cx, cy + 1, cz);
		RenderUtils.drawOutlinedBox(matrixStack, new AABB(mineTarget),
			0xC0FF0000, false);
	}
}
