package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import android.os.Handler;
import android.os.Looper;

public class DeviceControlActivity extends AppCompatActivity implements BleEventListener,
        MicsHandler.MicsListener, AicsHandler.AicsListener {

    private static final String TAG = "DeviceControlAct";
    // UUID canonical for AICS service (Audio Input Control Service)
    private static final UUID AICS_SERVICE_UUID = UUID.fromString("00001843-0000-1000-8000-00805f9b34fb");

    // activity-level retry for service discovery
    private int serviceDiscoveryRetries = 0;
    private static final int MAX_SERVICE_DISCOVERY_RETRIES = 3;

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
    private Button disableCccdButton; // optionnel

    private BluetoothDevice currentDevice;
    private volatile boolean isGattConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_control);

        uiController = new UiController(this);

        // Initialisation BleManager et handlers
        bleManager = new BleManager(this, this);
        micsHandler = new MicsHandler(bleManager, this);
        aicsHandler = new AicsHandler(bleManager, this);

        // Views
        topAppBar = findViewById(R.id.topAppBar);
        deviceNameView = findViewById(R.id.deviceName);
        microphoneIcon = findViewById(R.id.microphoneIcon);
        statusTextView = findViewById(R.id.statusText);
        muteButton = findViewById(R.id.muteButton);
        unmuteButton = findViewById(R.id.unmuteButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        //disableCccdButton = findViewById(R.id.disableCccdButton); // peut être null si non présent

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

        // Debug: long-press on deviceNameView to force rediscover services
        if (deviceNameView != null) {
            deviceNameView.setOnLongClickListener(v -> {
                if (bleManager != null) {
                    serviceDiscoveryRetries = 0;
                    uiController.showMessage("Relance discoverServices...");
                    try {
                        bleManager.discoverServices();
                    } catch (Exception e) {
                        Log.w(TAG, "discoverServices forced failed", e);
                        uiController.showMessage("Erreur relance discovery");
                    }
                    return true;
                }
                return false;
            });
        }

        // Tenter la connexion si permission accordée
        if (currentDevice != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bleManager.connect(currentDevice);
                    uiController.showMessage("Connexion en cours...");
                } else {
                    uiController.showMessage("Permission BLUETOOTH_CONNECT manquante");
                }
            } else {
                bleManager.connect(currentDevice);
                uiController.showMessage("Connexion en cours...");
            }
        } else {
            uiController.showMessage("Aucun appareil fourni");
        }

        // Boutons Mute / Unmute : écriture CCCD (notifications/indications) avec fallback
        muteButton.setOnClickListener(v -> {
            if (bleManager == null) return;
            // tenter CCCD (indications = "Mute" mapping)
            boolean requested = bleManager.writeCccdForCharacteristic(BleManager.MCS_MUTE, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            if (!requested) {
                // fallback : écrire une valeur sur la caractéristique (ex: 0x01 pour mute) — adapter selon device
                byte[] muteValue = new byte[]{0x01};
                boolean wrote = bleManager.writeCharacteristicByUuid(BleManager.MCS_MUTE, muteValue);
                if (!wrote) {
                    uiController.showMessage("Impossible d'activer Mute : caractéristique/CCCD absents");
                    setControlsEnabled(false);
                } else {
                    uiController.updateMicStatus("Mute demandé (écriture caractéristique)...");
                }
            } else {
                uiController.updateMicStatus("Mute demandé (CCCD)...");
            }
        });

        unmuteButton.setOnClickListener(v -> {
            if (bleManager == null) return;
            boolean requested = bleManager.writeCccdForCharacteristic(BleManager.MCS_MUTE, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!requested) {
                // fallback : écrire 0x00 pour unmute si le device attend ça
                byte[] unmuteValue = new byte[]{0x00};
                boolean wrote = bleManager.writeCharacteristicByUuid(BleManager.MCS_MUTE, unmuteValue);
                if (!wrote) {
                    uiController.showMessage("Impossible d'activer Unmute : caractéristique/CCCD absents");
                    setControlsEnabled(false);
                } else {
                    uiController.updateMicStatus("Unmute demandé (écriture caractéristique)...");
                }
            } else {
                uiController.updateMicStatus("Unmute demandé (CCCD)...");
            }
        });

        if (disableCccdButton != null) {
            disableCccdButton.setOnClickListener(v -> {
                if (bleManager == null) return;
                boolean requested = bleManager.writeCccdForCharacteristic(BleManager.MCS_MUTE, new byte[]{0x00, 0x00});
                if (!requested) {
                    uiController.showMessage("Impossible de désactiver CCCD : absent");
                } else {
                    uiController.updateMicStatus("Désactivation CCCD demandée...");
                }
            });
        }

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

    // -------------------------
    // Helpers UI
    // -------------------------
    private void setControlsEnabled(boolean enabled) {
        if (muteButton != null) muteButton.setEnabled(enabled);
        if (unmuteButton != null) unmuteButton.setEnabled(enabled);
        if (disconnectButton != null) disconnectButton.setEnabled(enabled);
        if (disableCccdButton != null) disableCccdButton.setEnabled(enabled);
    }

    private void updateToolbarTitle(String title) {
        runOnUiThread(() -> {
            if (topAppBar != null) {
                topAppBar.setTitle(title);
            }
        });
    }

    // -------------------------
    // BleEventListener callbacks
    // -------------------------
    @Override
    public void onScanResult(BluetoothDevice device) {
        // non utilisé ici
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        isGattConnected = true;
        String name = safeGetDeviceName(device);
        uiController.showMessage("Connecté à " + name);
        Log.d(TAG, "onConnected: " + name);

        runOnUiThread(() -> {
            if (deviceNameView != null) deviceNameView.setText("Appareil : " + name);
            if (statusTextView != null) statusTextView.setText("Statut : connecté");
            setControlsEnabled(true);
            updateToolbarTitle(name + " (connecté)");
        });
    }

    @Override
    public void onDisconnected(BluetoothDevice device) {
        isGattConnected = false;
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
    public void onServicesDiscovered(android.bluetooth.BluetoothGatt gatt) {
        runOnUiThread(() -> {
            if (gatt == null) {
                Log.w(TAG, "onServicesDiscovered: gatt null");
                uiController.showMessage("Services découverts: gatt null");
                return;
            }

            String deviceName = (gatt.getDevice() != null) ? safeGetDeviceName(gatt.getDevice()) : "device";
            Log.d(TAG, "Services discovered for device: " + deviceName);

            // Log all services/characteristics/descriptors to help debugging
            logAllDiscoveredServices(gatt);

            boolean hasMcs = false;
            boolean hasAics = false;

            java.util.List<android.bluetooth.BluetoothGattService> services = gatt.getServices();
            if (services == null || services.isEmpty()) {
                Log.w(TAG, "Aucun service découvert");
                uiController.showMessage("Aucun service découvert");
                // retry discovery from activity side (some devices need multiple attempts)
                if (bleManager != null && serviceDiscoveryRetries < MAX_SERVICE_DISCOVERY_RETRIES) {
                    serviceDiscoveryRetries++;
                    Log.w(TAG, "Activity will retry discoverServices in 500ms (attempt " + serviceDiscoveryRetries + ")");
                    uiController.showMessage("Retry discovery (" + serviceDiscoveryRetries + ")...");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            bleManager.discoverServices();
                        } catch (Exception e) {
                            Log.w(TAG, "discoverServices retry from Activity failed", e);
                        }
                    }, 500);
                } else {
                    setControlsEnabled(false);
                }
            } else {
                // reset activity retry counter on success
                serviceDiscoveryRetries = 0;
                for (android.bluetooth.BluetoothGattService s : services) {
                    String sUuid = (s.getUuid() != null) ? s.getUuid().toString() : "null";
                    Log.d(TAG, "Service UUID: " + sUuid);

                    if (s.getUuid() != null) {
                        // Compare MCS using the BleManager constant (expected to be a UUID)
                        try {
                            if (s.getUuid().equals(BleManager.MCS_SERVICE)) {
                                hasMcs = true;
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Comparaison MCS a échoué", t);
                        }
                        // Compare AICS using canonical UUID object
                        if (AICS_SERVICE_UUID.equals(s.getUuid())) {
                            hasAics = true;
                        }
                    }

                    for (android.bluetooth.BluetoothGattCharacteristic c : s.getCharacteristics()) {
                        String cUuid = (c.getUuid() != null) ? c.getUuid().toString() : "null";
                        Log.d(TAG, "  Char UUID: " + cUuid + " properties=" + c.getProperties());
                        if (c.getDescriptors() != null) {
                            for (android.bluetooth.BluetoothGattDescriptor d : c.getDescriptors()) {
                                String dUuid = (d.getUuid() != null) ? d.getUuid().toString() : "null";
                                Log.d(TAG, "    Desc UUID: " + dUuid);
                            }
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
                uiController.showMessage("AICS non disponible");
            } else {
                Log.d(TAG, "AICS present");
                uiController.showMessage("AICS disponible");
            }

            // déléguer aux handlers si présents
            micsHandler.onServicesDiscovered(gatt);
            aicsHandler.onServicesDiscovered(gatt);
        });
    }

    // Utility: logs a detailed tree of discovered services/characteristics/descriptors
    private void logAllDiscoveredServices(BluetoothGatt gatt) {
        if (gatt == null) {
            Log.d(TAG, "logAllDiscoveredServices: gatt is null");
            return;
        }
        List<android.bluetooth.BluetoothGattService> services = gatt.getServices();
        Log.d(TAG, "logAllDiscoveredServices: total services=" + (services == null ? 0 : services.size()));
        if (services == null) return;
        for (android.bluetooth.BluetoothGattService s : services) {
            if (s == null) {
                Log.d(TAG, "  service=null");
                continue;
            }
            Log.d(TAG, "  Service: " + s.getUuid());
            List<android.bluetooth.BluetoothGattCharacteristic> chars = s.getCharacteristics();
            if (chars == null) continue;
            for (android.bluetooth.BluetoothGattCharacteristic c : chars) {
                if (c == null) {
                    Log.d(TAG, "    characteristic=null");
                    continue;
                }
                Log.d(TAG, "    Characteristic: " + c.getUuid() + " props=" + c.getProperties());
                List<android.bluetooth.BluetoothGattDescriptor> descs = c.getDescriptors();
                if (descs == null) continue;
                for (android.bluetooth.BluetoothGattDescriptor d : descs) {
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
            uiController.showMessage("Caractéristique lue: " + uuid);
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
            uiController.showMessage("Notification reçue: " + uuid);
            if (characteristic.getUuid().equals(BleManager.MCS_MUTE)) {
                micsHandler.handleCharacteristic(characteristic);
            } else {
                aicsHandler.handleCharacteristic(characteristic);
            }
        });
    }

    @Override
    public void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
        runOnUiThread(() -> {
            String uuid = (descriptor != null && descriptor.getUuid() != null) ? descriptor.getUuid().toString() : "unknown";
            Log.d(TAG, "onDescriptorWrite uuid=" + uuid + " status=" + status);

            if (descriptor == null) {
                uiController.showMessage("Descriptor écrit: null");
                return;
            }

            android.bluetooth.BluetoothGattCharacteristic parentChar = descriptor.getCharacteristic();
            if (parentChar != null && parentChar.getUuid() != null && parentChar.getUuid().equals(BleManager.MCS_MUTE)) {
                byte[] value = descriptor.getValue();
                if (value == null) {
                    // fallback : lire explicitement le descriptor
                    Log.d(TAG, "descriptor.getValue() null, lecture explicite demandée");
                    bleManager.readDescriptorForCharacteristic(BleManager.MCS_MUTE);
                    return;
                }
                applyCccdValueToUi(value, status);
            } else {
                uiController.showMessage("Descriptor écrit: " + uuid + " (status=" + status + ")");
            }
        });
    }

    @Override
    public void onDescriptorRead(BluetoothGattDescriptor descriptor, int status) {
        runOnUiThread(() -> {
            String uuid = (descriptor != null && descriptor.getUuid() != null) ? descriptor.getUuid().toString() : "unknown";
            Log.d(TAG, "onDescriptorRead uuid=" + uuid + " status=" + status);
            if (descriptor == null) return;
            android.bluetooth.BluetoothGattCharacteristic parentChar = descriptor.getCharacteristic();
            if (parentChar != null && parentChar.getUuid() != null && parentChar.getUuid().equals(BleManager.MCS_MUTE)) {
                byte[] value = descriptor.getValue();
                applyCccdValueToUi(value, status);
            } else {
                uiController.showMessage("Descriptor lu: " + uuid + " (status=" + status + ")");
            }
        });
    }

    private void applyCccdValueToUi(byte[] value, int status) {
        String stateLabel = "Disabled";
        if (value != null) {
            if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                stateLabel = "Unmute";
            } else if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                stateLabel = "Mute";
            } else if (value.length >= 2 && value[0] == 0 && value[1] == 0) {
                stateLabel = "Disabled";
            } else {
                stateLabel = "Unknown (" + BleManager.bytesToHex(value) + ")";
            }
        } else {
            stateLabel = "Disabled";
        }

        if (status == BluetoothGatt.GATT_SUCCESS) {
            uiController.showMessage("CCCD: " + stateLabel);
        } else {
            uiController.showMessage("Lecture/écriture CCCD échouée (status=" + status + ")");
        }

        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (statusTextView != null) statusTextView.setText("Statut : " + stateLabel);
            if ("Mute".equals(stateLabel)) {
                if (microphoneIcon != null) microphoneIcon.setImageResource(R.drawable.mic_off);
                setControlsEnabled(false);
            } else if ("Unmute".equals(stateLabel)) {
                if (microphoneIcon != null) microphoneIcon.setImageResource(R.drawable.mic_on);
                setControlsEnabled(true);
            } else {
                if (microphoneIcon != null) microphoneIcon.setImageResource(R.drawable.mic_off);
                setControlsEnabled(false);
            }
            String name = (currentDevice != null) ? safeGetDeviceName(currentDevice) : "device";
            updateToolbarTitle(name + " (" + stateLabel + ")");
        }
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Log.w(TAG, "Erreur BLE: " + message);
            Toast.makeText(DeviceControlActivity.this, "Erreur BLE: " + message, Toast.LENGTH_LONG).show();
            uiController.showMessage("Erreur BLE: " + message);
        });
    }

    // -------------------------
    // MicsHandler.MicsListener
    // -------------------------
    @Override
    public void onMuteStateUpdated(String humanReadable) {
        runOnUiThread(() -> {
            uiController.updateMicStatus(humanReadable);
            if (statusTextView != null) statusTextView.setText("Statut : " + humanReadable);
        });
    }

    // -------------------------
    // AicsHandler.AicsListener
    // -------------------------
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

    // -------------------------
    // Utilitaires
    // -------------------------
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
}
