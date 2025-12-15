package com.example.projet;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

    private static final String TAG = "DeviceListAdapter";

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    private final List<BluetoothDevice> deviceList = new ArrayList<>();
    private final OnDeviceClickListener listener;
    private final Context context;

    public DeviceListAdapter(Context context, OnDeviceClickListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void setDevices(List<BluetoothDevice> devices) {
        deviceList.clear();
        if (devices != null) deviceList.addAll(devices);
        notifyDataSetChanged();
    }

    public void addDevice(BluetoothDevice device) {
        if (device == null) return;

        // Récupère un nom sûr (nom humain ou adresse si nom absent)
        String displayName = safeGetDeviceName(device);
        if (displayName == null) return;

        // Regex pour détecter une adresse MAC (AA:BB:CC:DD:EE:FF)
        boolean isMac = displayName.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$");

        // Ne pas afficher si le nom est "Inconnu" ou si c'est une adresse MAC
        if ("Inconnu".equalsIgnoreCase(displayName) || isMac) {
            // Optionnel : log pour debug
            Log.d(TAG, "Device ignoré (nom non affiché): " + displayName);
            return;
        }

        // Eviter doublons
        if (!deviceList.contains(device)) {
            deviceList.add(device);
            notifyItemInserted(deviceList.size() - 1);
            Log.d(TAG, "Device ajouté: " + displayName);
        }
    }

    public void clear() {
        deviceList.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DeviceListAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.device_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceListAdapter.ViewHolder holder, int position) {
        BluetoothDevice device = deviceList.get(position);
        String displayName = safeGetDeviceName(device);
        holder.deviceName.setText(displayName);
        String addr = "—";
        try {
            addr = device.getAddress();
        } catch (SecurityException ignored) { }
        holder.deviceAddress.setText(addr);
        holder.itemView.setOnClickListener(v -> {
            try {
                listener.onDeviceClick(device);
            } catch (Exception e) {
                Log.w(TAG, "Erreur lors du clic sur l'appareil", e);
            }
        });
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    private String safeGetDeviceName(BluetoothDevice device) {
        if (device == null) return "Sans nom";
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Permission BLUETOOTH_CONNECT refusée pour getName()", e);
        }
        try {
            String addr = device.getAddress();
            if (addr != null && !addr.trim().isEmpty()) return addr;
        } catch (SecurityException ignored) { }
        return "Sans nom";
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView deviceName;
        public final TextView deviceAddress;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.device_name);
            deviceAddress = itemView.findViewById(R.id.device_address);
        }
    }
}
