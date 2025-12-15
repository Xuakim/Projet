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

public class DeviceControlActivity extends AppCompatActivity implements BleManager.BleEventListener,
        MicsHandler.MicsListener, AicsHandler.AicsListener {

    private static final String TAG = "DeviceControlAct";

    private BleManager bleManager;
    private MicsHandler micsHandler;
    private AicsHandler aicsHandler;
    private UiController uiController;

    private TextView deviceNameView;
    private ImageView microphoneIcon;
    private Button muteButton;
    private Button unmuteButton;
    private Button disconnectButton;

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
        deviceNameView = findViewById(R.id.deviceName);
        microphoneIcon = findViewById(R.id.microphoneIcon);
        muteButton = findViewById(R.id.muteButton);
        unmuteButton = findViewById(R.id.unmuteButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        // Toolbar navigation
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        if (topAppBar != null) {
            topAppBar.setNavigationOnClickListener(v -> {
                if (bleManager != null) bleManager.disconnect();
                finish();
            });
        }

        // Récupération du BluetoothDevice passé par l'intent
        BluetoothDevice device = getIntent().getParcelableExtra("device");
        if (device != null) {
            String name = safeGetDeviceName(device);
            deviceNameView.setText("Appareil : " + name);

            // Connecter si permission accordée (ou si API < S)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                bleManager.connect(device);
                uiController.showMessage("Connexion en cours...");
            } else {
                uiController.showMessage("Permission BLUETOOTH_CONNECT manquante");
            }
        } else {
            deviceNameView.setText("Appareil : aucun");
        }

        // Boutons Mute / Unmute
        muteButton.setOnClickListener(v -> {
            uiController.updateMicStatus("Muting...");
            BluetoothGatt gatt = bleManager.getBluetoothGatt();
            if (gatt != null) {
                micsHandler.setMute(gatt, false);
            } else {
                uiController.showMessage("GATT non disponible");
            }
            microphoneIcon.setImageResource(R.drawable.mic_off);
        });

        unmuteButton.setOnClickListener(v -> {
            uiController.updateMicStatus("Unmuting...");
            BluetoothGatt gatt = bleManager.getBluetoothGatt();
            if (gatt != null) {
                micsHandler.setMute(gatt, true);
            } else {
                uiController.showMessage("GATT non disponible");
            }
            microphoneIcon.setImageResource(R.drawable.mic_on);
        });

        disconnectButton.setOnClickListener(v -> {
            if (bleManager != null) bleManager.disconnect();
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.disconnect();
        }
    }

    // -------------------------
    // BleManager.BleEventListener callbacks
    // -------------------------
    @Override
    public void onScanResult(BluetoothDevice device) {
        // non utilisé dans cette activité
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        String name = safeGetDeviceName(device);
        uiController.showMessage("Connecté à " + name);
        Log.d(TAG, "onConnected: " + name);
    }

    @Override
    public void onDisconnected(BluetoothDevice device) {
        uiController.showMessage("Déconnecté");
        Log.d(TAG, "onDisconnected");
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt) {
        uiController.showMessage("Services découverts");
        Log.d(TAG, "onServicesDiscovered");
        // déléguer aux handlers
        micsHandler.onServicesDiscovered(gatt);
        aicsHandler.onServicesDiscovered(gatt);
    }

    @Override
    public void onCharacteristicRead(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        if (characteristic.getUuid().equals(BleManager.MCS_MUTE)) {
            micsHandler.handleCharacteristic(characteristic);
        } else {
            aicsHandler.handleCharacteristic(characteristic);
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return;
        if (characteristic.getUuid().equals(BleManager.MCS_MUTE)) {
            micsHandler.handleCharacteristic(characteristic);
        } else {
            aicsHandler.handleCharacteristic(characteristic);
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGattDescriptor descriptor, int status) {
        String uuid = (descriptor != null && descriptor.getUuid() != null) ? descriptor.getUuid().toString() : "unknown";
        uiController.showMessage("Descriptor write: " + uuid + " status=" + status);
        Log.d(TAG, "onDescriptorWrite uuid=" + uuid + " status=" + status);
    }

    @Override
    public void onError(String message) {
        uiController.showMessage("Erreur BLE: " + message);
        Log.w(TAG, "onError: " + message);
    }

    // -------------------------
    // MicsHandler.MicsListener
    // -------------------------
    @Override
    public void onMuteStateUpdated(String humanReadable) {
        uiController.updateMicStatus(humanReadable);
    }

    // -------------------------
    // AicsHandler.AicsListener
    // -------------------------
    @Override
    public void onAudioInputState(String human) {
        uiController.updateAudioInputState(human);
    }

    @Override
    public void onGainSettings(String human) {
        uiController.updateGainSettings(human);
    }

    @Override
    public void onAudioInputType(String human) {
        uiController.updateAudioInputType(human);
    }

    @Override
    public void onAudioInputStatus(String human) {
        uiController.updateAudioInputStatus(human);
    }

    @Override
    public void onAudioInputDescription(String human) {
        uiController.updateAudioInputDescription(human);
    }

    // -------------------------
    // Utilitaires
    // -------------------------
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
                    // ignore
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Permission BLUETOOTH_CONNECT refusée pour getName()", e);
        }
        return "device";
    }
}
