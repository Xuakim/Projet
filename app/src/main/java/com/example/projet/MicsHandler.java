package com.example.projet;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import java.util.UUID;

public class MicsHandler {

    private static final String TAG = "MicsHandler";

    private final BleManager bleManager;
    private final MicsListener listener;

    public interface MicsListener {
        void onMuteStateUpdated(String humanReadable);
    }

    // UUIDs
    private static final UUID MCS_SERVICE = BleManager.MCS_SERVICE;
    private static final UUID MCS_MUTE = BleManager.MCS_MUTE;

    public MicsHandler(BleManager bleManager, MicsListener listener) {
        this.bleManager = bleManager;
        this.listener = listener;
    }

    public void onServicesDiscovered(BluetoothGatt gatt) {
        if (gatt == null) return;
        BluetoothGattService service = gatt.getService(MCS_SERVICE);
        if (service == null) {
            Log.d(TAG, "MICS service not present");
            return;
        }

        BluetoothGattCharacteristic muteChar = service.getCharacteristic(MCS_MUTE);
        if (muteChar != null) {
            // Lire la valeur initiale
            bleManager.safeReadCharacteristic(muteChar);
            // Activer les notifications
            bleManager.enableNotifications(muteChar, true);
        } else {
            Log.d(TAG, "Mute characteristic not found");
        }
    }

    public void handleCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        if (!characteristic.getUuid().equals(MCS_MUTE)) return;

        byte[] value = characteristic.getValue();
        String human = decodeMute(value);
        listener.onMuteStateUpdated(human);
    }

    private String decodeMute(byte[] value) {
        if (value == null || value.length == 0) return "N/A";
        int v = value[0] & 0xFF;
        switch (v) {
            case 0: return "Not Muted";
            case 1: return "Muted";
            case 2: return "Disabled";
            default: return "Unknown (" + v + ")";
        }
    }

    public void setMute(BluetoothGatt gatt, boolean mute) {
        if (gatt == null) {
            Log.w(TAG, "setMute: gatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(MCS_SERVICE);
        if (service == null) {
            Log.w(TAG, "setMute: MICS service not found");
            return;
        }

        BluetoothGattCharacteristic muteChar = service.getCharacteristic(MCS_MUTE);
        if (muteChar == null) {
            Log.w(TAG, "setMute: Mute characteristic not found");
            return;
        }

        // Vérifier les propriétés de la caractéristique
        int props = muteChar.getProperties();
        Log.d(TAG, "setMute: Mute characteristic properties = " + props);

        boolean hasWrite = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean hasWriteNoResponse = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;

        Log.d(TAG, "setMute: hasWrite=" + hasWrite + ", hasWriteNoResponse=" + hasWriteNoResponse);

        if (!hasWrite && !hasWriteNoResponse) {
            Log.e(TAG, "setMute: Characteristic does not support Write! Properties=" + props);
            return;
        }

        byte[] value = new byte[]{ (byte) (mute ? 0x01 : 0x00) };
        Log.d(TAG, "setMute: Attempting to write value: " + (mute ? "0x01 (Muted)" : "0x00 (Not Muted)"));

        bleManager.writeCharacteristic(muteChar, value);
    }
}