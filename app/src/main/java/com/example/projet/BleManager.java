package com.example.projet;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.UUID;

public class BleManager {

    private static final String TAG = "BleManager";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BleEventListener listener;

    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;

    // MICS / AICS UUIDs
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
        BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            this.scanner = adapter.getBluetoothLeScanner();
        }
    }

    // --- Scan ---
    /**
     * Démarre le scan BLE de façon sûre :
     * - vérifie explicitement la permission BLUETOOTH_SCAN sur Android S+,
     * - capture SecurityException pour éviter crash et avertissements lint.
     */
    @SuppressLint("MissingPermission")
    public void startScan() {
        if (scanner == null) {
            notifyError("Scanner BLE non disponible");
            return;
        }

        // Vérification explicite de permission pour satisfaire lint et runtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                notifyError("Permission BLUETOOTH_SCAN manquante");
                return;
            }
        } else {
            // Pour les anciennes versions, on peut exiger ACCESS_FINE_LOCATION si nécessaire
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Optionnel : vérifier ACCESS_FINE_LOCATION si ton app le requiert pour le scan
                // if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                //     notifyError("Permission ACCESS_FINE_LOCATION manquante");
                //     return;
                // }
            }
        }

        try {
            scanner.startScan(scanCallback);
            Log.d(TAG, "Scan démarré");
        } catch (SecurityException se) {
            notifyError("Démarrage du scan refusé : permission manquante");
            Log.w(TAG, "startScan SecurityException", se);
        } catch (Exception e) {
            notifyError("Erreur démarrage scan: " + e.getMessage());
            Log.w(TAG, "startScan Exception", e);
        }
    }

    /**
     * Arrête le scan BLE de façon sûre (vérifie permission et capture SecurityException).
     */
    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner == null) {
            return;
        }

        // Vérification explicite de permission pour éviter lint
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission BLUETOOTH_SCAN manquante pour stopScan");
                return;
            }
        }

        try {
            scanner.stopScan(scanCallback);
            Log.d(TAG, "Scan arrêté");
        } catch (SecurityException se) {
            notifyError("Arrêt du scan refusé : permission manquante");
            Log.w(TAG, "stopScan SecurityException", se);
        } catch (Exception e) {
            notifyError("Erreur arrêt scan: " + e.getMessage());
            Log.w(TAG, "stopScan Exception", e);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            mainHandler.post(() -> listener.onScanResult(device));
        }
    };

    // --- Connect / Disconnect ---
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
        }
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (SecurityException e) {
                notifyError("Permission refusée pour déconnecter");
            } finally {
                bluetoothGatt = null;
            }
        }
    }

    /**
     * Getter sécurisé pour accéder au BluetoothGatt courant.
     */
    public synchronized BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    // --- Read / Write / Notifications ---
    public void safeReadCharacteristic(BluetoothGattCharacteristic characteristic) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null || characteristic == null) return;
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante pour lecture");
            return;
        }
        try {
            boolean ok = gatt.readCharacteristic(characteristic);
            Log.d(TAG, "readCharacteristic " + characteristic.getUuid() + " -> " + ok);
        } catch (SecurityException e) {
            notifyError("Lecture caractéristique refusée : permission");
        }
    }

    public void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null || characteristic == null) return;
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante pour écriture");
            return;
        }
        characteristic.setValue(value);
        try {
            boolean ok = gatt.writeCharacteristic(characteristic);
            Log.d(TAG, "writeCharacteristic " + characteristic.getUuid() + " -> " + ok);
        } catch (SecurityException e) {
            notifyError("Écriture caractéristique refusée : permission");
        }
    }

    public void enableNotifications(BluetoothGattCharacteristic characteristic, boolean enable) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null || characteristic == null) return;
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante pour notifications");
            return;
        }
        try {
            gatt.setCharacteristicNotification(characteristic, enable);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor != null) {
                writeCccd(gatt, descriptor, enable);
            } else {
                Log.w(TAG, "CCCD non trouvé pour " + characteristic.getUuid());
            }
        } catch (SecurityException e) {
            notifyError("Activation notifications refusée : permission");
        }
    }

    private void writeCccd(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, boolean enable) {
        if (gatt == null || descriptor == null) return;
        byte[] value = enable ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value);
            } else {
                descriptor.setValue(value);
                gatt.writeDescriptor(descriptor);
            }
            Log.d(TAG, "writeCccd requested for " + descriptor.getUuid());
        } catch (SecurityException e) {
            notifyError("Écriture CCCD refusée : permission");
        }
    }

    // --- GATT callback ---
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = (gatt != null) ? gatt.getDevice() : null;
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "GATT connecté");
                mainHandler.post(() -> listener.onConnected(device));
                try {
                    gatt.discoverServices();
                } catch (SecurityException e) {
                    notifyError("discoverServices permission refusée");
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT déconnecté");
                mainHandler.post(() -> listener.onDisconnected(device));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services découverts");
                mainHandler.post(() -> listener.onServicesDiscovered(gatt));
            } else {
                notifyError("Services discovery failed: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> listener.onCharacteristicRead(characteristic));
            } else {
                notifyError("Characteristic read failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            mainHandler.post(() -> listener.onCharacteristicChanged(characteristic));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("Characteristic write failed: " + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            mainHandler.post(() -> listener.onDescriptorWrite(descriptor, status));
        }
    };

    // --- Helpers ---
    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void notifyError(String message) {
        Log.w(TAG, message);
        mainHandler.post(() -> listener.onError(message));
    }
}
