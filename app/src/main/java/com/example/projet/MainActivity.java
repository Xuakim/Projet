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
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;

public class MainActivity extends AppCompatActivity implements DeviceListAdapter.OnDeviceClickListener {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean scanning;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private DeviceListAdapter deviceListAdapter;
    private final List<BluetoothDevice> discoveredBtDevices = new ArrayList<>();
    private BluetoothGatt bluetoothGatt;
    private TextView dataDisplay;
    private ProgressBar scanProgressBar;

    private static final long SCAN_PERIOD = 10000; // 10 secondes

    // --- UUIDs pour le profil AICS (Audio Input Control Service) ---
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTING_PROPERTIES_UUID = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS_UUID = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_CONTROL_POINT_UUID = UUID.fromString("00002B7B-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION_UUID = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Queue<BluetoothGattCharacteristic> characteristicReadQueue = new LinkedList<>();

    private String micMuteState = "N/A";
    private String micGainState = "N/A";
    private String micGainModeState = "N/A";
    private String micGainProperties = "N/A";
    private String micInputType = "N/A";
    private String micStatus = "N/A";
    private String micDescription = "N/A";

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    scanLeDevice(true);
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
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        RecyclerView devicesRecyclerView = findViewById(R.id.devices_recycler_view);
        deviceListAdapter = new DeviceListAdapter(discoveredBtDevices, this);
        devicesRecyclerView.setAdapter(deviceListAdapter);
        dataDisplay = findViewById(R.id.data_display);
        scanProgressBar = findViewById(R.id.scan_progress_bar);

        checkAndRequestPermissions();
    }

    @Override
    public void onDeviceClick(BluetoothDevice device) {
        scanLeDevice(false);
        connectToDevice(device);
    }

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            String deviceName = device.getName() != null ? device.getName() : "appareil inconnu";
            runOnUiThread(() -> dataDisplay.setText("Connexion à " + deviceName + "..."));
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
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

    private void displayAllData() {
        final String formattedData = "--- État du Microphone ---\n" +
                "Mute: " + micMuteState + "\n" +
                "Gain: " + micGainState + "\n" +
                "Mode de gain: " + micGainModeState + "\n" +
                "Propriétés de gain: " + micGainProperties + "\n" +
                "Type d'entrée: " + micInputType + "\n" +
                "Statut: " + micStatus + "\n" +
                "Description: " + micDescription;
        runOnUiThread(() -> dataDisplay.setText(formattedData));
    }

    private void setNotificationForCharacteristic(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        bluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connecté au serveur GATT", Toast.LENGTH_SHORT).show());
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    characteristicReadQueue.clear();
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Déconnecté du serveur GATT.", Toast.LENGTH_SHORT).show();
                    dataDisplay.setText("En attente de connexion...");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            BluetoothGattService service = gatt.getService(AICS_SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Service de microphone non trouvé", Toast.LENGTH_SHORT).show());
                return;
            }

            characteristicReadQueue.clear();
            addCharacteristicToQueue(service, GAIN_SETTING_PROPERTIES_UUID);
            addCharacteristicToQueue(service, AUDIO_INPUT_TYPE_UUID);
            addCharacteristicToQueue(service, AUDIO_INPUT_STATUS_UUID);
            addCharacteristicToQueue(service, AUDIO_INPUT_DESCRIPTION_UUID);
            addCharacteristicToQueue(service, AUDIO_INPUT_STATE_UUID);

            BluetoothGattCharacteristic inputStateChar = service.getCharacteristic(AUDIO_INPUT_STATE_UUID);
            if (inputStateChar != null) {
                setNotificationForCharacteristic(inputStateChar, true);
            } else {
                 processReadQueue();
            }
        }

        private void addCharacteristicToQueue(BluetoothGattService service, UUID charUuid) {
            BluetoothGattCharacteristic characteristic = service.getCharacteristic(charUuid);
            if (characteristic != null) {
                characteristicReadQueue.add(characteristic);
            }
        }

        private void processReadQueue() {
            if (!characteristicReadQueue.isEmpty()) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                bluetoothGatt.readCharacteristic(characteristicReadQueue.poll());
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            processReadQueue();
        }
        
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseCharacteristic(characteristic);
            }
            processReadQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            parseCharacteristic(characteristic);
        }

        private void parseCharacteristic(final BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();
            byte[] data = characteristic.getValue();
            if (data == null) return;

            if (AUDIO_INPUT_STATE_UUID.equals(uuid) && data.length >= 3) {
                final int gain = data[0];
                micGainState = gain + " dB";
                micMuteState = (data[1] & 0xFF) == 1 ? "Mute" : "Non Mute";
                micGainModeState = (data[2] & 0xFF) == 1 ? "Automatique" : "Manuel";
            } else if (GAIN_SETTING_PROPERTIES_UUID.equals(uuid) && data.length >= 3) {
                micGainProperties = String.format("Unit: %d, Min: %d, Max: %d", data[0] & 0xFF, data[1], data[2]);
            } else if (AUDIO_INPUT_TYPE_UUID.equals(uuid) && data.length > 0) {
                micInputType = ((data[0] & 0xFF) == 0x07) ? "Microphone" : "Autre";
            } else if (AUDIO_INPUT_STATUS_UUID.equals(uuid) && data.length > 0) {
                micStatus = ((data[0] & 0xFF) == 0x01) ? "Actif" : "Inactif";
            } else if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) {
                micDescription = new String(data, StandardCharsets.UTF_8);
            }

            displayAllData();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        scanLeDevice(false);
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }
    }
}
