package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

/**
 * MainActivity : scan BLE, affichage liste d'appareils et navigation vers DeviceControlActivity.
 * Utilise BleManager centralisé pour le scan et les callbacks.
 */
public class MainActivity extends AppCompatActivity implements BleManager.BleEventListener,
        DeviceListAdapter.OnDeviceClickListener {

    private static final String TAG = "MainActivity";

    private BleManager bleManager;
    private DeviceListAdapter deviceListAdapter;
    private final ArrayList<BluetoothDevice> deviceList = new ArrayList<>();

    // Permissions request via Activity Result API
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!Boolean.TRUE.equals(granted)) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    startBleScan();
                } else {
                    showMessage("Permissions refusées, impossible de scanner.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialisation BleManager
        bleManager = new BleManager(getApplicationContext(), this);

        // RecyclerView + Adapter (nouvelle signature : Context, OnDeviceClickListener)
        RecyclerView recyclerView = findViewById(R.id.deviceListView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        deviceListAdapter = new DeviceListAdapter(this, this);
        recyclerView.setAdapter(deviceListAdapter);

        // Démarrer le flux : vérifier permissions puis scanner
        checkPermissionsAndStartScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Relancer le scan si permissions déjà accordées
        if (hasRequiredScanPermissions()) {
            startBleScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stopper le scan pour économiser la batterie
        stopBleScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Nettoyage
        stopBleScan();
        if (bleManager != null) {
            bleManager.disconnect();
        }
    }

    // -------------------------
    // Permissions
    // -------------------------
    private boolean hasRequiredScanPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            // Pour les anciennes versions, ACCESS_FINE_LOCATION peut être requis pour le scan BLE
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkPermissionsAndStartScan() {
        if (hasRequiredScanPermissions()) {
            startBleScan();
            return;
        }

        // Construire la liste de permissions à demander
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION // facultatif mais utile pour compatibilité
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
        }
    }

    // -------------------------
    // Scan control
    // -------------------------
    private void startBleScan() {
        deviceList.clear();
        deviceListAdapter.clear();
        try {
            bleManager.startScan();
            showMessage("Scan BLE démarré...");
        } catch (Exception e) {
            showMessage("Erreur démarrage scan: " + e.getMessage());
        }
    }

    private void stopBleScan() {
        try {
            bleManager.stopScan();
        } catch (Exception ignored) {
        }
    }

    // -------------------------
    // BleManager.BleEventListener callbacks
    // -------------------------
    @Override
    public void onScanResult(BluetoothDevice device) {
        // Filtrer et ajouter uniquement si pas déjà présent
        runOnUiThread(() -> {
            try {
                // On ajoute tous les devices (même sans nom) pour plus de visibilité.
                // DeviceListAdapter gère l'affichage sécurisé du nom (safeGetDeviceName).
                deviceListAdapter.addDevice(device);
            } catch (SecurityException se) {
                showMessage("Permission BLUETOOTH_CONNECT manquante pour lire le nom du périphérique");
            }
        });
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        runOnUiThread(() -> showMessage("Connecté à " + safeGetDeviceName(device)));
    }

    @Override
    public void onDisconnected(BluetoothDevice device) {
        runOnUiThread(() -> showMessage("Déconnecté"));
    }

    @Override
    public void onServicesDiscovered(android.bluetooth.BluetoothGatt gatt) {
        // Pas d'action ici dans la liste ; DeviceControlActivity gère la découverte après connexion
    }

    @Override
    public void onCharacteristicRead(android.bluetooth.BluetoothGattCharacteristic characteristic) {
        // Pas utilisé dans MainActivity
    }

    @Override
    public void onCharacteristicChanged(android.bluetooth.BluetoothGattCharacteristic characteristic) {
        // Pas utilisé dans MainActivity
    }

    @Override
    public void onDescriptorWrite(android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
        // Pas utilisé ici
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> showMessage("Erreur BLE: " + message));
    }

    // -------------------------
    // DeviceListAdapter.OnDeviceClickListener
    // -------------------------
    @Override
    public void onDeviceClick(BluetoothDevice device) {
        // Vérifier permission BLUETOOTH_CONNECT avant d'ouvrir l'activité de contrôle
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ok = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            ok = true;
        }

        if (!ok) {
            showMessage("Permission BLUETOOTH_CONNECT manquante");
            checkPermissionsAndStartScan();
            return;
        }

        // Stopper le scan avant de se connecter pour économiser ressources
        stopBleScan();

        Intent intent = new Intent(this, DeviceControlActivity.class);
        intent.putExtra("device", device); // BluetoothDevice est Parcelable
        startActivity(intent);
    }

    // -------------------------
    // Utilitaires UI
    // -------------------------
    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Récupère le nom de l'appareil de façon sûre : vérifie la permission et capture SecurityException.
     * Retourne une valeur lisible (nom, adresse ou "device") sans lancer d'exception.
     */
    private String safeGetDeviceName(BluetoothDevice device) {
        if (device == null) return "device";
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                String n = device.getName();
                if (n != null && !n.trim().isEmpty()) return n;
                // fallback to address if name absent and permission still allows getAddress()
                try {
                    String addr = device.getAddress();
                    if (addr != null && !addr.trim().isEmpty()) return addr;
                } catch (SecurityException se) {
                    // ignore, we'll return generic label below
                }
            }
        } catch (SecurityException e) {
            // log if tu veux
        }
        return "device";
    }

    // -------------------------
    // Compatibilité avec l'ancienne API (optionnel)
    // -------------------------
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // Pour compatibilité si tu utilises encore l'ancienne API
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            startBleScan();
        } else {
            showMessage("Permissions refusées, impossible de scanner.");
        }
    }
}
