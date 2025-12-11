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

    // Services
    private static final UUID MICP_SERVICE_UUID = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");

    // Characteristics
    private static final UUID UUID_MUTE = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_STATE = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_GAIN_SETTINGS = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_TYPE = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_STATUS = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_CONTROL_POINT = UUID.fromString("00002B7B-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_AUDIO_INPUT_DESCRIPTION = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");

    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private TextView headerText;
    private ProgressBar progressBar;
    private TextView micMuteStateView;
    private TextView micGainStateView;
    private TextView micGainModeStateView;
    private TextView micTypeView;
    private TextView micStatusView;
    private TextView micDescriptionView;
    private ImageView muteIcon;

    private BluetoothGatt bluetoothGatt;
    private final Queue<BluetoothGattCharacteristic> readQueue = new LinkedList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private BluetoothGattCharacteristic muteCharacteristic;
    private boolean isMuted = false;

    private static final long READ_DELAY_MS = 60;

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

        muteIcon.setOnClickListener(v -> toggleMute());

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
    
    private void toggleMute() {
        if (bluetoothGatt == null || muteCharacteristic == null) {
            Toast.makeText(this, "Caractéristique Mute non disponible.", Toast.LENGTH_SHORT).show();
            return;
        }

        if ((muteCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            Toast.makeText(this, "Caractéristique Mute non accessible en écriture.", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] newValue = new byte[1];
        newValue[0] = (byte) (isMuted ? 0x00 : 0x01); // 0x00 = Unmuted, 0x01 = Muted

        Log.d(TAG, "Writing mute value: " + newValue[0]);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        
        // Use the modern write API for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(muteCharacteristic, newValue, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            muteCharacteristic.setValue(newValue);
            bluetoothGatt.writeCharacteristic(muteCharacteristic);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected - discovering services");
                if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                gatt.discoverServices();
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
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                return;
            }

            readQueue.clear();

            BluetoothGattService aicsService = gatt.getService(AICS_SERVICE_UUID);
            BluetoothGattService micpService = gatt.getService(MICP_SERVICE_UUID);

            if (aicsService == null && micpService == null) {
                Log.w(TAG, "Neither AICS nor MICP found");
                runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Service Microphone / Audio Input introuvable.", Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                });
                return;
            }

            BluetoothGattCharacteristic stateCharForNotification = null;

            if (aicsService != null) {
                Log.i(TAG, "Found AICS Service");
                addIfNotNullToQueue(aicsService.getCharacteristic(UUID_GAIN_SETTINGS));
                addIfNotNullToQueue(aicsService.getCharacteristic(UUID_AUDIO_INPUT_TYPE));
                addIfNotNullToQueue(aicsService.getCharacteristic(UUID_AUDIO_INPUT_STATUS));
                addIfNotNullToQueue(aicsService.getCharacteristic(UUID_AUDIO_INPUT_DESCRIPTION));
                
                BluetoothGattCharacteristic aicsState = aicsService.getCharacteristic(UUID_AUDIO_INPUT_STATE);
                addIfNotNullToQueue(aicsState);
                stateCharForNotification = aicsState;
            }

            if (micpService != null) {
                Log.i(TAG, "Found MICP Service");
                muteCharacteristic = micpService.getCharacteristic(UUID_MUTE);
                addIfNotNullToQueue(muteCharacteristic);
                if (stateCharForNotification == null) {
                    stateCharForNotification = muteCharacteristic;
                }
            }

            if (stateCharForNotification != null) {
                enableNotifications(gatt, stateCharForNotification);
            } else {
                readNextWithDelay();
            }
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.d(TAG, "Descriptor written: " + descriptor.getUuid() + " status=" + status);
            readNextWithDelay();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            if (UUID_MUTE.equals(characteristic.getUuid())) {
                runOnUiThread(() -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Mute write successful");
                        // Optimistic UI update
                        isMuted = !isMuted;
                        updateMuteUI();
                    } else {
                        Toast.makeText(MicrophoneActivity.this, "Mute command failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        
        private void handleCharacteristicRead(BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseAndDisplay(characteristic, value);
            } else {
                Log.w(TAG, "Characteristic read failed: " + characteristic.getUuid() + " status=" + status);
            }
            readNextWithDelay();
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
            super.onCharacteristicRead(gatt, characteristic, value, status);
            handleCharacteristicRead(characteristic, value, status);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicRead(characteristic, characteristic.getValue(), status);
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            super.onCharacteristicChanged(gatt, characteristic, value);
            parseAndDisplay(characteristic, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                parseAndDisplay(characteristic, characteristic.getValue());
            }
        }
    };

    private void addIfNotNullToQueue(BluetoothGattCharacteristic c) {
        if (c != null) readQueue.add(c);
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
            readNext(); // sécurité
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;

        try {
            if (!bluetoothGatt.readCharacteristic(c)) {
                Log.w(TAG, "Failed to initiate read for " + c.getUuid());
                readNextWithDelay();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception reading " + c.getUuid(), e);
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
    
    private void updateMuteUI() {
        micMuteStateView.setText(isMuted ? "Mute" : "Actif");
        muteIcon.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
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
                        micGainModeStateView.setText(gainMode == 1 ? "Auto" : "Manuel");
                        isMuted = (mute == 1);
                        updateMuteUI();
                    } else {
                        micGainStateView.setText(bytesToHex(data));
                    }
                } else if (UUID_MUTE.equals(uuid)) {
                    int mute = data[0] & 0xFF;
                    isMuted = (mute == 1);
                    updateMuteUI();
                } else if (UUID_AUDIO_INPUT_TYPE.equals(uuid)) {
                    int type = data[0] & 0xFF;
                    micTypeView.setText(audioInputTypeLabel(type) + " (0x" + Integer.toHexString(type) + ")");
                } else if (UUID_AUDIO_INPUT_STATUS.equals(uuid)) {
                    micStatusView.setText(bytesToHex(data));
                } else if (UUID_AUDIO_INPUT_DESCRIPTION.equals(uuid)) {
                    micDescriptionView.setText(safeUtf8(data));
                } else if (UUID_GAIN_SETTINGS.equals(uuid)) {
                    Log.d(TAG, "Gain Settings Properties: " + bytesToHex(data));
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
            case 0x05: return "Radio (FM/AM)";
            case 0x06: return "Streaming";
            default: return "Inconnu";
        }
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private String safeUtf8(byte[] bytes) {
        if (bytes == null) return "";
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return bytesToHex(bytes);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt.close();
            }
            bluetoothGatt = null;
        }
    }
}
