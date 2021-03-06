package cgeo.geocaching.sensors;

import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.RxUtils.LooperCallbacks;

import rx.Observable;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import java.util.concurrent.TimeUnit;

public class GeoDataProvider extends LooperCallbacks<IGeoData> {

    private final LocationManager geoManager;
    private final LocationData gpsLocation = new LocationData();
    private final LocationData netLocation = new LocationData();
    private final Listener networkListener = new Listener(LocationManager.NETWORK_PROVIDER, netLocation);
    private final Listener gpsListener = new Listener(LocationManager.GPS_PROVIDER, gpsLocation);

    private static class LocationData {
        public Location location;
        public long timestamp = 0;

        public void update(final Location location) {
            this.location = location;
            timestamp = System.currentTimeMillis();
        }

        public boolean isRecent() {
            return isValid() && System.currentTimeMillis() < timestamp + 30000;
        }

        public boolean isValid() {
            return location != null;
        }
    }

    /**
     * Build a new geo data provider object.
     * <p/>
     * There is no need to instantiate more than one such object in an application, as observers can be added
     * at will.
     *
     * @param context the context used to retrieve the system services
     */
    protected GeoDataProvider(final Context context) {
        super(2500, TimeUnit.MILLISECONDS);
        geoManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static Observable<IGeoData> create(final Context context) {
        return Observable.create(new GeoDataProvider(context));
    }

    @Override
    public void onStart() {
        subscriber.onNext(findInitialLocation());
        Log.d("GeoDataProvider: starting the GPS and network listeners");
        for (final Listener listener : new Listener[]{networkListener, gpsListener}) {
            try {
                geoManager.requestLocationUpdates(listener.locationProvider, 0, 0, listener);
            } catch (final Exception e) {
                Log.w("There is no location provider " + listener.locationProvider, e);
            }
        }
    }

    @Override
    protected void onStop() {
        Log.d("GeoDataProvider: stopping the GPS and network listeners");
        geoManager.removeUpdates(networkListener);
        geoManager.removeUpdates(gpsListener);
    }

    private IGeoData findInitialLocation() {
        final Location initialLocation = new Location(GeoData.INITIAL_PROVIDER);
        try {
            // Try to find a sensible initial location from the last locations known to Android.
            final Location lastGpsLocation = geoManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            final Location lastNetworkLocation = geoManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            // If both providers are non-null, take the most recent one
            if (lastGpsLocation != null && lastNetworkLocation != null) {
                if (lastGpsLocation.getTime() >= lastNetworkLocation.getTime()) {
                    copyCoords(initialLocation, lastGpsLocation);
                } else {
                    copyCoords(initialLocation, lastNetworkLocation);
                }
            } else if (lastGpsLocation != null) {
                copyCoords(initialLocation, lastGpsLocation);
            } else if (lastNetworkLocation != null) {
                copyCoords(initialLocation, lastNetworkLocation);
            } else {
                Log.i("GeoDataProvider: no last known location available");
                return GeoData.dummyLocation();
            }
        } catch (final Exception e) {
            // This error is non-fatal as its only consequence is that we will start with a dummy location
            // instead of a previously known one.
            Log.e("GeoDataProvider: error when retrieving last known location", e);
        }
        // Start with an historical GeoData just in case someone queries it before we get
        // a chance to get any information.
        return new GeoData(initialLocation);
    }

    private static void copyCoords(final Location target, final Location source) {
        target.setLatitude(source.getLatitude());
        target.setLongitude(source.getLongitude());
    }

    private class Listener implements LocationListener {
        private final String locationProvider;
        private final LocationData locationData;

        Listener(final String locationProvider, final LocationData locationData) {
            this.locationProvider = locationProvider;
            this.locationData = locationData;
        }

        @Override
        public void onStatusChanged(final String provider, final int status, final Bundle extras) {
            // nothing
        }

        @Override
        public void onProviderDisabled(final String provider) {
            // nothing
        }

        @Override
        public void onProviderEnabled(final String provider) {
            // nothing
        }

        @Override
        public void onLocationChanged(final Location location) {
            locationData.update(location);
            selectBest();
        }
    }

    private LocationData best() {
        if (gpsLocation.isRecent() || !netLocation.isValid()) {
            return gpsLocation.isValid() ? gpsLocation : null;
        }
        if (!gpsLocation.isValid()) {
            return netLocation;
        }
        return gpsLocation.timestamp > netLocation.timestamp ? gpsLocation : netLocation;
    }

    private void selectBest() {
        assign(best());
    }

    private void assign(final LocationData locationData) {
        if (locationData == null) {
            return;
        }

        // We do not necessarily get signalled when satellites go to 0/0.
        final IGeoData current = new GeoData(locationData.location);
        subscriber.onNext(current);
    }

}
