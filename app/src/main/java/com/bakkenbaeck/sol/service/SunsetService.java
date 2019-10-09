package com.bakkenbaeck.sol.service;


import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.text.Html;

import com.bakkenbaeck.sol.R;
import com.bakkenbaeck.sol.ui.SunActivity;
import com.bakkenbaeck.sol.util.DailyMessage;
import com.bakkenbaeck.sol.util.SolPreferences;
import com.bakkenbaeck.sol.util.ThreeDayPhases;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

public class SunsetService extends Service implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    public static final String ACTION_UPDATE = "com.example.androidintentservice.UPDATE";
    public static final String EXTRA_DAILY_MESSAGE = "daily_message";
    public static final String EXTRA_SUNRISE_TIME = "sunrise_time";
    public static final String EXTRA_SUNSET_TIME = "sunset_time";
    public static final String EXTRA_LOCATION_MESSAGE = "location_message";
    public static final String EXTRA_CURRENT_PHASE = "current_phase";
    public static final String EXTRA_SHOW_NOTIFICATION = "show_notification";

    private GoogleApiClient googleApiClient;
    private DailyMessage dailyMessage;
    private SolPreferences prefs;

    private boolean shouldTryAndShowNotification;

    @Override
    public void onCreate() {
        super.onCreate();
        this.googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        this.dailyMessage = new DailyMessage(this);
        this.prefs = new SolPreferences(this);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        this.shouldTryAndShowNotification = intent.getBooleanExtra(EXTRA_SHOW_NOTIFICATION, false);
        this.googleApiClient.connect();
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.googleApiClient.disconnect();
    }

    @Override
    public void onConnected(final @Nullable Bundle bundle) {
        getUsersLocation();
    }

    private void getUsersLocation() {
        try {
            final Location currentLocation = LocationServices.FusedLocationApi.getLastLocation(this.googleApiClient);
            updateLocation(currentLocation);
        } catch (final SecurityException ex) {
            updateLocation(null);
        }
    }

    private void updateLocation(final Location location) {
        final Location safeLocation = storeLocation(location);
        final ThreeDayPhases threeDayPhases = new ThreeDayPhases().init(safeLocation);
        final long tomorrowsSunrise = threeDayPhases.getTomorrowsSunrise();

        final String currentPhaseName = threeDayPhases.getCurrentPhase().getName();
        final String todaysMessage = this.dailyMessage.generate(threeDayPhases);
        final String locationMessage = this.dailyMessage.getLocation(safeLocation.getLatitude(), safeLocation.getLongitude());

        final Intent intentUpdate = new Intent()
                .setAction(ACTION_UPDATE)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(EXTRA_DAILY_MESSAGE, todaysMessage)
                .putExtra(EXTRA_SUNRISE_TIME, threeDayPhases.getTodaysSunriseAsLong())
                .putExtra(EXTRA_SUNSET_TIME, threeDayPhases.getTodaysSunsetAsLong())
                .putExtra(EXTRA_LOCATION_MESSAGE, locationMessage)
                .putExtra(EXTRA_CURRENT_PHASE, currentPhaseName);

        sendBroadcast(intentUpdate);

        if (this.shouldTryAndShowNotification) {
            tryAndShowNotification(todaysMessage);
        }

        tryAndEnableTomorrowsAlarm(tomorrowsSunrise);

        stopSelf();
    }

    private Location storeLocation(final Location location) {
        if (location != null) {
            this.prefs.cacheLocation(location);
            return location;
        }

        return this.prefs.getCachedLocation();
    }

    private void tryAndShowNotification(final String todaysMessage) {
        final SolPreferences solPreferences = new SolPreferences(this);
        final boolean notificationEnabled = solPreferences.getShowNotification();
        if (!notificationEnabled) {
            return;
        }

        showNotification(todaysMessage);
    }

    private void showNotification(final String todaysMessage) {
        final Intent resultIntent = new Intent(this, SunActivity.class);
        final PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        final Bitmap largeIcon = BitmapFactory.decodeResource(getResources(), R.mipmap.daylight_icon);
        final String contentText = stripHtml(todaysMessage);
        final NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(getResources().getString(R.string.app_name))
                .bigText(contentText);
        final NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.mipmap.daylight_notification_icon)
                        .setLargeIcon(largeIcon)
                        .setContentTitle(getResources().getString(R.string.app_name))
                        .setContentText(contentText)
                        .setAutoCancel(true)
                        .setStyle(bigTextStyle)
                        .setContentIntent(resultPendingIntent);
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(1, mBuilder.build());
    }

    private void tryAndEnableTomorrowsAlarm(final long alarmTime) {
        final SolPreferences solPreferences = new SolPreferences(this);
        final boolean notificationEnabled = solPreferences.getShowNotification();
        if (!notificationEnabled) {
            return;
        }

        enableTomorrowsAlarm(alarmTime);
    }

    private void enableTomorrowsAlarm(final long alarmTime) {
        final Intent alarmIntent = new Intent(SunsetService.this, AlarmReceiver.class);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(SunsetService.this, 0, alarmIntent, 0);
        final AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        manager.set(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent);
    }

    public String stripHtml(final String html) {
        return Html.fromHtml(html).toString();
    }

    @Override
    public void onConnectionSuspended(int i) {}

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        updateLocation(null);
    }
}
