package me.contaria.seedqueue.mixin.compat.atum;

import com.bawnorton.mixinsquared.TargetHandler;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.contaria.seedqueue.SeedQueue;
import me.voidxwalker.autoreset.AttemptTracker;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = CreateWorldScreen.class, priority = 1500)
public abstract class CreateWorldScreenMixin {

    @Dynamic
    @TargetHandler(
            mixin = "me.voidxwalker.autoreset.mixin.config.CreateWorldScreenMixin",
            name = "modifyAtumCreateWorldScreen"
    )
    @WrapOperation(
            method = "@MixinSquared:Handler",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/voidxwalker/autoreset/AttemptTracker;increment(Lme/voidxwalker/autoreset/AttemptTracker$Type;)I",
                    remap = false
            )
    )
    private int doNotIncrementAtumAttemptTracker_whenLoadingQueue(AttemptTracker tracker, AttemptTracker.Type type, Operation<Integer> original) {
        if (!SeedQueue.inQueue() && SeedQueue.loadEntry()) {
            return Integer.parseInt(SeedQueue.currentEntry.getServer().getSaveProperties().getLevelName().split("#")[1]);
        }
        return original.call(tracker, type);
    }

    @ModifyArg(
            method = "createLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MinecraftClient;createWorld(Ljava/lang/String;Lnet/minecraft/world/level/LevelInfo;Lnet/minecraft/util/registry/RegistryTracker$Modifiable;Lnet/minecraft/world/gen/GeneratorOptions;)V"
            )
    )
    private String fixWorldName(String worldName) {
        if (!SeedQueue.inQueue() && SeedQueue.currentEntry != null) {
            // because the world already exists when this method is called, a different worldName is passed (for example Random Speedrun #123 (1))
            // StandardSettings uses this variable to save standardoptions.txt to the worldfile, so we need to correct it
            return SeedQueue.currentEntry.getSession().getDirectoryName();
        }
        return worldName;
    }
}
