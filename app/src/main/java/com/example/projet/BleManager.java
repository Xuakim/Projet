package com.example.projet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleManager {

    private static final String TAG = "BleManager";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BleEventListener listener;

    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;

    public static final UUID MCS_SERVICE = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    public static final UUID MCS_MUTE = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    public static final UUID AICS_SERVICE = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface BleEventListener {
        void onScanResult(BluetoothDevice device);
        void onConnected(BluetoothDevice device);
        void onDisconnected(BluetoothDevice device);
        void onServicesDiscovered(BluetoothGatt gatt);
        void onCharacteristicRead(BluetoothGattCharacteristic characteristic);
        void onCharacteristicChanged(BluetoothGattCharacteristic characteristic);
        void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status);
        void onError(String message);
    }

    public BleManager(Context context, BleEventListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            this.scanner = adapter.getBluetoothLeScanner();
        } else {
            Log.w(TAG, "BluetoothAdapter null");
        }
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyError("Bluetooth est désactivé. Veuillez l'activer.");
            return;
        }

        if (scanner == null) {
            scanner = bluetoothAdapter.getBluetoothLeScanner();
            if(scanner == null) {
                notifyError("Impossible d'obtenir le scanner BLE. L'appareil est-il compatible ?");
                return;
            }
        }

        if (!hasScanPermission()) {
            notifyError("Permission BLUETOOTH_SCAN manquante");
            return;
        }

        // On utilise un mode de scan basse consommation, plus compatible et moins
        // susceptible d'être bloqué par le système d'exploitation.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        try {
            // On lance un scan général sans filtre.
            scanner.startScan(null, settings, scanCallback);
            Log.d(TAG, "scanner.startScan a été appelé en mode LOW_POWER et SANS FILTRE.");
        } catch (Exception e) {
            notifyError("Erreur au démarrage du scan: " + e.getMessage());
            Log.e(TAG, "startScan Exception", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner == null || !hasScanPermission()) return;
        try {
            scanner.stopScan(scanCallback);
            Log.d(TAG, "Scan arrêté.");
        } catch (Exception e) {
            notifyError("Erreur à l'arrêt du scan: " + e.getMessage());
            Log.e(TAG, "stopScan Exception", e);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            mainHandler.post(() -> listener.onScanResult(result.getDevice()));
        }

        @Override
        public void onScanFailed(int errorCode) {
            String errorMessage;
            switch (errorCode) {
                case SCAN_FAILED_ALREADY_STARTED:
                    errorMessage = "Échec : Le scan a déjà démarré.";
                    break;
                case SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    errorMessage = "Échec : L'application n'a pas pu être enregistrée.";
                    break;
                case SCAN_FAILED_INTERNAL_ERROR:
                    errorMessage = "Échec : Erreur interne du sous-système Bluetooth.";
                    break;
                case SCAN_FAILED_FEATURE_UNSUPPORTED:
                    errorMessage = "Échec : Le scan BLE n'est pas supporté sur cet appareil.";
                    break;
                default:
                    errorMessage = "Échec du scan BLE, code d'erreur inconnu : " + errorCode;
                    break;
            }
            Log.e(TAG, "onScanFailed: " + errorMessage);
            notifyError(errorMessage);
        }
    };
    
    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante");
            return;
        }
        if (device == null) {
            notifyError("Device null");
            return;
        }
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback);
            Log.d(TAG, "Tentative de connexion à " + device.getAddress());
        } catch (SecurityException e) {
            notifyError("Connexion refusée : permission manquante");
            Log.w(TAG, "connect SecurityException", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {
                notifyError("Permission refusée pour déconnecter");
                Log.w(TAG, "disconnect SecurityException", e);
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    public synchronized BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    @SuppressLint("MissingPermission")
    public void safeReadCharacteristic(BluetoothGattCharacteristic characteristic) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null || characteristic == null || !hasConnectPermission()) return;
        try {
            gatt.readCharacteristic(characteristic);
        } catch (SecurityException e) {
            notifyError("Lecture caractéristique refusée : permission");
        }
    }

    @SuppressLint("MissingPermission")
    public void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null || characteristic == null || !hasConnectPermission()) return;
        characteristic.setValue(value);
        try {
            gatt.writeCharacteristic(characteristic);
        } catch (SecurityException e) {
            notifyError("Écriture caractéristique refusée : permission");
        }
    }

    @SuppressLint("MissingPermission")
    public void enableNotifications(BluetoothGattCharacteristic characteristic, boolean enable) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null || characteristic == null || !hasConnectPermission()) return;
        try {
            gatt.setCharacteristicNotification(characteristic, enable);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor != null) {
                writeCccd(gatt, descriptor, enable);
            }
        } catch (SecurityException e) {
            notifyError("Activation notifications refusée : permission");
        }
    }

    private void writeCccd(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, boolean enable) {
        byte[] value = enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value);
            } else {
                descriptor.setValue(value);
                gatt.writeDescriptor(descriptor);
            }
        } catch (SecurityException e) {
            notifyError("Écriture CCCD refusée : permission");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = gatt.getDevice();
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mainHandler.post(() -> listener.onConnected(device));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    notifyError("discoverServices permission refusée");
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                mainHandler.post(() -> listener.onDisconnected(device));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> listener.onServicesDiscovered(gatt));
            } else {
                notifyError("Services discovery failed: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> listener.onCharacteristicRead(characteristic));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            mainHandler.post(() -> listener.onCharacteristicChanged(characteristic));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            mainHandler.post(() -> listener.onDescriptorWrite(descriptor, status));
        }
    };

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void notifyError(String message) {
        Log.w(TAG, message);
        mainHandler.post(() -> listener.onError(message));
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) return "device";
        try {
            if (hasConnectPermission()) {
                String n = device.getName();
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (SecurityException ignored) {}
        return device.getAddress();
    }
}
