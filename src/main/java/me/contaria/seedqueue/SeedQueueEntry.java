package me.contaria.seedqueue;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import me.contaria.seedqueue.compat.ModCompat;
import me.contaria.seedqueue.compat.SeedQueuePreviewFrameBuffer;
import me.contaria.seedqueue.compat.SeedQueuePreviewProperties;
import me.contaria.seedqueue.compat.SeedQueueSettingsCache;
import me.contaria.seedqueue.debug.SeedQueueProfiler;
import me.contaria.seedqueue.interfaces.SQMinecraftServer;
import me.contaria.seedqueue.interfaces.SQWorldGenerationProgressTracker;
import me.contaria.seedqueue.mixin.accessor.MinecraftServerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.UserCache;
import net.minecraft.world.level.storage.LevelStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Stores the {@link MinecraftServer} and any other resources related to a seed in the queue.
 */
public class SeedQueueEntry {
    private final MinecraftServer server;

    private final LevelStorage.Session session;
    private final MinecraftClient.IntegratedResourceManager resourceManager;

    // will be created lazily when using wall, see MinecraftClientMixin
    @Nullable
    private final YggdrasilAuthenticationService yggdrasilAuthenticationService;
    @Nullable
    private final MinecraftSessionService minecraftSessionService;
    @Nullable
    private final GameProfileRepository gameProfileRepository;
    @Nullable
    private final UserCache userCache;

    @Nullable
    private WorldGenerationProgressTracker worldGenerationProgressTracker;
    @Nullable
    private SeedQueuePreviewProperties previewProperties;
    @Nullable
    private SeedQueuePreviewFrameBuffer frameBuffer;

    @Nullable
    private SeedQueueSettingsCache settingsCache;
    private int perspective;

    private volatile boolean locked;
    private volatile boolean loaded;
    private volatile boolean discarded;
    private volatile boolean maxWorldGenerationReached;

    /**
     * Stores the position (index) of the queue entry in the wall screen's main group.
     * A value of -1 indicates that this entry is not in the main group.
     */
    public int mainPosition = -1;

    public SeedQueueEntry(MinecraftServer server, LevelStorage.Session session, MinecraftClient.IntegratedResourceManager resourceManager, @Nullable YggdrasilAuthenticationService yggdrasilAuthenticationService, @Nullable MinecraftSessionService minecraftSessionService, @Nullable GameProfileRepository gameProfileRepository, @Nullable UserCache userCache) {
        this.server = server;
        this.session = session;
        this.resourceManager = resourceManager;
        this.yggdrasilAuthenticationService = yggdrasilAuthenticationService;
        this.minecraftSessionService = minecraftSessionService;
        this.gameProfileRepository = gameProfileRepository;
        this.userCache = userCache;

        ((SQMinecraftServer) server).seedQueue$setEntry(this);
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public LevelStorage.Session getSession() {
        return this.session;
    }

    public MinecraftClient.IntegratedResourceManager getResourceManager() {
        return this.resourceManager;
    }

    public @Nullable YggdrasilAuthenticationService getYggdrasilAuthenticationService() {
        return this.yggdrasilAuthenticationService;
    }

    public @Nullable MinecraftSessionService getMinecraftSessionService() {
        return this.minecraftSessionService;
    }

    public @Nullable GameProfileRepository getGameProfileRepository() {
        return this.gameProfileRepository;
    }

    public @Nullable UserCache getUserCache() {
        return this.userCache;
    }

    public @Nullable WorldGenerationProgressTracker getWorldGenerationProgressTracker() {
        return this.worldGenerationProgressTracker;
    }

    public void setWorldGenerationProgressTracker(@Nullable WorldGenerationProgressTracker worldGenerationProgressTracker) {
        this.worldGenerationProgressTracker = worldGenerationProgressTracker;
    }

    public @Nullable SeedQueuePreviewProperties getPreviewProperties() {
        return this.previewProperties;
    }

    public synchronized void setPreviewProperties(@Nullable SeedQueuePreviewProperties previewProperties) {
        this.previewProperties = previewProperties;
    }

    public SeedQueuePreviewFrameBuffer getFrameBuffer() {
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw new IllegalStateException("Tried to get WorldPreviewFrameBuffer off-thread!");
        }
        if (this.frameBuffer == null) {
            SeedQueueProfiler.push("create_framebuffer");
            this.frameBuffer = new SeedQueuePreviewFrameBuffer();
            SeedQueueProfiler.pop();
        }
        return this.frameBuffer;
    }

    public boolean hasFrameBuffer() {
        return this.frameBuffer != null;
    }

    /**
     * Deletes and removes this entry's framebuffer.
     *
     * @see SeedQueuePreviewFrameBuffer#discard
     */
    public void discardFrameBuffer() {
        if (!MinecraftClient.getInstance().isOnThread()) {
            throw new RuntimeException("Tried to discard WorldPreviewFrameBuffer off-thread!");
        }
        if (this.frameBuffer != null) {
            this.frameBuffer.discard();
            this.frameBuffer = null;
        }
    }

    /**
     * @return True if this entry has either {@link SeedQueuePreviewProperties} or a {@link SeedQueuePreviewFrameBuffer}.
     */
    public boolean hasWorldPreview() {
        return this.previewProperties != null || this.frameBuffer != null;
    }

    public @Nullable SeedQueueSettingsCache getSettingsCache() {
        return this.settingsCache;
    }

    /**
     * Sets the settings cache to be loaded when loading this entry.
     *
     * @throws IllegalStateException If this method is called but {@link SeedQueueEntry#previewProperties} is null.
     */
    public void setSettingsCache(SeedQueueSettingsCache settingsCache) {
        if (this.previewProperties == null) {
            throw new IllegalStateException("Tried to set SettingsCache but SeedQueuePreviewProperties is null!");
        }
        this.settingsCache = settingsCache;
        this.settingsCache.loadPlayerModelParts(this.previewProperties.player);
        this.perspective = this.previewProperties.getPerspective();
    }

    /**
     * Loads this entry's {@link SeedQueueEntry#settingsCache} and {@link SeedQueueEntry#perspective}.
     *
     * @return True if this entry has a settings cache which was loaded.
     */
    public boolean loadSettingsCache() {
        if (this.settingsCache != null) {
            this.settingsCache.load();
            MinecraftClient.getInstance().options.perspective = this.getPerspective();
            return true;
        }
        return false;
    }

    /**
     * @return The perspective used in the preview of this entry.
     */
    public int getPerspective() {
        return this.perspective;
    }

    /**
     * Checks if this entry should pause.
     * <p>
     * Returns true if:
     * <p>
     * - the entry has finished world generation
     * <p>
     * - the entry has reached the {@link SeedQueueConfig#maxWorldGenerationPercentage} and is not locked
     * <p>
     * - the entry has been scheduled to pause by the {@link SeedQueueThread}
     *
     * @return If this entry's {@link MinecraftServer} should pause in its current state.
     * @see SQMinecraftServer#seedQueue$shouldPause
     */
    public boolean shouldPause() {
        return ((SQMinecraftServer) this.server).seedQueue$shouldPause();
    }

    /**
     * @return If the entry is currently paused.
     * @see SQMinecraftServer#seedQueue$isPaused
     * @see SeedQueueEntry#shouldPause
     */
    public boolean isPaused() {
        return ((SQMinecraftServer) this.server).seedQueue$isPaused();
    }

    /**
     * @return If the entry has been scheduled to pause by the {@link SeedQueueThread} but hasn't been paused yet.
     * @see SQMinecraftServer#seedQueue$isScheduledToPause
     * @see SeedQueueEntry#shouldPause
     */
    public boolean isScheduledToPause() {
        return ((SQMinecraftServer) this.server).seedQueue$isScheduledToPause();
    }

    /**
     * Schedules this entry to be paused.
     *
     * @see SQMinecraftServer#seedQueue$schedulePause
     */
    public void schedulePause() {
        ((SQMinecraftServer) this.server).seedQueue$schedulePause();
    }

    /**
     * @return True if the entry is not currently paused or scheduled to pause.
     */
    public boolean canPause() {
        return !this.isScheduledToPause() && !this.isPaused();
    }

    /**
     * Unpauses this entry.
     *
     * @see SQMinecraftServer#seedQueue$unpause
     */
    public void unpause() {
        ((SQMinecraftServer) this.server).seedQueue$unpause();
    }

    /**
     * An entry can be unpaused if:
     * <p>
     * - it was paused by reaching the {@link SeedQueueConfig#maxWorldGenerationPercentage} but has been locked since
     * <p>
     * - it was scheduled to be paused by the {@link SeedQueueThread}
     *
     * @return True if this entry is currently paused or scheduled to be paused and is allowed to be unpaused.
     */
    public boolean canUnpause() {
        return this.isScheduledToPause() || (this.isPaused() && !this.shouldPause());
    }

    /**
     * @return True if the entry was paused and has now been successfully unpaused.
     * @see SeedQueueEntry#unpause
     * @see SeedQueueEntry#canUnpause
     */
    public boolean tryToUnpause() {
        synchronized (this.server) {
            if (this.canUnpause()) {
                this.unpause();
                return true;
            }
            return false;
        }
    }

    /**
     * @return True if the {@link MinecraftServer} has fully finished generation and is ready to be joined by the player.
     */
    public boolean isReady() {
        return this.server.isLoading();
    }

    /**
     * @see SeedQueueEntry#lock
     */
    public boolean isLocked() {
        return this.locked;
    }

    /**
     * @return True if the {@link MinecraftServer} has not reached {@link SeedQueueConfig#maxWorldGenerationPercentage}.
     */
    public boolean isMaxWorldGenerationReached() {
        return this.maxWorldGenerationReached;
    }

    /**
     * Marks this entry as having reached {@link SeedQueueConfig#maxWorldGenerationPercentage}.
     */
    public void setMaxWorldGenerationReached() {
        this.maxWorldGenerationReached = true;
    }

    /**
     * Locks this entry from being mass-reset on the Wall Screen.
     * Mass Resets include Reset All, Focus Reset, Reset Row, Reset Column.
     *
     * @return True if the entry was not locked before.
     */
    public boolean lock() {
        if (!this.locked) {
            this.locked = true;
            SeedQueue.ping();
            return true;
        }
        return false;
    }

    /**
     * @see SeedQueueEntry#load
     */
    public boolean isLoaded() {
        return this.loaded;
    }

    /**
     * Marks this entry as loaded and discards its framebuffer.
     */
    public synchronized void load() {
        synchronized (this.server) {
            if (this.discarded) {
                throw new IllegalStateException("Tried to load \"" + this.session.getDirectoryName() + "\" but it has already been discarded!");
            }

            this.loaded = true;

            SeedQueueProfiler.push("discard_framebuffer");
            this.discardFrameBuffer();

            SeedQueueProfiler.swap("unpause");
            this.unpause();
            SeedQueueProfiler.pop();
        }
    }

    /**
     * @see SeedQueueEntry#discard
     */
    public boolean isDiscarded() {
        return this.discarded;
    }

    /**
     * Discards this entry and all the resources attached to it, including shutting down the {@link MinecraftServer}.
     */
    public synchronized void discard() {
        synchronized (this.server) {
            if (this.discarded) {
                SeedQueue.LOGGER.warn("Tried to discard \"{}\" but it has already been discarded!", this.session.getDirectoryName());
                return;
            }

            SeedQueue.LOGGER.info("Discarding \"{}\"...", this.session.getDirectoryName());

            this.discarded = true;

            SeedQueueProfiler.push("discard_framebuffer");
            this.discardFrameBuffer();

            SeedQueueProfiler.swap("stop_server");
            if (!ModCompat.worldpreview$kill(this.server)) {
                ModCompat.fastReset$fastReset(this.server);
                ((MinecraftServerAccessor) this.server).seedQueue$setRunning(false);
            }
            SeedQueueProfiler.swap("unpause");
            this.unpause();
            SeedQueueProfiler.pop();
        }
    }

    /**
     * @return The world generation progress percentage for this entry based on an improved calculation in {@link SQWorldGenerationProgressTracker}.
     */
    public int getProgressPercentage() {
        // doubtful this will happen, but the field is @Nullable
        if (this.worldGenerationProgressTracker == null) {
            return 0;
        }
        return ((SQWorldGenerationProgressTracker) this.worldGenerationProgressTracker).seedQueue$getProgressPercentage();
    }
}
