/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.mojang.blaze3d.vertex.PoseStack;

import net.wurstclient.hacks.automineralmine.OrderCollector;
import net.wurstclient.hacks.automineralmine.SellHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
	// ---- Coordinates ----
	private final TextFieldSetting centerX = new TextFieldSetting("Center X",
		"X coordinate of the center of block formation.", "0",
		s -> s.matches("-?\\d+"));
	private final TextFieldSetting centerY = new TextFieldSetting("Center Y",
		"Y coordinate of the bottom layer of the block formation.", "0",
		s -> s.matches("-?\\d+"));
	private final TextFieldSetting centerZ = new TextFieldSetting("Center Z",
		"Z coordinate of the center of block formation.", "0",
		s -> s.matches("-?\\d+"));
	
	// ---- Placement ----
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
	
	// ---- Auto Collect ----
	private final CheckboxSetting autoCollect =
		new CheckboxSetting("Auto Collect Orders",
			"Automatically collect completed /orders when ore count drops below"
				+ " the threshold.",
			true);
	private final SliderSetting collectThreshold =
		new SliderSetting("Collect Threshold",
			"Trigger collection when total ore in inventory falls below this.",
			9, 1, 54, 1, ValueDisplay.INTEGER);
	private final SliderSetting minFillPct = new SliderSetting("Min Fill %",
		"Minimum fill percentage for an order to be collected.", 0, 0, 100, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting collectGuiDelay =
		new SliderSetting("Collect GUI Delay (ticks)",
			"Ticks to wait between GUI interactions during collection.", 6, 1,
			40, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxSlotsToPull = new SliderSetting(
		"Max Slots To Pull", "Maximum GUI slots to pull per collect cycle.", 10,
		1, 54, 1, ValueDisplay.INTEGER);
	
	// ---- Selling ----
	private final SliderSetting sellThreshold =
		new SliderSetting("Sell Threshold",
			"Number of processed ore to accumulate before starting to sell.",
			600, 1, 2500, 1, ValueDisplay.INTEGER);
	
	/**
	 * Minimum price per item (in raw $) to accept an order.
	 * For example, 4000 means only accept orders paying >= $4,000 each.
	 * Set to 0 to disable the filter.
	 */
	private final SliderSetting minSellThreshold =
		new SliderSetting("Min Sell Price ($)",
			"Minimum price per item to accept a sell order. "
				+ "Guards against profit loss from low-ball orders. "
				+ "0 = accept any price.",
			0, 0, 100_000, 100, ValueDisplay.INTEGER);
	
	/**
	 * Ticks to wait between each sell GUI action / screen transition.
	 * Higher values are safer on laggy servers; lower values are faster.
	 */
	private final SliderSetting sellTransitionDelay =
		new SliderSetting("Sell Transition Delay (ticks)",
			"Ticks to wait between sell GUI screen changes and actions. "
				+ "Increase on laggy servers.",
			6, 1, 60, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting useUsernameSellFilter = new CheckboxSetting(
		"Use Username Filter",
		"Filter /orders by a specific player username instead of item name.",
		true);
	private final TextFieldSetting usernameToSell = new TextFieldSetting(
		"Username Filter", "Username to filter sell orders by.", "FBTob1");
	
	// ---- Ore toggles ----
	private final CheckboxSetting useDiamond =
		new CheckboxSetting("Diamond Ore", "Use diamond ore.", true);
	private final CheckboxSetting useGold =
		new CheckboxSetting("Gold Ore", "Use gold ore.", true);
	private final CheckboxSetting useRedstone =
		new CheckboxSetting("Redstone Ore", "Use redstone ore.", false);
	
	// -------------------------------------------------------------------------
	// 3x3x2 placement offsets: front face (dz=0) then back face (dz=1)
	// -------------------------------------------------------------------------
	private static final int[][] PLACE_OFFSETS = {
		// dz=0 (front face)
		{-1, 0, 0}, {0, 0, 0}, {1, 0, 0}, {-1, 1, 0}, {0, 1, 0}, {1, 1, 0},
		{-1, 2, 0}, {0, 2, 0}, {1, 2, 0},
		// dz=1 (back face)
		{-1, 0, 1}, {0, 0, 1}, {1, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1},
		{-1, 2, 1}, {0, 2, 1}, {1, 2, 1}};
	
	private enum State
	{
		PLACING,
		MINING,
		COLLECTING,
		SELLING
	}
	
	private State currentState = State.PLACING;
	private State stateAfterCollect = State.PLACING;
	private State stateAfterSell = State.PLACING;
	private int delayTimer = 0;
	private final Random random = new Random();
	private BlockPos targetCenterPos = null;
	private BlockPos lockedPlacePos = null;
	private boolean transitionDelayApplied = false;
	private float currentYaw = 0;
	private float currentPitch = 0;
	private int collectRotationIndex = 0;
	
	// Mining layer: 0 = front face (dz=0), 1 = back face (dz=1)
	private int miningLayer = 0;
	
	private final OrderCollector orderCollector = new OrderCollector(MC);
	private final SellHandler sellHandler = new SellHandler(MC);
	
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
		addSetting(minFillPct);
		addSetting(collectGuiDelay);
		addSetting(maxSlotsToPull);
		addSetting(sellThreshold);
		addSetting(minSellThreshold);
		addSetting(sellTransitionDelay);
		addSetting(useUsernameSellFilter);
		addSetting(usernameToSell);
		addSetting(useDiamond);
		addSetting(useGold);
		addSetting(useRedstone);
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
		stateAfterSell = State.PLACING;
		delayTimer = 0;
		lockedPlacePos = null;
		transitionDelayApplied = false;
		collectRotationIndex = 0;
		miningLayer = 0;
		
		orderCollector.reset();
		sellHandler.reset();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	public void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		lockedPlacePos = null;
		miningLayer = 0;
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
			case PLACING -> handlePlacing();
			case MINING -> handleMining();
			case COLLECTING -> handleCollecting();
			case SELLING -> handleSelling();
		}
	}
	
	// =========================================================================
	// ORE HELPERS
	// =========================================================================
	
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
			ores.add(Items.RAW_GOLD);
		if(useRedstone.isChecked())
			ores.add(Items.REDSTONE);
		return ores;
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
	
	// =========================================================================
	// SELLING
	// =========================================================================
	
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
		
		// Pick the ore we have the most of
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
		sellHandler.setSettings(maxSlotsToPull.getValueI(),
			minFillPct.getValueI(), useUsernameSellFilter.isChecked());
		sellHandler.setMinSellThreshold(minSellThreshold.getValueI());
		sellHandler
			.setSellTransitionDelayTicks(sellTransitionDelay.getValueI());
		
		if(useUsernameSellFilter.isChecked())
			sellHandler.setUsernameFilter(usernameToSell.getValue());
		
		sellHandler.queueSell(target, amount);
		sellHandler.start();
		
		currentState = State.SELLING;
		
		ChatUtils.message("AutoMineralMine: Selling " + amount + "x "
			+ new ItemStack(target).getHoverName().getString()
			+ (minSellThreshold.getValueI() > 0
				? " (min $" + minSellThreshold.getValueI() + " each)" : "")
			+ "...");
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
	
	// =========================================================================
	// ROTATION
	// =========================================================================
	
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
	
	// =========================================================================
	// PLACING
	// =========================================================================
	
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
			if(emptySpots.size() < 18)
				transitionToMining();
			else
			{
				ChatUtils.error("Out of selected ores!");
				setEnabled(false);
			}
			return;
		}
		
		// If our locked pos is no longer empty or no longer safe, clear it
		if(lockedPlacePos != null && (!emptySpots.contains(lockedPlacePos)
			|| !hasSolidNeighbor(lockedPlacePos)))
			lockedPlacePos = null;
		
		// Pick the next safe spot, randomised within each layer
		if(lockedPlacePos == null)
		{
			lockedPlacePos = getNextSafePlacePos(emptySpots);
			if(lockedPlacePos == null)
			{
				// No safe placement available yet — wait a couple ticks
				delayTimer = 2;
				return;
			}
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
	
	// =========================================================================
	// MINING
	// =========================================================================
	
	private void handleMining()
	{
		closeAnyOpenContainer();
		
		if(shouldTriggerSell())
		{
			beginSell(State.MINING);
			return;
		}
		
		// Mine center of the current layer
		BlockPos targetPos =
			new BlockPos(targetCenterPos.getX(), targetCenterPos.getY() + 1,
				targetCenterPos.getZ() + (miningLayer == 0 ? 1 : 0)); // dz=1
																		// first,
																		// dz=0
																		// second
		
		if(BlockUtils.getState(targetPos).canBeReplaced())
		{
			// This layer's center is gone — advance or wrap back to placing
			if(miningLayer < 1)
			{
				miningLayer++;
				transitionDelayApplied = false;
				return;
			}
			// Both layers mined, restart the cycle
			miningLayer = 0;
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
		{
			swingHand.swing(InteractionHand.MAIN_HAND);
			// Once the block is gone, move to the next layer next tick
			if(BlockUtils.getState(targetPos).canBeReplaced())
			{
				miningLayer = (miningLayer < 1) ? miningLayer + 1 : 0;
				transitionDelayApplied = false;
				if(miningLayer == 0)
				{
					int min = transitionDelay.getValueI();
					int max = (int)(min * 1.5);
					delayTimer = min + random.nextInt(max - min + 1);
					currentState = State.PLACING;
				}
			}
		}
	}
	
	// =========================================================================
	// COLLECTING
	// =========================================================================
	
	private boolean shouldTriggerCollect()
	{
		if(!autoCollect.isChecked())
			return false;
		return countAllSelectedOres() < collectThreshold.getValueI();
	}
	
	private void beginCollect(State returnTo)
	{
		stateAfterCollect = returnTo;
		
		List<Item> candidates = getSelectedOres();
		if(candidates.isEmpty())
		{
			ChatUtils.error("No ores selected!");
			setEnabled(false);
			return;
		}
		
		int totalCount = countAllSelectedOres();
		int desiredAmount = collectThreshold.getValueI() - totalCount;
		
		if(desiredAmount <= 0)
			return;
		
		List<Item> rotatedCandidates =
			rotateCandidates(candidates, collectRotationIndex);
		collectRotationIndex = (collectRotationIndex + 1) % candidates.size();
		
		orderCollector.reset();
		orderCollector.setSettings(maxSlotsToPull.getValueI(),
			minFillPct.getValueI(), false);
		orderCollector.queueCollectAny(rotatedCandidates, desiredAmount);
		orderCollector.start();
		
		currentState = State.COLLECTING;
		ChatUtils.message("Collecting " + desiredAmount + "x ore(s)...");
	}
	
	private void handleCollecting()
	{
		orderCollector.tick();
		if(orderCollector.isFinished())
			finishCollect();
	}
	
	private void finishCollect()
	{
		orderCollector.reset();
		currentState = stateAfterCollect;
		delayTimer = collectGuiDelay.getValueI() * 3;
		ChatUtils.message("AutoMineralMine: Collection finished. Resuming.");
	}
	
	private List<Item> rotateCandidates(List<Item> items, int startIndex)
	{
		List<Item> rotated = new ArrayList<>();
		if(items == null || items.isEmpty())
			return rotated;
		
		int size = items.size();
		for(int i = 0; i < size; i++)
			rotated.add(items.get((startIndex + i) % size));
		
		return rotated;
	}
	
	// =========================================================================
	// PLACEMENT SAFETY HELPERS
	// =========================================================================
	
	/**
	 * Returns a random safe placement position, respecting layer order:
	 * the entire front face (dz=0) must be filled before any back face
	 * (dz=1) block is placed. Within the active layer, candidates are
	 * shuffled randomly so the fill pattern looks organic.
	 */
	private BlockPos getNextSafePlacePos(List<BlockPos> emptySpots)
	{
		int cz = targetCenterPos.getZ();
		
		// Split into front layer (dz=0) and back layer (dz=1)
		List<BlockPos> frontLayer = new ArrayList<>();
		List<BlockPos> backLayer = new ArrayList<>();
		
		for(BlockPos pos : emptySpots)
		{
			if(pos.getZ() == cz)
				frontLayer.add(pos);
			else
				backLayer.add(pos);
		}
		
		// Always complete front layer before touching back layer
		List<BlockPos> activeLayer =
			!frontLayer.isEmpty() ? frontLayer : backLayer;
		
		if(activeLayer.isEmpty())
			return null;
		
		// Collect only positions that have a solid neighbor to click against
		List<BlockPos> safe = new ArrayList<>();
		for(BlockPos pos : activeLayer)
			if(hasSolidNeighbor(pos))
				safe.add(pos);
			
		if(safe.isEmpty())
			return null;
		
		Collections.shuffle(safe, random);
		return safe.get(0);
	}
	
	/**
	 * Returns true if the position has at least one solid (non-replaceable)
	 * cardinal neighbor, meaning a block can legally be placed there.
	 */
	private boolean hasSolidNeighbor(BlockPos pos)
	{
		BlockPos[] neighbors = {pos.below(), pos.above(), pos.north(),
			pos.south(), pos.east(), pos.west()};
		for(BlockPos n : neighbors)
			if(!BlockUtils.getState(n).canBeReplaced())
				return true;
		return false;
	}
	
	// =========================================================================
	// MISC HELPERS
	// =========================================================================
	
	private void closeAnyOpenContainer()
	{
		if(MC.screen instanceof AbstractContainerScreen<?>)
			MC.player.closeContainer();
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
		
		// Outline covers both layers (dz=0 and dz=1)
		AABB box = new AABB(cx - 1, cy, cz - 0.05, cx + 2, cy + 3, cz + 1.05);
		RenderUtils.drawOutlinedBox(matrixStack, box, 0xC000FF00, false);
		
		// Highlight the current mining target
		BlockPos mineTarget =
			new BlockPos(cx, cy + 1, cz + (miningLayer == 0 ? 1 : 0));
		RenderUtils.drawOutlinedBox(matrixStack, new AABB(mineTarget),
			0xC0FF0000, false);
	}
}
