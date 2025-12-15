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
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.UUID;

public class BleManager {

    private static final String TAG = "BleManager";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BleEventListener listener;
    // retry counter for service discovery
    private int serviceDiscoveryRetries = 0;
    private static final int MAX_SERVICE_DISCOVERY_RETRIES = 3;

    private BluetoothLeScanner scanner;
    private BluetoothGatt bluetoothGatt;

    // UUIDs utilisés (adapter si nécessaire)
    public static final UUID MCS_SERVICE = UUID.fromString("0000184D-0000-1000-8000-00805f9b34fb");
    public static final UUID MCS_MUTE = UUID.fromString("00002BC3-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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

    // --- Scan ---
    @SuppressLint("MissingPermission")
    public void startScan() {
        Log.d(TAG, "startScan called");
        if (scanner == null) {
            notifyError("Scanner BLE non disponible");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                notifyError("Permission BLUETOOTH_SCAN manquante");
                return;
            }
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            Log.d(TAG, "scanner.startScan invoked (low latency)");
        } catch (SecurityException se) {
            notifyError("Démarrage du scan refusé : permission manquante");
            Log.w(TAG, "startScan SecurityException", se);
        } catch (Exception e) {
            notifyError("Erreur démarrage scan: " + e.getMessage());
            Log.w(TAG, "startScan Exception", e);
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner == null) return;
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
            String name = safeName(device);
            Log.d(TAG, "onScanResult: name=" + name + " addr=" + (device != null ? device.getAddress() : "null"));
            mainHandler.post(() -> listener.onScanResult(device));
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "Scan failed: " + errorCode);
            notifyError("Scan failed: " + errorCode);
        }
    };

    // --- Connect / Disconnect ---
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

    // --- Read / Write / Notifications / Descriptors ---
    @SuppressLint("MissingPermission")
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
            Log.w(TAG, "readCharacteristic SecurityException", e);
        }
    }

    @SuppressLint("MissingPermission")
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
            Log.w(TAG, "writeCharacteristic SecurityException", e);
        }
    }

    @SuppressLint("MissingPermission")
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
            Log.w(TAG, "enableNotifications SecurityException", e);
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
            Log.w(TAG, "writeCccd SecurityException", e);
        }
    }

    /**
     * Écrire une valeur CCCD (notifications/indications/disable) pour une caractéristique donnée.
     * Retourne true si la demande d'écriture a été lancée.
     */
    @SuppressLint("MissingPermission")
    public boolean writeCccdForCharacteristic(UUID charUuid, byte[] cccdValue) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null) {
            notifyError("GATT non disponible");
            return false;
        }
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante");
            return false;
        }

        // Cherche la caractéristique en parcourant tous les services (tolérance casse)
        BluetoothGattCharacteristic targetChar = null;
        List<BluetoothGattService> services = gatt.getServices();
        if (services != null) {
            for (BluetoothGattService s : services) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (c != null && c.getUuid() != null && c.getUuid().equals(charUuid)) {
                        targetChar = c;
                        break;
                    }
                }
                if (targetChar != null) break;
            }
        }

        if (targetChar == null) {
            notifyError("Caractéristique non trouvée: " + charUuid);
            return false;
        }

        // Activer localement la notification/indication
        boolean localOk = gatt.setCharacteristicNotification(targetChar, true);
        Log.d(TAG, "setCharacteristicNotification -> " + localOk);

        BluetoothGattDescriptor cccd = targetChar.getDescriptor(CCCD);
        if (cccd == null) {
            Log.w(TAG, "CCCD non trouvé pour la caractéristique " + charUuid);
            notifyError("CCCD non trouvé pour la caractéristique " + charUuid);
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, cccdValue);
            } else {
                cccd.setValue(cccdValue);
                gatt.writeDescriptor(cccd);
            }
            Log.d(TAG, "writeDescriptor demandé pour " + charUuid);
            return true;
        } catch (SecurityException se) {
            notifyError("Écriture CCCD refusée : permission manquante");
            Log.w(TAG, "writeDescriptor SecurityException", se);
            return false;
        } catch (Exception e) {
            notifyError("Erreur écriture CCCD: " + e.getMessage());
            Log.w(TAG, "writeDescriptor Exception", e);
            return false;
        }
    }

    /**
     * Fallback : écrire une valeur directement sur la caractéristique (si le périphérique attend une écriture).
     * Retourne true si l'appel à writeCharacteristic a été lancé.
     */
    @SuppressLint("MissingPermission")
    public boolean writeCharacteristicByUuid(UUID charUuid, byte[] valueToWrite) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null) {
            notifyError("GATT non disponible");
            return false;
        }
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante");
            return false;
        }

        BluetoothGattCharacteristic targetChar = null;
        List<BluetoothGattService> services = gatt.getServices();
        if (services != null) {
            for (BluetoothGattService s : services) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (c != null && c.getUuid() != null && c.getUuid().equals(charUuid)) {
                        targetChar = c;
                        break;
                    }
                }
                if (targetChar != null) break;
            }
        }

        if (targetChar == null) {
            notifyError("Caractéristique non trouvée pour écriture: " + charUuid);
            return false;
        }

        if (valueToWrite != null) targetChar.setValue(valueToWrite);
        try {
            boolean ok = gatt.writeCharacteristic(targetChar);
            Log.d(TAG, "writeCharacteristic " + targetChar.getUuid() + " -> " + ok);
            return ok;
        } catch (SecurityException se) {
            notifyError("Écriture caractéristique refusée : permission");
            Log.w(TAG, "writeCharacteristic SecurityException", se);
            return false;
        } catch (Exception e) {
            notifyError("Erreur écriture caractéristique: " + e.getMessage());
            Log.w(TAG, "writeCharacteristic Exception", e);
            return false;
        }
    }

    /**
     * Lire explicitement le CCCD d'une caractéristique (fallback si getValue() null).
     */
    @SuppressLint("MissingPermission")
    public void readDescriptorForCharacteristic(UUID charUuid) {
        BluetoothGatt gatt = getBluetoothGatt();
        if (gatt == null) {
            notifyError("GATT non disponible pour readDescriptor");
            return;
        }
        if (!hasConnectPermission()) {
            notifyError("Permission BLUETOOTH_CONNECT manquante pour readDescriptor");
            return;
        }

        BluetoothGattDescriptor targetDesc = null;
        List<BluetoothGattService> services = gatt.getServices();
        if (services != null) {
            for (BluetoothGattService s : services) {
                BluetoothGattCharacteristic c = s.getCharacteristic(charUuid);
                if (c != null) {
                    targetDesc = c.getDescriptor(CCCD);
                    break;
                }
            }
        }

        if (targetDesc == null) {
            notifyError("CCCD non trouvé pour lecture: " + charUuid);
            return;
        }

        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = gatt.readDescriptor(targetDesc);
            } else {
                ok = gatt.readDescriptor(targetDesc);
            }
            Log.d(TAG, "readDescriptor demandé -> " + ok);
        } catch (SecurityException se) {
            notifyError("Lecture CCCD refusée : permission manquante");
            Log.w(TAG, "readDescriptor SecurityException", se);
        } catch (Exception e) {
            notifyError("Erreur lecture CCCD: " + e.getMessage());
            Log.w(TAG, "readDescriptor Exception", e);
        }
    }

    // --- GATT callback ---
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = (gatt != null) ? gatt.getDevice() : null;
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "GATT connecté");
                // reset retry counter on new connection
                serviceDiscoveryRetries = 0;
                mainHandler.post(() -> listener.onConnected(device));
                try {
                    // some devices require a short delay before service discovery succeeds
                    final BluetoothGatt localGatt = gatt;
                    mainHandler.postDelayed(() -> {
                        try {
                            Log.d(TAG, "Lancement delayed de discoverServices()");
                            if (localGatt != null) localGatt.discoverServices();
                        } catch (SecurityException e) {
                            notifyError("discoverServices permission refusée");
                            Log.w(TAG, "discoverServices SecurityException", e);
                        }
                    }, 500);
                } catch (SecurityException e) {
                    notifyError("discoverServices permission refusée");
                    Log.w(TAG, "discoverServices SecurityException", e);
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
                // reset retry counter on success
                serviceDiscoveryRetries = 0;
                mainHandler.post(() -> listener.onServicesDiscovered(gatt));
            } else {
                notifyError("Services discovery failed: " + status);
                // try a limited number of retries after a short delay
                if (serviceDiscoveryRetries < MAX_SERVICE_DISCOVERY_RETRIES) {
                    serviceDiscoveryRetries++;
                    Log.w(TAG, "Retrying discoverServices in 500ms (attempt " + serviceDiscoveryRetries + ")");
                    mainHandler.postDelayed(() -> {
                        try {
                            if (bluetoothGatt != null) bluetoothGatt.discoverServices();
                        } catch (SecurityException se) {
                            notifyError("discoverServices retry permission refusée");
                            Log.w(TAG, "discoverServices retry SecurityException", se);
                        }
                    }, 500);
                }
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
            Log.d(TAG, "onDescriptorWrite callback uuid=" + (descriptor != null ? descriptor.getUuid() : "null") + " status=" + status);
            mainHandler.post(() -> listener.onDescriptorWrite(descriptor, status));
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorRead callback uuid=" + (descriptor != null ? descriptor.getUuid() : "null") + " status=" + status);
            mainHandler.post(() -> listener.onDescriptorRead(descriptor, status));
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

    private String safeName(BluetoothDevice device) {
        if (device == null) return "device";
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                String n = device.getName();
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (SecurityException ignored) { }
        try {
            String addr = device.getAddress();
            if (addr != null && !addr.trim().isEmpty()) return addr;
        } catch (SecurityException ignored) { }
        return "device";
    }

    // utilitaire hex (debug)
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    /**
     * Try to refresh the GATT cache using hidden API. Returns true if the call succeeded.
     * This helps when Android caches services and doesn't show newly exposed services.
     */
    private boolean refreshDeviceCache(BluetoothGatt gatt) {
        if (gatt == null) return false;
        try {
            java.lang.reflect.Method refresh = gatt.getClass().getMethod("refresh");
            if (refresh != null) {
                boolean result = (boolean) refresh.invoke(gatt);
                Log.d(TAG, "refreshDeviceCache invoked -> " + result);
                return result;
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshDeviceCache failed", e);
        }
        return false;
    }

    /**
     * Expose a safe public method to trigger service discovery again.
     */
    @SuppressLint("MissingPermission")
    public void discoverServices() {
        if (bluetoothGatt == null) {
            notifyError("GATT non disponible pour discoverServices");
            return;
        }
        try {
            // attempt to refresh cache before discovery to avoid stale service lists
            try {
                refreshDeviceCache(bluetoothGatt);
            } catch (Throwable ignored) { }
            boolean ok = bluetoothGatt.discoverServices();
            Log.d(TAG, "discoverServices requested -> " + ok);
        } catch (SecurityException se) {
            notifyError("discoverServices permission refusée");
            Log.w(TAG, "discoverServices SecurityException", se);
        } catch (Exception e) {
            notifyError("discoverServices error: " + e.getMessage());
            Log.w(TAG, "discoverServices Exception", e);
        }
    }
}
