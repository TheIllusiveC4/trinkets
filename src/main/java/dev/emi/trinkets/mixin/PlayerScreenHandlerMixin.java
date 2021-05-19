package dev.emi.trinkets.mixin;

import com.mojang.datafixers.util.Function3;
import dev.emi.trinkets.TrinketPlayerScreenHandler;
import dev.emi.trinkets.TrinketSlot;
import dev.emi.trinkets.TrinketsClient;
import dev.emi.trinkets.api.SlotGroup;
import dev.emi.trinkets.api.SlotType;
import dev.emi.trinkets.api.Trinket.SlotReference;
import dev.emi.trinkets.api.TrinketInventory;
import dev.emi.trinkets.api.TrinketsApi;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adds trinket slots to the player's screen handler
 *
 * @author Emi
 */
@Mixin(PlayerScreenHandler.class)
public abstract class PlayerScreenHandlerMixin extends ScreenHandler implements TrinketPlayerScreenHandler {

	@Shadow @Final
	private PlayerEntity owner;

	@Unique
	private final Map<SlotGroup, Pair<Integer, Integer>> groupPos = new HashMap<>();
	@Unique
	private int trinketSlotStart = 0;
	@Unique
	private int trinketSlotEnd = 0;

	protected PlayerScreenHandlerMixin(ScreenHandlerType<?> type, int syncId) {
		super(type, syncId);
	}

	@Inject(at = @At("RETURN"), method = "<init>")
	public void init(PlayerInventory playerInv, boolean onServer, PlayerEntity owner, CallbackInfo info) {
		updateTrinketSlots();
	}

	@Override
	public void updateTrinketSlots() {
		groupPos.clear();

		while (trinketSlotStart < trinketSlotEnd) {
			slots.remove(trinketSlotStart);
			trinketSlotEnd--;
		}

		Map<Integer, SlotGroup> ids = new HashMap<>();
		for (SlotGroup group : TrinketsApi.getPlayerSlots().values()) {
			if (group.getSlotId() != -1) {
				ids.put(group.getSlotId(), group);
			}
		}

		for (Slot slot : this.slots) {
			if (ids.containsKey(slot.id) && slot.inventory instanceof PlayerInventory) {
				groupPos.put(ids.get(slot.id), new Pair<>(slot.x, slot.y));
			}
		}

		int groupNum = 1; // Start at 1 because offhand exists
		if (TrinketsApi.getPlayerSlots().containsKey("hand")) { // Hardcode the hand slot group to always be above the offhand, if it exists
			groupNum++;
			groupPos.put(TrinketsApi.getPlayerSlots().get("hand"), new Pair<>(77, 44));
		}

		for (SlotGroup group : TrinketsApi.getPlayerSlots().values()) {
			if (!groupPos.containsKey(group)) {
				int x = 77;
				int y;
				if (groupNum >= 4) {
					x = -4 - (groupNum / 4) * 18;
					y = 7 + (groupNum % 4) * 18;
				} else {
					y = 62 - groupNum * 18;
				}
				groupPos.put(group, new Pair<>(x, y));
				groupNum++;
			}
		}

		trinketSlotStart = slots.size();

		TrinketsApi.getTrinketComponent(owner).ifPresent(trinkets -> {
			TrinketInventory inv = trinkets.getInventory();
			inv.update();

			for (int i = 0; i < inv.size(); i++) {
				Pair<SlotType, Integer> p = inv.posMap.get(i);
				SlotGroup group = TrinketsApi.getPlayerSlots().get(p.getLeft().getGroup());
				int groupPos = inv.groupOffsetMap.get(p.getLeft()) + p.getRight();
				int groupAmount = inv.groupOccupancyMap.get(group);

				if (group.getSlotId() == -1) {
					groupAmount += 1;
					groupPos += 1;
				}

				groupPos = groupPos - groupAmount / 2;
				if (group.getSlotId() != -1 && groupPos >= 0) groupPos++;
				Pair<Integer, Integer> pos = getGroupPos(group);
				this.addSlot(new TrinketSlot(inv, i, pos.getLeft() + groupPos * 18, pos.getRight(), group, p.getLeft(), p.getRight(), groupPos == 0, p.getRight() == 0));
			}
		});

		trinketSlotEnd = slots.size();
	}
	
	@Override
	public Pair<Integer, Integer> getGroupPos(SlotGroup group) {
		return groupPos.get(group);
	}

	@Inject(at = @At("HEAD"), method = "close")
	private void close(PlayerEntity player, CallbackInfo info) {
		if (player.world.isClient) {
			TrinketsClient.activeGroup = null;
			TrinketsClient.activeType = null;
			TrinketsClient.quickMoveGroup = null;
		}
	}

	@Inject(at = @At("HEAD"), method = "transferSlot", cancellable = true)
	public void transferSlot(PlayerEntity player, int index, CallbackInfoReturnable<ItemStack> info) {
		Slot slot = slots.get(index);

		if (slot.hasStack()) {
			ItemStack stack = slot.getStack();
			if (index >= trinketSlotStart && index < trinketSlotEnd) {
				if (!this.insertItem(stack, 9, 45, false)) {
					info.setReturnValue(ItemStack.EMPTY);
				} else {
					info.setReturnValue(stack);
				}
			} else if (index >= 9 && index < 45) {
				TrinketsApi.getTrinketComponent(owner).ifPresent(comp -> {
					TrinketInventory inv = comp.getInventory();

					for (int i = 0; i < inv.size(); i++) {
						if (!slots.get(trinketSlotStart + i).canInsert(stack)) {
							continue;
						}

						Pair<SlotType, Integer> pair = inv.posMap.get(i);
						SlotType type = pair.getLeft();
						SlotReference ref = new SlotReference(type, pair.getRight());
						TriState state = TriState.DEFAULT;

						for (Identifier id : type.getValidators()) {
							Optional<Function3<ItemStack, SlotReference, LivingEntity, TriState>> function = TrinketsApi.getValidatorPredicate(id);
							if (function.isPresent()) {
								state = function.get().apply(stack, ref, owner);
							}
							if (state != TriState.DEFAULT) {
								break;
							}
						}

						if (state == TriState.DEFAULT) {
							Optional<Function3<ItemStack, SlotReference, LivingEntity, TriState>> quickMovePredicate =
									TrinketsApi.getQuickMovePredicate(new Identifier("trinkets", "always"));

							if (quickMovePredicate.isPresent()) {
								// FIXME: state is unused
								state = quickMovePredicate.get().apply(stack, ref, owner);
							}
						}

						if (this.insertItem(stack, trinketSlotStart + i, trinketSlotStart + i + 1, false)) {
							if (owner.world.isClient) {
								TrinketsClient.quickMoveTimer = 20;
								TrinketsClient.quickMoveGroup = TrinketsApi.getPlayerSlots().get(type.getGroup());

								if (ref.index > 0) {
									TrinketsClient.quickMoveType = type;
								}
							}
						}
					}
				});
			}
		}
	}
}
