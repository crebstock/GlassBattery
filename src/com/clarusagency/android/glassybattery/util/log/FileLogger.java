package com.clarusagency.android.glassybattery.util.log;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.*;
import java.nio.channels.FileChannel;
import java.util.Date;

/**
 * User: crebstock
 * Date: 7/12/13
 * Time: 10:43 AM
 */
public class FileLogger {
    private static String FILENAME = "GlassLog.txt";

    public static void log(Context context, String message) {
        FileOutputStream outputStream;

        Date date = new Date();
        String time = String.valueOf(date.getHours() + ":" + date.getMinutes() + ":" + date.getSeconds());
        String output = time + " ::: " + message + "\n\n";

        try {
            outputStream = context.openFileOutput(FILENAME, Context.MODE_APPEND);
            outputStream.write(output.getBytes());
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void dumpLogToSD(Context context) {
        if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File logFile = new File(context.getFilesDir(), FILENAME);
            File externalFile = new File(context.getExternalFilesDir(null), FILENAME);

            FileChannel inChannel = null;
            FileChannel outChannel = null;
            try {
                inChannel = new FileInputStream(logFile).getChannel();
                outChannel = new FileOutputStream(externalFile).getChannel();
                inChannel.transferTo(0, inChannel.size(), outChannel);
            } catch(Exception e) {
              //Whoops!
            }
            try {
                if (inChannel != null)
                    inChannel.close();
                if (outChannel != null)
                    outChannel.close();
            } catch(IOException e) {
                //Whoops!
            }
        }
    }

    public static void clearLogFile(Context context) {
        File logFile = new File(context.getFilesDir(), FILENAME);
        logFile.delete();
    }
}
