package com.clarusagency.android.glassybattery.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.clarusagency.android.glassybattery.service.BatteryService;

/**
 * User: crebstock
 * Date: 7/11/13
 * Time: 11:39 AM
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent serviceIntent = new Intent(context, BatteryService.class);
        context.startService(serviceIntent);
    }
}
