package com.pauwma.glyphbeat.sound

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.pauwma.glyphbeat.services.notification.MediaNotificationListenerService
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow
import java.util.concurrent.CopyOnWriteArrayList

class MediaControlHelper(private val context: Context) {
    
    // State change callback interface
    interface StateChangeCallback {
        fun onPlaybackStateChanged(isPlaying: Boolean, hasActiveMedia: Boolean)
        fun onActiveAppChanged(packageName: String?, appName: String?)
    }
    
    private val mediaSessionManager: MediaSessionManager by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    
    private var activeController: MediaController? = null
    private var activeControllerCallback: MediaController.Callback? = null
    private val stateChangeCallbacks = CopyOnWriteArrayList<StateChangeCallback>()
    
    private var lastLoggedSessionCount = -1
    private var lastLoggedActivePackage: String? = null
    private var lastLoggedActiveState: Int? = null
    private var lastLoggedSessions = mutableMapOf<String, Int>()
    
    // Cache for isPlaying log to prevent duplicates
    private var lastIsPlayingLogState: Int? = null
    private var lastIsPlayingLogPackage: String? = null
    
    // Cache for callback logs to prevent duplicates
    private var lastCallbackLogState: Int? = null
    private var lastMetadataLogHasMedia: Boolean? = null
    
    // Cached state to avoid unnecessary callbacks
    private var cachedIsPlaying = false
    private var cachedHasActiveMedia = false
    private var cachedActiveApp: String? = null
    
    // Simple memory cache for loaded URI bitmaps (URI -> Bitmap)
    // Limited to 5 entries to avoid excessive memory usage
    private val uriImageCache = mutableMapOf<String, Bitmap>()
    private val maxCacheSize = 5
    
    /**
     * Register a callback to receive immediate state change notifications
     */
    fun registerStateChangeCallback(callback: StateChangeCallback) {
        stateChangeCallbacks.add(callback)
        Log.d(LOG_TAG, "State change callback registered, total callbacks: ${stateChangeCallbacks.size}")
    }
    
    /**
     * Unregister a state change callback
     */
    fun unregisterStateChangeCallback(callback: StateChangeCallback) {
        stateChangeCallbacks.remove(callback)
        Log.d(LOG_TAG, "State change callback unregistered, remaining callbacks: ${stateChangeCallbacks.size}")
    }
    
    /**
     * Notify all registered callbacks of state changes
     */
    private fun notifyStateChange(isPlaying: Boolean, hasActiveMedia: Boolean, activeApp: String? = null) {
        val stateChanged = isPlaying != cachedIsPlaying || hasActiveMedia != cachedHasActiveMedia
        val appChanged = activeApp != cachedActiveApp
        
        if (stateChanged || appChanged) {
            cachedIsPlaying = isPlaying
            cachedHasActiveMedia = hasActiveMedia
            cachedActiveApp = activeApp
            
            Log.d(LOG_TAG, "State changed - playing: $isPlaying, hasMedia: $hasActiveMedia, activeApp: $activeApp, notifying ${stateChangeCallbacks.size} callbacks")
            
            stateChangeCallbacks.forEach { callback ->
                try {
                    if (stateChanged) {
                        callback.onPlaybackStateChanged(isPlaying, hasActiveMedia)
                    }
                    if (appChanged) {
                        val appName = activeApp?.let { getAppNameFromPackage(it) }
                        callback.onActiveAppChanged(activeApp, appName)
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Error in state change callback: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Get app name from package name
     */
    fun getAppNameFromPackage(packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // Fallback to beautifying the package name
            when {
                packageName.contains("spotify", ignoreCase = true) -> "Spotify"
                packageName.contains("youtube", ignoreCase = true) -> "YouTube Music"
                packageName.contains("amazon", ignoreCase = true) -> "Amazon Music"
                packageName.contains("apple", ignoreCase = true) -> "Apple Music"
                packageName.contains("tidal", ignoreCase = true) -> "Tidal"
                packageName.contains("deezer", ignoreCase = true) -> "Deezer"
                packageName.contains("soundcloud", ignoreCase = true) -> "SoundCloud"
                packageName.contains("pandora", ignoreCase = true) -> "Pandora"
                else -> packageName.substringAfterLast('.').replaceFirstChar { 
                    if (it.isLowerCase()) it.titlecase() else it.toString() 
                }
            }
        }
    }
    
    fun getActiveMediaController(): MediaController? {
        try {
            // Pass our NotificationListenerService component to get proper permissions
            val componentName = ComponentName(context, MediaNotificationListenerService::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)
            
            // Only log session count when it changes
            if (activeSessions.size != lastLoggedSessionCount) {
                Log.d(LOG_TAG, "Found ${activeSessions.size} active sessions")
                lastLoggedSessionCount = activeSessions.size
            }
            
            // Log session states only when they change
            activeSessions.forEach { controller ->
                val packageName = controller.packageName
                val state = controller.playbackState?.state
                val lastState = lastLoggedSessions[packageName]
                
                if (state != lastState) {
                    Log.d(LOG_TAG, "Session $packageName: state changed $lastState -> $state")
                    lastLoggedSessions[packageName] = state ?: -1
                }
            }
            
            // First try to find actively playing sessions
            activeController = activeSessions.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state == PlaybackState.STATE_PLAYING
            }
            
            // If no playing sessions, look for recently active ones (paused/buffering with recent activity)
            if (activeController == null) {
                activeController = activeSessions.firstOrNull { controller ->
                    val state = controller.playbackState?.state
                    val hasMetadata = controller.metadata != null
                    val hasValidTitle = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true
                    
                    // Only consider paused/buffering sessions that have actual media content
                    (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING) && 
                    hasMetadata && hasValidTitle
                }
            }
            
            val currentPackage = activeController?.packageName
            val currentState = activeController?.playbackState?.state
            
            // Check if controller changed before updating logged values
            val controllerChanged = currentPackage != lastLoggedActivePackage
            
            // Only log active controller when package or state changes
            if (controllerChanged || currentState != lastLoggedActiveState) {
                Log.d(LOG_TAG, "Active controller: $currentPackage, state: $currentState")
                lastLoggedActivePackage = currentPackage
                lastLoggedActiveState = currentState
                
                // Register callback on new controller if it changed
                if (controllerChanged) {
                    Log.d(LOG_TAG, "Controller changed, registering new callback for: $currentPackage")
                    registerCallbackOnActiveController()
                } else {
                    // Log.v(LOG_TAG, "Controller unchanged: $currentPackage")
                }
            }
            
            // Notify state changes based on current controller state
            val isPlaying = currentState == PlaybackState.STATE_PLAYING
            val hasActiveMedia = activeController != null
            notifyStateChange(isPlaying, hasActiveMedia, currentPackage)
            
            return activeController
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Media control requires notification access permission - please grant notification access in settings")
            return null
        } catch (e: IllegalStateException) {
            Log.w(LOG_TAG, "Media session service not available - notification listener may not be connected")
            return null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error getting media sessions: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Get active media controller excluding specific packages
     */
    fun getActiveMediaControllerExcluding(excludedPackages: Set<String>): MediaController? {
        try {
            val componentName = ComponentName(context, MediaNotificationListenerService::class.java)
            val activeSessions = mediaSessionManager.getActiveSessions(componentName)
            
            // Filter out excluded packages
            val filteredSessions = activeSessions.filter { controller ->
                !excludedPackages.contains(controller.packageName)
            }
            
            // First try to find actively playing sessions from non-excluded packages
            val activeController = filteredSessions.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state == PlaybackState.STATE_PLAYING
            }
            
            // If no playing sessions, look for recently active ones (paused/buffering with recent activity)
            return activeController ?: filteredSessions.firstOrNull { controller ->
                val state = controller.playbackState?.state
                val hasMetadata = controller.metadata != null
                val hasValidTitle = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.isNotBlank() == true
                
                // Only consider paused/buffering sessions that have actual media content
                (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING) && 
                hasMetadata && hasValidTitle
            }
            
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Media control requires notification access permission")
            return null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error getting filtered media sessions: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Register callback on the currently active media controller
     */
    private fun registerCallbackOnActiveController() {
        // Unregister previous callback if exists
        activeControllerCallback?.let { callback ->
            try {
                // Note: We can't easily unregister without keeping the original controller reference
                // Log.v(LOG_TAG, "Previous callback will be cleaned up automatically")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Error cleaning up previous callback: ${e.message}")
            }
        }
        
        // Register new callback if we have an active controller
        activeController?.let { controller ->
            try {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                        val hasActiveMedia = activeController != null
                        
                        // Only log if state actually changed
                        if (state?.state != lastCallbackLogState) {
                            Log.v(LOG_TAG, "MediaController callback - state changed to: ${state?.state}, playing: $isPlaying")
                            lastCallbackLogState = state?.state
                        }
                        
                        notifyStateChange(isPlaying, hasActiveMedia, activeController?.packageName)
                    }
                    
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        // Track changes in media metadata which might affect active media status
                        val hasActiveMedia = activeController != null && metadata != null
                        
                        // Only log if hasActiveMedia status changed
                        if (hasActiveMedia != lastMetadataLogHasMedia) {
                            Log.v(LOG_TAG, "MediaController callback - metadata changed, hasActiveMedia: $hasActiveMedia")
                            lastMetadataLogHasMedia = hasActiveMedia
                        }
                        
                        notifyStateChange(cachedIsPlaying, hasActiveMedia, activeController?.packageName)
                    }
                }
                
                // Ensure callback registration happens on the main thread
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    controller.registerCallback(callback)
                    activeControllerCallback = callback
                    Log.d(LOG_TAG, "Registered callback on controller: ${controller.packageName}")
                } else {
                    // Post to main thread if not already on it
                    Handler(Looper.getMainLooper()).post {
                        try {
                            controller.registerCallback(callback)
                            activeControllerCallback = callback
                            Log.d(LOG_TAG, "Registered callback on controller (via main thread): ${controller.packageName}")
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "Failed to register callback on main thread: ${e.message}")
                            activeControllerCallback = null
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to register callback on controller: ${e.message}")
                activeControllerCallback = null
            }
        }
    }
    
    /**
     * Clean up resources and callbacks
     */
    fun cleanup() {
        stateChangeCallbacks.clear()
        activeControllerCallback = null
        activeController = null
        uriImageCache.clear() // Clear image cache to free memory
        Log.d(LOG_TAG, "MediaControlHelper cleaned up")
    }
    
    fun isPlaying(): Boolean {
        val controller = getActiveMediaController()
        val state = controller?.playbackState?.state
        val playing = state == PlaybackState.STATE_PLAYING
        
        // Only log if state or package has changed
        val currentPackage = controller?.packageName
        if (state != lastIsPlayingLogState || currentPackage != lastIsPlayingLogPackage) {
            Log.d(LOG_TAG, "isPlaying check: state=$state, playing=$playing, package=$currentPackage")
            lastIsPlayingLogState = state
            lastIsPlayingLogPackage = currentPackage
        }
        
        return playing
    }
    
    /**
     * Check if there are any active media sessions available
     */
    fun hasActiveMediaSession(blacklistedPackages: Set<String> = emptySet()): Boolean {
        return try {
            val componentName = ComponentName(context, MediaNotificationListenerService::class.java)
            val sessions = mediaSessionManager.getActiveSessions(componentName)
            
            // Filter out blacklisted packages
            val relevantSessions = sessions.filter { controller ->
                val packageName = controller.packageName
                !blacklistedPackages.contains(packageName) && (controller.playbackState != null || controller.metadata != null)
            }
            
            val hasMedia = relevantSessions.isNotEmpty()
            
            if (blacklistedPackages.isNotEmpty()) {
                val blacklistedSessions = sessions.filter { blacklistedPackages.contains(it.packageName) }
                Log.v(LOG_TAG, "hasActiveMediaSession check: ${sessions.size} total sessions, ${blacklistedSessions.size} blacklisted, ${relevantSessions.size} relevant, hasMedia: $hasMedia")
                if (blacklistedSessions.isNotEmpty()) {
                    Log.d(LOG_TAG, "Ignored blacklisted packages: ${blacklistedSessions.map { it.packageName }}")
                }
            } else {
                Log.v(LOG_TAG, "hasActiveMediaSession check: ${sessions.size} sessions, hasMedia: $hasMedia")
            }
            
            hasMedia
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error checking for active media sessions: ${e.message}")
            false
        }
    }
    
    fun togglePlayPause(): Boolean {
        val controller = getActiveMediaController()
        
        if (controller == null) {
            Log.w(LOG_TAG, "No active media controller found - trying fallback method")
            return sendMediaKeyEvent()
        }
        
        return try {
            val currentState = controller.playbackState?.state
            Log.d(LOG_TAG, "Current playback state: $currentState")
            
            when (currentState) {
                PlaybackState.STATE_PLAYING -> {
                    controller.transportControls.pause()
                    Log.d(LOG_TAG, "Sent pause command")
                    true
                }
                PlaybackState.STATE_PAUSED -> {
                    controller.transportControls.play()
                    Log.d(LOG_TAG, "Sent play command")
                    true
                }
                else -> {
                    controller.transportControls.play()
                    Log.d(LOG_TAG, "Sent play command (unknown state: $currentState)")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error toggling play/pause with MediaController, trying fallback", e)
            return sendMediaKeyEvent()
        }
    }
    
    private fun sendMediaKeyEvent(): Boolean {
        return try {
            val keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
            
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
            
            Log.d(LOG_TAG, "Sent media key event (play/pause)")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error sending media key event", e)
            false
        }
    }
    
    fun skipToNext(): Boolean {
        val controller = getActiveMediaController() ?: return false
        return try {
            controller.transportControls.skipToNext()
            Log.d(LOG_TAG, "Sent skip next command")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error skipping to next", e)
            false
        }
    }
    
    fun skipToPrevious(): Boolean {
        val controller = getActiveMediaController() ?: return false
        return try {
            controller.transportControls.skipToPrevious()
            Log.d(LOG_TAG, "Sent skip previous command")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error skipping to previous", e)
            false
        }
    }
    
    fun getCurrentVolume(): Int {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    
    fun getMaxVolume(): Int {
        return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
    
    fun setVolume(volume: Int): Boolean {
        return try {
            val clampedVolume = volume.coerceIn(0, getMaxVolume())
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                clampedVolume,
                AudioManager.FLAG_SHOW_UI
            )
            Log.d(LOG_TAG, "Set volume to $clampedVolume")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error setting volume", e)
            false
        }
    }
    
    fun increaseVolume(): Boolean {
        val currentVolume = getCurrentVolume()
        val maxVolume = getMaxVolume()
        return if (currentVolume < maxVolume) {
            setVolume(currentVolume + 1)
        } else false
    }
    
    fun decreaseVolume(): Boolean {
        val currentVolume = getCurrentVolume()
        return if (currentVolume > 0) {
            setVolume(currentVolume - 1)
        } else false
    }
    
    fun getTrackInfo(): TrackInfo? {
        val controller = getActiveMediaController() ?: return null
        val metadata = controller.metadata ?: return null
        
        return try {
            val albumArt = extractAlbumArt(metadata)
            TrackInfo(
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown",
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown",
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "Unknown",
                appName = controller.packageName ?: "Unknown",
                albumArt = albumArt
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error getting track info", e)
            null
        }
    }
    
    /**
     * Get track info with full resolution album art for UI display.
     * Unlike getTrackInfo(), this returns the original resolution album art.
     */
    fun getTrackInfoForUI(): TrackInfo? {
        val controller = getActiveMediaController() ?: return null
        val metadata = controller.metadata ?: return null
        
        return try {
            // Get full resolution album art - first try Bitmap metadata
            var albumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            
            // If no Bitmap found, try URI-based metadata
            if (albumArt == null) {
                val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                
                if (artUri != null) {
                    Log.d(LOG_TAG, "Loading full resolution album art from URI: $artUri")
                    albumArt = loadBitmapFromUri(artUri)
                }
            }
            
            TrackInfo(
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown",
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown",
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "Unknown",
                appName = controller.packageName ?: "Unknown",
                albumArt = albumArt
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error getting track info for UI", e)
            null
        }
    }
    
    /**
     * Extract album art from media metadata.
     * Supports both Bitmap metadata and URI-based metadata.
     * @param metadata MediaMetadata object containing track information
     * @return Bitmap of album art or null if not available
     */
    private fun extractAlbumArt(metadata: MediaMetadata): Bitmap? {
        return try {
            // First try to get album art from Bitmap metadata (fastest)
            var bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            
            // If no Bitmap found, try URI-based metadata
            if (bitmap == null) {
                val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                
                if (artUri != null) {
                    Log.d(LOG_TAG, "Found album art URI: $artUri")
                    bitmap = loadBitmapFromUri(artUri)
                }
            }
            
            bitmap?.let { originalBitmap ->
                // Downsample to 25x25 for the Glyph Matrix
                resizeBitmapToMatrix(originalBitmap)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error extracting album art: ${e.message}")
            null
        }
    }
    
    /**
     * Load bitmap from URI string.
     * Supports content:// URIs (local media) and http/https URLs (network images).
     * Uses caching to avoid repeated loads of the same image.
     * @param uriString URI string pointing to the image
     * @return Bitmap or null if loading fails
     */
    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        // Check cache first
        uriImageCache[uriString]?.let { cachedBitmap ->
            Log.d(LOG_TAG, "Using cached bitmap for URI: ${uriString.take(50)}...")
            return cachedBitmap
        }
        
        val bitmap = try {
            when {
                // Handle SoundCloud's special content URI format
                uriString.contains("com.soundcloud.android.imageProvider") -> {
                    extractAndLoadSoundCloudImage(uriString)
                }
                // Handle content:// URIs (local media)
                uriString.startsWith("content://") -> {
                    loadLocalBitmap(uriString)
                }
                // Handle http/https URLs (network images)
                uriString.startsWith("http://") || uriString.startsWith("https://") -> {
                    loadNetworkBitmap(uriString)
                }
                // Handle file:// URIs
                uriString.startsWith("file://") -> {
                    val path = Uri.parse(uriString).path
                    if (path != null) {
                        BitmapFactory.decodeFile(path)
                    } else null
                }
                else -> {
                    Log.w(LOG_TAG, "Unsupported URI scheme: $uriString")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error loading bitmap from URI: ${e.message}")
            null
        }
        
        // Add to cache if successful
        bitmap?.let {
            // Ensure cache doesn't grow too large
            if (uriImageCache.size >= maxCacheSize) {
                // Remove oldest entry (first in map)
                val oldestKey = uriImageCache.keys.firstOrNull()
                oldestKey?.let { key ->
                    uriImageCache.remove(key)
                    Log.d(LOG_TAG, "Removed oldest cache entry: ${key.take(50)}...")
                }
            }
            uriImageCache[uriString] = it
            Log.d(LOG_TAG, "Cached bitmap for URI: ${uriString.take(50)}...")
        }
        
        return bitmap
    }
    
    /**
     * Extract the real URL from SoundCloud's special content URI format and load the image.
     * SoundCloud encodes the actual image URL in query parameters.
     * Example: content://com.soundcloud.android.imageProvider/artworks-000142004856-njango-t500x500.jpg?_HOST_=i1.sndcdn.com&_PROTOCOL_=https
     * Becomes: https://i1.sndcdn.com/artworks-000142004856-njango-t500x500.jpg
     * @param uriString The SoundCloud content URI
     * @return Bitmap or null if loading fails
     */
    private fun extractAndLoadSoundCloudImage(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val host = uri.getQueryParameter("_HOST_")
            val protocol = uri.getQueryParameter("_PROTOCOL_") ?: "https"
            val path = uri.path
            
            if (host != null && path != null) {
                val realUrl = "$protocol://$host$path"
                Log.d(LOG_TAG, "Extracted SoundCloud image URL: $realUrl")
                loadNetworkBitmap(realUrl)
            } else {
                Log.w(LOG_TAG, "Could not extract URL from SoundCloud URI: $uriString")
                null
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error extracting SoundCloud image URL: ${e.message}")
            null
        }
    }
    
    /**
     * Load bitmap from local content:// URI.
     * @param uriString Content URI string
     * @return Bitmap or null if loading fails
     */
    private fun loadLocalBitmap(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error loading local bitmap: ${e.message}")
            null
        }
    }
    
    /**
     * Load bitmap from network URL.
     * Uses synchronous loading for simplicity, with timeout protection.
     * @param urlString HTTP/HTTPS URL string
     * @return Bitmap or null if loading fails
     */
    private fun loadNetworkBitmap(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                doInput = true
                connectTimeout = 5000 // 5 second timeout
                readTimeout = 5000 // 5 second timeout
                connect()
            }
            
            val input: InputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(input)
            input.close()
            connection.disconnect()
            
            Log.d(LOG_TAG, "Successfully loaded network image from: ${urlString.take(50)}...")
            bitmap
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error loading network bitmap: ${e.message}")
            null
        }
    }
    
    /**
     * Resize bitmap to grid size pixels for the Glyph Matrix display.
     * @param originalBitmap Source bitmap to resize
     * @return Resized bitmap or null if processing fails
     */
    private fun resizeBitmapToMatrix(originalBitmap: Bitmap): Bitmap? {
        return try {
            val gs = com.pauwma.glyphbeat.core.DeviceManager.resolution.gridSize
            val resized = Bitmap.createScaledBitmap(originalBitmap, gs, gs, true)
            resized
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error resizing album art: ${e.message}")
            null
        }
    }
    
    /**
     * Rotate a bitmap by the specified angle around its center.
     * Ensures the output remains 25x25 and properly centered.
     * @param bitmap Source bitmap to rotate (should be 25x25)
     * @param rotationAngle Rotation angle in degrees (0-360)
     * @return Rotated bitmap or original bitmap if rotation fails
     */
    private fun rotateBitmap(bitmap: Bitmap, rotationAngle: Float): Bitmap {
        if (rotationAngle == 0f) return bitmap
        
        return try {
            // Create a matrix for rotation around center
            val matrix = Matrix()
            matrix.postRotate(rotationAngle, bitmap.width / 2f, bitmap.height / 2f)
            
            // Create rotated bitmap with same dimensions to preserve centering
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888
            )
            
            // Draw the rotated bitmap onto the new canvas
            val canvas = Canvas(rotatedBitmap)
            canvas.drawBitmap(bitmap, matrix, null)
            
            // Log.v(LOG_TAG, "Bitmap rotated by ${rotationAngle}° (centered)")
            rotatedBitmap
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error rotating bitmap by ${rotationAngle}°: ${e.message}")
            bitmap // Return original bitmap on error
        }
    }
    
    /**
     * Convert a 25x25 bitmap to a matrix intensity array for the Glyph Matrix.
     * @param bitmap 25x25 bitmap to convert
     * @param brightnessMultiplier Multiplier for brightness (0.0-1.0), default 1.0
     * @param enhanceContrast Whether to apply contrast enhancement, default true
     * @param rotationAngle Rotation angle in degrees (0-360), default 0.0 (no rotation)
     * @return IntArray of 625 elements with intensity values (0-255)
     */
    fun bitmapToMatrixArray(bitmap: Bitmap?, brightnessMultiplier: Float = 1f, enhanceContrast: Boolean = true, rotationAngle: Float = 0f): IntArray {
        val gs = com.pauwma.glyphbeat.core.DeviceManager.resolution.gridSize
        if (bitmap == null || bitmap.width != gs || bitmap.height != gs) {
            Log.w(LOG_TAG, "Invalid bitmap for matrix conversion, using fallback pattern")
            return createFallbackPattern()
        }

        return try {
            // Apply rotation if specified
            val processedBitmap = if (rotationAngle != 0f) {
                rotateBitmap(bitmap, rotationAngle)
            } else {
                bitmap
            }

            val rawArray = IntArray(gs * gs)

            // First pass: Convert to grayscale and store raw luminance values
            for (row in 0 until gs) {
                for (col in 0 until gs) {
                    val pixel = processedBitmap.getPixel(col, row)

                    // Extract RGB components
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF

                    // Convert to grayscale using luminance formula
                    val luminance = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()

                    // Store raw luminance value
                    rawArray[row * gs + col] = luminance
                }
            }
            
            // Apply contrast enhancement if requested
            val processedArray = if (enhanceContrast) {
                enhanceContrast(rawArray)
            } else {
                rawArray
            }
            
            // Apply brightness multiplier and clamp to 0-255
            val finalArray = processedArray.map { luminance ->
                (luminance * brightnessMultiplier).toInt().coerceIn(0, 255)
            }.toIntArray()
            
            // Log.v(LOG_TAG, "Bitmap converted to matrix array (contrast: $enhanceContrast, brightness: $brightnessMultiplier)")
            finalArray
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error converting bitmap to matrix array: ${e.message}")
            createFallbackPattern()
        }
    }
    
    /**
     * Enhance contrast using histogram stretching and S-curve enhancement.
     * @param luminanceArray Array of luminance values (0-255)
     * @return Enhanced array with improved contrast
     */
    private fun enhanceContrast(luminanceArray: IntArray): IntArray {
        if (luminanceArray.isEmpty()) return luminanceArray
        
        // Find min and max values for histogram stretching
        val minLum = luminanceArray.minOrNull() ?: 0
        val maxLum = luminanceArray.maxOrNull() ?: 255
        
        // Avoid division by zero
        val range = (maxLum - minLum).coerceAtLeast(1)
        
        return luminanceArray.map { lum ->
            // Step 1: Histogram stretching - expand dynamic range
            val stretched = ((lum - minLum) * 255.0 / range).toInt().coerceIn(0, 255)
            
            // Step 2: Apply S-curve for more dramatic contrast
            // Maps 0->0, 128->128, 255->255, but creates steeper curve in between
            val normalized = stretched / 255.0
            val sCurve = applySCurve(normalized, 1.5) // Contrast factor of 1.5
            
            // Step 3: Final enhancement - push extreme values further apart
            val enhanced = when {
                sCurve < 0.3 -> sCurve * 0.7 // Darken shadows more
                sCurve > 0.7 -> 0.3 + (sCurve - 0.7) * 2.33 // Brighten highlights more  
                else -> 0.3 + (sCurve - 0.3) * 1.0 // Keep midtones relatively unchanged
            }
            
            (enhanced * 255).toInt().coerceIn(0, 255)
        }.toIntArray()
    }
    
    /**
     * Apply S-curve transformation for contrast enhancement.
     * @param x Normalized input value (0.0-1.0)
     * @param contrast Contrast factor (1.0 = no change, >1.0 = more contrast)
     * @return Enhanced value (0.0-1.0)
     */
    private fun applySCurve(x: Double, contrast: Double): Double {
        return if (x < 0.5) {
            (2 * x).pow(contrast) / 2
        } else {
            1 - (2 * (1 - x)).pow(contrast) / 2
        }
    }
    
    // =========================================================================
    // ENHANCED DETAIL PROCESSING
    // =========================================================================

    /**
     * Process full-resolution album art with enhanced detail preservation.
     *
     * Pipeline:
     * 1. Scale to intermediate size (~100px) to keep processing fast
     * 2. Unsharp mask at intermediate resolution to emphasize edges before downscale
     * 3. Downscale to grid size (25x25 / 13x13)
     * 4. Optionally fit within the diamond shape
     * 5. Apply rotation, grayscale conversion, contrast, brightness
     * 6. Multi-level Floyd-Steinberg dithering for perceptual detail
     *
     * @param fullResBitmap Full-resolution album art bitmap
     * @param brightnessMultiplier Brightness (0.0-1.0)
     * @param enhanceContrast Whether to apply contrast enhancement
     * @param rotationAngle Rotation in degrees
     * @param fitToGlyph Whether to fit the image inside the diamond shape
     * @param ditherLevels Number of brightness levels for dithering (2-16, default 8)
     * @return IntArray of pixel intensities for the Glyph Matrix
     */
    fun processFullResAlbumArt(
        fullResBitmap: Bitmap,
        brightnessMultiplier: Float = 1f,
        enhanceContrast: Boolean = true,
        rotationAngle: Float = 0f,
        fitToGlyph: Boolean = false,
        ditherLevels: Int = 8
    ): IntArray {
        val res = com.pauwma.glyphbeat.core.DeviceManager.resolution
        val gs = res.gridSize

        return try {
            // Step 1: Scale to intermediate resolution for unsharp masking
            // ~100px is enough to preserve edges while keeping processing fast
            val maxDim = maxOf(fullResBitmap.width, fullResBitmap.height)
            val intermediateSize = 100.coerceAtMost(maxDim)
            val intermediate = Bitmap.createScaledBitmap(
                fullResBitmap, intermediateSize, intermediateSize, true
            )

            // Step 2: Unsharp mask — emphasize edges before the extreme downscale
            val sharpened = applyUnsharpMask(intermediate)

            // Step 3: Downscale to final target size
            val targetSize = if (fitToGlyph) calculateGlyphFitSize(res) else gs
            val downscaled = Bitmap.createScaledBitmap(sharpened, targetSize, targetSize, true)

            // Step 4: If fitting to glyph, center on a black grid-sized canvas
            val gridBitmap = if (fitToGlyph && targetSize < gs) {
                val result = Bitmap.createBitmap(gs, gs, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                canvas.drawColor(Color.BLACK)
                val offset = (gs - targetSize) / 2f
                canvas.drawBitmap(downscaled, offset, offset, null)
                result
            } else {
                downscaled
            }

            // Step 5: Apply rotation
            val rotated = if (rotationAngle != 0f) {
                rotateBitmap(gridBitmap, rotationAngle)
            } else {
                gridBitmap
            }

            // Step 6: Convert to grayscale
            val rawArray = IntArray(gs * gs)
            for (row in 0 until gs) {
                for (col in 0 until gs) {
                    val pixel = rotated.getPixel(col, row)
                    val red = (pixel shr 16) and 0xFF
                    val green = (pixel shr 8) and 0xFF
                    val blue = pixel and 0xFF
                    rawArray[row * gs + col] = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()
                }
            }

            // Step 7: Contrast enhancement
            val contrastArray = if (enhanceContrast) enhanceContrast(rawArray) else rawArray

            // Step 8: Brightness
            val brightnessArray = contrastArray.map {
                (it * brightnessMultiplier).toInt().coerceIn(0, 255)
            }.toIntArray()

            // Step 9: Multi-level Floyd-Steinberg dithering
            applyFloydSteinbergDithering(brightnessArray, gs, gs, ditherLevels)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Enhanced processing failed, falling back: ${e.message}")
            bitmapToMatrixArray(
                Bitmap.createScaledBitmap(fullResBitmap, gs, gs, true),
                brightnessMultiplier, enhanceContrast, rotationAngle
            )
        }
    }

    /**
     * Find the largest centered square that fits entirely within the Glyph diamond shape.
     * Phone 3: 17  (rows 4-20 all have width ≥ 17)
     * Phone 4A: 9  (rows 2-10 all have width ≥ 9)
     */
    private fun calculateGlyphFitSize(
        res: com.pauwma.glyphbeat.core.GlyphResolution
    ): Int {
        val gs = res.gridSize
        val shape = res.shape
        for (s in gs downTo 1) {
            val startRow = (gs - s) / 2
            val endRow = startRow + s - 1
            var fits = true
            for (row in startRow..endRow) {
                if (row < 0 || row >= gs || shape[row] < s) {
                    fits = false
                    break
                }
            }
            if (fits) return s
        }
        return gs
    }

    /**
     * Unsharp masking: sharpen an image by subtracting a blurred version.
     * result = original + strength * (original − blurred)
     *
     * @param bitmap Source bitmap (any size, but ~100px is the sweet spot here)
     * @param strength How aggressively to sharpen (1.0 = subtle, 2.0 = strong)
     * @param blurRadius Box-blur radius in pixels
     */
    private fun applyUnsharpMask(
        bitmap: Bitmap,
        strength: Float = 1.5f,
        blurRadius: Int = 2
    ): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // Two-pass box blur as a fast Gaussian approximation
        val blurred = twoPassBoxBlur(pixels, w, h, blurRadius)

        // Combine: sharpen = original + strength * (original − blurred) per channel
        val result = IntArray(pixels.size)
        for (i in pixels.indices) {
            val oA = (pixels[i] shr 24) and 0xFF
            val oR = (pixels[i] shr 16) and 0xFF
            val oG = (pixels[i] shr 8) and 0xFF
            val oB = pixels[i] and 0xFF

            val bR = (blurred[i] shr 16) and 0xFF
            val bG = (blurred[i] shr 8) and 0xFF
            val bB = blurred[i] and 0xFF

            val nR = (oR + strength * (oR - bR)).toInt().coerceIn(0, 255)
            val nG = (oG + strength * (oG - bG)).toInt().coerceIn(0, 255)
            val nB = (oB + strength * (oB - bB)).toInt().coerceIn(0, 255)

            result[i] = (oA shl 24) or (nR shl 16) or (nG shl 8) or nB
        }

        val out = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Separable two-pass box blur (horizontal then vertical).
     * O(width * height * radius) — trivial at ~100px.
     */
    private fun twoPassBoxBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray {
        val temp = IntArray(pixels.size)
        val out = IntArray(pixels.size)

        // Horizontal pass
        for (y in 0 until height) {
            for (x in 0 until width) {
                var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, width - 1)
                    val px = pixels[y * width + nx]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                    count++
                }
                val a = (pixels[y * width + x] shr 24) and 0xFF
                temp[y * width + x] =
                    (a shl 24) or
                    ((rSum / count) shl 16) or
                    ((gSum / count) shl 8) or
                    (bSum / count)
            }
        }

        // Vertical pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                var rSum = 0; var gSum = 0; var bSum = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val px = temp[ny * width + x]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                    count++
                }
                val a = (temp[y * width + x] shr 24) and 0xFF
                out[y * width + x] =
                    (a shl 24) or
                    ((rSum / count) shl 16) or
                    ((gSum / count) shl 8) or
                    (bSum / count)
            }
        }

        return out
    }

    /**
     * Multi-level Floyd-Steinberg error-diffusion dithering.
     *
     * Quantizes to [levels] brightness steps and spreads the rounding error to
     * neighbouring pixels, creating perceptual half-tones that reveal detail
     * the raw grayscale values cannot show at this resolution.
     *
     * @param array   Pixel intensities 0-255 in row-major order
     * @param width   Grid width
     * @param height  Grid height
     * @param levels  Number of output brightness levels (2 = pure B&W, 8 = recommended)
     */
    private fun applyFloydSteinbergDithering(
        array: IntArray,
        width: Int,
        height: Int,
        levels: Int
    ): IntArray {
        val work = FloatArray(array.size) { array[it].toFloat() }
        val result = IntArray(array.size)
        val step = 255f / (levels - 1).coerceAtLeast(1)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val oldVal = work[idx].coerceIn(0f, 255f)

                // Quantize to the nearest allowed level
                val newVal = (Math.round(oldVal / step) * step).coerceIn(0f, 255f)
                result[idx] = newVal.toInt()

                val error = oldVal - newVal

                // Distribute error with Floyd-Steinberg coefficients
                if (x + 1 < width)
                    work[idx + 1] += error * 7f / 16f
                if (y + 1 < height) {
                    if (x - 1 >= 0)
                        work[(y + 1) * width + (x - 1)] += error * 3f / 16f
                    work[(y + 1) * width + x] += error * 5f / 16f
                    if (x + 1 < width)
                        work[(y + 1) * width + (x + 1)] += error * 1f / 16f
                }
            }
        }

        return result
    }

    /**
     * Create a fallback pattern when no album art is available.
     * Shows a simple music note pattern.
     * @return IntArray of 625 elements representing a music note
     */
    private fun createFallbackPattern(): IntArray {
        val res = com.pauwma.glyphbeat.core.DeviceManager.resolution
        val gs = res.gridSize
        val cx = res.center
        val pattern = IntArray(res.flatSize) { 0 }

        // Create a simple music note pattern in the center, scaled to resolution
        // Stem: vertical line from center
        val stemTop = (cx * 5 + 12) / 25
        val stemBottom = (cx * 14 + 12) / 25
        for (row in stemTop..stemBottom) {
            if (row in 0 until gs && cx in 0 until gs) {
                pattern[row * gs + cx] = 180
            }
        }
        // Note head: small oval below stem
        val headY = (cx * 15 + 12) / 25
        val headRadius = kotlin.math.max(1, (2 * gs + 12) / 25)
        for (dy in -1..1) {
            for (dx in -headRadius..headRadius) {
                val r = headY + dy
                val c = cx + dx
                if (r in 0 until gs && c in 0 until gs) {
                    pattern[r * gs + c] = 180
                }
            }
        }
        // Flag: diagonal from top of stem
        val flagLen = kotlin.math.max(2, (4 * gs + 12) / 25)
        for (i in 0 until flagLen) {
            val r = stemTop + i
            val c = cx + 1 + i / 2
            if (r in 0 until gs && c in 0 until gs) {
                pattern[r * gs + c] = 180
            }
        }

        return pattern
    }
    
    data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val appName: String,
        val albumArt: Bitmap? = null
    )
    
    private companion object {
        private val LOG_TAG = MediaControlHelper::class.java.simpleName
    }
}