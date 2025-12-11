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
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class DeviceDetailActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_ADDRESS = "extra_device_address";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice device;
    private BluetoothGatt bluetoothGatt;

    private TextView deviceNameTextView;
    private TextView deviceAddressTextView;
    private TextView connectionStatusTextView;
    private TextView characteristicsDisplayTextView;
    private ProgressBar detailProgressBar;

    // --- UUIDs pour le profil AICS (Audio Input Control Service) ---
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTING_PROPERTIES_UUID = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS_UUID = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_detail);

        deviceNameTextView = findViewById(R.id.device_name_detail);
        deviceAddressTextView = findViewById(R.id.device_address_detail);
        connectionStatusTextView = findViewById(R.id.connection_status_detail);
        characteristicsDisplayTextView = findViewById(R.id.characteristics_display);
        detailProgressBar = findViewById(R.id.detail_progress_bar);

        String deviceAddress = getIntent().getStringExtra(EXTRA_DEVICE_ADDRESS);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || deviceAddress == null) {
            Toast.makeText(this, "Erreur: Adaptateur Bluetooth ou adresse non disponible.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            deviceNameTextView.setText(device.getName() != null ? device.getName() : "Appareil inconnu");
            deviceAddressTextView.setText(device.getAddress());
            connectToDevice();
        }
    }

    private void connectToDevice() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> {
                    connectionStatusTextView.setText("Connecté");
                    detailProgressBar.setVisibility(View.GONE);
                    Toast.makeText(DeviceDetailActivity.this, "Connecté au serveur GATT", Toast.LENGTH_SHORT).show();
                });
                if (ActivityCompat.checkSelfPermission(DeviceDetailActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    connectionStatusTextView.setText("Déconnecté");
                    Toast.makeText(DeviceDetailActivity.this, "Déconnecté du serveur GATT.", Toast.LENGTH_SHORT).show();
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            BluetoothGattService service = gatt.getService(AICS_SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() -> Toast.makeText(DeviceDetailActivity.this, "Service de microphone non trouvé", Toast.LENGTH_SHORT).show());
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
                if (ActivityCompat.checkSelfPermission(DeviceDetailActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
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
    
    private void displayAllData() {
        final String formattedData = "--- État du Microphone ---\n" +
                "Mute: " + micMuteState + "\n" +
                "Gain: " + micGainState + "\n" +
                "Mode de gain: " + micGainModeState + "\n" +
                "Propriétés de gain: " + micGainProperties + "\n" +
                "Type d'entrée: " + micInputType + "\n" +
                "Statut: " + micStatus + "\n" +
                "Description: " + micDescription;
        runOnUiThread(() -> {
            characteristicsDisplayTextView.setText(formattedData);
            characteristicsDisplayTextView.setVisibility(View.VISIBLE);
        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
                bluetoothGatt = null;
            }
        }
    }
}