package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.PackageManager; // ADDED
import android.content.Context;
import android.content.ComponentName;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.List;
import java.util.UUID;

public class DeviceControlActivity extends AppCompatActivity implements BleEventListener,
        MicsHandler.MicsListener, AicsHandler.AicsListener {

    private static final String TAG = "DeviceControlAct";
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");

    private BleManager bleManager;
    private MicsHandler micsHandler;
    private AicsHandler aicsHandler;
    private UiController uiController;

    private MaterialToolbar topAppBar;
    private TextView deviceNameView;
    private ImageView microphoneIcon;
    private TextView statusTextView;
    private Button muteButton;
    private Button unmuteButton;
    private Button disconnectButton;

    private BluetoothDevice currentDevice;

    // NEW: launcher + pending device for permission flow
    private ActivityResultLauncher<String[]> permissionLauncher; // request multiple permissions
    private BluetoothDevice pendingDeviceForConnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_control);

        uiController = new UiController(this);

        // Initialize BLE manager and handlers
        bleManager = new BleManager(this, this);
        micsHandler = new MicsHandler(bleManager, this);
        aicsHandler = new AicsHandler(bleManager, this);

        // Initialize views
        topAppBar = findViewById(R.id.topAppBar);
        deviceNameView = findViewById(R.id.deviceName);
        microphoneIcon = findViewById(R.id.microphoneIcon);
        statusTextView = findViewById(R.id.statusText);
        muteButton = findViewById(R.id.muteButton);
        unmuteButton = findViewById(R.id.unmuteButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        // Register a multiple-permission launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!Boolean.TRUE.equals(granted)) {
                            allGranted = false;
                            break;
                        }
                    }
                    if (allGranted) {
                        Log.d(TAG, "Permissions BLE accordées via permissionLauncher");
                        uiController.showMessage("Permissions accordées, connexion... ");
                        // Prefer pendingDeviceForConnect, else currentDevice
                        BluetoothDevice toConnect = pendingDeviceForConnect != null ? pendingDeviceForConnect : currentDevice;
                        pendingDeviceForConnect = null;
                        if (toConnect != null) {
                            try {
                                bleManager.connect(toConnect);
                            } catch (Exception e) {
                                Log.w(TAG, "Erreur lors de la connexion après permission", e);
                                uiController.showMessage("Erreur connexion: " + e.getMessage());
                            }
                        }
                    } else {
                        Log.w(TAG, "Permissions BLE refusées via permissionLauncher");
                        uiController.showMessage("Permissions BLE requises");
                        // If user denied permanently, suggest opening settings
                        boolean showRationale = false;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pendingDeviceForConnect != null) {
                            String[] checkPerms = new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
                            for (String p : checkPerms) {
                                if (p != null && shouldShowRequestPermissionRationale(p)) {
                                    showRationale = true;
                                    break;
                                }
                            }
                        }
                        if (!showRationale && pendingDeviceForConnect != null) {
                            // likely permanently denied
                            uiController.showMessage("Veuillez autoriser les permissions BLE dans les paramètres de l'app");
                            // Try opening MIUI-specific permission screens first for MIUI devices
                            if (isMiui()) {
                                boolean opened = openMiuiPermissionSettings();
                                if (!opened) {
                                    // fallback to app settings
                                    openAppSettings();
                                }
                            } else {
                                openAppSettings();
                            }
                        }
                        pendingDeviceForConnect = null;
                    }
                }
        );

        // Toolbar navigation
        if (topAppBar != null) {
            topAppBar.setNavigationOnClickListener(v -> {
                if (bleManager != null) bleManager.disconnect();
                finish();
            });
        }

        // Désactiver les boutons tant que non connecté
        setControlsEnabled(false);

        // Récupération du BluetoothDevice passé par l'intent
        currentDevice = getIntent().getParcelableExtra("device");
        String initialName = (currentDevice != null) ? safeGetDeviceName(currentDevice) : "Inconnu";

        // Mettre le titre du toolbar et le deviceNameView
        if (topAppBar != null) topAppBar.setTitle(initialName);
        if (deviceNameView != null) deviceNameView.setText("Appareil : " + initialName);

        // Tenter la connexion si permission accordée
        if (currentDevice != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                boolean hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
                boolean hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
                if (hasConnect && hasScan) {
                    bleManager.connect(currentDevice);
                    uiController.showMessage("Connexion en cours...");
                } else {
                    // Request permissions and remember device to connect after grant
                    pendingDeviceForConnect = currentDevice;
                    uiController.showMessage("Demande des permissions BLUETOOTH_CONNECT/SCAN...");
                    String[] perms = new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
                    permissionLauncher.launch(perms);
                }
            } else {
                // pre-S flow: require ACCESS_FINE_LOCATION on older devices
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    bleManager.connect(currentDevice);
                    uiController.showMessage("Connexion en cours...");
                } else {
                    pendingDeviceForConnect = currentDevice;
                    uiController.showMessage("Demande permission localisation pour BLE (pré-S)...");
                    permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
                }
            }
        } else {
            uiController.showMessage("Aucun appareil fourni");
        }

        // Boutons Mute / Unmute
        muteButton.setOnClickListener(v -> {
            if (bleManager != null && bleManager.getBluetoothGatt() != null) {
                Log.d(TAG, "Mute button clicked");
                micsHandler.setMute(bleManager.getBluetoothGatt(), true);
                Toast.makeText(this, "Envoi commande Mute...", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Mute button clicked but GATT not ready");
                Toast.makeText(this, "Connexion non prête", Toast.LENGTH_SHORT).show();
            }
        });

        unmuteButton.setOnClickListener(v -> {
            if (bleManager != null && bleManager.getBluetoothGatt() != null) {
                Log.d(TAG, "Unmute button clicked");
                micsHandler.setMute(bleManager.getBluetoothGatt(), false);
                Toast.makeText(this, "Envoi commande Unmute...", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Unmute button clicked but GATT not ready");
                Toast.makeText(this, "Connexion non prête", Toast.LENGTH_SHORT).show();
            }
        });

        disconnectButton.setOnClickListener(v -> {
            if (bleManager != null) bleManager.disconnect();
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.disconnect();
        }
    }

    private void setControlsEnabled(boolean enabled) {
        if (muteButton != null) muteButton.setEnabled(enabled);
        if (unmuteButton != null) unmuteButton.setEnabled(enabled);
        if (disconnectButton != null) disconnectButton.setEnabled(enabled);
    }

    private void updateToolbarTitle(String title) {
        runOnUiThread(() -> {
            if (topAppBar != null) {
                topAppBar.setTitle(title);
            }
        });
    }

    // --- BleEventListener callbacks ---
    @Override
    public void onScanResult(BluetoothDevice device) {
        // non utilisé ici
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        String name = safeGetDeviceName(device);
        uiController.showMessage("Connecté à " + name);
        Log.d(TAG, "onConnected: " + name);

        // Ensure we have BLUETOOTH_CONNECT before calling getBondState() or createBond()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT manquante au moment d'onConnected - demande de permission pour bonding");
                // Remember device and ask permission; the launcher will resume bonding/connection when granted
                pendingDeviceForConnect = device;
                uiController.showMessage("Permission BLUETOOTH_CONNECT requise pour l'appairage");
                permissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN});
                return;
            }
        }

        // Forcer le bonding si nécessaire (permission checked above)
        if (device != null) {
            try {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "Device not bonded, attempting to create bond...");
                    bleManager.createBond(device);
                    Toast.makeText(this, "Appairage en cours...", Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException se) {
                Log.w(TAG, "getBondState/createBond SecurityException", se);
                uiController.showMessage("Impossible d'appairer : permission manquante");
            }
        }

        runOnUiThread(() -> {
            if (deviceNameView != null) deviceNameView.setText("Appareil : " + name);
            if (statusTextView != null) statusTextView.setText("Statut : connecté");
            setControlsEnabled(true);
            updateToolbarTitle(name + " (connecté)");
        });
    }

    @Override
    public void onDisconnected(BluetoothDevice device) {
        String name = (device != null) ? safeGetDeviceName(device) : "Inconnu";
        uiController.showMessage("Déconnecté de " + name);
        Log.d(TAG, "onDisconnected: " + name);

        runOnUiThread(() -> {
            if (statusTextView != null) statusTextView.setText("Statut : déconnecté");
            setControlsEnabled(false);
            updateToolbarTitle(name + " (déconnecté)");
        });
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt) {
        runOnUiThread(() -> {
            if (gatt == null) {
                Log.w(TAG, "onServicesDiscovered: gatt null");
                uiController.showMessage("Services découverts: gatt null");
                return;
            }

            String deviceName = (gatt.getDevice() != null) ? safeGetDeviceName(gatt.getDevice()) : "device";
            Log.d(TAG, "Services discovered for device: " + deviceName);

            // Log all services
            logAllDiscoveredServices(gatt);

            boolean hasMcs = false;
            boolean hasAics = false;

            List<BluetoothGattService> services = gatt.getServices();
            if (services == null || services.isEmpty()) {
                Log.w(TAG, "Aucun service découvert");
                uiController.showMessage("Aucun service découvert");
                setControlsEnabled(false);
            } else {
                for (BluetoothGattService s : services) {
                    if (s.getUuid() != null) {
                        if (s.getUuid().equals(BleManager.MCS_SERVICE)) {
                            hasMcs = true;
                        }
                        if (AICS_SERVICE_UUID.equals(s.getUuid())) {
                            hasAics = true;
                        }
                    }
                }
            }

            if (!hasMcs) {
                Log.d(TAG, "MICS service not present");
                uiController.showMessage("MICS non disponible");
                setControlsEnabled(false);
            } else {
                Log.d(TAG, "MICS service present");
                uiController.showMessage("MICS disponible");
            }

            if (!hasAics) {
                Log.d(TAG, "AICS not present");
            } else {
                Log.d(TAG, "AICS present");
            }

            // Déléguer aux handlers
            micsHandler.onServicesDiscovered(gatt);
            aicsHandler.onServicesDiscovered(gatt);
        });
    }

    private void logAllDiscoveredServices(BluetoothGatt gatt) {
        if (gatt == null) {
            Log.d(TAG, "logAllDiscoveredServices: gatt is null");
            return;
        }
        List<BluetoothGattService> services = gatt.getServices();
        Log.d(TAG, "logAllDiscoveredServices: total services=" + (services == null ? 0 : services.size()));
        if (services == null) return;

        for (BluetoothGattService s : services) {
            if (s == null) {
                Log.d(TAG, "  service=null");
                continue;
            }
            Log.d(TAG, "  Service: " + s.getUuid());
            List<BluetoothGattCharacteristic> chars = s.getCharacteristics();
            if (chars == null) continue;

            for (BluetoothGattCharacteristic c : chars) {
                if (c == null) {
                    Log.d(TAG, "    characteristic=null");
                    continue;
                }
                Log.d(TAG, "    Characteristic: " + c.getUuid() + " props=" + c.getProperties());
                List<BluetoothGattDescriptor> descs = c.getDescriptors();
                if (descs == null) continue;

                for (BluetoothGattDescriptor d : descs) {
                    Log.d(TAG, "      Descriptor: " + (d == null ? "null" : d.getUuid()));
                }
            }
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        runOnUiThread(() -> {
            String uuid = characteristic.getUuid() != null ? characteristic.getUuid().toString() : "unknown";
            Log.d(TAG, "onCharacteristicRead uuid=" + uuid);

            if (characteristic.getUuid().equals(BleManager.MCS_MUTE)) {
                micsHandler.handleCharacteristic(characteristic);
            } else {
                aicsHandler.handleCharacteristic(characteristic);
            }
        });
    }

    @Override
    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        runOnUiThread(() -> {
            String uuid = characteristic.getUuid() != null ? characteristic.getUuid().toString() : "unknown";
            Log.d(TAG, "onCharacteristicChanged uuid=" + uuid);

            if (characteristic.getUuid().equals(BleManager.MCS_MUTE)) {
                micsHandler.handleCharacteristic(characteristic);
            } else {
                aicsHandler.handleCharacteristic(characteristic);
            }
        });
    }

    @Override
    public void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
        // Géré automatiquement par BleManager
    }

    @Override
    public void onDescriptorRead(BluetoothGattDescriptor descriptor, int status) {
        // Géré automatiquement par BleManager
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Log.w(TAG, "Erreur BLE: " + message);
            // Ne pas afficher de Toast pour éviter la surcharge
        });
    }

    // --- MicsHandler.MicsListener ---
    @Override
    public void onMuteStateUpdated(String humanReadable) {
        runOnUiThread(() -> {
            uiController.updateMicStatus(humanReadable);
            if (statusTextView != null) statusTextView.setText("Statut : " + humanReadable);

            // Mettre à jour l'icône
            if (humanReadable.contains("Muted") || humanReadable.contains("Disabled")) {
                if (microphoneIcon != null) microphoneIcon.setImageResource(R.drawable.mic_off);
            } else {
                if (microphoneIcon != null) microphoneIcon.setImageResource(R.drawable.mic_on);
            }
        });
    }

    // --- AicsHandler.AicsListener ---
    @Override
    public void onAudioInputState(String human) {
        runOnUiThread(() -> uiController.updateAudioInputState(human));
    }

    @Override
    public void onGainSettings(String human) {
        runOnUiThread(() -> uiController.updateGainSettings(human));
    }

    @Override
    public void onAudioInputType(String human) {
        runOnUiThread(() -> uiController.updateAudioInputType(human));
    }

    @Override
    public void onAudioInputStatus(String human) {
        runOnUiThread(() -> uiController.updateAudioInputStatus(human));
    }

    @Override
    public void onAudioInputDescription(String human) {
        runOnUiThread(() -> uiController.updateAudioInputDescription(human));
    }

    // --- Utilitaires ---
    private String safeGetDeviceName(BluetoothDevice device) {
        if (device == null) return "Inconnu";
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                String n = device.getName();
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Permission BLUETOOTH_CONNECT refusée pour getName()", e);
        }
        try {
            String addr = device.getAddress();
            return addr != null ? addr : "Inconnu";
        } catch (SecurityException e) {
            Log.w(TAG, "Permission refusée pour getAddress", e);
            return "Inconnu";
        }
    }

    // Open generic app settings
    private void openAppSettings() {
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Impossible d'ouvrir les paramètres de l'application", e);
        }
    }

    // Detect MIUI by checking system properties
    private boolean isMiui() {
        String manufacturer = android.os.Build.MANUFACTURER;
        if (manufacturer == null) return false;
        if (manufacturer.toLowerCase().contains("xiaomi") || manufacturer.toLowerCase().contains("redmi")) return true;
        // also try MIUI system property
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = cls.getMethod("get", String.class);
            String miui = (String) m.invoke(null, "ro.miui.ui.version.name");
            return miui != null && !miui.isEmpty();
        } catch (Exception ignored) {
        }
        return false;
    }

    // Try opening MIUI permission/autostart screens. Returns true if one intent succeeded.
    private boolean openMiuiPermissionSettings() {
        // Try common MIUI activities
        String pkg = "com.miui.securitycenter";
        String[] components = new String[]{
                "com.miui.permcenter.permissions.PermissionsEditorActivity", // MIUI 12+
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity", // older
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.powercenter.PowerSettings"
        };
        for (String comp : components) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(pkg, comp));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                Log.d(TAG, "MIUI permission activity not available: " + comp);
            }
        }
        return false;
    }

    // Also offer to open battery optimization ignore screen
    private void openIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent();
            intent.setAction(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Unable to open battery optimization settings", e);
        }
    }
}
