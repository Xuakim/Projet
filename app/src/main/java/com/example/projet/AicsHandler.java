package com.example.projet;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AicsHandler {

    private static final String TAG = "AicsHandler";

    private final BleManager bleManager;
    private final AicsListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface AicsListener {
        void onAudioInputState(String human);
        void onGainSettings(String human);
        void onAudioInputType(String human);
        void onAudioInputStatus(String human);
        void onAudioInputDescription(String human);
    }

    // UUIDs
    private static final UUID AICS_SERVICE = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
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

        // Collecter toutes les caractéristiques à lire
        List<UUID> charsToRead = new ArrayList<>();
        charsToRead.add(AUDIO_INPUT_STATE);
        charsToRead.add(GAIN_SETTINGS_PROPERTIES);
        charsToRead.add(AUDIO_INPUT_TYPE);
        charsToRead.add(AUDIO_INPUT_STATUS);
        charsToRead.add(AUDIO_INPUT_DESCRIPTION);

        // Lire et s'abonner avec un délai entre chaque opération
        scheduleOperations(service, charsToRead, 0);
    }

    private void scheduleOperations(BluetoothGattService service, List<UUID> uuids, int index) {
        if (index >= uuids.size()) return;

        UUID charUuid = uuids.get(index);
        BluetoothGattCharacteristic c = service.getCharacteristic(charUuid);

        if (c != null) {
            // Lire la caractéristique
            bleManager.safeReadCharacteristic(c);

            // Si elle supporte les notifications, s'y abonner après un délai
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                handler.postDelayed(() -> {
                    bleManager.enableNotifications(c, true);
                    // Passer à la suivante après un autre délai
                    handler.postDelayed(() -> scheduleOperations(service, uuids, index + 1), 200);
                }, 200);
            } else {
                // Pas de notification, passer directement à la suivante après un délai
                handler.postDelayed(() -> scheduleOperations(service, uuids, index + 1), 200);
            }
        } else {
            // Caractéristique non trouvée, passer à la suivante
            handler.postDelayed(() -> scheduleOperations(service, uuids, index + 1), 100);
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

    // Decoders
    private String decodeAudioInputState(byte[] data) {
        if (data == null || data.length < 4) return "N/A";
        try {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int gainSetting = bb.get(); // int8
            int mute = bb.get() & 0xFF;
            int gainMode = bb.get() & 0xFF;
            int changeCounter = bb.get() & 0xFF;

            String muteStr = (mute == 0) ? "Not Muted" : (mute == 1) ? "Muted" : "Disabled";
            String modeStr = (gainMode == 0) ? "Manual Only" : (gainMode == 1) ? "Auto Only" : (gainMode == 2) ? "Manual" : "Auto";

            return String.format("Gain=%d; %s; %s; Counter=%d", gainSetting, muteStr, modeStr, changeCounter);
        } catch (Exception e) {
            Log.w(TAG, "Error decoding Audio Input State", e);
            return "Error";
        }
    }

    private String decodeGainSettings(byte[] data) {
        if (data == null || data.length < 3) return "N/A";
        try {
            int units = data[0] & 0xFF; // 0.1 dB units
            int min = data[1]; // int8
            int max = data[2]; // int8
            return String.format("Units=%.1f dB; Min=%d; Max=%d", units / 10.0, min, max);
        } catch (Exception e) {
            Log.w(TAG, "Error decoding Gain Settings", e);
            return "Error";
        }
    }

    private String decodeAudioInputType(byte[] data) {
        if (data == null || data.length < 1) return "N/A";
        int t = data[0] & 0xFF;
        String typeStr;
        switch (t) {
            case 0x00: typeStr = "Unspecified"; break;
            case 0x01: typeStr = "Bluetooth"; break;
            case 0x02: typeStr = "Microphone"; break;
            case 0x03: typeStr = "Analog"; break;
            case 0x04: typeStr = "Digital"; break;
            case 0x05: typeStr = "Radio"; break;
            default: typeStr = "Unknown (" + t + ")"; break;
        }
        return typeStr;
    }

    private String decodeAudioInputStatus(byte[] data) {
        if (data == null || data.length < 1) return "N/A";
        int s = data[0] & 0xFF;
        return (s == 0x00) ? "Inactive" : (s == 0x01) ? "Active" : "Unknown (" + s + ")";
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