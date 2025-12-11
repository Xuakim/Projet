package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DeviceListAdapter.OnDeviceClickListener {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean scanning;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private DeviceListAdapter deviceListAdapter;
    private final List<BluetoothDevice> discoveredBtDevices = new ArrayList<>();
    private ProgressBar scanProgressBar;

    private static final long SCAN_PERIOD = 10000; // 10 secondes

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    scanLeDevice(true);
                } else {
                    Toast.makeText(this, "L\'activation du Bluetooth a été refusée.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Cet appareil ne supporte pas le Bluetooth.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        RecyclerView devicesRecyclerView = findViewById(R.id.devices_recycler_view);
        deviceListAdapter = new DeviceListAdapter(discoveredBtDevices, this);
        devicesRecyclerView.setAdapter(deviceListAdapter);
        scanProgressBar = findViewById(R.id.scan_progress_bar);

        checkAndRequestPermissions();
    }

    @Override
    public void onDeviceClick(BluetoothDevice device) {
        scanLeDevice(false);
        Intent intent = new Intent(this, DeviceDetailActivity.class);
        intent.putExtra(DeviceDetailActivity.EXTRA_DEVICE_ADDRESS, device.getAddress());
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
             startActivity(intent);
        }
    }

    private void scanLeDevice(final boolean enable) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission BLUETOOTH_SCAN non accordée.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (enable) {
            handler.postDelayed(() -> {
                if (scanning) {
                    scanning = false;
                    scanProgressBar.setVisibility(View.GONE);
                    bluetoothLeScanner.stopScan(leScanCallback);
                    Toast.makeText(this, "Fin de la recherche BLE.", Toast.LENGTH_SHORT).show();
                }
            }, SCAN_PERIOD);

            scanning = true;
            discoveredBtDevices.clear();
            deviceListAdapter.notifyDataSetChanged();
            scanProgressBar.setVisibility(View.VISIBLE);
            bluetoothLeScanner.startScan(leScanCallback);
        } else {
            if (scanning) {
                scanning = false;
                scanProgressBar.setVisibility(View.GONE);
                bluetoothLeScanner.stopScan(leScanCallback);
            }
        }
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
            
            String deviceName = device.getName();
            if (deviceName != null && !deviceName.isEmpty() && !discoveredBtDevices.contains(device)) {
                discoveredBtDevices.add(device);
                runOnUiThread(() -> deviceListAdapter.notifyDataSetChanged());
            }
        }
    };

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        
        List<String> permissionsToRequestFiltered = new ArrayList<>();
        for(String p : permissionsToRequest) {
            if(ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequestFiltered.add(p);
            }
        }

        if (!permissionsToRequestFiltered.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequestFiltered.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        } else {
            enableBluetooth();
        }
    }

    private void enableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                 requestEnableBluetoothLauncher.launch(enableBtIntent);
            } else {
                Toast.makeText(this, "Connect permission is required to enable Bluetooth", Toast.LENGTH_LONG).show();
            }
        } else {
            scanLeDevice(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE && grantResults.length > 0) {
            boolean allGranted = true;
            for(int grantResult : grantResults) {
                if(grantResult != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                enableBluetooth();
            } else {
                Toast.makeText(this, "Permissions Bluetooth refusées.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
