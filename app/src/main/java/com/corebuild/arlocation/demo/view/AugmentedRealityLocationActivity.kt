package com.corebuild.arlocation.demo.view

import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.corebuild.arlocation.demo.R
import com.corebuild.arlocation.demo.model.Geolocation
import com.corebuild.arlocation.demo.model.Venue
import com.corebuild.arlocation.demo.utils.AugmentedRealityLocationUtils
import com.corebuild.arlocation.demo.utils.AugmentedRealityLocationUtils.INITIAL_MARKER_SCALE_MODIFIER
import com.corebuild.arlocation.demo.utils.AugmentedRealityLocationUtils.INVALID_MARKER_SCALE_MODIFIER
import com.corebuild.arlocation.demo.utils.PermissionUtils
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.gson.GsonBuilder
import kotlinx.android.synthetic.main.activity_augmented_reality_location.*
import kotlinx.android.synthetic.main.location_layout_renderable.view.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uk.co.appoly.arcorelocation.LocationMarker
import uk.co.appoly.arcorelocation.LocationScene
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture

class AugmentedRealityLocationActivity : AppCompatActivity() {

    private var arCoreInstallRequested = false

    // Our ARCore-Location scene
    private var locationScene: LocationScene? = null

    private var arHandler = Handler(Looper.getMainLooper())

    private val resumeArElementsTask = Runnable {
        locationScene?.resume()
        arSceneView.resume()
    }

    private var userGeolocation = Geolocation.EMPTY_GEOLOCATION

    private var venuesSet: MutableSet<Venue> = mutableSetOf()
    private var areAllMarkersLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_augmented_reality_location)
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
    }

    override fun onPause() {
        super.onPause()
        arSceneView.session?.let {
            locationScene?.pause()
            arSceneView?.pause()
        }
    }

    private fun setupSession() {
        if (arSceneView == null) {
            return
        }

        if (arSceneView.session == null) {
            try {
                val session = AugmentedRealityLocationUtils.setupSession(this, arCoreInstallRequested)
                if (session == null) {
                    arCoreInstallRequested = true
                    return
                } else {
                    arSceneView.setupSession(session)
                }
            } catch (e: UnavailableException) {
                AugmentedRealityLocationUtils.handleSessionException(this, e)
            }
        }

        if (locationScene == null) {
            locationScene = LocationScene(this, arSceneView)
            locationScene!!.setMinimalRefreshing(true)
            locationScene!!.setOffsetOverlapping(true)
//            locationScene!!.setRemoveOverlapping(true)
            locationScene!!.anchorRefreshInterval = 2000
        }

        try {
            resumeArElementsTask.run()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Unable to get camera", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (userGeolocation == Geolocation.EMPTY_GEOLOCATION) {
            var deviceLatitude: Double?
            var deviceLongitude: Double?
            do {
                deviceLatitude = locationScene?.deviceLocation?.currentBestLocation?.latitude
                deviceLongitude = locationScene?.deviceLocation?.currentBestLocation?.longitude
            } while (deviceLatitude == null || deviceLongitude == null)
            var deviceLocation= listOf(deviceLatitude, deviceLongitude)

            //Setup DEMO
            val venueList  = mutableListOf<Venue>()
            venueList.add(Venue("Ho Hoan Kiem","Ho Chi Minh", long = "105.8194541", lat = "21.0227387", iconURL = "https://lh5.googleusercontent.com/p/AF1QipN-clDi7mRpBisf7Y9wNlu8aktEA70YL5z4yF-j=w408-h272-k-no"))
            venueList.add(Venue("Nha Linh","Nha Linh", long = "106.6603883", lat = "10.807806", iconURL = "https://maps.gstatic.com/tactile/pane/default_geocode-1x.png"))
            venueList.add(Venue("Can Tho","Can Tho", long = "105.7628273", lat = "10.0295328", iconURL = "https://lh5.googleusercontent.com/p/AF1QipPjziYi3_NKLqqHooYUqVa2VaiP3EF-WfeIhnls=w408-h725-k-no"))

            venuesSet.clear()
            venuesSet.addAll(venueList)
            areAllMarkersLoaded = false
            locationScene!!.clearMarkers()
            renderVenues()
        }
    }

    private fun renderVenues() {
        setupAndRenderVenuesMarkers()
        updateVenuesMarkers()
    }

    private fun setupAndRenderVenuesMarkers() {
        venuesSet.forEach { venue ->
            val completableFutureViewRenderable = ViewRenderable.builder()
                .setView(this, R.layout.location_layout_renderable)
                .build()
            CompletableFuture.anyOf(completableFutureViewRenderable)
                .handle<Any> { _, throwable ->
                    //here we know the renderable was built or not
                    if (throwable != null) {
                        // handle renderable load fail
                        return@handle null
                    }
                    try {
                        val venueMarker = LocationMarker(
                            venue.long.toDouble(),
                            venue.lat.toDouble(),
                            setVenueNode(venue, completableFutureViewRenderable)
                        )
                        arHandler.postDelayed({
                            attachMarkerToScene(
                                venueMarker,
                                completableFutureViewRenderable.get().view
                            )
                            if (venuesSet.indexOf(venue) == venuesSet.size - 1) {
                                areAllMarkersLoaded = true
                            }
                        }, 200)

                    } catch (ex: Exception) {
                        //                        showToast(getString(R.string.generic_error_msg))
                    }
                    null
                }
        }
    }

    private fun updateVenuesMarkers() {
        arSceneView.scene.addOnUpdateListener()
        {
            if (!areAllMarkersLoaded) {
                return@addOnUpdateListener
            }

            locationScene?.mLocationMarkers?.forEach { locationMarker ->
                locationMarker.height =
                    AugmentedRealityLocationUtils.generateRandomHeightBasedOnDistance(
                        locationMarker?.anchorNode?.distance ?: 0
                    )
            }


            val frame = arSceneView!!.arFrame ?: return@addOnUpdateListener
            if (frame.camera.trackingState != TrackingState.TRACKING) {
                return@addOnUpdateListener
            }
            locationScene!!.processFrame(frame)
        }
    }


    private fun attachMarkerToScene(
        locationMarker: LocationMarker,
        layoutRendarable: View
    ) {
        resumeArElementsTask.run {
            locationMarker.scalingMode = LocationMarker.ScalingMode.FIXED_SIZE_ON_SCREEN
            locationMarker.scaleModifier = INITIAL_MARKER_SCALE_MODIFIER

            locationScene?.mLocationMarkers?.add(locationMarker)
            locationMarker.anchorNode?.isEnabled = true

            arHandler.post {
                locationScene?.refreshAnchors()
                layoutRendarable.pinContainer.visibility = View.VISIBLE
            }
        }
        locationMarker.setRenderEvent { locationNode ->
            layoutRendarable.distance.text = AugmentedRealityLocationUtils.showDistance(locationNode.distance)
            resumeArElementsTask.run {
                computeNewScaleModifierBasedOnDistance(locationMarker, locationNode.distance)
            }
        }
    }

    private fun computeNewScaleModifierBasedOnDistance(locationMarker: LocationMarker, distance: Int) {
        val scaleModifier = AugmentedRealityLocationUtils.getScaleModifierBasedOnRealDistance(distance)
        return if (scaleModifier == INVALID_MARKER_SCALE_MODIFIER) {
            detachMarker(locationMarker)
        } else {
            locationMarker.scaleModifier = scaleModifier
        }
    }

    private fun detachMarker(locationMarker: LocationMarker) {
        locationMarker.anchorNode?.anchor?.detach()
        locationMarker.anchorNode?.isEnabled = false
        locationMarker.anchorNode = null
    }


    private fun setVenueNode(venue: Venue, completableFuture: CompletableFuture<ViewRenderable>): Node {
        val node = Node()
        node.renderable = completableFuture.get()

        val nodeLayout = completableFuture.get().view
        val venueName = nodeLayout.name
        val markerLayoutContainer = nodeLayout.pinContainer
        venueName.text = venue.name
        markerLayoutContainer.visibility = View.GONE
        nodeLayout.setOnTouchListener { _, _ ->
            Toast.makeText(this, venue.address, Toast.LENGTH_SHORT).show()
            false
        }

        Glide.with(this)
            .load(venue.iconURL)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .into(nodeLayout.categoryIcon)

        return node
    }


    private fun checkAndRequestPermissions() {
        if (!PermissionUtils.hasLocationAndCameraPermissions(this)) {
            PermissionUtils.requestCameraAndLocationPermissions(this)
        } else {
            setupSession()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (!PermissionUtils.hasLocationAndCameraPermissions(this)) {
            Toast.makeText(
                this, R.string.camera_and_location_permission_request, Toast.LENGTH_LONG
            )
                .show()
            if (!PermissionUtils.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                PermissionUtils.launchPermissionSettings(this)
            }
            finish()
        }
    }
}