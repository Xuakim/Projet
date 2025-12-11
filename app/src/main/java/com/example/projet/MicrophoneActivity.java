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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class MicrophoneActivity extends AppCompatActivity {

    private static final String TAG = "MicrophoneActivity";

    // --- UUIDs for AICS & MICP Profile ---
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID MICP_SERVICE_UUID = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb"); // Microphone Control Profile

    // Characteristics
    private static final UUID UUID_AUDIO_INPUT_STATE = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_GAIN_SETTINGS = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_TYPE = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_STATUS = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_DESCRIPTION = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");

    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // UI Elements
    private TextView headerText;
    private ProgressBar progressBar;
    private TextView micMuteStateView, micGainStateView, micGainModeStateView, micTypeView, micStatusView, micDescriptionView;
    private ImageView muteIcon;

    private BluetoothGatt bluetoothGatt;
    private final Queue<BluetoothGattCharacteristic> readQueue = new LinkedList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final long READ_DELAY_MS = 100;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_microphone);

        headerText = findViewById(R.id.header_text);
        progressBar = findViewById(R.id.microphone_progress_bar);
        micMuteStateView = findViewById(R.id.mic_mute_state);
        micGainStateView = findViewById(R.id.mic_gain_state);
        micGainModeStateView = findViewById(R.id.mic_gain_mode_state);
        micTypeView = findViewById(R.id.mic_type);
        micStatusView = findViewById(R.id.mic_status);
        micDescriptionView = findViewById(R.id.mic_description);
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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission BLUETOOTH_CONNECT requise.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
        progressBar.setVisibility(View.VISIBLE);
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected - discovering services");
                if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                    mainHandler.postDelayed(gatt::discoverServices, 500);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected");
                runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Déconnecté.", Toast.LENGTH_SHORT).show();
                    progressBar.setVisibility(View.GONE);
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(AICS_SERVICE_UUID);
            if (service == null) service = gatt.getService(MICP_SERVICE_UUID);

            if (service == null) {
                Log.w(TAG, "Neither AICS nor MICP found");
                return;
            }

            readQueue.clear();
            addIfNotNullToQueue(service.getCharacteristic(UUID_GAIN_SETTINGS));
            addIfNotNullToQueue(service.getCharacteristic(UUID_AUDIO_INPUT_TYPE));
            addIfNotNullToQueue(service.getCharacteristic(UUID_AUDIO_INPUT_STATUS));
            addIfNotNullToQueue(service.getCharacteristic(UUID_AUDIO_INPUT_DESCRIPTION));
            addIfNotNullToQueue(service.getCharacteristic(UUID_AUDIO_INPUT_STATE));

            BluetoothGattCharacteristic stateChar = service.getCharacteristic(UUID_AUDIO_INPUT_STATE);
            if (stateChar != null) {
                enableNotifications(gatt, stateChar);
            } else {
                readNextWithDelay();
            }
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, int status) {
            readNextWithDelay();
        }
        
        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseAndDisplay(characteristic, value);
            }
            readNextWithDelay();
        }
        
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    parseAndDisplay(characteristic, characteristic.getValue());
                }
                readNextWithDelay();
            }
        }
        
        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            parseAndDisplay(characteristic, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                parseAndDisplay(characteristic, characteristic.getValue());
            }
        }
    };

    private void addIfNotNullToQueue(BluetoothGattCharacteristic c) {
        if (c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
            readQueue.add(c);
    }

    private void readNextWithDelay() {
        mainHandler.postDelayed(this::readNext, READ_DELAY_MS);
    }

    private void readNext() {
        if (readQueue.isEmpty()) {
            runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            return;
        }

        BluetoothGattCharacteristic c = readQueue.poll();
        if (c == null) {
            readNext();
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;

        if (!bluetoothGatt.readCharacteristic(c)) {
            Log.w(TAG, "Failed to initiate read for " + c.getUuid());
            readNextWithDelay();
        }
    }

    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;

        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd != null) {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
        } else {
            readNextWithDelay();
        }
    }

    private void parseAndDisplay(BluetoothGattCharacteristic c, byte[] data) {
        if (c == null || data == null) return;

        UUID uuid = c.getUuid();
        runOnUiThread(() -> {
            try {
                if (UUID_AUDIO_INPUT_STATE.equals(uuid)) {
                    if (data.length >= 3) {
                        int gain = data[0];
                        int mute = data[1] & 0xFF;
                        int gainMode = data[2] & 0xFF;

                        micGainStateView.setText(gain + " dB");
                        micMuteStateView.setText(mute == 0 ? "Désactivée" : "Activée");
                        micGainModeStateView.setText(gainMode == 0 ? "Manuel" : "Automatique");
                        muteIcon.setImageResource(mute == 0 ? R.drawable.ic_mic : R.drawable.ic_mic_off);
                    }
                } else if (UUID_AUDIO_INPUT_TYPE.equals(uuid) && data.length > 0) {
                    micTypeView.setText(String.format("Type : %s", audioInputTypeLabel(data[0] & 0xFF)));
                } else if (UUID_AUDIO_INPUT_STATUS.equals(uuid) && data.length > 0) {
                     micStatusView.setText(String.format("Statut : %s", (data[0] & 0xFF) == 1 ? "Actif" : "Inactif"));
                } else if (UUID_AUDIO_INPUT_DESCRIPTION.equals(uuid)) {
                    micDescriptionView.setText(String.format("Description : %s", new String(data, StandardCharsets.UTF_8)));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing characteristic " + uuid, e);
            }
        });
    }
    
    private String audioInputTypeLabel(int type) {
        switch (type) {
            case 0x00: return "Non spécifié";
            case 0x01: return "Bluetooth";
            case 0x02: return "Microphone";
            case 0x03: return "Analogique";
            case 0x04: return "Numérique";
            case 0x05: return "Radio";
            case 0x06: return "Streaming";
            default: return "Inconnu (" + type + ")";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
            }
            bluetoothGatt = null;
        }
    }
}