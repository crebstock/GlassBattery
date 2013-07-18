package com.clarusagency.android.glassybattery;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import com.clarusagency.android.glassybattery.receiver.BatteryChangeReceiver;
import com.clarusagency.android.glassybattery.service.BatteryService;
import com.clarusagency.android.glassybattery.util.log.FileLogger;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.AccountPicker;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User: crebstock
 * Date: 6/20/13
 * Time: 1:19 PM
 */

public class MainActivity extends Activity {

    private static final String OAUTH_SHARED_PREFS = "OAuthSharedPrefs";
    private static final String OAuthTokenAccount = "OAuthTokenAccount";
    private static final String GLASS_CARD_ID = "GlassCardId";
    private static final String GLASS_CARD_TIME = "GlassCardTime";


    private static final String TAG = "Glass";
    private static final String PARAM_AUTH_TOKEN =
            "com.example.mirror.android.AUTH_TOKEN";

    private static final int REQUEST_ACCOUNT_PICKER = 1;
    private static final int REQUEST_AUTHORIZATION = 2;

    private static final String GLASS_TIMELINE_SCOPE =
            "https://www.googleapis.com/auth/glass.timeline";
    private static final String GLASS_LOCATION_SCOPE =
            "https://www.googleapis.com/auth/glass.location";
    private static final String SCOPE = String.format("oauth2: %s %s",
            GLASS_TIMELINE_SCOPE, GLASS_LOCATION_SCOPE);

    private static ExecutorService sThreadPool =
            Executors.newSingleThreadExecutor();

    private final Handler mHandler = new Handler();

    private String mAuthToken;
    private String mAccount;

    private Button mRemoveButton;
    private Button mClearSharedPreferences;
    private Button mTestDumpFileLog;

    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Define our layout
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences(OAUTH_SHARED_PREFS, MODE_PRIVATE);

        mAccount = mPrefs.getString(OAuthTokenAccount, "");
        if(mAccount.equals("")) {
            Intent intent = AccountPicker.newChooseAccountIntent(
                    null, null, new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE},
                    false, null, null, null, null);
            startActivityForResult(intent, REQUEST_ACCOUNT_PICKER);
        } else {
            fetchTokenForAccount(mAccount);
        }

        mRemoveButton = (Button)findViewById(R.id.removeTokenButton);
        mClearSharedPreferences = (Button)findViewById(R.id.clearSharedPreferences);
        mTestDumpFileLog = (Button)findViewById(R.id.testDumpFileLog);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(PARAM_AUTH_TOKEN, mAuthToken);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        switch (requestCode) {
            case REQUEST_ACCOUNT_PICKER:
                if (RESULT_OK == resultCode) {
                    String account = data.getStringExtra(
                            AccountManager.KEY_ACCOUNT_NAME);
                    String type = data.getStringExtra(
                            AccountManager.KEY_ACCOUNT_TYPE);

                    // TODO: Cache the chosen account
                    Log.d(TAG, String.format("User selected account %s of type %s",
                            account, type));

                    SharedPreferences.Editor editor = mPrefs.edit();
                    editor.putString(OAuthTokenAccount, account);
                    editor.commit();

                    fetchTokenForAccount(account);
                }
                break;
            case REQUEST_AUTHORIZATION:
                if (RESULT_OK == resultCode) {
                    String token = data.getStringExtra(
                            AccountManager.KEY_AUTHTOKEN);

                    Log.d(TAG, String.format(
                            "Authorization request returned token %s", token));
                    onTokenResult(token);
                }
                break;
        }
    }

    private void onTokenResult(String token) {
        Log.d(TAG, "onTokenResult: " + token);
        if (!TextUtils.isEmpty(token)) {
            mAuthToken = token;
            Toast.makeText(this, "New token result", Toast.LENGTH_SHORT).show();

            //Service
            Intent serviceIntent = new Intent(MainActivity.this, BatteryService.class);
            startService(serviceIntent);

            mRemoveButton = (Button)findViewById(R.id.removeTokenButton);
            mRemoveButton.setEnabled(true);
            mRemoveButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    GoogleAuthUtil.invalidateToken(getApplicationContext(), mAuthToken);

                    if(mAccount.equals("")) {
                        Intent intent = AccountPicker.newChooseAccountIntent(
                                null, null, new String[]{GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE},
                                false, null, null, null, null);
                        startActivityForResult(intent, REQUEST_ACCOUNT_PICKER);
                    } else {
                        fetchTokenForAccount(mAccount);
                    }
                }
            });

            mClearSharedPreferences = (Button)findViewById(R.id.clearSharedPreferences);
            mClearSharedPreferences.setEnabled(true);
            mClearSharedPreferences.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SharedPreferences.Editor editor = mPrefs.edit();
                    editor.putString(GLASS_CARD_ID, "");
                    editor.putLong(GLASS_CARD_TIME, 0);
                    editor.commit();
                }
            });

            mTestDumpFileLog = (Button)findViewById(R.id.testDumpFileLog);
            mTestDumpFileLog.setEnabled(true);
            mTestDumpFileLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FileLogger.dumpLogToSD(getApplicationContext());
                }
            });
        } else {
            Toast.makeText(this, "Sorry, invalid token result", Toast.LENGTH_SHORT).show();
        }
    }

    private void fetchTokenForAccount(final String account) {
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
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                onTokenResult(token);
                            }
                        });
                    }
                } catch (final UserRecoverableAuthException e) {
                    // This means that the app hasn't been authorized by the user for access
                    // to the scope, so we're going to have to fire off the (provided) Intent
                    // to arrange for that. But we only want to do this once. Multiple
                    // attempts probably mean the user said no.
                    Log.d(TAG, "Handling a UserRecoverableAuthException");

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            startActivityForResult(e.getIntent(), REQUEST_AUTHORIZATION);
                        }
                    });
                } catch (IOException e) {
                    // Something is stressed out; the auth servers are by definition
                    // high-traffic and you can't count on 100% success. But it would be
                    // bad to retry instantly, so back off
                    Log.e(TAG, "Failed to fetch auth token!", e);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Failed to fetch token, try again later", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (GoogleAuthException e) {
                    // Can't recover from this!
                    Log.e(TAG, "Failed to fetch auth token!", e);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "Failed to fetch token, can't recover", Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if(sThreadPool != null && !sThreadPool.isShutdown() || !sThreadPool.isTerminated()) {
                                sThreadPool.shutdownNow();
                            }
                        }
                    });
                }
            }
        });
    }
}