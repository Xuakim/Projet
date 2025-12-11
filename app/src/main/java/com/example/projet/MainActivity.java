package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private static final String TAG = "BLE_DEBUG";

    // --- UUIDs Microphone Control Service ---
    private static final UUID MIC_CONTROL_SERVICE_UUID = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    private static final UUID MIC_MUTE_UUID = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");

    // --- UUIDs Audio Input Control Service ---
    private static final UUID AUDIO_INPUT_CONTROL_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002b77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTINGS_PROPERTIES_UUID = UUID.fromString("00002b78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID = UUID.fromString("00002b79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS_UUID = UUID.fromString("00002b7a-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_CONTROL_POINT_UUID = UUID.fromString("00002b7b-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION_UUID = UUID.fromString("00002b7c-0000-1000-8000-00805f9b34fb");

    // --- Descriptor UUID ---
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // --- UI Elements ---
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();
    private TextView dataDisplay;

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    startDiscovery();
                } else {
                    Toast.makeText(this, "L'activation du Bluetooth a été refusée.", Toast.LENGTH_SHORT).show();
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

        deviceListView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevices);
        deviceListView.setAdapter(deviceListAdapter);
        dataDisplay = findViewById(R.id.data_display);

        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            String deviceInfo = discoveredDevices.get(position);
            String deviceAddress = deviceInfo.substring(deviceInfo.length() - 17);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            }
            deviceListView.setVisibility(View.GONE);
            dataDisplay.setVisibility(View.VISIBLE);
            dataDisplay.setText("Connexion à " + deviceAddress + "...");
            connectToDevice(device);
        });

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        List<String> permissionsNotGranted = new ArrayList<>();
        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsNotGranted.add(permission);
            }
        }

        if (!permissionsNotGranted.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNotGranted.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
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
                checkAndRequestPermissions();
            }
        } else {
            startDiscovery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
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

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    String deviceName = device.getName();
                    String deviceAddress = device.getAddress();
                    String deviceInfo = (deviceName != null ? deviceName : "Appareil inconnu") + "\n" + deviceAddress;
                    if (!discoveredDevices.contains(deviceInfo)) {
                        discoveredDevices.add(deviceInfo);
                        deviceListAdapter.notifyDataSetChanged();
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Toast.makeText(MainActivity.this, "Recherche terminée.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private void startDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions();
            return;
        }
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
        Toast.makeText(this, "Recherche des appareils en cours...", Toast.LENGTH_SHORT).show();
        discoveredDevices.clear();
        deviceListAdapter.notifyDataSetChanged();
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connecté au serveur GATT.");
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Déconnecté du serveur GATT.");
                runOnUiThread(() -> {
                    dataDisplay.setText("Déconnecté. Retour à la liste des appareils.");
                    deviceListView.setVisibility(View.VISIBLE);
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered a reçu un statut d'erreur: " + status);
                return;
            }

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;

            // --- Microphone Control Service ---
            BluetoothGattService micControlService = gatt.getService(MIC_CONTROL_SERVICE_UUID);
            if (micControlService != null) {
                Log.d(TAG, "Service Microphone Control trouvé !");
                BluetoothGattCharacteristic muteChar = micControlService.getCharacteristic(MIC_MUTE_UUID);
                if (muteChar != null) {
                    Log.d(TAG, "Caractéristique Mute trouvée !");
                    subscribeToCharacteristic(gatt, muteChar);
                }
            }

            // --- Audio Input Control Service ---
            BluetoothGattService audioInputService = gatt.getService(AUDIO_INPUT_CONTROL_SERVICE_UUID);
            if (audioInputService != null) {
                Log.d(TAG, "Service Audio Input Control trouvé !");
                runOnUiThread(() -> dataDisplay.append("\nService Audio Input Control trouvé !\n"));

                readCharacteristic(gatt, audioInputService.getCharacteristic(GAIN_SETTINGS_PROPERTIES_UUID));
                readCharacteristic(gatt, audioInputService.getCharacteristic(AUDIO_INPUT_TYPE_UUID));
                readCharacteristic(gatt, audioInputService.getCharacteristic(AUDIO_INPUT_STATUS_UUID));
                readCharacteristic(gatt, audioInputService.getCharacteristic(AUDIO_INPUT_DESCRIPTION_UUID));

                // Subscribe to Audio Input State
                subscribeToCharacteristic(gatt, audioInputService.getCharacteristic(AUDIO_INPUT_STATE_UUID));
            }
        }

        private void subscribeToCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) return;
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;

            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }

        private void readCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) return;
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) return;
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
            gatt.readCharacteristic(characteristic);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            final UUID uuid = characteristic.getUuid();
            final byte[] data = characteristic.getValue();

            String info = "";
            if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) {
                info = "Audio Input Description: " + new String(data);
            } else if (AUDIO_INPUT_TYPE_UUID.equals(uuid)) {
                // Implement parsing based on spec
                info = "Audio Input Type: " + bytesToHex(data);
            } else if (GAIN_SETTINGS_PROPERTIES_UUID.equals(uuid)) {
                // Implement parsing based on spec
                info = "Gain Settings: " + bytesToHex(data);
            } else if (AUDIO_INPUT_STATUS_UUID.equals(uuid)) {
                // Implement parsing based on spec
                info = "Audio Input Status: " + bytesToHex(data);
            }

            final String finalInfo = info;
            Log.d(TAG, "Read " + finalInfo);
            runOnUiThread(() -> dataDisplay.append(finalInfo + "\n"));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Abonnement réussi pour la caractéristique: " + descriptor.getCharacteristic().getUuid());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            final UUID uuid = characteristic.getUuid();
            final byte[] data = characteristic.getValue();
            String info = "";

            if (MIC_MUTE_UUID.equals(uuid)) {
                String muteState = (data[0] == 1) ? "Muet" : "Non muet";
                info = "État du microphone: " + muteState;
            } else if (AUDIO_INPUT_STATE_UUID.equals(uuid)) {
                if (data.length >= 3) {
                    int gain = data[0];
                    String muteState = (data[1] == 1) ? "Muet" : "Non muet";
                    String gainMode = (data[2] == 0) ? "Manual" : "Automatic";
                    info = String.format("Audio State: Gain=%d, Mute=%s, Mode=%s", gain, muteState, gainMode);
                }
            }
            final String finalInfo = info;
            Log.d(TAG, "Changed " + finalInfo);
            runOnUiThread(() -> dataDisplay.append(finalInfo + "\n"));
        }
    };

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered", e);
        }
    }
}
