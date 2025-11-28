package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS_CODE = 123;
    private BluetoothAdapter bluetoothAdapter;

    // Déclaration des variables pour la liste des appareils
    private ListView deviceListView;
    private ArrayAdapter<String> deviceListAdapter;
    private final List<String> discoveredDevices = new ArrayList<>();

    private final ActivityResultLauncher<Intent> requestEnableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Toast.makeText(this, "Bluetooth activé.", Toast.LENGTH_SHORT).show();
                    startDiscovery();
                } else {
                    Toast.makeText(this, "L'activation du Bluetooth a été refusée.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Initialisation du BluetoothAdapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Cet appareil ne supporte pas le Bluetooth.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialisation de la ListView et de son adaptateur
        // CORRECTION : On initialise la variable de classe deviceListView
        deviceListView = findViewById(R.id.device_list);
        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, discoveredDevices);
        deviceListView.setAdapter(deviceListAdapter);

        // Enregistrer le BroadcastReceiver pour la découverte d'appareils
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);

        // Vérifier et demander les permissions nécessaires
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSIONS_CODE);
        } else {
            enableBluetooth();
        }
    }

    // CORRECTION : Méthode enableBluetooth() nettoyée et corrigée
    private void enableBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    requestEnableBluetoothLauncher.launch(enableBtIntent);
                } else {
                    Toast.makeText(this, "Permission BLUETOOTH_CONNECT requise.", Toast.LENGTH_LONG).show();
                }
            } else {
                requestEnableBluetoothLauncher.launch(enableBtIntent);
            }
        } else {
            Toast.makeText(this, "Le Bluetooth est déjà activé.", Toast.LENGTH_SHORT).show();
            startDiscovery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            boolean allPermissionsGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                Toast.makeText(this, "Permissions accordées.", Toast.LENGTH_SHORT).show();
                enableBluetooth();
            } else {
                Toast.makeText(this, "Permissions Bluetooth refusées.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        String deviceName = device.getName();
                        String deviceAddress = device.getAddress();
                        String deviceInfo = (deviceName != null ? deviceName : "Appareil inconnu") + "\n" + deviceAddress;

                        if (!discoveredDevices.contains(deviceInfo)) {
                            discoveredDevices.add(deviceInfo);
                            deviceListAdapter.notifyDataSetChanged();
                        }
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Toast.makeText(MainActivity.this, "Recherche terminée.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private void startDiscovery() {
        // Pour Android 12+ (API 31+), on vérifie les permissions avant TOUTE opération de découverte.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission BLUETOOTH_SCAN requise pour la recherche.", Toast.LENGTH_LONG).show();
                // On ne peut pas continuer sans la permission.
                return;
            }
        }
        // Pour les versions antérieures, la permission ACCESS_FINE_LOCATION (déjà demandée) est suffisante.

        // Si une recherche est déjà en cours, on l'arrête.
        // Cette action est maintenant protégée par la vérification ci-dessus.
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // On démarre une nouvelle recherche.
        // Cette action est également protégée.
        if (bluetoothAdapter.startDiscovery()) {
            Toast.makeText(this, "Recherche des appareils en cours...", Toast.LENGTH_SHORT).show();
            discoveredDevices.clear(); // On vide la liste pour la nouvelle recherche.
            deviceListAdapter.notifyDataSetChanged();
        } else {
            Toast.makeText(this, "Erreur lors du démarrage de la recherche.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // D'abord, on arrête la recherche si elle est en cours, en vérifiant les permissions.
        if (bluetoothAdapter != null) {
            boolean canScan = true;
            // Sur Android 12+, on vérifie la permission BLUETOOTH_SCAN.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    canScan = false; // La permission n'est pas accordée.
                }
            }

            // On ne peut annuler la découverte que si on a la permission.
            if (canScan && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        }

        // Ensuite, on désenregistre le receiver pour éviter les fuites mémoire.
        // Cette opération doit toujours être effectuée si le receiver a été enregistré.
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException e) {
            // Cette exception peut être levée si le receiver n'a jamais été enregistré.
            // On peut l'ignorer en toute sécurité.
            e.printStackTrace();
        }
    }
}