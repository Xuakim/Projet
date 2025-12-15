package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class MainActivity extends AppCompatActivity implements BleManager.BleEventListener,
        DeviceListAdapter.OnDeviceClickListener {

    private static final String TAG = "MainActivity";

    private BleManager bleManager;
    private DeviceListAdapter deviceListAdapter;
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d(TAG, "MainActivity onCreate");

        bleManager = new BleManager(getApplicationContext(), this);

        RecyclerView recyclerView = findViewById(R.id.deviceListView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        deviceListAdapter = new DeviceListAdapter(this, this);
        recyclerView.setAdapter(deviceListAdapter);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!Boolean.TRUE.equals(granted)) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) {
                        Log.d(TAG, "Permissions accordées par l'utilisateur");
                        checkPermissionsAndStartScan();
                    } else {
                        Log.w(TAG, "Permissions refusées par l'utilisateur");
                        Toast.makeText(this, "Permissions refusées, impossible de scanner.", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainActivity onResume");
        checkPermissionsAndStartScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "MainActivity onPause");
        if (bleManager != null) {
            bleManager.stopScan();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.stopScan();
            bleManager.disconnect();
        }
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return locationManager != null && (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    private boolean hasRequiredScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkPermissionsAndStartScan() {
        if (!hasRequiredScanPermissions()) {
            String[] permissionsToRequest;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionsToRequest = new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                };
            } else {
                permissionsToRequest = new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                };
            }
            Log.d(TAG, "Demande des permissions");
            permissionLauncher.launch(permissionsToRequest);
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            Toast.makeText(this, "Veuillez activer la localisation pour le scan BLE.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        startBleScan();
    }

    private void startBleScan() {
        if (deviceListAdapter != null) deviceListAdapter.clear();
        if (bleManager != null) {
            Log.d(TAG, "startBleScan: lancement du scan BLE");
            bleManager.startScan();
            Toast.makeText(this, "Scan BLE démarré...", Toast.LENGTH_SHORT).show();
        } else {
            Log.w(TAG, "startBleScan: bleManager null");
        }
    }

    @Override
    public void onScanResult(BluetoothDevice device) {
        runOnUiThread(() -> {
            if (deviceListAdapter != null) {
                deviceListAdapter.addDevice(device);
                Log.d(TAG, "onScanResult -> device ajouté: " + safeGetDeviceName(device));
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Log.w(TAG, "Erreur BLE: " + message);
            Toast.makeText(this, "Erreur BLE: " + message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onDeviceClick(BluetoothDevice device) {
        if (bleManager != null) bleManager.stopScan();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission BLUETOOTH_CONNECT manquante", Toast.LENGTH_SHORT).show();
                checkPermissionsAndStartScan();
                return;
            }
        }

        Intent intent = new Intent(this, DeviceControlActivity.class);
        intent.putExtra("device", device);
        startActivity(intent);
    }

    private String safeGetDeviceName(BluetoothDevice device) {
        if (device == null) return "Inconnu";
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Permission refusée pour getName", e);
        }
        try {
            String addr = device.getAddress();
            return addr != null ? addr : "Inconnu";
        } catch (SecurityException e) {
            Log.w(TAG, "Permission refusée pour getAddress", e);
            return "Inconnu";
        }
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        runOnUiThread(() -> {
            String name = (device != null) ? safeGetDeviceName(device) : "Inconnu";
            Log.d(TAG, "onConnected: " + name);
            Toast.makeText(MainActivity.this, "Connecté à " + name, Toast.LENGTH_SHORT).show();
            if (bleManager != null) {
                bleManager.stopScan();
                Log.d(TAG, "Scan arrêté après connexion");
            }
        });
    }

    @Override
    public void onDisconnected(BluetoothDevice device) {
        runOnUiThread(() -> {
            String name = (device != null) ? safeGetDeviceName(device) : "Inconnu";
            Log.d(TAG, "onDisconnected: " + name);
            Toast.makeText(MainActivity.this, "Déconnecté de " + name, Toast.LENGTH_SHORT).show();
            if (bleManager != null) {
                Log.d(TAG, "Relancement du scan après déconnexion");
                bleManager.startScan();
            }
        });
    }

    @Override
    public void onServicesDiscovered(android.bluetooth.BluetoothGatt gatt) {
        runOnUiThread(() -> {
            String info = (gatt != null && gatt.getDevice() != null) ? safeGetDeviceName(gatt.getDevice()) : "device";
            Log.d(TAG, "onServicesDiscovered for " + info);
            Toast.makeText(MainActivity.this, "Services découverts pour " + info, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onCharacteristicRead(android.bluetooth.BluetoothGattCharacteristic characteristic) {
        runOnUiThread(() -> {
            if (characteristic == null) {
                Log.w(TAG, "onCharacteristicRead: characteristic null");
                return;
            }
            String uuid = characteristic.getUuid() != null ? characteristic.getUuid().toString() : "unknown";
            Log.d(TAG, "onCharacteristicRead uuid=" + uuid);
            Toast.makeText(MainActivity.this, "Caractéristique lue: " + uuid, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onCharacteristicChanged(android.bluetooth.BluetoothGattCharacteristic characteristic) {
        runOnUiThread(() -> {
            if (characteristic == null) {
                Log.w(TAG, "onCharacteristicChanged: characteristic null");
                return;
            }
            String uuid = characteristic.getUuid() != null ? characteristic.getUuid().toString() : "unknown";
            Log.d(TAG, "onCharacteristicChanged uuid=" + uuid);
            Toast.makeText(MainActivity.this, "Notification reçue: " + uuid, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDescriptorWrite(android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
        runOnUiThread(() -> {
            String uuid = (descriptor != null && descriptor.getUuid() != null) ? descriptor.getUuid().toString() : "unknown";
            Log.d(TAG, "onDescriptorWrite uuid=" + uuid + " status=" + status);
            Toast.makeText(MainActivity.this, "Descriptor écrit: " + uuid + " (status=" + status + ")", Toast.LENGTH_SHORT).show();
        });
    }
}
