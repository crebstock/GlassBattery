package com.clarusagency.android.glassybattery.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.util.Log;
import com.clarusagency.android.glassybattery.service.BatteryService;

/**
 * User: crebstock
 * Date: 6/10/13
 * Time: 9:54 AM
 */

/*

TODO: swiping off recents list removes broadcast receiver maybe use service to register receiver

TODO: If the update (with existing ID) fails we need to determine whether we should retry

*/

public class BatteryChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "Glass";
    private static long lastCalled = 0;
    public static int cloudBatteryLevel = -1;

    public static boolean DEBUG = false;

    //////////////////////////////////////////////////////////
    // RELEASE VALUES ////////////////////////////////////////
    //////////////////////////////////////////////////////////
    public static int LONG_UPDATE_TIME = 1800000;
    public static int SHORT_UPDATE_TIME = 300000;

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

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "BroadcastReceiver onReceive");
        Log.d(TAG, "currentTimeMillis - lastCalled: " + System.currentTimeMillis() + " - " + lastCalled + " = " + (System.currentTimeMillis() - lastCalled));

        int newBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, DEFAULT_BATTERY_LEVEL);

        boolean runService = false;

        if(lastCalled == 0) {
            //The service has never been started
            runService = true;
        } else if(Math.abs(System.currentTimeMillis() - lastCalled) > LONG_UPDATE_TIME) {
            //its been 30 minutes since it was last called

            if(newBatteryLevel < LOW_BATTERY_LEVEL) {
                //Is the battery really low?  If so, skip the change check and just post
                runService = true;
            } else if(cloudBatteryLevel != DEFAULT_BATTERY_LEVEL && Math.abs(cloudBatteryLevel - newBatteryLevel) > MIN_BATTERY_CHANGE) {
                //Has the battery never been posted, or has it changed enough to warrant posting?
                runService = true;
            } else if(cloudBatteryLevel == DEFAULT_BATTERY_LEVEL) {
                //Battery has changed more than 5 percent, or there is no cloud battery level
                runService = true;
            }
        } else if(Math.abs(cloudBatteryLevel - newBatteryLevel) > LOW_BATTERY_LEVEL
                    && Math.abs(System.currentTimeMillis() - lastCalled) > SHORT_UPDATE_TIME) {
            //Battery has changed more than 15 percent since last uploaded, tie to call again
            runService = true;
        } else if(newBatteryLevel < EXTREMELY_LOW_BATTERY_LEVEL && cloudBatteryLevel != -1 && newBatteryLevel != cloudBatteryLevel) {
            //Battery level is extremely low, show every point change
            runService = true;
        } else if(DEBUG) {
            runService = true;
        }

        if(runService && Math.abs(System.currentTimeMillis() - lastCalled) > 60000) {
            lastCalled = System.currentTimeMillis();

            Intent serviceIntent = new Intent(context, BatteryService.class);
            serviceIntent.putExtra(BatteryManager.EXTRA_LEVEL, newBatteryLevel);
            serviceIntent.putExtra(BatteryManager.EXTRA_SCALE, intent.getIntExtra(BatteryManager.EXTRA_SCALE, -15));
            context.startService(serviceIntent);
        }

    }
}
