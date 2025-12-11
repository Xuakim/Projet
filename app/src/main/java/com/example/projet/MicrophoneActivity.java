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
    private static final UUID MUTE_CHARACTERISTIC_UUID = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb"); // Mute Characteristic
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // UUIDs for Audio Input Control Service (AICS)
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    private static final UUID AICS_CONTROL_POINT_CHARACTERISTIC_UUID = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");

    // UI Elements
    private TextView headerText;
    private ProgressBar progressBar;
    private MaterialCardView micStateCard;
    private TextView micMuteStateView;
    private ImageView muteIcon;
    private SeekBar gainSeekBar;
    private Button muteButton;
    private Button unmuteButton;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic aicsControlPointChar;
    private BluetoothGattCharacteristic muteCharacteristic;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_microphone);

        headerText = findViewById(R.id.header_text);
        progressBar = findViewById(R.id.microphone_progress_bar);
        micStateCard = findViewById(R.id.mic_state_card);
        micMuteStateView = findViewById(R.id.mic_mute_state);
        muteIcon = findViewById(R.id.mute_icon);
        gainSeekBar = findViewById(R.id.gain_seekbar);
        muteButton = findViewById(R.id.mute_button);
        unmuteButton = findViewById(R.id.unmute_button);

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

        gainSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setGain(seekBar.getProgress());
            }
        });

        muteButton.setOnClickListener(v -> setMute(true));
        unmuteButton.setOnClickListener(v -> setMute(false));
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected - discovering services");
                if (ActivityCompat.checkSelfPermission(MicrophoneActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected");
                runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Déconnecté.", Toast.LENGTH_SHORT).show();
                    progressBar.setVisibility(View.GONE);
                    micStateCard.setVisibility(View.GONE);
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: " + status);
                return;
            }

            BluetoothGattService micpService = gatt.getService(MICP_SERVICE_UUID);
            if (micpService == null) {
                Log.w(TAG, "Microphone Control Service (MICP) not found");
                 runOnUiThread(() -> {
                    Toast.makeText(MicrophoneActivity.this, "Service Microphone (MICP) non trouvé.", Toast.LENGTH_LONG).show();
                    progressBar.setVisibility(View.GONE);
                });
                return;
            }

            muteCharacteristic = micpService.getCharacteristic(MUTE_CHARACTERISTIC_UUID);
            if (muteCharacteristic != null) {
                enableNotifications(gatt, muteCharacteristic);
            } else {
                Log.w(TAG, "Mute characteristic not found");
            }

            BluetoothGattService aicsService = gatt.getService(AICS_SERVICE_UUID);
            if (aicsService != null) {
                aicsControlPointChar = aicsService.getCharacteristic(AICS_CONTROL_POINT_CHARACTERISTIC_UUID);
                if (aicsControlPointChar == null) {
                    Log.w(TAG, "AICS Control Point characteristic not found");
                }
            } else {
                Log.w(TAG, "Audio Input Control Service (AICS) not found");
            }
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notification subscription successful.");
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    micStateCard.setVisibility(View.VISIBLE);
                });
            } else {
                 Log.w(TAG, "Descriptor write failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            if (characteristic.getUuid().equals(MUTE_CHARACTERISTIC_UUID)) {
                parseAndDisplayMuteState(value);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                 if (characteristic.getUuid().equals(MUTE_CHARACTERISTIC_UUID)) {
                    parseAndDisplayMuteState(characteristic.getValue());
                }
            }
        }
    };

    private void setMute(boolean mute) {
        if (bluetoothGatt == null || muteCharacteristic == null) {
            Log.w(TAG, "Cannot set mute state, GATT or characteristic not available.");
            Toast.makeText(this, "Impossible de changer l'état du micro.", Toast.LENGTH_SHORT).show();
            return;
        }

        if ((muteCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            Log.w(TAG, "Mute characteristic is not writable");
            Toast.makeText(this, "Caractéristique Mute non accessible en écriture.", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] value = new byte[1];
        value[0] = (byte) (mute ? 1 : 0);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        muteCharacteristic.setValue(value);
        bluetoothGatt.writeCharacteristic(muteCharacteristic);
    }

    private void setGain(int gain) {
        if (bluetoothGatt == null || aicsControlPointChar == null) {
            Log.w(TAG, "Cannot set gain, GATT or characteristic not available.");
            Toast.makeText(this, "Impossible de régler le gain.", Toast.LENGTH_SHORT).show();
            return;
        }

        int properties = aicsControlPointChar.getProperties();
        if ((properties & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
            Log.w(TAG, "AICS Control Point characteristic is not writable");
            Toast.makeText(this, "Caractéristique de gain non accessible en écriture.", Toast.LENGTH_SHORT).show();
            return;
        }

        byte[] value = new byte[2];
        value[0] = 0x01; // Opcode for Set Gain Setting
        value[1] = (byte) gain;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(aicsControlPointChar, value, writeType);
        } else {
            aicsControlPointChar.setWriteType(writeType);
            aicsControlPointChar.setValue(value);
            bluetoothGatt.writeCharacteristic(aicsControlPointChar);
        }
    }

    private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        if ((properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) {
            Log.w(TAG, "Mute characteristic does not support notifications or indications");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        gatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd != null) {
            byte[] value = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            
            cccd.setValue(value);
            if (!gatt.writeDescriptor(cccd)) {
                Log.w(TAG, "Failed to write CCCD descriptor for Mute characteristic");
            }
        }
    }

    private void parseAndDisplayMuteState(byte[] data) {
        if (data == null || data.length == 0) return;
        
        int mute = data[0] & 0xFF; // Mute is a uint8

        runOnUiThread(() -> {
            micMuteStateView.setText(mute == 0 ? "Actif" : "Mute");
            muteIcon.setImageResource(mute == 0 ? R.drawable.ic_mic : R.drawable.ic_mic_off);
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
