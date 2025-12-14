package com.example.projet;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

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

import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

    private final List<BluetoothDevice> deviceList;
    private final OnDeviceClickListener listener;
    private final Context context;

    public interface OnDeviceClickListener {
        void onDeviceClick(BluetoothDevice device);
    }

    public DeviceListAdapter(Context context, List<BluetoothDevice> deviceList, OnDeviceClickListener listener) {
        this.deviceList = deviceList;
        this.listener = listener;
        this.context = context;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.device_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDevice device = deviceList.get(position);

        String name = null;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                name = device.getName();
            } catch (SecurityException e) {
                Log.e(TAG, "Permission refusée pour accéder au nom du périphérique", e);
            }
        } else {
            Log.w(TAG, "Permission BLUETOOTH_CONNECT manquante pour lire le nom du périphérique");
        }

// Si le nom est null ou vide, tu peux choisir de ne pas afficher l'appareil
        if (name == null || name.trim().isEmpty()) {
            holder.deviceName.setText(""); // ou "Sans nom"
        } else {
            holder.deviceName.setText(name);
        }


        // Filtrer les appareils sans nom
        if (name == null || name.trim().isEmpty()) {
            holder.deviceName.setText(""); // ou "Sans nom" si tu veux
        } else {
            holder.deviceName.setText(name);
        }

        // Ne pas afficher l’adresse MAC
        holder.deviceAddress.setVisibility(View.GONE);

        holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView deviceName;
        TextView deviceAddress;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            deviceName = itemView.findViewById(R.id.deviceName);
        }
    }
}
