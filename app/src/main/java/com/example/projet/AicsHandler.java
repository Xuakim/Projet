package com.example.projet;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public class AicsHandler {

    private static final String TAG = "AicsHandler";

    private final BleManager bleManager;
    private final AicsListener listener;

    public interface AicsListener {
        void onAudioInputState(String human);
        void onGainSettings(String human);
        void onAudioInputType(String human);
        void onAudioInputStatus(String human);
        void onAudioInputDescription(String human);
    }

    // UUIDs
    private static final UUID AICS_SERVICE = BleManager.AICS_SERVICE;
    private static final UUID AUDIO_INPUT_STATE = UUID.fromString("00002B77-0000-1000-8000-00805f9b34fb");
    private static final UUID GAIN_SETTINGS_PROPERTIES = UUID.fromString("00002B78-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_TYPE = UUID.fromString("00002B79-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_STATUS = UUID.fromString("00002B7A-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_CONTROL_POINT = UUID.fromString("00002B7B-0000-1000-8000-00805f9b34fb");
    private static final UUID AUDIO_INPUT_DESCRIPTION = UUID.fromString("00002B7C-0000-1000-8000-00805f9b34fb");

    public AicsHandler(BleManager bleManager, AicsListener listener) {
        this.bleManager = bleManager;
        this.listener = listener;
    }

    public void onServicesDiscovered(BluetoothGatt gatt) {
        if (gatt == null) return;
        BluetoothGattService service = gatt.getService(AICS_SERVICE);
        if (service == null) {
            Log.d(TAG, "AICS not present");
            return;
        }
        // Read and subscribe to relevant characteristics if present
        readAndSubscribe(gatt, service, AUDIO_INPUT_STATE);
        readAndSubscribe(gatt, service, GAIN_SETTINGS_PROPERTIES);
        readAndSubscribe(gatt, service, AUDIO_INPUT_TYPE);
        readAndSubscribe(gatt, service, AUDIO_INPUT_STATUS);
        readAndSubscribe(gatt, service, AUDIO_INPUT_DESCRIPTION);
    }

    private void readAndSubscribe(BluetoothGatt gatt, BluetoothGattService service, UUID charUuid) {
        BluetoothGattCharacteristic c = service.getCharacteristic(charUuid);
        if (c != null) {
            bleManager.safeReadCharacteristic(c);
            // subscribe to notifications where applicable
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                bleManager.enableNotifications(c, true);
            }
        }
    }

    public void handleCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        UUID uuid = characteristic.getUuid();
        byte[] data = characteristic.getValue();
        if (uuid.equals(AUDIO_INPUT_STATE)) {
            listener.onAudioInputState(decodeAudioInputState(data));
        } else if (uuid.equals(GAIN_SETTINGS_PROPERTIES)) {
            listener.onGainSettings(decodeGainSettings(data));
        } else if (uuid.equals(AUDIO_INPUT_TYPE)) {
            listener.onAudioInputType(decodeAudioInputType(data));
        } else if (uuid.equals(AUDIO_INPUT_STATUS)) {
            listener.onAudioInputStatus(decodeAudioInputStatus(data));
        } else if (uuid.equals(AUDIO_INPUT_DESCRIPTION)) {
            listener.onAudioInputDescription(decodeDescription(data));
        }
    }

    // Decoders (basic, readable)
    private String decodeAudioInputState(byte[] data) {
        if (data == null || data.length < 4) return "N/A";
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int gainSetting = bb.get(); // int8
        int mute = bb.get() & 0xFF;
        int gainMode = bb.get() & 0xFF;
        int changeCounter = bb.get() & 0xFF;
        return String.format("Gain=%d; Mute=%d; Mode=%d; Counter=%d", gainSetting, mute, gainMode, changeCounter);
    }

    private String decodeGainSettings(byte[] data) {
        if (data == null || data.length < 3) return "N/A";
        int units = data[0] & 0xFF; // 0.1 dB units
        int min = data[1]; // int8
        int max = data[2]; // int8
        return String.format("Units=%.1f dB; Min=%d; Max=%d", units / 10.0, min, max);
    }

    private String decodeAudioInputType(byte[] data) {
        if (data == null || data.length < 1) return "N/A";
        int t = data[0] & 0xFF;
        return "Type=" + t;
    }

    private String decodeAudioInputStatus(byte[] data) {
        if (data == null || data.length < 1) return "N/A";
        int s = data[0] & 0xFF;
        return "Status=" + s;
    }

    private String decodeDescription(byte[] data) {
        if (data == null || data.length == 0) return "N/A";
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return bytesToHex(data);
        }
    }

    private String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}

