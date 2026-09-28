package ca.fuwafuwa.kaku;

import shiroikuma.kaku.KakuToast;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import ca.fuwafuwa.kaku.Interfaces.Stoppable;
import ca.fuwafuwa.kaku.Windows.Window;
import ca.fuwafuwa.kaku.Windows.WindowCoordinator;
import shiroikuma.kaku.ProjectionConsentActivity;

import static androidx.core.app.NotificationCompat.FLAG_FOREGROUND_SERVICE;
import static androidx.core.app.NotificationCompat.FLAG_ONGOING_EVENT;

/**
 * Created by 0xbad1d3a5 on 4/9/2016.
 */
public class MainService extends Service implements Stoppable {

    private static final String TAG = MainService.class.getName();

    public static class CloseMainService extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "GOT CLOSE");
            context.stopService(new Intent(context, MainService.class));
        }
    }

    public static class ToggleImagePreviewMainService extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            SharedPreferences prefs = context.getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
            boolean imagePreview = prefs.getBoolean(Constants.KAKU_PREF_IMAGE_FILTER, true);
            prefs.edit().putBoolean(Constants.KAKU_PREF_IMAGE_FILTER, !imagePreview).apply();

            KakuTools.startKakuService(context, new Intent(context, MainService.class));
        }
    }

    public static class ToggleShowHideMainService extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            SharedPreferences prefs = context.getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
            boolean shown = prefs.getBoolean(Constants.KAKU_PREF_SHOW_HIDE, true);
            prefs.edit().putBoolean(Constants.KAKU_PREF_SHOW_HIDE, !shown).apply();

            KakuTools.startKakuService(context, new Intent(context, MainService.class));
        }
    }

    public static class TogglePageModeMainService extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            SharedPreferences prefs = context.getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
            TextDirection textDirection = TextDirection.valueOf(prefs.getString(Constants.KAKU_PREF_TEXT_DIRECTION, TextDirection.AUTO.toString()));
            textDirection = TextDirection.Companion.getByValue((textDirection.ordinal() + 1) % 3);
            prefs.edit().putString(Constants.KAKU_PREF_TEXT_DIRECTION, textDirection.toString()).apply();

            KakuTools.startKakuService(context, new Intent(context, MainService.class));
        }
    }

    public static class ToggleInstantModeMainService extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            SharedPreferences prefs = context.getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
            boolean pageMode = prefs.getBoolean(Constants.KAKU_PREF_INSTANT_MODE, true);
            prefs.edit().putBoolean(Constants.KAKU_PREF_INSTANT_MODE, !pageMode).apply();

            KakuTools.startKakuService(context, new Intent(context, MainService.class));
        }
    }

    public static class ScreenOffReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            SharedPreferences prefs = context.getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(Constants.KAKU_PREF_SHOW_HIDE, false).apply();

            KakuTools.startKakuService(context, new Intent(context, MainService.class));
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback{
        @Override
        public void onStop(){
            Log.d(TAG, "Stopping projection");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (MediaProjectionStopCallback.this == mMediaProjectionStopCallback){
                        if (mVirtualDisplay != null){
                            mVirtualDisplay.release();
                            mVirtualDisplay = null;
                        }
                        mMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                        mMediaProjection = null;
                        if (mImageReader != null){
                            mImageReader.close();
                            mImageReader = null;
                        }
                    }
                }
            });
        }
    }

    private static boolean isKakuRunning = false;

    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int NOTIFICATION_ID = 1;

    private IntentFilter mIntentFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    private ScreenOffReceiver mScreenOffReceiver = new ScreenOffReceiver();

    private Intent mProjectionResultIntent;
    private int mProjectionResultCode;
    // A screen-capture consent is single-use from Android 14 on: once getMediaProjection() has
    // consumed it, a new projection needs a new consent (ProjectionConsentActivity).
    private boolean mProjectionTokenUsed = false;

    private WindowManager mWindowManager;
    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private ImageReader mImageReader;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private MainServiceHandler mHandler;

    private int mRotation;
    private Point mRealDisplaySize = new Point();
    private DisplayManager mDisplayManager;
    // Rotation alone misses changes on the Mate XT (unfolding, and rotations the service is not
    // told about as a rotation): react to any change of the default display's size too.
    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener()
    {
        @Override public void onDisplayAdded(int displayId) {}
        @Override public void onDisplayRemoved(int displayId) {}
        @Override public void onDisplayChanged(int displayId)
        {
            if (displayId == Display.DEFAULT_DISPLAY)
            {
                onDisplayGeometryMaybeChanged();
            }
        }
    };

    private MediaProjectionStopCallback mMediaProjectionStopCallback;
    private WindowCoordinator mWindowCoordinator = new WindowCoordinator(this);

    @Override
    public IBinder onBind(Intent intent)
    {
        // Not used
        return null;
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        if (!isKakuRunning)
        {
            SharedPreferences prefs = getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(Constants.KAKU_PREF_SHOW_HIDE, true).apply();
        }

        Log.d(TAG, "CREATING MAINSERVICE: " + System.identityHashCode(this));
        KakuToast.show(this, getString(R.string.service_starting), true);

        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mDisplayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        mHandler = new MainServiceHandler(this, mWindowCoordinator);

        ContextCompat.registerReceiver(this, mScreenOffReceiver, mIntentFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        // The service only starts after MainActivity (or ProjectionConsentActivity) obtained the
        // screen-capture consent, as Android 14 requires for a mediaProjection-typed service.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, getNotification(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0);
        isKakuRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Log.d(TAG, "onStartCommand");

        if (intent.getExtras() != null &&
            intent.getExtras().containsKey(Constants.EXTRA_PROJECTION_RESULT_CODE) &&
            intent.getExtras().containsKey(Constants.EXTRA_PROJECTION_RESULT_INTENT))
        {
            mProjectionResultIntent = (Intent) intent.getExtras().get(Constants.EXTRA_PROJECTION_RESULT_INTENT);
            mProjectionResultCode = intent.getExtras().getInt(Constants.EXTRA_PROJECTION_RESULT_CODE);
            mProjectionTokenUsed = false;
        }

        // Determine if we need to start/stop the capture service
        SharedPreferences prefs = getSharedPreferences(Constants.KAKU_PREF_FILE, Context.MODE_PRIVATE);
        Boolean shown = prefs.getBoolean(Constants.KAKU_PREF_SHOW_HIDE, true);
        if (shown)
        {
            // Re-init CaptureWindow as well as prefs may have changed (BroadcastReceiver go to onStartCommand())
            mWindowCoordinator.getWindow(Constants.WINDOW_CAPTURE).reInit(new Window.ReinitOptions());
        }
        else
        {
            mWindowCoordinator.stopAllWindows();
            stop();
        }

        // Set notification text
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, getNotification());

        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig)
    {
        super.onConfigurationChanged(newConfig);
        onDisplayGeometryMaybeChanged();
    }

    /**
     * Re-fit the capture surface and every window when the screen's rotation or size changed
     * (rotation, fold / unfold).
     */
    private void onDisplayGeometryMaybeChanged()
    {
        if (mWindowCoordinator == null || !mWindowCoordinator.hasWindow(Constants.WINDOW_CAPTURE))
        {
            return;
        }

        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        display.getRealSize(size);
        final int rotation = display.getRotation();

        if (rotation != mRotation || !size.equals(mRealDisplaySize))
        {
            Log.d(TAG, String.format("Display changed: rotation %d, %dx%d", rotation, size.x, size.y));
            mRotation = rotation;
            if (mMediaProjection != null)
            {
                createVirtualDisplay();
            }
            else
            {
                mRealDisplaySize.set(size.x, size.y);
            }
            mWindowCoordinator.reinitAllWindows();
        }
    }

    @Override
    public void onDestroy()
    {
        unregisterReceiver(mScreenOffReceiver);
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        stopForeground(true);
        Log.d(TAG, "DESTORYING MAINSERVICE: " + System.identityHashCode(this));

        stop();
        mWindowCoordinator.stopAllWindows();
        mWindowCoordinator = null;
        isKakuRunning = false;

        Log.d(TAG, String.format("MAINSERVICE: %s DESTROYED", System.identityHashCode(this)));
        super.onDestroy();
    }

    @Override
    public void stop()
    {
        if (mMediaProjection != null)
        {
            mMediaProjection.stop();
        }
    }

    public static boolean IsRunning()
    {
        return isKakuRunning;
    }

    /**
     * This function is here as a bug fix against {@link #onConfigurationChanged(Configuration)} not
     * triggering when the app is first started and immediately switches to another orientation. In
     * such a case onConfigurationChanged will not trigger and {@link Window#reInit(ca.fuwafuwa.kaku.Windows.Window.ReinitOptions)} will not
     * update the LayoutParams.
     */
    public void onCaptureWindowFinishedInitializing()
    {
        if (mMediaProjection == null){
            Log.d(TAG, "mMediaProjection is null");
            if (mProjectionResultIntent == null || (mProjectionTokenUsed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)){
                // The consent was spent (screen turned off, or the system stopped the projection):
                // ask again; its result restarts this service with a fresh token.
                ProjectionConsentActivity.request(this);
                return;
            }
            mMediaProjection = mMediaProjectionManager.getMediaProjection(mProjectionResultCode, mProjectionResultIntent);
            mProjectionTokenUsed = true;
            mMediaProjectionStopCallback = new MediaProjectionStopCallback();
            mMediaProjection.registerCallback(mMediaProjectionStopCallback, mHandler);
        }
        createVirtualDisplay();
    }

    public Handler getHandler()
    {
        return mHandler;
    }

    public Image getScreenshot() throws InterruptedException
    {
        long startTime = System.nanoTime();
        Image image = mImageReader.acquireLatestImage();
        while (image == null && System.nanoTime() < startTime + 2000000000){
            Thread.sleep(20);
            image = mImageReader.acquireLatestImage();
        }
        return image;
    }

    private Notification getNotification()
    {
        String channelId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            channelId = createNotificationChannel();
        }
        else {
            channelId = "";
        }

        PendingIntent toggleShowHide = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_TOGGLE_SHOW_HIDE, new Intent(this, ToggleShowHideMainService.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggleImagePreview = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_TOGGLE_IMAGE_PREVIEW, new Intent(this, ToggleImagePreviewMainService.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent togglePageMode = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_TOGGLE_PAGE_MODE, new Intent(this, TogglePageModeMainService.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggleInstantMode = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_TOGGLE_INSTANT_MODE, new Intent(this, ToggleInstantModeMainService.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent closeMainService = PendingIntent.getBroadcast(this, Constants.REQUEST_SERVICE_SHUTDOWN, new Intent(this, CloseMainService.class), PendingIntent.FLAG_IMMUTABLE);

        Prefs prefs = KakuTools.getPrefs(this);

        String contentTitle = getString(R.string.app_name);
        switch (prefs.getTextDirectionSetting())
        {
            case AUTO:
                contentTitle = getString(R.string.notification_direction_auto);
                break;
            case VERTICAL:
                contentTitle = getString(R.string.notification_direction_vertical);
                break;
            case HORIZONTAL:
                contentTitle = getString(R.string.notification_direction_horizontal);
                break;
        }

        Notification n;
        if (prefs.getShowHideSetting())
        {
            n = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.kaku_notification_icon)
                    .setContentTitle(contentTitle)
                    .setContentText(getString(R.string.notification_state,
                            getString(prefs.getInstantModeSetting() ? R.string.on : R.string.off),
                            getString(prefs.getImageFilterSetting() ? R.string.on : R.string.off)))
                    .setContentIntent(toggleShowHide)
                    .addAction(0, getString(R.string.notification_instant_mode), toggleInstantMode)
                    .addAction(0, getString(R.string.notification_image_filter), toggleImagePreview)
                    .addAction(0, getString(R.string.notification_shutdown), closeMainService)
                    .build();
        }
        else {
            n = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.kaku_notification_icon)
                    .setContentTitle(getString(R.string.notification_hidden))
                    .setContentIntent(toggleShowHide)
                    .build();
        }

        n.flags = FLAG_ONGOING_EVENT | FLAG_FOREGROUND_SERVICE;

        return n;
    }

    private void createVirtualDisplay()
    {
        // display metrics
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int mDensity = metrics.densityDpi;
        mDisplay = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);

        // get width and height
        mDisplay.getRealSize(mRealDisplaySize);
        mRotation = mDisplay.getRotation();

        // start capture reader
        Log.d(TAG, String.format("Starting Projection: %dx%d", mRealDisplaySize.x, mRealDisplaySize.y));
        ImageReader oldReader = mImageReader;
        mImageReader = ImageReader.newInstance(mRealDisplaySize.x, mRealDisplaySize.y, PixelFormat.RGBA_8888, 2);
        if (mVirtualDisplay != null){
            // Android 14 allows one virtual display per projection: resize it (rotation, fold)
            // instead of creating a second one.
            mVirtualDisplay.resize(mRealDisplaySize.x, mRealDisplaySize.y, mDensity);
            mVirtualDisplay.setSurface(mImageReader.getSurface());
        }
        else {
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(getClass().getName(), mRealDisplaySize.x, mRealDisplaySize.y, mDensity, VIRTUAL_DISPLAY_FLAGS, mImageReader.getSurface(), null, mHandler);
        }
        if (oldReader != null){
            oldReader.close();
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel()
    {
        String channelId = Constants.KAKU_CHANNEL_ID;
        String channelName = getString(R.string.notification_channel_name);

        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW);
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(channel);

        return channelId;
    }
}
