package com.foohyfooh.bb8.notifications;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.orbotix.common.DiscoveryAgent;
import com.orbotix.common.Robot;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements BB8CommandService.CommandListener {

    private static final int REQUEST_CODE_LOCATION_PERMISSION = 1;
    private static final int REQUEST_CODE_BLUETOOTH = 2;
    private static final String TAG = "MainActivity";

    private TextView connectionStatus;
    private boolean mIsBound, bluetoothNotDenied = true;
    private BB8CommandService bb8CommandService;
    private ServiceConnection serviceConnection;
    private AppInfoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        connectionStatus = (TextView) findViewById(R.id.main_connectionStatus);

        RecyclerView recyclerView =  (RecyclerView) findViewById(R.id.main_appList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppInfoAdapter(this);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.main_notification).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(bb8CommandService != null){
                    Log.d(TAG, "BB8 Command Service is not Null");
                    bb8CommandService.startForeground(1, new NotificationCompat.Builder(view.getContext())
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(getText(R.string.app_name))
                            .build());
                }else{
                    Log.d(TAG, "BB8 Command Service is Null");
                }
            }
        });

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
            if(hasLocationPermission != PackageManager.PERMISSION_GRANTED) {
                //Log.e(TAG, "Location permission has not already been granted");
                List<String> permissions = new ArrayList<>();
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                requestPermissions(permissions.toArray(new String[permissions.size()]), REQUEST_CODE_LOCATION_PERMISSION);
            } else {
                //Log.d(TAG, "Location permission already granted");
                doBindService();
            }
        }else{
            doBindService();
        }
    }

    private void handleRequirements(){
        ComponentName cn = new ComponentName(this, NotificationListener.class);
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean notificationEnabled = flat != null && flat.contains(cn.flattenToString());

        boolean settingNotFound = false, locationEnabled = false;
        try {
             locationEnabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Settings.SettingNotFoundException e) {
            settingNotFound = true;
        }

        if(!notificationEnabled){
            new AlertDialog.Builder(this)
                    .setTitle(R.string.notification_access_title)
                    .setMessage(R.string.notification_access_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.enable, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                        }
                    })
                    .show();
        }else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !settingNotFound && !locationEnabled){
            new AlertDialog.Builder(this)
                    .setTitle(R.string.location_access_title)
                    .setMessage(R.string.location_access_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.enable, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            Intent enableLocationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            startActivity(enableLocationIntent);
                        }
                    })
                    .show();
        }else if(bluetoothNotDenied && BluetoothAdapter.getDefaultAdapter() != null
                && !BluetoothAdapter.getDefaultAdapter().isEnabled()){
            Intent enableBluetoothIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetoothIntent, REQUEST_CODE_BLUETOOTH);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleRequirements();
        adapter.notifyDataSetChanged(); //Notify of changes since the ConfigureActivity may have disabled an app
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(bb8CommandService != null) bb8CommandService.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CODE_LOCATION_PERMISSION: {
                for(int i = 0; i < permissions.length; i++) {
                    if(grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "Permission Granted: " + permissions[i]);
                        doBindService();
                    } else if(grantResults[i] == PackageManager.PERMISSION_DENIED) {
                        Log.d(TAG, "Permission Denied: " + permissions[i]);
                        Toast.makeText(MainActivity.this, "Cannot work without the permission", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            break;
            default: {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode){
            case REQUEST_CODE_BLUETOOTH: {
                if(resultCode == RESULT_OK){
                    if(bb8CommandService != null) bb8CommandService.start();
                }else{
                    bluetoothNotDenied = false;
                }
                break;
            }
        }
    }

    void doBindService() {
        serviceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                bb8CommandService = ((BB8CommandService.BB8Binder)service).getService();
                bb8CommandService.addStateListener(MainActivity.this);
                //bb8CommandService.startDiscovery();
                bb8CommandService.start();
            }

            public void onServiceDisconnected(ComponentName className) {
                bb8CommandService = null;
            }
        };
        bindService(new Intent(this, BB8CommandService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        mIsBound = true;
    }

    void doUnbindService() {
        if (mIsBound) {
            unbindService(serviceConnection);
            mIsBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(bb8CommandService != null) bb8CommandService.stop();
        doUnbindService();
    }

    @Override
    public void handleRobotsAvailable(List<Robot> list) {}

    @Override
    public void onDiscoveryDidStart(DiscoveryAgent discoveryAgent) {}

    @Override
    public void onDiscoveryDidStop(DiscoveryAgent discoveryAgent) {}

    @Override
    public void handleRobotChangedState(Robot robot, RobotChangedStateNotificationType type) {
        if(type == RobotChangedStateNotificationType.Online){
            connectionStatus.setText(String.format(getString(R.string.status_connected), robot.getName()));
        }else{
            connectionStatus.setText(getString(R.string.status_disconnected));
        }
    }
}
