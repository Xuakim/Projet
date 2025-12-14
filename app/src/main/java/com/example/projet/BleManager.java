package com.example.projet;

import com.example.projet.MicServiceHandler;
import com.example.projet.AudioInputServiceHandler;
import com.example.projet.UiController;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import java.util.UUID;

public class BleManager {

    private static final String TAG = "BLE_MANAGER";

    private BluetoothGatt bluetoothGatt;
    private final Context context;
    private final UiController uiController;

    private final MicServiceHandler micHandler;
    private final AudioInputServiceHandler audioHandler;

    public BleManager(Context context, UiController uiController) {
        this.context = context;
        this.uiController = uiController;
        this.micHandler = new MicServiceHandler(this, uiController);
        this.audioHandler = new AudioInputServiceHandler(this, uiController);
    }

    // --- Connexion ---
    public void connectToDevice(BluetoothDevice device) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    // --- Callback principal ---
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connecté au serveur GATT.");
                gatt.discoverServices();
                uiController.showMessage("Connecté, découverte des services...");
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Déconnecté du serveur GATT.");
                uiController.showMessage("Déconnecté.");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Découverte des services échouée: " + status);
                return;
            }
            for (BluetoothGattService service : gatt.getServices()) {
                Log.d(TAG, "Service trouvé: " + service.getUuid());
            }
            micHandler.onServiceDiscovered(gatt);
            audioHandler.onServiceDiscovered(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            UUID uuid = characteristic.getUuid();
            micHandler.handleCharacteristic(characteristic, value);
            audioHandler.handleCharacteristic(characteristic, value);
        }
    };

    // --- Méthodes utilitaires ---
    public BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }
}
