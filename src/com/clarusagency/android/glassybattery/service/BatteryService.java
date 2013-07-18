package com.clarusagency.android.glassybattery.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import com.clarusagency.android.glassybattery.MirrorApiClient;
import com.clarusagency.android.glassybattery.receiver.BatteryChangeReceiver;
import com.clarusagency.android.glassybattery.util.log.FileLogger;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import org.apache.http.HttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User: crebstock
 * Date: 6/22/13
 * Time: 2:02 PM
 */
public class BatteryService extends IntentService {
    public static boolean DEBUG = true;

    private static final String OAUTH_SHARED_PREFS = "OAuthSharedPrefs";
    private static final String OAUTH_TOKEN_ACCOUNT = "OAuthTokenAccount";
    private static final String GLASS_CARD_ID = "GlassCardId";
    private static final String GLASS_CARD_TIME = "GlassCardTime";

    private static final String GLASS_CLOUD_BATTERY_LEVEL = "CLOUD_BATTERY_LEVEL";

    private static final String TAG = "Glass";
    private static final String PARAM_AUTH_TOKEN =
            "com.example.mirror.android.AUTH_TOKEN";

    private static final String GLASS_TIMELINE_SCOPE =
            "https://www.googleapis.com/auth/glass.timeline";
    private static final String GLASS_LOCATION_SCOPE =
            "https://www.googleapis.com/auth/glass.location";
    private static final String SCOPE = String.format("oauth2: %s %s",
            GLASS_TIMELINE_SCOPE, GLASS_LOCATION_SCOPE);

    private static long lastCalled = 0;
    public static int cloudBatteryLevel = -1;

    public static int WAKE_LOCK_TIME = 30000;

    //////////////////////////////////////////////////////////
    // RELEASE VALUES ////////////////////////////////////////
    //////////////////////////////////////////////////////////
    public static int LONG_UPDATE_TIME = 2700000;
    public static int SHORT_UPDATE_TIME = 1800000;
    public static int REALLY_SHORT_UPDATE_TIME = 600000;

    public static int DEFAULT_BATTERY_LEVEL = -15;
    public static int LOW_BATTERY_LEVEL = 20;
    public static int EXTREMELY_LOW_BATTERY_LEVEL = 10;

    public static int MIN_BATTERY_CHANGE = 5;
    //////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////

    //////////////////////////////////////////////////////////
    // DEBUG VALUES //////////////////////////////////////////
    //////////////////////////////////////////////////////////
//    public static int LONG_UPDATE_TIME = 30000;
//    public static int SHORT_UPDATE_TIME = 30000;
//
//    public static int DEFAULT_BATTERY_LEVEL = -15;
//    public static int LOW_BATTERY_LEVEL = 20;
//    public static int EXTREMELY_LOW_BATTERY_LEVEL = 10;
//
//    public static int MIN_BATTERY_CHANGE = 0;
    //////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////

    private static ExecutorService sThreadPool =
            Executors.newSingleThreadExecutor();

    private final Handler mHandler = new Handler();

    private String mAuthToken;
    private String mAccount;

    private String mGlassCardId;
    private long mGlassCardTime;

    private SharedPreferences mPrefs;

    private int mBatteryLevel;
    private int mBatteryScale;

    private PowerManager.WakeLock wakeLock;

    public BatteryService() {
        super(BatteryService.class.getName());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "Started Service: " + BatteryService.this);
//        FileLogger.log(getApplicationContext(), "Started Service: " + BatteryService.this);

        acquireWakeLock();

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        BatteryChangeReceiver receiver = new BatteryChangeReceiver();
        Intent batteryIntent = registerReceiver(null, filter);

        mBatteryLevel = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -15);
        mBatteryScale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -15);

        boolean shouldRun = checkRun();

        if(shouldRun) {
            mPrefs = getSharedPreferences(OAUTH_SHARED_PREFS, MODE_PRIVATE);

            mGlassCardId = mPrefs.getString(GLASS_CARD_ID, "");
            mGlassCardTime = mPrefs.getLong(GLASS_CARD_TIME, 0);
            cloudBatteryLevel = mPrefs.getInt(GLASS_CLOUD_BATTERY_LEVEL, -1);
            if(cloudBatteryLevel != -1 && Math.abs(mBatteryLevel - cloudBatteryLevel) > 5
                    || DEBUG) {
                String empty = "";
                if(mAuthToken == null || mAccount == null) {
                    Log.d(TAG, "STARTING FETCH TOKEN");
//                    FileLogger.log(getApplicationContext(), "STARTING FETCH TOKEN");

                    mAccount = mPrefs.getString(OAUTH_TOKEN_ACCOUNT, "");
                    if(mAccount != null) {
                        fetchTokenForAccount(mAccount);
                    }
                } else {
                    //Post battery and end
                    String battery = "Phone Battery: " + (mBatteryLevel / (float)mBatteryScale) * 100 + "%";
                    Log.d(BatteryService.class.getName(), battery);
//                    FileLogger.log(getApplicationContext(), battery);
                    sendToTimeline(battery);
                }
            }
        }

        lastCalled = System.currentTimeMillis();
        scheduleNextRun();
    }

    private boolean checkRun() {
        if(mBatteryLevel < LOW_BATTERY_LEVEL) {
            //Is the battery really low?  If so, skip the change check and just post
//            FileLogger.log(getApplicationContext(), "mBatteryLevel < LOW_BATTERY_LEVEL: " + mBatteryLevel);
            return true;
        } else if(cloudBatteryLevel != DEFAULT_BATTERY_LEVEL && Math.abs(cloudBatteryLevel - mBatteryLevel) > MIN_BATTERY_CHANGE) {
            //Has the battery never been posted, or has it changed enough to warrant posting?
//            FileLogger.log(getApplicationContext(),
//                    "cloudBatteryLevel != DEFAULT_BATTERY_LEVEL && Math.abs(cloudBatteryLevel - mBatteryLevel) > MIN_BATTERY_CHANGE"
//                    + ", mBatteryLevel=" + mBatteryLevel + ", cloudBatteryLevel =" + cloudBatteryLevel);
            return true;
        } else if(cloudBatteryLevel == DEFAULT_BATTERY_LEVEL) {
            //Battery has changed more than 5 percent, or there is no cloud battery level
//            FileLogger.log(getApplicationContext(), "cloudBatteryLevel == DEFAULT_BATTERY_LEVEL");
            return true;
        }

        return false;
    }

    private void fetchTokenForAccount(final String account) {
        Log.d(TAG, "fetchTokenForAccount");
//        FileLogger.log(getApplicationContext(), "fetchTokenForAccount");
        // We fetch the token on a background thread otherwise Google Play
        // Services will throw an IllegalStateException
        sThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // If this returns immediately the OAuth framework thinks
                    // the token should be usable
                    final String token = GoogleAuthUtil.getToken(
                            getApplicationContext(), account, SCOPE);

                    if (token != null) {
                        // Pass the token back to the UI thread
                        Log.d(TAG, String.format("getToken returned token %s", token));
//                        FileLogger.log(getApplicationContext(), String.format("getToken returned token %s", token));
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                onTokenResult(token);
                            }
                        });
                    }
                } catch (final UserRecoverableAuthException e) {
                    // Token no longer authorized
                    Log.d(TAG, "Token no longer authorized");
//                    FileLogger.log(getApplicationContext(), "Token no longer authorized");
                } catch (IOException e) {
                    // Some error server side
                    Log.d(TAG, "Some error server side");
//                    FileLogger.log(getApplicationContext(), "Some error server side");
                } catch (GoogleAuthException e) {
                    // Can't recover from this
                    Log.d(TAG, "Can't recover from this");
//                    FileLogger.log(getApplicationContext(), "Can't recover from this");
                }
            }
        });
    }

    private void onTokenResult(String token) {
        Log.d(TAG, "onTokenResult: " + token);
//        FileLogger.log(getApplicationContext(), "onTokenResult: " + token);
        if (!TextUtils.isEmpty(token)) {
            mAuthToken = token;

            //Post battery and end
            //String battery = "Battery: " + mBatteryLevel + "/" + mBatteryScale;
            String battery = "Phone Battery: " + (int)((mBatteryLevel / (float)mBatteryScale) * 100) + "%";
            Log.d(BatteryService.class.getName(), battery);
//            FileLogger.log(getApplicationContext(), "onTokenResult, battery: " + battery);
            sendToTimeline(battery);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire(WAKE_LOCK_TIME);
    }

    private void releaseWakeLock() {
        if(wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    //TODO: Make update time user configurable
    private void scheduleNextRun() {
        Intent intent = new Intent(this, this.getClass());
        PendingIntent pendingIntent =
                PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        long nextUpdateTimeMillis = System.currentTimeMillis();

        if(mBatteryLevel < EXTREMELY_LOW_BATTERY_LEVEL) {
            nextUpdateTimeMillis += REALLY_SHORT_UPDATE_TIME;
        } else if(mBatteryLevel < LOW_BATTERY_LEVEL) {
            nextUpdateTimeMillis += SHORT_UPDATE_TIME;
        } else {
            nextUpdateTimeMillis += LONG_UPDATE_TIME;
        }

        //TODO: Possible that this route isn't as reliable as calling a broadcastreceiver to launch the service
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, nextUpdateTimeMillis, pendingIntent);
    }

    private void sendToTimeline(String message) {
        Log.d(TAG, "sendToTimeline");
//        FileLogger.log(getApplicationContext(), "sendToTimeline");
        if (!TextUtils.isEmpty(mAuthToken)) {
            if (!TextUtils.isEmpty(message)) {
                try {
                    JSONObject notification = new JSONObject();
                    notification.put("level", "DEFAULT"); // Play a chime

                    JSONObject json = new JSONObject();
                    json.put("text", message);
                    json.put("notification", notification);

                    //Add menu items

                    JSONObject deleteMenuItem = new JSONObject();
                    deleteMenuItem.put("action", "DELETE");

                    JSONObject pinMenuItem = new JSONObject();
                    pinMenuItem.put("action", "TOGGLE_PINNED");

                    JSONArray menuItems = new JSONArray();
                    menuItems.put(deleteMenuItem);
                    menuItems.put(pinMenuItem);

                    json.put("menuItems", menuItems);

                    MirrorApiClient client = MirrorApiClient.getInstance(this);

                    MirrorApiClient.Callback callback = new MirrorApiClient.Callback() {
                        @Override
                        public void onSuccess(HttpResponse response) {
                            try {
                                Log.d(TAG, "onSuccess");
//                                FileLogger.log(getApplicationContext(), "onSuccess");
                                String responseBody = EntityUtils.toString(response.getEntity());
                                JSONObject responseObject = new JSONObject(responseBody);

                                SharedPreferences.Editor editor = mPrefs.edit();
                                editor.putString(GLASS_CARD_ID, responseObject.getString("id"));
                                editor.putLong(GLASS_CARD_TIME, System.currentTimeMillis());
                                editor.putInt(GLASS_CLOUD_BATTERY_LEVEL, mBatteryLevel);
                                editor.commit();

                                cloudBatteryLevel = mBatteryLevel;

                                releaseWakeLock();
                            } catch (IOException exception) {
                                Log.d(TAG, "IOException");
//                                FileLogger.log(getApplicationContext(), "IOException");
                                //Failed on getting the response, assume a reset and wipe any current ID and Time
                                SharedPreferences.Editor editor = mPrefs.edit();
                                editor.putString(GLASS_CARD_ID, "");
                                editor.putLong(GLASS_CARD_TIME, 0);
                                editor.commit();
                            } catch(JSONException exception) {
                                Log.d(TAG, "JSONException");
//                                FileLogger.log(getApplicationContext(), "JSONException");
                                //Failed on parsing the response, assume a reset and wipe any current ID and Time
                                SharedPreferences.Editor editor = mPrefs.edit();
                                editor.putString(GLASS_CARD_ID, "");
                                editor.putLong(GLASS_CARD_TIME, 0);
                                editor.commit();
                            }
                        }

                        @Override
                        public void onFailure(HttpResponse response, Throwable e) {
                            Log.d(TAG, "onFailure");
//                            FileLogger.log(getApplicationContext(), "onFailure");

                            //Failed on getting the response, assume a reset and wipe any current ID and Time
                            SharedPreferences.Editor editor = mPrefs.edit();
                            editor.putString(GLASS_CARD_ID, "");
                            editor.putLong(GLASS_CARD_TIME, 0);
                            editor.commit();
                        }
                    };

                    //TODO: check to see if this card ID is too old to be posting too
                    if(!mGlassCardId.equals("")) {
                        Log.d(TAG, "UpdateTimeLineItem: " + mGlassCardId);
//                        FileLogger.log(getApplicationContext(), "UpdateTimeLineItem: " + mGlassCardId);
                        json.put("id", mGlassCardId);
                        client.updateTimelineItem(mGlassCardId, mAuthToken, json, callback);
                    } else {
                        Log.d(TAG, "CreateTimelineItem");
                        FileLogger.log(getApplicationContext(), "CreateTimeLineItem");
                        client.createTimelineItem(mAuthToken, json, callback);
                    }
                } catch (JSONException e) {
                    Log.d(TAG, "Sorry, can't serialize that to JSON");
//                    FileLogger.log(getApplicationContext(), "Sorry, can't serialize that to JSON");
                }
            } else {
                Log.d(TAG, "Sorry, can't create an empty timeline item");
//                FileLogger.log(getApplicationContext(), "Sorry, can't create an empty timeline item");
            }
        } else {
            Log.d(TAG, "Sorry, can't create a new timeline card without a token");
//            FileLogger.log(getApplicationContext(), "Sorry, can't create a new timeline card without a token");
        }
    }

}
