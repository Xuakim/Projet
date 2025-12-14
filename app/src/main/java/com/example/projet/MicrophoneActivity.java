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
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.card.MaterialCardView;

import java.util.UUID;

public class MicrophoneActivity extends AppCompatActivity {

    private static final String TAG = "MicrophoneActivity";

    // UUIDs for Microphone Control Profile (MICP)
    private static final UUID MICP_SERVICE_UUID = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    private static final UUID MUTE_CHARACTERISTIC_UUID = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // UUIDs for Audio Input Control Service (AICS)
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AICS_STATE_CHARACTERISTIC_UUID = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID AICS_GAIN_SETTING_PROPERTIES_CHARACTERISTIC_UUID = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AICS_CONTROL_POINT_CHARACTERISTIC_UUID = UUID.fromString("00002B7B-0000-1000-8000-00805f9b34fb");

    // UI Elements
    private TextView headerText, micMuteStateView, micGainStateView, micGainModeStateView;
    private ProgressBar progressBar;
    private MaterialCardView micStateCard;
    private ImageView muteIcon;
    private SeekBar gainSeekBar;
    private Button muteButton, unmuteButton;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic aicsControlPointChar, muteCharacteristic, aicsStateCharacteristic;

    private byte aicsChangeCounter = 0;
    private int gainMin = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_microphone);

        // --- Find UI elements ---
        headerText = findViewById(R.id.header_text);
        progressBar = findViewById(R.id.microphone_progress_bar);
        micStateCard = findViewById(R.id.mic_state_card);
        micMuteStateView = findViewById(R.id.mic_mute_state);
        micGainStateView = findViewById(R.id.mic_gain_state);
        micGainModeStateView = findViewById(R.id.mic_gain_mode_state);
        muteIcon = findViewById(R.id.mute_icon);
        gainSeekBar = findViewById(R.id.gain_seekbar);
        muteButton = findViewById(R.id.mute_button);
        unmuteButton = findViewById(R.id.unmute_button);

        // --- Bluetooth Setup ---
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
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth non supporté ou désactivé.", Toast.LENGTH_LONG).show();
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
        micStateCard.setVisibility(View.GONE);
        bluetoothGatt = device.connectGatt(this, false, gattCallback);

        // --- UI Listeners ---
        gainSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setGain(seekBar.getProgress() + gainMin); // Add offset back
            }
        });

        muteButton.setOnClickListener(v -> setMuteState(true));
        unmuteButton.setOnClickListener(v -> setMuteState(false));
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected. Discovering services...");
                if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");
                runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Déconnecté.", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered received: " + status);
                return;
            }

            BluetoothGattService micpService = gatt.getService(MICP_SERVICE_UUID);
            if (micpService != null) {
                muteCharacteristic = micpService.getCharacteristic(MUTE_CHARACTERISTIC_UUID);
                if (muteCharacteristic != null) {
                    enableNotifications(gatt, muteCharacteristic);
                } else {
                    Log.w(TAG, "Mute characteristic not found");
                }
            } else {
                Log.w(TAG, "MICP service not found");
            }

            BluetoothGattService aicsService = gatt.getService(AICS_SERVICE_UUID);
            if (aicsService != null) {
                aicsControlPointChar = aicsService.getCharacteristic(AICS_CONTROL_POINT_CHARACTERISTIC_UUID);
                aicsStateCharacteristic = aicsService.getCharacteristic(AICS_STATE_CHARACTERISTIC_UUID);
                BluetoothGattCharacteristic gainPropsChar = aicsService.getCharacteristic(AICS_GAIN_SETTING_PROPERTIES_CHARACTERISTIC_UUID);

                if (aicsControlPointChar == null || aicsStateCharacteristic == null || gainPropsChar == null) {
                    Log.w(TAG, "One or more AICS characteristics not found");
                } else {
                    // First, read gain properties to configure the SeekBar
                    if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.readCharacteristic(gainPropsChar);
                    }
                    // Then, enable notifications for state changes
                    enableNotifications(gatt, aicsStateCharacteristic);
                }
            } else {
                Log.w(TAG, "AICS service not found");
            }
        }

        @Override
        public void onCharacteristicRead(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.getUuid().equals(AICS_GAIN_SETTING_PROPERTIES_CHARACTERISTIC_UUID)) {
                parseGainSettings(value);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "Wrote to " + characteristic.getUuid() + ", status=" + status);
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Wrote to descriptor " + descriptor.getUuid() + ", status=" + status);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                micStateCard.setVisibility(View.VISIBLE);
            });
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            if (characteristic.getUuid().equals(MUTE_CHARACTERISTIC_UUID)) {
                parseAndDisplayMuteState(value);
            } else if (characteristic.getUuid().equals(AICS_STATE_CHARACTERISTIC_UUID)) {
                parseAndDisplayAicsState(value);
            }
        }

        // Deprecated callbacks
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onCharacteristicRead(gatt, characteristic, characteristic.getValue(), status);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onCharacteristicChanged(gatt, characteristic, characteristic.getValue());
            }
        }
    };

    private void setMuteState(boolean mute) {
        if (aicsControlPointChar == null) return;
        byte opcode = mute ? (byte) 0x03 : (byte) 0x02; // 0x03 = Mute, 0x02 = Unmute
        byte[] command = {opcode, aicsChangeCounter++};
        writeAicsControlPoint(command);
    }

    private void setGain(int gain) {
        if (aicsControlPointChar == null) return;
        byte opcode = 0x01; // Set Absolute Gain
        byte[] command = {opcode, aicsChangeCounter++, (byte) gain};
        writeAicsControlPoint(command);
    }

    private void writeAicsControlPoint(byte[] command) {
        if (bluetoothGatt == null || aicsControlPointChar == null) {
            Log.w(TAG, "GATT or AICS Control Point not available.");
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        int properties = aicsControlPointChar.getProperties();
        int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(aicsControlPointChar, command, writeType);
        } else {
            aicsControlPointChar.setWriteType(writeType);
            aicsControlPointChar.setValue(command);
            bluetoothGatt.writeCharacteristic(aicsControlPointChar);
        }
    }

    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        if ((properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) {
            Log.w(TAG, "Characteristic does not support notifications: " + characteristic.getUuid());
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        gatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd != null) {
            byte[] value = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value);
            } else {
                cccd.setValue(value);
                gatt.writeDescriptor(cccd);
            }
        }
    }

    private void parseGainSettings(byte[] data) {
        if (data == null || data.length < 3) return;
        // data[0] = Gain Setting Units
        gainMin = data[1]; // sint8
        int gainMax = data[2]; // sint8

        Log.d(TAG, "Gain settings found: min=" + gainMin + ", max=" + gainMax);
        runOnUiThread(() -> {
            gainSeekBar.setMax(gainMax - gainMin);
        });
    }

    private void parseAndDisplayMuteState(byte[] data) {
        if (data == null || data.length == 0) return;
        int mute = data[0] & 0xFF;
        runOnUiThread(() -> {
            micMuteStateView.setText(mute != 0 ? "Mute" : "Actif");
            muteIcon.setImageResource(mute != 0 ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        });
    }

    private void parseAndDisplayAicsState(byte[] data) {
        if (data == null || data.length < 4) return;
        int gain = data[0];       // sint8
        int mute = data[1];       // uint8
        int gainMode = data[2]; // uint8

        runOnUiThread(() -> {
            micGainStateView.setText(gain + " dB");
            micGainModeStateView.setText(gainMode == 1 ? "Manuel" : "Auto");
            gainSeekBar.setProgress(gain - gainMin); // Use offset
            parseAndDisplayMuteState(new byte[]{(byte) mute});
        });
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
