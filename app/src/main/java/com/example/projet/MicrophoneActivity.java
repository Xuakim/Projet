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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class MicrophoneActivity extends AppCompatActivity {
    private static final String TAG = "MicrophoneActivity";

    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATE_UUID = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTING_PROPERTIES_UUID = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE_UUID = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS_UUID = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION_UUID = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private TextView headerText;
    private ProgressBar progressBar;

    private TextView micMuteStateView;
    private TextView micGainStateView;
    private TextView micGainModeStateView;
    private ImageView muteIcon;

    private BluetoothGatt bluetoothGatt;
    private final Queue<BluetoothGattCharacteristic> characteristicReadQueue = new LinkedList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_microphone);

        headerText = findViewById(R.id.header_text);
        progressBar = findViewById(R.id.microphone_progress_bar);
        micMuteStateView = findViewById(R.id.mic_mute_state);
        micGainStateView = findViewById(R.id.mic_gain_state);
        micGainModeStateView = findViewById(R.id.mic_gain_mode_state);
        muteIcon = findViewById(R.id.mute_icon);

        Intent intent = getIntent();
        String deviceAddress = intent.getStringExtra("device_address");
        String deviceName = intent.getStringExtra("device_name");

        headerText.setText(deviceName != null ? deviceName : "Appareil BLE");

        if (deviceAddress == null) {
            Toast.makeText(this, "Adresse de l'appareil manquante.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth non supporté.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission BLUETOOTH_CONNECT requise.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread(() -> Toast.makeText(MicrophoneActivity.this, "Connecté au périphérique", Toast.LENGTH_SHORT).show());
                if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                characteristicReadQueue.clear();
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Déconnecté.", Toast.LENGTH_SHORT).show();
                    progressBar.setVisibility(View.GONE);
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            BluetoothGattService service = gatt.getService(AICS_SERVICE_UUID);
            if (service == null) {
                runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Service Microphone non trouvé.", Toast.LENGTH_SHORT).show();
                    progressBar.setVisibility(View.GONE);
                });
                return;
            }

            BluetoothGattCharacteristic gainPropertiesChar = service.getCharacteristic(GAIN_SETTING_PROPERTIES_UUID);
            if (gainPropertiesChar != null) characteristicReadQueue.add(gainPropertiesChar);

            BluetoothGattCharacteristic inputTypeChar = service.getCharacteristic(AUDIO_INPUT_TYPE_UUID);
            if (inputTypeChar != null) characteristicReadQueue.add(inputTypeChar);

            BluetoothGattCharacteristic inputStatusChar = service.getCharacteristic(AUDIO_INPUT_STATUS_UUID);
            if (inputStatusChar != null) characteristicReadQueue.add(inputStatusChar);

            BluetoothGattCharacteristic inputDescriptionChar = service.getCharacteristic(AUDIO_INPUT_DESCRIPTION_UUID);
            if (inputDescriptionChar != null) characteristicReadQueue.add(inputDescriptionChar);

            BluetoothGattCharacteristic inputStateChar = service.getCharacteristic(AUDIO_INPUT_STATE_UUID);
            if (inputStateChar != null) {
                setNotificationForCharacteristic(inputStateChar, true);
            } else {
                if (!characteristicReadQueue.isEmpty()) {
                    readNextCharacteristic();
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                 readNextCharacteristic();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseCharacteristic(characteristic, value);
            }
            readNextCharacteristic();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            parseCharacteristic(characteristic, value);
        }
    };

    private void readNextCharacteristic() {
        if (!characteristicReadQueue.isEmpty()) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            bluetoothGatt.readCharacteristic(characteristicReadQueue.poll());
        } else {
            runOnUiThread(() -> progressBar.setVisibility(View.GONE));
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

    private void parseCharacteristic(final BluetoothGattCharacteristic characteristic, final byte[] data) {
        UUID uuid = characteristic.getUuid();
        if (data == null) return;

        runOnUiThread(() -> {
            if (AUDIO_INPUT_STATE_UUID.equals(uuid) && data.length >= 3) {
                final int gain = data[0]; // sint8
                final int mute = data[1] & 0xFF; // uint8
                final int gainMode = data[2] & 0xFF; // uint8

                micGainStateView.setText(gain + " dB");
                micMuteStateView.setText((mute == 1) ? "Mute" : "Non Mute");
                micGainModeStateView.setText((gainMode == 1) ? "Automatique" : "Manuel");

                if (mute == 1) {
                    muteIcon.setImageResource(R.drawable.ic_mic_off);
                    muteIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_light));
                } else {
                    muteIcon.setImageResource(R.drawable.ic_mic);
                    muteIcon.setColorFilter(null);
                }
            }
        });
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
