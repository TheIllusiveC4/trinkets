package dev.emi.trinkets.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.emi.trinkets.TrinketSlot;
import dev.emi.trinkets.TrinketsClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws trinket slot backs, adjusts z location of draw calls, and makes non-trinket slots un-interactable while a trinket slot group is focused
 * 
 * @author Emi
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
	@Unique
	private static final Identifier MORE_SLOTS = new Identifier("trinkets", "textures/gui/more_slots.png");
	@Unique
	private static final Identifier BLANK_BACK = new Identifier("trinkets", "textures/gui/blank_back.png");

	protected HandledScreenMixin(Text title) {
		super(title);
	}

	@Inject(at = @At(value = "FIELD", target = "Lnet/minecraft/client/render/item/ItemRenderer;zOffset:F",
			opcode = Opcodes.PUTFIELD, ordinal = 0, shift = Shift.AFTER), method = "drawSlot")
	private void changeZ(MatrixStack matrices, Slot slot, CallbackInfo info) {
		// Items are drawn at z + 150 (normal items are drawn at 250)
		// Item tooltips (count, item bar) are drawn at z + 200 (normal itmes are drawn at 300)
		// Inventory tooltip is drawn at 400
		if (slot instanceof TrinketSlot) {
			assert this.client != null;
			TrinketSlot ts = (TrinketSlot) slot;
			Identifier id = ts.getBackgroundIdentifier();

			if (slot.getStack().isEmpty() && id != null) {
				// TODO apply this transformation at parse?
				this.client.getTextureManager().bindTexture(new Identifier(id.getNamespace(), "textures/" + id.getPath() + ".png"));
			} else {
				this.client.getTextureManager().bindTexture(BLANK_BACK);
			}

			RenderSystem.enableDepthTest();

			if (ts.isTrinketFocused()) {
				// Thus, I need to draw trinket slot backs over normal items at z 300 (310 was chosen)
				drawTexture(matrices, slot.x, slot.y, 310, 0, 0, 16, 16, 16, 16);
				// I also need to draw items in trinket slots *above* 310 but *below* 400, (320 for items and 370 for tooltips was chosen)
				this.itemRenderer.zOffset = 170F;
			} else {
				drawTexture(matrices, slot.x, slot.y, 0, 0, 0, 16, 16, 16, 16);
				this.client.getTextureManager().bindTexture(MORE_SLOTS);
				drawTexture(matrices, slot.x - 1, slot.y - 1, 0, 4, 4, 18, 18, 256, 256);
			}
		}
	}

	@Inject(at = @At("HEAD"), method = "isPointOverSlot", cancellable = true)
	private void isPointOverSlot(Slot slot, double pointX, double pointY, CallbackInfoReturnable<Boolean> info) {
		if (TrinketsClient.activeGroup != null) {
			if (slot instanceof TrinketSlot) {
				if (!((TrinketSlot) slot).isTrinketFocused()) {
					info.setReturnValue(false);
				}
			} else {
				if (!(slot.inventory instanceof PlayerInventory) || slot.id != TrinketsClient.activeGroup.getSlotId()) {
					info.setReturnValue(false);
				}
			}
		}
	}
}
