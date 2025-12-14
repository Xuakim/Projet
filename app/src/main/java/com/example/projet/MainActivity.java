package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity implements DeviceListAdapter.OnDeviceClickListener {

    private static final int REQUEST_PERMISSIONS = 1;

    private BluetoothLeScanner bluetoothLeScanner;
    private final ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    private DeviceListAdapter deviceListAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Check for BLE feature
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showMessage("Bluetooth LE n'est pas supporté sur cet appareil.");
            finish();
            return;
        }

        // 2. Get BluetoothManager and check for null
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            showMessage("Impossible d'accéder au service Bluetooth.");
            finish();
            return;
        }

        // 3. Get BluetoothAdapter and check for null
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            showMessage("Bluetooth n'est pas supporté sur cet appareil.");
            finish();
            return;
        }

        // 4. Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled()) {
            showMessage("Veuillez activer le Bluetooth et redémarrer l'application.");
            finish();
            return;
        }

        // 5. Get Scanner and check for null
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            showMessage("Impossible d'initialiser le scanner BLE. Vérifiez les permissions et l'état du Bluetooth.");
            finish();
            return;
        }

        RecyclerView deviceRecyclerView = findViewById(R.id.deviceListView);
        deviceRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        deviceListAdapter = new DeviceListAdapter(this, deviceList, this);
        deviceRecyclerView.setAdapter(deviceListAdapter);

        checkPermissionsAndStartScan();
    }

    private void checkPermissionsAndStartScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_PERMISSIONS);
                return;
            }
        }
        startBleScan();
    }

    private void startBleScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                deviceList.clear();
                deviceListAdapter.notifyDataSetChanged();
                bluetoothLeScanner.startScan(scanCallback);
                showMessage("Scan en cours...");
            } catch (SecurityException e) {
                showMessage("Impossible de scanner, permission refusée");
            }
        } else {
            showMessage("Permission de scan manquante");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                String deviceName = device.getName();
                if (deviceName != null && !deviceName.trim().isEmpty() && !deviceList.contains(device)) {
                    deviceList.add(device);
                    runOnUiThread(() -> deviceListAdapter.notifyDataSetChanged());
                }
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startBleScan();
            } else {
                showMessage("Permissions refusées, impossible de continuer.");
            }
        }
    }

    @Override
    public void onDeviceClick(BluetoothDevice device) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                Intent intent = new Intent(MainActivity.this, DeviceControlActivity.class);
                intent.putExtra("device", device);
                startActivity(intent);
            } catch (SecurityException e) {
                showMessage("Impossible d'ouvrir la page, permission refusée");
            }
        } else {
            showMessage("Permission BLUETOOTH_CONNECT manquante");
        }
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
