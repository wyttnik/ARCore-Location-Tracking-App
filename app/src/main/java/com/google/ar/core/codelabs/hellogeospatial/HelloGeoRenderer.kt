/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import com.google.ar.core.codelabs.hellogeospatial.helpers.JsonAnchor
import com.google.ar.core.dependencies.i
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.maps.android.SphericalUtil
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlinx.serialization.encodeToString


class HelloGeoRenderer(val activity: HelloGeoActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "HelloGeoRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
  }

  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false
  var cameraPos:LatLng? = null
  var curId = 0
  val anchorDists = booleanArrayOf(false,false,false)
  val sharedPreference =  activity.getPreferences(Context.MODE_PRIVATE)
  var launchFlag = true

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  fun checkSavedAnchors() {
    activity.runOnUiThread {
      for (i in 0..2) {
        val stringJson = sharedPreference.getString(i.toString(),null)
        if (stringJson != null) {
          val jsonAnchor = Json.decodeFromString<JsonAnchor>(stringJson)
          onMapClick(LatLng(jsonAnchor.lat,jsonAnchor.lon), jsonAnchor)
        }
        else {
          ++curId
          if (curId == 3) curId = 0
        }
      }
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj");
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      return
    }

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    //</editor-fold>

    val earth = session.earth

    if (earth?.trackingState == TrackingState.TRACKING) {
      val cameraGeospatialPose = earth.cameraGeospatialPose
      activity.view.mapView?.updateMapPosition(
        latitude = cameraGeospatialPose.latitude,
        longitude = cameraGeospatialPose.longitude,
        heading = cameraGeospatialPose.heading
      )
      cameraPos = LatLng(cameraGeospatialPose.latitude, cameraGeospatialPose.longitude)
      for (i in 0..2) {
        val stringJson = sharedPreference.getString(i.toString(),null)
        if (stringJson != null) {
          val jsonAnchor = Json.decodeFromString<JsonAnchor>(stringJson)
          val dist = SphericalUtil.computeDistanceBetween(LatLng(jsonAnchor.lat, jsonAnchor.lon), cameraPos)
          anchorDists[i] = dist <= 15
        }
      }

      activity.view.updateStatusText(earth, cameraGeospatialPose)
    }
    if (launchFlag && cameraPos != null) {
      checkSavedAnchors()
      launchFlag = false
    }

    // Draw the placed anchor, if it exists.
    for (i in 0..2) {
      earthAnchorArray[i]?.let {
        if (anchorDists[i]) render.renderCompassAtAnchor(it)
      }
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  val earthAnchorArray: Array<Anchor?> = arrayOfNulls(3)

  fun onLongMapClick(){
    onMapClick(cameraPos!!)
  }

  fun onMapClick(latLng: LatLng, jsonAnchor: JsonAnchor? = null) {
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }
    earthAnchorArray[curId]?.detach()

    val altitude: Double
    // The rotation quaternion of the anchor in EUS coordinates.
    val qx:Float
    val qy:Float
    val qz:Float
    val qw: Float
    if (jsonAnchor == null) {
      val cameraGeospatialPose = earth.cameraGeospatialPose
      altitude = cameraGeospatialPose.altitude - 1
      // The rotation quaternion of the anchor in EUS coordinates.
      qx = 0f
      qy = 0f
      qz = 0f
      qw = 1f
      earthAnchorArray[curId] = earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw)
      with (sharedPreference.edit()) {
        putString(curId.toString(),Json.encodeToString(JsonAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw)))
        apply()
      }
    }
    else {
      altitude = jsonAnchor.alt
      // The rotation quaternion of the anchor in EUS coordinates.
      qx = jsonAnchor.qx
      qy = jsonAnchor.qy
      qz = jsonAnchor.qz
      qw = jsonAnchor.qw
      earthAnchorArray[curId] = earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw)
    }

    when(curId) {
      0 -> {
        activity.view.mapView?.earthMarker0?.apply {
          position = latLng
          isVisible = true
        }
      }
      1 -> {
        activity.view.mapView?.earthMarker1?.apply {
          position = latLng
          isVisible = true
        }
      }
      2 -> {
        activity.view.mapView?.earthMarker2?.apply {
          position = latLng
          isVisible = true
        }
      }
    }

    ++curId
    if (curId == 3) curId = 0
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor) {
    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    anchor.pose.toMatrix(modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}
