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
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class MicrophoneActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    // --- UUIDs for AICS Profile ---
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTING_PROPERTIES_UUID = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS_UUID = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION_UUID = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGatt bluetoothGatt;
    private final Queue<BluetoothGattCharacteristic> characteristicReadQueue = new LinkedList<>();

    // UI Elements
    private TextView deviceNameTextView;
    private TextView deviceAddressTextView;
    private TextView connectionStatusTextView;
    private TextView characteristicsDisplayTextView;
    private ProgressBar detailProgressBar;

    // Characteristic data holders
    private String micMuteState = "N/A";
    private String micGainState = "N/A";
    private String micGainModeState = "N/A";
    private String micGainProperties = "N/A";
    private String micInputType = "N/A";
    private String micStatus = "N/A";
    private String micDescription = "N/A";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_detail);

        deviceNameTextView = findViewById(R.id.device_name_detail);
        deviceAddressTextView = findViewById(R.id.device_address_detail);
        connectionStatusTextView = findViewById(R.id.connection_status_detail);
        characteristicsDisplayTextView = findViewById(R.id.characteristics_display);
        detailProgressBar = findViewById(R.id.detail_progress_bar);

        String deviceAddress = getIntent().getStringExtra(EXTRA_DEVICE_ADDRESS);
        String deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);

        deviceNameTextView.setText(deviceName != null ? deviceName : "Appareil Inconnu");
        deviceAddressTextView.setText(deviceAddress);

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || deviceAddress == null) {
            Toast.makeText(this, "Erreur: Adaptateur Bluetooth ou adresse non disponible.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
        connectToDevice(device);
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
                runOnUiThread(() -> {
                    connectionStatusTextView.setText("Connecté");
                    detailProgressBar.setVisibility(View.GONE);
                });
                if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> connectionStatusTextView.setText("Déconnecté"));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(AICS_SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() -> Toast.makeText(MicrophoneActivity.this, "Service AICS non trouvé.", Toast.LENGTH_SHORT).show());
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

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            processReadQueue();
        }

        // Corrected callback for Android 13+
        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseCharacteristic(characteristic.getUuid(), value);
            }
            processReadQueue();
        }

        // Deprecated callback for older Android versions
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    parseCharacteristic(characteristic.getUuid(), characteristic.getValue());
                }
                processReadQueue();
            }
        }

        // Corrected callback for Android 13+
        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            parseCharacteristic(characteristic.getUuid(), value);
        }

        // Deprecated callback for older Android versions
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                parseCharacteristic(characteristic.getUuid(), characteristic.getValue());
            }
        }
    };

    private void processReadQueue() {
        if (!characteristicReadQueue.isEmpty()) {
            BluetoothGattCharacteristic characteristic = characteristicReadQueue.poll();
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.readCharacteristic(characteristic);
            }
        }
    }

    private void addCharacteristicToQueue(BluetoothGattService service, UUID uuid) {
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(uuid);
        if (characteristic != null) {
            characteristicReadQueue.add(characteristic);
        }
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

    private void parseCharacteristic(final UUID uuid, final byte[] data) {
        if (data == null) return;

        if (AUDIO_INPUT_STATE_UUID.equals(uuid) && data.length >= 3) {
            micGainState = data[0] + " dB";
            micMuteState = (data[1] & 0xFF) == 1 ? "Mute" : "Non Mute";
            micGainModeState = (data[2] & 0xFF) == 1 ? "Automatique" : "Manuel";
        } else if (GAIN_SETTING_PROPERTIES_UUID.equals(uuid) && data.length >= 3) {
            micGainProperties = String.format("Unit: %d, Min: %d, Max: %d", data[0] & 0xFF, data[1], data[2]);
        } else if (AUDIO_INPUT_TYPE_UUID.equals(uuid) && data.length > 0) {
            micInputType = ((data[0] & 0xFF) == 0x07) ? "Microphone" : "Autre";
        } else if (AUDIO_INPUT_STATUS_UUID.equals(uuid) && data.length > 0) {
            micStatus = ((data[0] & 0xFF) == 1) ? "Actif" : "Inactif";
        } else if (AUDIO_INPUT_DESCRIPTION_UUID.equals(uuid)) {
            micDescription = new String(data, StandardCharsets.UTF_8);
        }
        displayAllData();
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
        runOnUiThread(() -> {
            characteristicsDisplayTextView.setText(formattedData);
            characteristicsDisplayTextView.setVisibility(View.VISIBLE);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
            }
        }
    }
}
