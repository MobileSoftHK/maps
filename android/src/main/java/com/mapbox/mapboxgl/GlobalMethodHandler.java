package com.mapbox.mapboxgl;

import android.content.Context;
import android.util.Log;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mapbox.mapboxsdk.net.ConnectivityReceiver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;


class GlobalMethodHandler implements MethodChannel.MethodCallHandler {
    private static final String TAG = GlobalMethodHandler.class.getSimpleName();
    private static final String DATABASE_NAME = "mbgl-offline.db";
    private static final int BUFFER_SIZE = 1024 * 2;

    @Nullable
    private PluginRegistry.Registrar registrar;
    @Nullable
    private FlutterPlugin.FlutterAssets flutterAssets;
    @NonNull
    private final Context context;
    @NonNull
    private final BinaryMessenger messenger;


    //Netwrok override
    private  ConnectivityManager connectivityManager;
    private NetworkRequest networkRequest = new NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build();
    private ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
        }
    
        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            setConnectedState(true);
        }
    
        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
        }
    };

    void setConnectedState(boolean isConnected){
        if(context != null){
            ConnectivityReceiver cr = ConnectivityReceiver.instance(context);
            if(cr != null){
                cr.setConnected(isConnected);
            }
        }
    }

    GlobalMethodHandler(@NonNull PluginRegistry.Registrar registrar) {
        this.registrar = registrar;
        this.context = registrar.activeContext();
        this.messenger = registrar.messenger();
        this.initNetworkStateOverride();

    }

    GlobalMethodHandler(@NonNull FlutterPlugin.FlutterPluginBinding binding) {
        this.context = binding.getApplicationContext();
        this.flutterAssets = binding.getFlutterAssets();
        this.messenger = binding.getBinaryMessenger();
        this.initNetworkStateOverride();
    }


    private void initNetworkStateOverride(){
        if(this.connectivityManager == null){
            this.connectivityManager = (ConnectivityManager) this.context.getSystemService(ConnectivityManager.class);
            this.connectivityManager.registerNetworkCallback(this.networkRequest, networkCallback);
            setConnectedState(true);
        }
    }


    @Override
    public void onMethodCall(MethodCall methodCall, MethodChannel.Result result) {
        String accessToken = methodCall.argument("accessToken");
        MapBoxUtils.getMapbox(context, accessToken);

        switch (methodCall.method) {
            case "installOfflineMapTiles":
                String tilesDb = methodCall.argument("tilesdb");
                installOfflineMapTiles(tilesDb);
                result.success(null);
                break;
            case "setOffline":
                boolean offline = methodCall.argument("offline");
                ConnectivityReceiver.instance(context).setConnected(offline ? false : null);
                result.success(null);
                break;
            case "setRegionDownloadState":
                boolean isActive = methodCall.argument("isActive");
                String regionID = methodCall.argument("regionID").toString();
                OfflineManagerUtils.setOfflineRegionDownloadState(regionID, isActive);
                break;
            case "mergeOfflineRegions":
                OfflineManagerUtils.mergeRegions(result, context, methodCall.argument("path"));
                break;
            case "setOfflineTileCountLimit":
                OfflineManagerUtils.setOfflineTileCountLimit(result, context, methodCall.<Number>argument("limit").longValue());
                break;
            case "downloadOfflineRegion":
                // Get args from caller
                Map<String, Object> definitionMap = (Map<String, Object>) methodCall.argument("definition");
                Map<String, Object> metadataMap = (Map<String, Object>) methodCall.argument("metadata");
                String channelName = methodCall.argument("channelName");

                // Prepare args
                OfflineChannelHandlerImpl channelHandler = new OfflineChannelHandlerImpl(messenger, channelName);

                // Start downloading
                OfflineManagerUtils.downloadRegion(result, context, definitionMap, metadataMap, channelHandler);
                break;
            case "getListOfRegions":
                OfflineManagerUtils.regionsList(result, context);
                break;
            case "updateOfflineRegionMetadata":
                // Get download region arguments from caller
                Map<String, Object> metadata = (Map<String, Object>) methodCall.argument("metadata");
                OfflineManagerUtils.updateRegionMetadata(result, context, methodCall.<Number>argument("id").longValue(), metadata);
                break;
            case "deleteOfflineRegion":
                OfflineManagerUtils.deleteRegion(result, context, methodCall.<Number>argument("id").longValue());
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void installOfflineMapTiles(String tilesDb) {
        final File dest = new File(context.getFilesDir(), DATABASE_NAME);
        try (InputStream input = openTilesDbFile(tilesDb);
             OutputStream output = new FileOutputStream(dest)) {
            copy(input, output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private InputStream openTilesDbFile(String tilesDb) throws IOException {
        if (tilesDb.startsWith("/")) { // Absolute path.
            return new FileInputStream(new File(tilesDb));
        } else {
            String assetKey;
            if (registrar != null) {
                assetKey = registrar.lookupKeyForAsset(tilesDb);
            } else if(flutterAssets != null) {
                assetKey = flutterAssets.getAssetFilePathByName(tilesDb);
            } else {
                throw new IllegalStateException();
            }
            return context.getAssets().open(assetKey);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        final byte[] buffer = new byte[BUFFER_SIZE];
        final BufferedInputStream in = new BufferedInputStream(input, BUFFER_SIZE);
        final BufferedOutputStream out = new BufferedOutputStream(output, BUFFER_SIZE);
        int count = 0;
        int n = 0;
        try {
            while ((n = in.read(buffer, 0, BUFFER_SIZE)) != -1) {
                out.write(buffer, 0, n);
                count += n;
            }
            out.flush();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
            try {
                in.close();
            } catch (IOException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }
    }
}
